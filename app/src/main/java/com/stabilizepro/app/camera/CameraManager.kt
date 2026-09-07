package com.stabilizepro.app.camera

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager as Camera2Manager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.stabilizepro.app.logs.DebugCenter
import com.stabilizepro.app.logs.LogLevel
import com.stabilizepro.app.logs.LogModule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraManager(private val context: Context) {

    companion object {
        private const val TAG = "CameraManager"
        private const val PREFS_NAME = "stabilize_camera_prefs"
        private const val KEY_AUTO_STABILIZE = "key_auto_stabilize_recording"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null

    private var currentLifecycleOwner: LifecycleOwner? = null
    private var currentSurfaceProvider: Preview.SurfaceProvider? = null

    private val _settings = MutableStateFlow(
        CameraSettings(
            autoStabilizeAfterRecording = prefs.getBoolean(KEY_AUTO_STABILIZE, true)
        )
    )
    val settings: StateFlow<CameraSettings> = _settings.asStateFlow()

    private val _availableLenses = MutableStateFlow<List<LensType>>(
        listOf(LensType.ULTRA_WIDE, LensType.WIDE, LensType.TELEPHOTO, LensType.FRONT)
    )
    val availableLenses: StateFlow<List<LensType>> = _availableLenses.asStateFlow()

    init {
        detectAvailableLenses()
    }

    private fun detectAvailableLenses() {
        // Standard triple-lens + front setup is always available via Camera2/CameraX zoom and sensor switching
        _availableLenses.value = listOf(LensType.ULTRA_WIDE, LensType.WIDE, LensType.TELEPHOTO, LensType.FRONT)
    }

    fun initialize(
        lifecycleOwner: LifecycleOwner,
        surfaceProvider: Preview.SurfaceProvider,
        onReady: () -> Unit = {}
    ) {
        currentLifecycleOwner = lifecycleOwner
        currentSurfaceProvider = surfaceProvider

        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            try {
                cameraProvider = providerFuture.get()
                bindCameraUseCases()
                onReady()
            } catch (e: Exception) {
                DebugCenter.logAndToastError(
                    context = context,
                    module = LogModule.CameraX,
                    errorCode = "#204",
                    detailedMessage = "Falha ao inicializar CameraX Provider: ${e.message}",
                    throwable = e
                )
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun updateSettings(newSettings: CameraSettings) {
        val oldSettings = _settings.value
        if (oldSettings.autoStabilizeAfterRecording != newSettings.autoStabilizeAfterRecording) {
            prefs.edit().putBoolean(KEY_AUTO_STABILIZE, newSettings.autoStabilizeAfterRecording).apply()
        }
        _settings.value = newSettings

        // If lens or mode or quality changed, rebind use cases
        if (oldSettings.selectedLens != newSettings.selectedLens ||
            oldSettings.captureMode != newSettings.captureMode ||
            oldSettings.quality != newSettings.quality
        ) {
            bindCameraUseCases()
        } else {
            // Otherwise, update Camera2 manual controls in real-time
            applyCamera2Controls(newSettings)
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun bindCameraUseCases() {
        val provider = cameraProvider ?: return
        val lifecycleOwner = currentLifecycleOwner ?: return
        val surfaceProvider = currentSurfaceProvider ?: return

        try {
            provider.unbindAll()

            // Camera selector according to selected lens
            val cameraSelector = when (_settings.value.selectedLens) {
                LensType.FRONT -> CameraSelector.DEFAULT_FRONT_CAMERA
                else -> CameraSelector.DEFAULT_BACK_CAMERA
            }

            // Preview use case
            preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(surfaceProvider)
                }

            // ImageCapture use case
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            // VideoCapture use case with QualitySelector (UHD -> FHD -> HD automatic fallback)
            val qualitySelector = when (_settings.value.quality) {
                VideoQualityOption.UHD_4K -> QualitySelector.from(
                    Quality.UHD,
                    FallbackStrategy.higherQualityOrLowerThan(Quality.FHD)
                )
                VideoQualityOption.QHD_2K -> QualitySelector.from(
                    Quality.HIGHEST,
                    FallbackStrategy.higherQualityOrLowerThan(Quality.FHD)
                )
                VideoQualityOption.FHD_1080P -> QualitySelector.from(
                    Quality.FHD,
                    FallbackStrategy.higherQualityOrLowerThan(Quality.HD)
                )
                VideoQualityOption.HD_720P -> QualitySelector.from(
                    Quality.HD,
                    FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                )
                VideoQualityOption.AUTO_MAX -> QualitySelector.fromOrderedList(
                    listOf(Quality.UHD, Quality.FHD, Quality.HD),
                    FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                )
            }

            val recorder = Recorder.Builder()
                .setExecutor(cameraExecutor)
                .setQualitySelector(qualitySelector)
                .build()

            videoCapture = VideoCapture.withOutput(recorder)

            // Bind to lifecycle
            camera = provider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageCapture,
                videoCapture
            )

            // Apply Camera2 manual controls
            applyCamera2Controls(_settings.value)

            DebugCenter.log(
                LogModule.CameraX,
                LogLevel.INFO,
                "CameraX inicializada: Lente=${_settings.value.selectedLens.name}, Modo=${_settings.value.captureMode.name}, Resolução=${_settings.value.quality.name}"
            )
        } catch (e: Exception) {
            DebugCenter.logAndToastError(
                context = context,
                module = LogModule.CameraX,
                errorCode = "#204",
                detailedMessage = "Erro ao vincular use-cases da câmera: ${e.message}",
                throwable = e
            )
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun applyCamera2Controls(settings: CameraSettings) {
        val cam = camera ?: return
        try {
            val camera2Control = Camera2CameraControl.from(cam.cameraControl)
            val builder = CaptureRequestOptions.Builder()

            // 1. Manual Focus vs Continuous Auto Focus
            if (settings.focusDistance >= 0.0f) {
                builder.setCaptureRequestOption(
                    CaptureRequest.CONTROL_AF_MODE,
                    CameraMetadata.CONTROL_AF_MODE_OFF
                )
                builder.setCaptureRequestOption(
                    CaptureRequest.LENS_FOCUS_DISTANCE,
                    settings.focusDistance
                )
            } else {
                builder.setCaptureRequestOption(
                    CaptureRequest.CONTROL_AF_MODE,
                    CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                )
            }

            // 2. ISO / Sensor Sensitivity
            if (settings.iso > 0) {
                builder.setCaptureRequestOption(
                    CaptureRequest.CONTROL_AE_MODE,
                    CameraMetadata.CONTROL_AE_MODE_OFF
                )
                builder.setCaptureRequestOption(
                    CaptureRequest.SENSOR_SENSITIVITY,
                    settings.iso
                )
            } else {
                builder.setCaptureRequestOption(
                    CaptureRequest.CONTROL_AE_MODE,
                    CameraMetadata.CONTROL_AE_MODE_ON
                )
            }

            // 3. Shutter Speed / Exposure Time (nanoseconds)
            if (settings.shutterSpeedNs > 0L) {
                builder.setCaptureRequestOption(
                    CaptureRequest.CONTROL_AE_MODE,
                    CameraMetadata.CONTROL_AE_MODE_OFF
                )
                builder.setCaptureRequestOption(
                    CaptureRequest.SENSOR_EXPOSURE_TIME,
                    settings.shutterSpeedNs
                )
            }

            // 4. EV Compensation
            cam.cameraControl.setExposureCompensationIndex(settings.evCompensation)

            // 5. White Balance
            builder.setCaptureRequestOption(
                CaptureRequest.CONTROL_AWB_MODE,
                settings.whiteBalanceMode
            )

            // 6. HDR Scene mode when requested
            if (settings.isHdrEnabled) {
                builder.setCaptureRequestOption(
                    CaptureRequest.CONTROL_SCENE_MODE,
                    CameraMetadata.CONTROL_SCENE_MODE_HDR
                )
            }

            // 7. Target FPS range (30 FPS vs 60 FPS)
            val fpsRange = if (settings.targetFps == 60) {
                android.util.Range(30, 60)
            } else {
                android.util.Range(30, 30)
            }
            builder.setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                fpsRange
            )

            // 8. Multi-lens sensor switching via Zoom Ratio (0.5x Ultra-Wide, 1x Normal, 2x Telephoto)
            val zoomState = cam.cameraInfo.zoomState.value
            if (zoomState != null) {
                val minZoom = zoomState.minZoomRatio
                val maxZoom = zoomState.maxZoomRatio
                val targetZoom = when (settings.selectedLens) {
                    LensType.ULTRA_WIDE -> minZoom.coerceAtMost(0.6f)
                    LensType.WIDE -> 1.0f.coerceIn(minZoom, maxZoom)
                    LensType.TELEPHOTO -> 2.0f.coerceIn(minZoom, maxZoom)
                    LensType.FRONT -> 1.0f
                }
                cam.cameraControl.setZoomRatio(targetZoom)
            }

            camera2Control.setCaptureRequestOptions(builder.build())
        } catch (e: Exception) {
            DebugCenter.log(
                LogModule.CameraX,
                LogLevel.WARN,
                "Aviso ao aplicar controles Camera2: ${e.message}",
                errorCode = "#205"
            )
        }
    }

    fun takePhoto(
        outputFile: File,
        onPhotoSaved: (Uri) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val capture = imageCapture ?: run {
            val ex = IllegalStateException("ImageCapture não está inicializado.")
            DebugCenter.logAndToastError(context, LogModule.CameraX, "#206", ex.message ?: "", ex)
            mainHandler.post { onError(ex) }
            return
        }

        val outputOptions = ImageCapture.OutputFileOptions.Builder(outputFile).build()
        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    val uri = outputFileResults.savedUri ?: Uri.fromFile(outputFile)
                    DebugCenter.log(LogModule.CameraX, LogLevel.INFO, "Foto salva com sucesso: ${uri.path}")
                    mainHandler.post {
                        try {
                            onPhotoSaved(uri)
                        } catch (t: Throwable) {
                            DebugCenter.log(LogModule.CameraX, LogLevel.ERROR, "Erro no callback onPhotoSaved: ${t.message}", throwable = t)
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    DebugCenter.logAndToastError(
                        context,
                        LogModule.CameraX,
                        "#206",
                        "Falha ao capturar foto: ${exception.message}",
                        exception
                    )
                    mainHandler.post {
                        try {
                            onError(exception)
                        } catch (t: Throwable) {
                            DebugCenter.log(LogModule.CameraX, LogLevel.ERROR, "Erro no callback onError: ${t.message}", throwable = t)
                        }
                    }
                }
            }
        )
    }

    fun startRecording(
        outputFile: File,
        onVideoSaved: (Uri) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val vCapture = videoCapture ?: run {
            val ex = IllegalStateException("VideoCapture não está inicializado.")
            DebugCenter.logAndToastError(context, LogModule.CameraX, "#207", ex.message ?: "", ex)
            mainHandler.post { onError(ex) }
            return
        }

        try {
            val outputOptions = FileOutputOptions.Builder(outputFile).build()
            val pendingRecording = vCapture.output.prepareRecording(context, outputOptions)

            // Audio recording check
            try {
                if (ContextCompat.checkSelfPermission(
                        context,
                        android.Manifest.permission.RECORD_AUDIO
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    pendingRecording.withAudioEnabled()
                }
            } catch (ignored: SecurityException) {}

            var recordingStartTime = 0L

            activeRecording = pendingRecording.start(ContextCompat.getMainExecutor(context)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        recordingStartTime = System.currentTimeMillis()
                        _settings.value = _settings.value.copy(isRecording = true, recordingDurationSec = 0L)
                        DebugCenter.activePipeline = "Gravação de Vídeo Ativa"
                        DebugCenter.log(LogModule.CameraX, LogLevel.INFO, "Gravação iniciada: ${outputFile.name}")
                    }
                    is VideoRecordEvent.Status -> {
                        val duration = (System.currentTimeMillis() - recordingStartTime) / 1000L
                        _settings.value = _settings.value.copy(recordingDurationSec = duration)
                    }
                    is VideoRecordEvent.Finalize -> {
                        _settings.value = _settings.value.copy(isRecording = false, recordingDurationSec = 0L)
                        DebugCenter.activePipeline = "Câmera Preview (OpenGL ES Real-time)"

                        if (event.hasError()) {
                            activeRecording = null
                            val ex = RuntimeException("Erro na gravação (código ${event.error}): ${event.cause?.message}")
                            DebugCenter.logAndToastError(
                                context,
                                LogModule.CameraX,
                                "#208",
                                ex.message ?: "",
                                ex
                            )
                            mainHandler.post {
                                try {
                                    onError(ex)
                                } catch (t: Throwable) {
                                    DebugCenter.log(LogModule.CameraX, LogLevel.ERROR, "Erro no callback onError: ${t.message}", throwable = t)
                                }
                            }
                        } else {
                            activeRecording = null
                            val uri = event.outputResults.outputUri
                            val finalUri = if (uri != Uri.EMPTY) uri else Uri.fromFile(outputFile)
                            DebugCenter.log(LogModule.CameraX, LogLevel.INFO, "Gravação finalizada: $finalUri")
                            mainHandler.post {
                                try {
                                    onVideoSaved(finalUri)
                                } catch (t: Throwable) {
                                    DebugCenter.log(LogModule.CameraX, LogLevel.ERROR, "Erro no callback onVideoSaved: ${t.message}", throwable = t)
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            DebugCenter.logAndToastError(
                context,
                LogModule.CameraX,
                "#209",
                "Falha ao iniciar gravação: ${e.message}",
                e
            )
            mainHandler.post { onError(e) }
        }
    }

    fun stopRecording() {
        try {
            val rec = activeRecording
            activeRecording = null
            rec?.stop()
        } catch (e: Exception) {
            DebugCenter.log(
                LogModule.CameraX,
                LogLevel.WARN,
                "Aviso ao parar gravação: ${e.message}",
                errorCode = "#208"
            )
        }
    }

    fun release() {
        try {
            stopRecording()
        } catch (ignored: Exception) {}
        try {
            cameraProvider?.unbindAll()
        } catch (ignored: Exception) {}
        currentSurfaceProvider = null
    }
}
