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
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.DisplayOrientedMeteringPointFactory
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import java.util.concurrent.TimeUnit
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.stabilizepro.app.logs.DebugCenter
import com.stabilizepro.app.logs.LogLevel
import com.stabilizepro.app.logs.LogModule
import com.stabilizepro.app.renderer.CameraGlSurfaceView
import com.stabilizepro.app.renderer.GlRecordingPipeline
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

class CameraManager(private val context: Context) {

    companion object {
        private const val TAG = "CameraManager"
        private const val PREFS_NAME = "stabilize_camera_prefs"
        private const val KEY_AUTO_STABILIZE = "key_auto_stabilize_recording"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null

    private var currentLifecycleOwner: LifecycleOwner? = null
    private var currentSurfaceProvider: Preview.SurfaceProvider? = null
    private var glSurfaceView: CameraGlSurfaceView? = null
    private var recordingTimerRunnable: Runnable? = null

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

    private val _ultraWideLabel = MutableStateFlow("0.5x")
    val ultraWideLabel: StateFlow<String> = _ultraWideLabel.asStateFlow()

    private var backWideCameraId: String? = null
    private var backUltraWideCameraId: String? = null
    private var backTelephotoCameraId: String? = null
    private var backMacroCameraId: String? = null
    private var frontCameraId: String? = null
    private var logicalSupportsUltraWideZoom: Boolean = false
    private var ultraWideZoomRatio: Float = 0.6f
    private var isUsingPhysicalLensSelector: Boolean = false

    init {
        detectAvailableLenses()
    }

    private fun detectAvailableLenses() {
        try {
            val c2Manager = context.getSystemService(Context.CAMERA_SERVICE) as? Camera2Manager
            if (c2Manager == null) {
                _availableLenses.value = listOf(LensType.ULTRA_WIDE, LensType.WIDE, LensType.TELEPHOTO, LensType.FRONT)
                return
            }

            val publicCameraIds = c2Manager.cameraIdList.toSet()
            var foundUltraWide = false
            var foundTelephoto = false
            val candidateIds = LinkedHashSet<String>()

            // 1. Regular public IDs
            candidateIds.addAll(c2Manager.cameraIdList)

            // 2. Physical IDs from Logical Multi-Cameras on Android P+
            for (id in c2Manager.cameraIdList) {
                try {
                    val chars = c2Manager.getCameraCharacteristics(id)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        candidateIds.addAll(chars.physicalCameraIds)
                    }
                } catch (ignored: Exception) {}
            }

            // 3. Probe numeric candidate IDs (OEMs frequently map secondary physical sensors to 0..7)
            for (i in 0..7) {
                candidateIds.add(i.toString())
            }

            var mainFocalLength = 4.5f
            var ultraWideFocal = 0.0f

            for (id in candidateIds) {
                try {
                    val chars = c2Manager.getCameraCharacteristics(id)
                    val facing = chars.get(CameraCharacteristics.LENS_FACING)

                    if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
                        if (frontCameraId == null && publicCameraIds.contains(id)) frontCameraId = id
                        continue
                    }

                    if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                        val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                        val sensorSize = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                        val minFocusDist = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0.0f

                        val minFocal = focalLengths?.minOrNull() ?: 4.5f
                        val maxFocal = focalLengths?.maxOrNull() ?: 4.5f

                        val fov = if (sensorSize != null && minFocal > 0f) {
                            2.0 * Math.toDegrees(Math.atan((sensorSize.width / (2.0 * minFocal)).toDouble()))
                        } else 0.0

                        if (backWideCameraId == null || id == "0") {
                            backWideCameraId = id
                            mainFocalLength = minFocal
                        }

                        // Check zoom ratio range on Android 11+ for logical camera (e.g. 0.5x, 0.6x)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && (id == "0" || backWideCameraId == id)) {
                            val zoomRange = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                            if (zoomRange != null && zoomRange.lower < 1.0f) {
                                logicalSupportsUltraWideZoom = true
                                ultraWideZoomRatio = zoomRange.lower
                                foundUltraWide = true
                                val detectedFactor = String.format(java.util.Locale.US, "%.1fx", zoomRange.lower)
                                _ultraWideLabel.value = detectedFactor
                            }
                        }

                        // Ultra-Wide detection: focal length < 3.2mm or FOV >= 85 degrees
                        // Only consider public camera IDs that 3rd-party apps are permitted to open without OEM restriction
                        if ((minFocal < 3.2f || fov >= 85.0) && publicCameraIds.contains(id)) {
                            if (backUltraWideCameraId == null || minFocal < ultraWideFocal || ultraWideFocal == 0.0f) {
                                backUltraWideCameraId = id
                                ultraWideFocal = minFocal
                                foundUltraWide = true
                            }
                        } else if ((maxFocal > 6.5f || (fov > 0.0 && fov < 50.0)) && publicCameraIds.contains(id)) {
                            backTelephotoCameraId = id
                            foundTelephoto = true
                        } else if (minFocusDist > 10.0f && backMacroCameraId == null && publicCameraIds.contains(id)) {
                            backMacroCameraId = id
                        }
                    }
                } catch (ignored: Exception) {}
            }

            // Calibrate Ultra-Wide factor label (e.g. 0.5x vs 0.6x)
            if (foundUltraWide && !logicalSupportsUltraWideZoom && ultraWideFocal > 0f && mainFocalLength > 0f) {
                val ratio = (ultraWideFocal / mainFocalLength).coerceIn(0.4f, 0.8f)
                val label = if (ratio <= 0.55f) "0.5x" else "0.6x"
                _ultraWideLabel.value = label
                ultraWideZoomRatio = ratio
            }

            val list = mutableListOf<LensType>()
            if (logicalSupportsUltraWideZoom || (foundUltraWide && backUltraWideCameraId != null && publicCameraIds.contains(backUltraWideCameraId))) {
                list.add(LensType.ULTRA_WIDE)
            }
            list.add(LensType.WIDE)
            list.add(LensType.TELEPHOTO)
            list.add(LensType.FRONT)

            if (!list.contains(_settings.value.selectedLens)) {
                _settings.value = _settings.value.copy(selectedLens = LensType.WIDE)
            }
            _availableLenses.value = list

            DebugCenter.log(
                LogModule.CameraX,
                LogLevel.INFO,
                "Sensores Físicos: Principal=$backWideCameraId, UltraWide=$backUltraWideCameraId (${_ultraWideLabel.value}), Telefoto=$backTelephotoCameraId, Macro=$backMacroCameraId, Frontal=$frontCameraId, ZoomLógico=${logicalSupportsUltraWideZoom} (${ultraWideZoomRatio}x)"
            )
        } catch (e: Exception) {
            _availableLenses.value = listOf(LensType.ULTRA_WIDE, LensType.WIDE, LensType.TELEPHOTO, LensType.FRONT)
        }
    }

    private fun createCameraSelectorForId(targetId: String, fallback: CameraSelector): CameraSelector {
        return try {
            CameraSelector.Builder()
                .addCameraFilter { cameraInfos ->
                    val filtered = cameraInfos.filter { info ->
                        try {
                            Camera2CameraInfo.from(info).cameraId == targetId
                        } catch (e: Exception) {
                            false
                        }
                    }
                    if (filtered.isNotEmpty()) filtered else cameraInfos
                }
                .build()
        } catch (e: Exception) {
            fallback
        }
    }

    fun initialize(
        lifecycleOwner: LifecycleOwner,
        glSurfaceView: CameraGlSurfaceView,
        surfaceProvider: Preview.SurfaceProvider,
        onReady: () -> Unit = {}
    ) {
        currentLifecycleOwner = lifecycleOwner
        this.glSurfaceView = glSurfaceView
        currentSurfaceProvider = surfaceProvider

        val (w, h) = getTargetRecordingResolution(_settings.value.quality, _settings.value.aspectRatio)
        glSurfaceView.setFboResolution(w, h)

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

        if (oldSettings.quality != newSettings.quality || oldSettings.aspectRatio != newSettings.aspectRatio) {
            val (w, h) = getTargetRecordingResolution(newSettings.quality, newSettings.aspectRatio)
            glSurfaceView?.setFboResolution(w, h)
        }

        // Check if camera pipeline needs re-binding or if we can just update zoom/Camera2 controls
        val isBackToBackLensChange = 
            (oldSettings.selectedLens != newSettings.selectedLens) &&
            (oldSettings.selectedLens != LensType.FRONT && newSettings.selectedLens != LensType.FRONT) &&
            !isUsingPhysicalLensSelector

        val needsRebind = oldSettings.captureMode != newSettings.captureMode ||
            oldSettings.quality != newSettings.quality ||
            oldSettings.aspectRatio != newSettings.aspectRatio ||
            ((oldSettings.selectedLens == LensType.FRONT) != (newSettings.selectedLens == LensType.FRONT)) ||
            (!isBackToBackLensChange && oldSettings.selectedLens != newSettings.selectedLens)

        if (needsRebind) {
            bindCameraUseCases()
        } else {
            // Otherwise, update Camera2 manual controls in real-time (instant zoom, EV, WB)
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

            // 1. Camera selector with robust standard fallbacks
            val (cameraSelector, usesPhysical) = when (_settings.value.selectedLens) {
                LensType.FRONT -> {
                    Pair(CameraSelector.DEFAULT_FRONT_CAMERA, false)
                }
                LensType.ULTRA_WIDE -> {
                    if (!logicalSupportsUltraWideZoom && backUltraWideCameraId != null) {
                        Pair(createCameraSelectorForId(backUltraWideCameraId!!, CameraSelector.DEFAULT_BACK_CAMERA), true)
                    } else {
                        Pair(CameraSelector.DEFAULT_BACK_CAMERA, false)
                    }
                }
                LensType.TELEPHOTO -> {
                    if (backTelephotoCameraId != null) {
                        Pair(createCameraSelectorForId(backTelephotoCameraId!!, CameraSelector.DEFAULT_BACK_CAMERA), true)
                    } else {
                        Pair(CameraSelector.DEFAULT_BACK_CAMERA, false)
                    }
                }
                LensType.WIDE -> {
                    Pair(CameraSelector.DEFAULT_BACK_CAMERA, false)
                }
            }

            // 2. Aspect Ratio Strategy matching user choice
            val targetCameraAspect = when (_settings.value.aspectRatio) {
                CameraAspectRatio.RATIO_16_9 -> AspectRatio.RATIO_16_9
                CameraAspectRatio.RATIO_4_3 -> AspectRatio.RATIO_4_3
                CameraAspectRatio.RATIO_1_1 -> AspectRatio.RATIO_4_3
            }
            val aspectRatioStrategy = AspectRatioStrategy(
                targetCameraAspect,
                AspectRatioStrategy.FALLBACK_RULE_AUTO
            )

            // 3. Resolution Strategy for Preview: 1080p max (guaranteed supported on all Android hardware levels)
            val previewResolutionStrategy = ResolutionStrategy(
                Size(1920, 1080),
                ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER
            )
            val previewResolutionSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(aspectRatioStrategy)
                .setResolutionStrategy(previewResolutionStrategy)
                .build()

            // 4. Resolution Strategy for ImageCapture
            val imageTargetSize = when (_settings.value.quality) {
                VideoQualityOption.UHD_4K -> Size(3840, 2160)
                VideoQualityOption.QHD_2K -> Size(2560, 1440)
                VideoQualityOption.FHD_1080P -> Size(1920, 1080)
                VideoQualityOption.HD_720P -> Size(1280, 720)
                VideoQualityOption.AUTO_MAX -> Size(3840, 2160)
            }
            val imageResolutionStrategy = ResolutionStrategy(
                imageTargetSize,
                ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER
            )
            val imageResolutionSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(aspectRatioStrategy)
                .setResolutionStrategy(imageResolutionStrategy)
                .build()

            // Preview use case with resolution selector (feeds SurfaceTexture for GL FBO)
            val previewBuilder = Preview.Builder()
                .setResolutionSelector(previewResolutionSelector)

            // ImageCapture use case
            val imageCaptureBuilder = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setResolutionSelector(imageResolutionSelector)

            val displayRotation = glSurfaceView?.display?.rotation ?: android.view.Surface.ROTATION_0
            previewBuilder.setTargetRotation(displayRotation)
            imageCaptureBuilder.setTargetRotation(displayRotation)

            preview = previewBuilder.build().also {
                it.setSurfaceProvider(surfaceProvider)
            }
            imageCapture = imageCaptureBuilder.build()

            // Multi-level binding fallback: guarantees camera opens on 100% of Android hardware
            val fallbackSelector = if (_settings.value.selectedLens == LensType.FRONT) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }

            try {
                camera = provider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageCapture
                )
                isUsingPhysicalLensSelector = usesPhysical
            } catch (bindEx: Exception) {
                DebugCenter.log(
                    LogModule.CameraX,
                    LogLevel.WARN,
                    "Tentativa 1 falhou (${bindEx.message}), tentando seletor padrão."
                )
                try {
                    camera = provider.bindToLifecycle(
                        lifecycleOwner,
                        fallbackSelector,
                        preview,
                        imageCapture
                    )
                    isUsingPhysicalLensSelector = false
                } catch (bindEx2: Exception) {
                    DebugCenter.log(
                        LogModule.CameraX,
                        LogLevel.WARN,
                        "Tentativa 2 falhou (${bindEx2.message}), usando use-cases padrão."
                    )
                    val barePreview = Preview.Builder().build().also {
                        it.setSurfaceProvider(surfaceProvider)
                    }
                    val bareImageCapture = ImageCapture.Builder().build()
                    try {
                        camera = provider.bindToLifecycle(
                            lifecycleOwner,
                            fallbackSelector,
                            barePreview,
                            bareImageCapture
                        )
                        preview = barePreview
                        imageCapture = bareImageCapture
                        isUsingPhysicalLensSelector = false
                    } catch (bindEx3: Exception) {
                        // Level 4 fallback: ONLY preview
                        camera = provider.bindToLifecycle(
                            lifecycleOwner,
                            fallbackSelector,
                            barePreview
                        )
                        preview = barePreview
                        imageCapture = null
                        isUsingPhysicalLensSelector = false
                    }
                }
            }

            // Apply Camera2 manual controls
            applyCamera2Controls(_settings.value)

            DebugCenter.log(
                LogModule.CameraX,
                LogLevel.INFO,
                "CameraX inicializada: Lente=${_settings.value.selectedLens.displayName}, Físico=$isUsingPhysicalLensSelector, Resolução=${_settings.value.quality.displayName}"
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
            // When pro manual focus is active (focusDistance >= 0), override with CONTROL_AF_MODE_OFF.
            // When auto focus is active (focusDistance < 0), DO NOT set CONTROL_AF_MODE in CaptureRequestOptions.
            // Overriding CONTROL_AF_MODE in CaptureRequestOptions forcefully aborts CameraX's startFocusAndMetering() tap triggers.
            if (settings.focusDistance >= 0.0f) {
                builder.setCaptureRequestOption(
                    CaptureRequest.CONTROL_AF_MODE,
                    CameraMetadata.CONTROL_AF_MODE_OFF
                )
                builder.setCaptureRequestOption(
                    CaptureRequest.LENS_FOCUS_DISTANCE,
                    settings.focusDistance
                )
            }

            // 2. ISO / Sensor Sensitivity & 3. Shutter Speed (coordinated manual exposure)
            val isManualIso = settings.iso > 0
            val isManualShutter = settings.shutterSpeedNs > 0L

            if (isManualIso || isManualShutter) {
                builder.setCaptureRequestOption(
                    CaptureRequest.CONTROL_AE_MODE,
                    CameraMetadata.CONTROL_AE_MODE_OFF
                )
                val c2Info = try { Camera2CameraInfo.from(cam.cameraInfo) } catch (e: Exception) { null }
                val sensRange = c2Info?.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                val expRange = c2Info?.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)

                val clampedIso = if (isManualIso) {
                    if (sensRange != null) settings.iso.coerceIn(sensRange.lower, sensRange.upper) else settings.iso
                } else {
                    sensRange?.lower ?: 100
                }
                builder.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, clampedIso)

                val clampedShutter = if (isManualShutter) {
                    if (expRange != null) settings.shutterSpeedNs.coerceIn(expRange.lower, expRange.upper) else settings.shutterSpeedNs
                } else {
                    expRange?.let { (16_666_666L).coerceIn(it.lower, it.upper) } ?: 16_666_666L
                }
                builder.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, clampedShutter)
            }

            // 4. EV Compensation
            cam.cameraControl.setExposureCompensationIndex(settings.evCompensation)

            // 5. White Balance
            builder.setCaptureRequestOption(
                CaptureRequest.CONTROL_AWB_MODE,
                settings.whiteBalanceMode
            )

            // 6. HDR Scene mode when requested and supported by HAL
            if (settings.isHdrEnabled) {
                val c2Info = try { Camera2CameraInfo.from(cam.cameraInfo) } catch (e: Exception) { null }
                val availableScenes = c2Info?.getCameraCharacteristic(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES)
                if (availableScenes?.contains(CameraMetadata.CONTROL_SCENE_MODE_HDR) == true) {
                    builder.setCaptureRequestOption(
                        CaptureRequest.CONTROL_MODE,
                        CameraMetadata.CONTROL_MODE_USE_SCENE_MODE
                    )
                    builder.setCaptureRequestOption(
                        CaptureRequest.CONTROL_SCENE_MODE,
                        CameraMetadata.CONTROL_SCENE_MODE_HDR
                    )
                } else {
                    DebugCenter.log(LogModule.CameraX, LogLevel.INFO, "Modo de cena HDR não suportado pelo sensor selecionado.")
                }
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
                    LensType.ULTRA_WIDE -> {
                        if (logicalSupportsUltraWideZoom) {
                            ultraWideZoomRatio.coerceIn(minZoom, maxZoom)
                        } else if (isUsingPhysicalLensSelector && backUltraWideCameraId != null) {
                            1.0f
                        } else {
                            minZoom.coerceAtMost(ultraWideZoomRatio)
                        }
                    }
                    LensType.WIDE -> 1.0f.coerceIn(minZoom, maxZoom)
                    LensType.TELEPHOTO -> {
                        if (isUsingPhysicalLensSelector && backTelephotoCameraId != null) 1.0f
                        else 2.0f.coerceIn(minZoom, maxZoom)
                    }
                    LensType.FRONT -> 1.0f
                }
                try {
                    cam.cameraControl.setZoomRatio(targetZoom)
                } catch (ignored: Exception) {}
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

        val displayRotation = glSurfaceView?.display?.rotation ?: android.view.Surface.ROTATION_0
        capture.targetRotation = displayRotation

        val metadata = ImageCapture.Metadata().apply {
            isReversedHorizontal = (_settings.value.selectedLens == LensType.FRONT)
        }
        val outputOptions = ImageCapture.OutputFileOptions.Builder(outputFile)
            .setMetadata(metadata)
            .build()

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

    /**
     * Triggers Tap-to-Focus and Auto-Exposure metering at the specified viewfinder coordinates.
     * When [isLock] is true, disables auto-cancel to lock focus and exposure until unlocked.
     */
    fun focusAndMeter(
        x: Float,
        y: Float,
        viewWidth: Int,
        viewHeight: Int,
        isLock: Boolean = false
    ) {
        val cam = camera ?: return
        if (viewWidth <= 0 || viewHeight <= 0) return
        try {
            val display = glSurfaceView?.display ?: run {
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager
                @Suppress("DEPRECATION")
                wm?.defaultDisplay
            }

            val aspect = _settings.value.aspectRatio.ratioValue
            val screenAspect = viewWidth.toFloat() / viewHeight.coerceAtLeast(1).toFloat()

            val previewWidth: Float
            val previewHeight: Float
            val previewLeft: Float
            val previewTop: Float

            if (screenAspect < aspect) {
                previewWidth = viewWidth.toFloat()
                previewHeight = viewWidth / aspect
                previewLeft = 0f
                previewTop = (viewHeight - previewHeight) / 2f
            } else {
                previewHeight = viewHeight.toFloat()
                previewWidth = viewHeight * aspect
                previewLeft = (viewWidth - previewWidth) / 2f
                previewTop = 0f
            }

            val clampedTapX = (x - previewLeft).coerceIn(0f, previewWidth)
            val clampedTapY = (y - previewTop).coerceIn(0f, previewHeight)

            val factory = if (display != null) {
                DisplayOrientedMeteringPointFactory(display, cam.cameraInfo, previewWidth, previewHeight)
            } else {
                preview?.let {
                    SurfaceOrientedMeteringPointFactory(previewWidth, previewHeight, it)
                } ?: SurfaceOrientedMeteringPointFactory(previewWidth, previewHeight)
            }

            val point = factory.createPoint(clampedTapX, clampedTapY)

            // Dynamically detect if AF and AE are supported for metering on this sensor
            val testAf = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF).build()
            val supportsAf = cam.cameraInfo.isFocusMeteringSupported(testAf)

            val testAe = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AE).build()
            val supportsAe = cam.cameraInfo.isFocusMeteringSupported(testAe)

            var flags = 0
            if (supportsAf) flags = flags or FocusMeteringAction.FLAG_AF
            if (supportsAe) flags = flags or FocusMeteringAction.FLAG_AE

            if (flags == 0) {
                flags = FocusMeteringAction.FLAG_AF
            }

            val builder = FocusMeteringAction.Builder(point, flags)
            if (isLock) {
                builder.disableAutoCancel()
            } else {
                builder.setAutoCancelDuration(4000, TimeUnit.MILLISECONDS)
            }
            val action = builder.build()

            val future = cam.cameraControl.startFocusAndMetering(action)
            future.addListener({
                try {
                    val result = future.get()
                    DebugCenter.log(
                        LogModule.CameraX,
                        LogLevel.INFO,
                        "Foco completado. Sucesso=${result.isFocusSuccessful}, flags=$flags"
                    )
                } catch (e: Exception) {
                    DebugCenter.log(
                        LogModule.CameraX,
                        LogLevel.WARN,
                        "Aviso convergência foco: ${e.message}"
                    )
                }
            }, ContextCompat.getMainExecutor(context))

            DebugCenter.log(
                LogModule.CameraX,
                LogLevel.INFO,
                "Foco acionado em (${clampedTapX.toInt()}, ${clampedTapY.toInt()}) [viewport ${previewWidth.toInt()}x${previewHeight.toInt()}], lock=$isLock, flags=$flags"
            )
        } catch (e: Exception) {
            DebugCenter.log(
                LogModule.CameraX,
                LogLevel.WARN,
                "Erro ao acionar foco/fotometria: ${e.message}"
            )
        }
    }

    fun unlockFocusAndMetering() {
        val cam = camera ?: return
        try {
            cam.cameraControl.cancelFocusAndMetering()
            DebugCenter.log(LogModule.CameraX, LogLevel.INFO, "Foco e fotometria destravados.")
        } catch (e: Exception) {
            DebugCenter.log(LogModule.CameraX, LogLevel.WARN, "Erro ao cancelar foco: ${e.message}")
        }
    }

    fun setExposureCompensation(biasIndex: Int) {
        val cam = camera ?: return
        try {
            val exposureState = cam.cameraInfo.exposureState
            val range = exposureState.exposureCompensationRange
            val clamped = biasIndex.coerceIn(range.lower, range.upper)
            cam.cameraControl.setExposureCompensationIndex(clamped)
        } catch (e: Exception) {
            DebugCenter.log(LogModule.CameraX, LogLevel.WARN, "Erro ao ajustar exposição: ${e.message}")
        }
    }

    fun getExposureCompensationRange(): android.util.Range<Int> {
        return camera?.cameraInfo?.exposureState?.exposureCompensationRange ?: android.util.Range(0, 0)
    }

    fun getExposureCompensationIndex(): Int {
        return camera?.cameraInfo?.exposureState?.exposureCompensationIndex ?: 0
    }

    fun getTargetRecordingResolution(
        quality: VideoQualityOption,
        aspectRatio: CameraAspectRatio
    ): Pair<Int, Int> {
        val baseShortDim = when (quality) {
            VideoQualityOption.UHD_4K -> 2160
            VideoQualityOption.QHD_2K -> 1440
            VideoQualityOption.FHD_1080P -> 1080
            VideoQualityOption.HD_720P -> 720
            VideoQualityOption.AUTO_MAX -> 2160 // Inicia no limite superior (4K) e é reduzido pelas capacidades reais do encoder
        }
        val (rawW, rawH) = when (aspectRatio) {
            CameraAspectRatio.RATIO_16_9 -> {
                val longDim = (baseShortDim * 16 / 9) / 16 * 16
                Pair(baseShortDim, longDim)
            }
            CameraAspectRatio.RATIO_4_3 -> {
                val longDim = (baseShortDim * 4 / 3) / 16 * 16
                Pair(baseShortDim, longDim)
            }
            CameraAspectRatio.RATIO_1_1 -> {
                Pair(baseShortDim, baseShortDim)
            }
        }
        return GlRecordingPipeline.getSupportedVideoResolution(rawW, rawH)
    }

    fun startRecording(
        outputFile: File,
        onVideoSaved: (Uri) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val glView = glSurfaceView ?: run {
            val ex = IllegalStateException("CameraGlSurfaceView não está inicializado.")
            DebugCenter.logAndToastError(context, LogModule.CameraX, "#207", ex.message ?: "", ex)
            mainHandler.post { onError(ex) }
            return
        }

        try {
            val (width, height) = getTargetRecordingResolution(_settings.value.quality, _settings.value.aspectRatio)
            val fps = _settings.value.targetFps
            val recordingStartTime = System.currentTimeMillis()

            _settings.value = _settings.value.copy(isRecording = true, recordingDurationSec = 0L)
            DebugCenter.activePipeline = "Gravação de Vídeo OpenGL ES → FBO → MediaCodec"
            DebugCenter.log(LogModule.CameraX, LogLevel.INFO, "Gravação iniciada: ${outputFile.name} (${width}×${height})")

            recordingTimerRunnable?.let { mainHandler.removeCallbacks(it) }
            val timerRunnable = object : Runnable {
                override fun run() {
                    if (_settings.value.isRecording) {
                        val duration = (System.currentTimeMillis() - recordingStartTime) / 1000L
                        _settings.value = _settings.value.copy(recordingDurationSec = duration)
                        mainHandler.postDelayed(this, 1000L)
                    }
                }
            }
            recordingTimerRunnable = timerRunnable
            mainHandler.postDelayed(timerRunnable, 1000L)

            glView.startRecording(
                outputFile = outputFile,
                width = width,
                height = height,
                fps = fps,
                onVideoSaved = { uri ->
                    recordingTimerRunnable?.let { mainHandler.removeCallbacks(it) }
                    _settings.value = _settings.value.copy(isRecording = false, recordingDurationSec = 0L)
                    DebugCenter.activePipeline = "Câmera Preview (OpenGL ES FBO Pipeline)"
                    DebugCenter.log(LogModule.CameraX, LogLevel.INFO, "Gravação finalizada: $uri")
                    mainHandler.post {
                        try {
                            onVideoSaved(uri)
                        } catch (t: Throwable) {
                            DebugCenter.log(LogModule.CameraX, LogLevel.ERROR, "Erro no callback onVideoSaved: ${t.message}", throwable = t)
                        }
                    }
                },
                onError = { ex ->
                    recordingTimerRunnable?.let { mainHandler.removeCallbacks(it) }
                    _settings.value = _settings.value.copy(isRecording = false, recordingDurationSec = 0L)
                    DebugCenter.activePipeline = "Câmera Preview (OpenGL ES FBO Pipeline)"
                    val errCode = if (ex.message?.contains("#R01") == true) "#R01"
                        else if (ex.message?.contains("#R02") == true) "#R02"
                        else if (ex.message?.contains("#R03") == true) "#R03"
                        else "#208"
                    DebugCenter.logAndToastError(
                        context,
                        LogModule.CameraX,
                        errCode,
                        ex.message ?: "Falha durante a gravação",
                        ex
                    )
                    mainHandler.post {
                        try {
                            onError(ex)
                        } catch (t: Throwable) {
                            DebugCenter.log(LogModule.CameraX, LogLevel.ERROR, "Erro no callback onError: ${t.message}", throwable = t)
                        }
                    }
                }
            )
        } catch (e: Exception) {
            _settings.value = _settings.value.copy(isRecording = false, recordingDurationSec = 0L)
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
            recordingTimerRunnable?.let { mainHandler.removeCallbacks(it) }
            glSurfaceView?.stopRecording()
            _settings.value = _settings.value.copy(isRecording = false, recordingDurationSec = 0L)
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
        glSurfaceView = null
    }
}
