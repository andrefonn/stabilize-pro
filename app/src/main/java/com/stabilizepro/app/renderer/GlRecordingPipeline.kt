package com.stabilizepro.app.renderer

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.core.content.ContextCompat
import com.stabilizepro.app.logs.DebugCenter
import com.stabilizepro.app.logs.LogLevel
import com.stabilizepro.app.logs.LogModule
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Offscreen OpenGL ES → MediaCodec recording pipeline.
 *
 * Architecture:
 *   Camera OES → ColorGradingShader (14 uniforms) → Master FBO
 *       ├── Rendered to GLSurfaceView (Preview)
 *       └── Rendered to MediaCodec Input Surface (Recording via [renderFboFrame])
 *
 * Preview and Video share the exact same FBO texture, ensuring identical pixels.
 *
 * Mandatory Specs:
 *   - Video Timestamp: SurfaceTexture timestamp via EGLExt.eglPresentationTimeANDROID
 *   - Backpressure: Non-blocking dequeue (timeout 0), drop frame if busy, never stall GL thread
 *   - Color Space: BT.709, Full Range, YUV420, H.264 High Profile
 *   - Bitrate: Proportional to max dimension (40 Mbps for 4K, 25 Mbps for 2K, 16 Mbps for 1080p)
 *   - Audio: Dedicated AudioRecord thread → MediaCodec AAC → MediaMuxer with graceful video-only fallback
 *   - Buffer Queue: Pre-muxer frame queue guarantees zero dropped keyframes and prevents 0-byte files
 */
class GlRecordingPipeline(private val context: Context) {

    companion object {
        private const val TAG = "GlRecordingPipeline"

        private const val VIDEO_MIME = "video/avc"
        private const val VIDEO_FRAME_RATE = 30
        private const val VIDEO_I_FRAME_INTERVAL = 1

        private const val VIDEO_BITRATE_4K    = 40_000_000
        private const val VIDEO_BITRATE_2K    = 25_000_000
        private const val VIDEO_BITRATE_1080P = 16_000_000
        private const val VIDEO_BITRATE_720P  = 8_000_000

        private const val AUDIO_MIME = "audio/mp4a-latm"
        private const val AUDIO_SAMPLE_RATE = 44100
        private const val AUDIO_CHANNELS = 2
        private const val AUDIO_BITRATE = 192_000
        // Wait up to 1500ms for audio track before fallback to video-only.
        // Early video frames are safely buffered in pendingFrames.
        private const val AUDIO_TIMEOUT_MS = 1500L

        // Standard quad geometry: X, Y, U, V
        private val QUAD_VERTICES = floatArrayOf(
            -1.0f, -1.0f, 0.0f, 0.0f,
             1.0f, -1.0f, 1.0f, 0.0f,
            -1.0f,  1.0f, 0.0f, 1.0f,
             1.0f,  1.0f, 1.0f, 1.0f
        )
        private const val STRIDE = 4 * 4

        private val CANDIDATE_CONFIG_SPECS = listOf(
            // 1. RGBA8888 + RECORDABLE
            intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                0x3142, 1, // EGL_RECORDABLE_ANDROID
                EGL14.EGL_NONE
            ),
            // 2. RGB888 + RECORDABLE
            intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                0x3142, 1, // EGL_RECORDABLE_ANDROID
                EGL14.EGL_NONE
            ),
            // 3. RGBA8888 standard
            intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_NONE
            ),
            // 4. RGB888 standard
            intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_NONE
            )
        )

        /**
         * Clamps requested video dimensions to the maximum dimensions supported by the device's H.264 hardware encoder.
         * Queries the primary default encoder directly to guarantee exact profile and capability alignment.
         */
        fun getSupportedVideoResolution(requestedWidth: Int, requestedHeight: Int): Pair<Int, Int> {
            try {
                // First test capabilities of the default encoder instance
                val defaultEncoder = try {
                    MediaCodec.createEncoderByType(VIDEO_MIME)
                } catch (e: Exception) {
                    null
                }
                val defaultCaps = defaultEncoder?.codecInfo?.getCapabilitiesForType(VIDEO_MIME)?.videoCapabilities
                defaultEncoder?.release()

                if (defaultCaps != null) {
                    if (defaultCaps.isSizeSupported(requestedWidth, requestedHeight)) {
                        return Pair(requestedWidth, requestedHeight)
                    }

                    val isPortrait = requestedHeight >= requestedWidth
                    val aspect = if (isPortrait) {
                        requestedWidth.toDouble() / requestedHeight.toDouble()
                    } else {
                        requestedHeight.toDouble() / requestedWidth.toDouble()
                    }

                    val candidateShortDims = intArrayOf(2160, 1440, 1080, 720, 540, 480)
                    for (shortDim in candidateShortDims) {
                        val (w, h) = if (isPortrait) {
                            val w = shortDim
                            val h = (((w / aspect) / 16).toInt()) * 16
                            Pair(w, h)
                        } else {
                            val h = shortDim
                            val w = (((h / aspect) / 16).toInt()) * 16
                            Pair(w, h)
                        }
                        if (defaultCaps.isSizeSupported(w, h)) {
                            Log.w(TAG, "Ajustando resolução ${requestedWidth}x${requestedHeight} para ${w}x${h} via encoder padrão")
                            return Pair(w, h)
                        }
                    }
                }

                // Fallback: iterate over regular codecs list
                val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
                for (info in codecList.codecInfos) {
                    if (!info.isEncoder) continue
                    val types = info.supportedTypes
                    for (type in types) {
                        if (type.equals(VIDEO_MIME, ignoreCase = true)) {
                            val caps = info.getCapabilitiesForType(VIDEO_MIME)?.videoCapabilities ?: continue
                            if (caps.isSizeSupported(requestedWidth, requestedHeight)) {
                                return Pair(requestedWidth, requestedHeight)
                            }

                            val isPortrait = requestedHeight >= requestedWidth
                            val aspect = if (isPortrait) {
                                requestedWidth.toDouble() / requestedHeight.toDouble()
                            } else {
                                requestedHeight.toDouble() / requestedWidth.toDouble()
                            }

                            val candidateShortDims = intArrayOf(2160, 1440, 1080, 720, 540, 480)
                            for (shortDim in candidateShortDims) {
                                val (w, h) = if (isPortrait) {
                                    val w = shortDim
                                    val h = (((w / aspect) / 16).toInt()) * 16
                                    Pair(w, h)
                                } else {
                                    val h = shortDim
                                    val w = (((h / aspect) / 16).toInt()) * 16
                                    Pair(w, h)
                                }
                                if (caps.isSizeSupported(w, h)) {
                                    Log.w(TAG, "Ajustando resolução não suportada ${requestedWidth}x${requestedHeight} para ${w}x${h}")
                                    return Pair(w, h)
                                }
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
                try {
                    Log.w(TAG, "Erro ao consultar MediaCodecList: ${e.message}")
                } catch (ignored: Throwable) {}
            }

            val isPortrait = requestedHeight >= requestedWidth
            return if (isPortrait && requestedWidth > 1080) {
                val aspect = requestedWidth.toDouble() / requestedHeight.toDouble()
                val w = 1080
                val h = (((w / aspect) / 16).toInt()) * 16
                Pair(w, h)
            } else if (!isPortrait && requestedHeight > 1080) {
                val aspect = requestedHeight.toDouble() / requestedWidth.toDouble()
                val h = 1080
                val w = (((h / aspect) / 16).toInt()) * 16
                Pair(w, h)
            } else {
                Pair(requestedWidth, requestedHeight)
            }
        }
    }

    private class CachedFrame(
        val isVideo: Boolean,
        val data: ByteBuffer,
        val info: MediaCodec.BufferInfo
    )

    // EGL objects
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglRecordSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    // 2D Blit GL objects (used to blit FBO texture to codec surface)
    private var blitProgram = 0
    private var locPosition = -1
    private var locTexCoord = -1
    private var locMVP = -1
    private var locTexture = -1
    private val mvpIdentity = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    private val vertexBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(QUAD_VERTICES.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply { put(QUAD_VERTICES); position(0) }

    // Video encoder
    private var videoCodec: MediaCodec? = null
    private var videoCodecSurface: Surface? = null
    private var videoWidth = 1080
    private var videoHeight = 1920

    // Audio encoder + record
    private var audioCodec: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var audioThread: Thread? = null
    private var hasAudio = true

    // Muxer & pending frames queue
    private var muxer: MediaMuxer? = null
    private val muxerLock = Any()
    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    private val muxerStarted = AtomicBoolean(false)
    private var videoTrackAdded = false
    private var audioTrackAdded = false
    private val pendingFrames = ArrayList<CachedFrame>()
    private var recordingStartTimeMs = 0L

    // Controle e instrumentação anti-0 bytes
    private var formatChanged = false
    private var videoSamplesWritten = 0L
    private var audioSamplesWritten = 0L
    val samplesWritten: Long get() = videoSamplesWritten + audioSamplesWritten
    private var totalBytesWritten = 0L
    private var eosReceived = false
    private val fatalErrorOccurred = AtomicBoolean(false)
    private var firstFatalError: Exception? = null

    // Timing and synchronization
    private var firstVideoPtsUs = -1L
    private var lastVideoPtsUs = -1L
    private var firstAudioPtsUs = -1L
    private var lastAudioPtsUs = -1L
    private var recordingStartSensorNs = -1L
    private var lastSubmittedPtsNs = -1L

    // Diagnostic counter: how many eglSwapBuffers calls completed successfully
    private var framesSubmittedToEncoder = 0

    // State
    val isRecording = AtomicBoolean(false)
    private var outputFile: File? = null
    private var onVideoSaved: ((Uri) -> Unit)? = null
    private var onError: ((Exception) -> Unit)? = null

    /**
     * Start recording.
     * MUST be called from the GL thread where [sharedEglContext] is active.
     */
    fun start(
        outputFile: File,
        width: Int,
        height: Int,
        fps: Int = VIDEO_FRAME_RATE,
        sharedEglContext: EGLContext,
        onVideoSaved: (Uri) -> Unit,
        onError: (Exception) -> Unit
    ) {
        if (isRecording.get()) return

        this.outputFile = outputFile
        this.onVideoSaved = onVideoSaved
        this.onError = onError
        val (resW, resH) = getSupportedVideoResolution(width, height)
        this.videoWidth = resW
        this.videoHeight = resH
        this.firstVideoPtsUs = -1L
        this.lastVideoPtsUs = -1L
        this.firstAudioPtsUs = -1L
        this.lastAudioPtsUs = -1L
        this.recordingStartSensorNs = -1L
        this.lastSubmittedPtsNs = -1L
        this.framesSubmittedToEncoder = 0
        this.recordingStartTimeMs = SystemClock.uptimeMillis()
        this.formatChanged = false
        this.videoSamplesWritten = 0L
        this.audioSamplesWritten = 0L
        this.totalBytesWritten = 0L
        this.eosReceived = false
        this.fatalErrorOccurred.set(false)
        this.firstFatalError = null

        try {
            // Correct bitrate based on maximum dimension (portrait or landscape)
            val maxDim = maxOf(resW, resH)
            val videoBitrate = when {
                maxDim >= 3840 -> VIDEO_BITRATE_4K
                maxDim >= 2560 -> VIDEO_BITRATE_2K
                maxDim >= 1920 -> VIDEO_BITRATE_1080P
                else           -> VIDEO_BITRATE_720P
            }

            // 1. Configure Video Format: BT.709, Full Range, YUV420 (COLOR_FormatSurface)
            val vc = MediaCodec.createEncoderByType(VIDEO_MIME)
            val caps = try { vc.codecInfo.getCapabilitiesForType(VIDEO_MIME) } catch (e: Exception) { null }
            val supportedProfiles = caps?.profileLevels?.map { it.profile } ?: emptyList()

            val profilesToTry = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val list = mutableListOf<Int?>()
                if (supportedProfiles.contains(MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)) {
                    list.add(MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
                }
                if (supportedProfiles.contains(MediaCodecInfo.CodecProfileLevel.AVCProfileMain)) {
                    list.add(MediaCodecInfo.CodecProfileLevel.AVCProfileMain)
                }
                list.add(null) // Fallback to baseline
                list
            } else {
                listOf(null)
            }

            var configured = false
            for (profile in profilesToTry) {
                val videoFormat = MediaFormat.createVideoFormat(VIDEO_MIME, resW, resH).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, videoBitrate)
                    setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, VIDEO_I_FRAME_INTERVAL)

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
                        setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_FULL)
                        setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
                    }

                    if (profile != null) {
                        setInteger(MediaFormat.KEY_PROFILE, profile)
                        // Never hardcode KEY_LEVEL: MediaCodec selects the required level based on macroblocks and fps!
                    }
                }

                try {
                    vc.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    configured = true
                    Log.i(TAG, "MediaCodec configurado com sucesso (perfil: $profile, ${resW}x${resH})")
                    break
                } catch (cfgEx: Exception) {
                    Log.w(TAG, "Perfil $profile rejeitado para ${resW}x${resH}: ${cfgEx.message}")
                }
            }

            if (!configured) {
                throw IllegalStateException("Não foi possível configurar MediaCodec para ${resW}x${resH}")
            }

            videoCodecSurface = vc.createInputSurface()
            vc.start()
            videoCodec = vc

            // 2. Create EGLSurface targeting the MediaCodec surface in the shared EGLContext
            eglDisplay = if (sharedEglContext != EGL14.EGL_NO_CONTEXT) {
                val curDisplay = EGL14.eglGetCurrentDisplay()
                if (curDisplay != EGL14.EGL_NO_DISPLAY) curDisplay else EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            } else {
                EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            }
            eglContext = sharedEglContext

            var chosenConfig: EGLConfig? = null
            // Query exact config of the shared context for 100% compatibility (prevents EGL_BAD_MATCH)
            val configId = IntArray(1)
            if (EGL14.eglQueryContext(eglDisplay, sharedEglContext, EGL14.EGL_CONFIG_ID, configId, 0) && configId[0] != 0) {
                val exactAttrs = intArrayOf(EGL14.EGL_CONFIG_ID, configId[0], EGL14.EGL_NONE)
                val matched = arrayOfNulls<EGLConfig>(1)
                val matchedCount = IntArray(1)
                if (EGL14.eglChooseConfig(eglDisplay, exactAttrs, 0, matched, 0, 1, matchedCount, 0) && matchedCount[0] > 0 && matched[0] != null) {
                    try {
                        val testSurface = EGL14.eglCreateWindowSurface(
                            eglDisplay, matched[0], videoCodecSurface, intArrayOf(EGL14.EGL_NONE), 0
                        )
                        if (testSurface != EGL14.EGL_NO_SURFACE && EGL14.eglGetError() == EGL14.EGL_SUCCESS) {
                            chosenConfig = matched[0]
                            eglRecordSurface = testSurface
                            Log.i(TAG, "EGLSurface criada com o config ID exato do contexto GL: ${configId[0]}")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Tentativa com config idêntico falhou: ${e.message}")
                    }
                }
            }

            if (eglRecordSurface == EGL14.EGL_NO_SURFACE) {
                val configs = arrayOfNulls<EGLConfig>(1)
                val numConfigs = IntArray(1)
                for (spec in CANDIDATE_CONFIG_SPECS) {
                    if (EGL14.eglChooseConfig(eglDisplay, spec, 0, configs, 0, 1, numConfigs, 0) && numConfigs[0] > 0 && configs[0] != null) {
                        val testSurface = EGL14.eglCreateWindowSurface(
                            eglDisplay, configs[0], videoCodecSurface, intArrayOf(EGL14.EGL_NONE), 0
                        )
                        if (testSurface != EGL14.EGL_NO_SURFACE && EGL14.eglGetError() == EGL14.EGL_SUCCESS) {
                            chosenConfig = configs[0]
                            eglRecordSurface = testSurface
                            break
                        }
                    }
                }
            }
            if (eglRecordSurface == EGL14.EGL_NO_SURFACE) {
                val err = EGL14.eglGetError()
                throw RuntimeException("Falha crítica ao criar EGLSurface para gravação (EGL error 0x${Integer.toHexString(err)})")
            }

            // Fail-fast test: verify that eglMakeCurrent works with this surface and context
            val origDraw = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
            val origRead = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)
            val testOk = EGL14.eglMakeCurrent(eglDisplay, eglRecordSurface, eglRecordSurface, eglContext)
            if (!testOk) {
                val err = EGL14.eglGetError()
                throw IllegalStateException("Falha de compatibilidade EGL: contexto GL rejeitou a superfície do encoder (EGL error 0x${Integer.toHexString(err)}).")
            }
            if (origDraw != EGL14.EGL_NO_SURFACE) {
                EGL14.eglMakeCurrent(eglDisplay, origDraw, origRead, eglContext)
            }

            // 3. Compile standard 2D texture blit program for codec surface
            blitProgram = ColorGradingShader.createProgram(
                ColorGradingShader.BLIT_VERTEX_SHADER,
                ColorGradingShader.BLIT_FRAGMENT_SHADER
            )
            locPosition = GLES20.glGetAttribLocation(blitProgram, "aPosition")
            locTexCoord = GLES20.glGetAttribLocation(blitProgram, "aTextureCoord")
            locMVP      = GLES20.glGetUniformLocation(blitProgram, "uMVPMatrix")
            locTexture  = GLES20.glGetUniformLocation(blitProgram, "sTexture")

            // 4. Initialize MediaMuxer
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxerStarted.set(false)
            videoTrackAdded = false
            audioTrackAdded = false
            videoTrackIndex = -1
            audioTrackIndex = -1
            synchronized(muxerLock) {
                pendingFrames.clear()
            }

            // 5. Mark recording active BEFORE starting audio thread to prevent race condition
            isRecording.set(true)
            startAudioPipeline()

            DebugCenter.log(LogModule.CameraX, LogLevel.INFO, "GlRecordingPipeline: gravação iniciada (${resW}×${resH}, ${videoBitrate / 1_000_000} Mbps) via FBO")
        } catch (e: Exception) {
            DebugCenter.logAndToastError(context, LogModule.CameraX, "#R01", "Falha ao iniciar pipeline: ${e.message}", e)
            releaseInternal()
            onError(e)
        }
    }

    /**
     * Renders the master FBO texture into the MediaCodec surface.
     * MUST be called from the GL thread (inside [CameraGlRenderer.onDrawFrame]).
     *
     * Dynamically preserves and restores the caller's EGL draw/read surfaces.
     *
     * @param fboTextureId The GL_TEXTURE_2D texture containing the post-processed frame.
     * @param frameTimestampNs Exact timestamp from SurfaceTexture.getTimestamp() (nanoseconds).
     */
    fun renderFboFrame(fboTextureId: Int, frameTimestampNs: Long) {
        if (!isRecording.get() || eglRecordSurface == EGL14.EGL_NO_SURFACE) return

        val origDrawSurface = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
        val origReadSurface = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)

        try {
            // Switch to MediaCodec's EGL surface (same context, different surface)
            val makeOk = EGL14.eglMakeCurrent(eglDisplay, eglRecordSurface, eglRecordSurface, eglContext)
            if (!makeOk) {
                val err = EGL14.eglGetError()
                val msg = "renderFboFrame: eglMakeCurrent falhou (EGL error 0x${Integer.toHexString(err)})"
                Log.e(TAG, msg)
                if (fatalErrorOccurred.compareAndSet(false, true)) {
                    firstFatalError = IllegalStateException(msg)
                }
                return
            }

            // High-precision normalized monotonic timestamp generator:
            // 1. Calculate delta relative to recording start
            // 2. Guarantee strictly increasing PTS (at least 1ms advance)
            val rawNs = if (frameTimestampNs > 0L) frameTimestampNs else SystemClock.elapsedRealtimeNanos()
            if (recordingStartSensorNs < 0L) {
                recordingStartSensorNs = rawNs
            }
            val relativeDeltaNs = rawNs - recordingStartSensorNs
            val ptsNs = if (relativeDeltaNs > lastSubmittedPtsNs) {
                relativeDeltaNs
            } else {
                lastSubmittedPtsNs + 1_000_000L // Strictly monotonic advance (minimum 1ms)
            }
            lastSubmittedPtsNs = ptsNs

            EGLExt.eglPresentationTimeANDROID(eglDisplay, eglRecordSurface, ptsNs)

            GLES20.glViewport(0, 0, videoWidth, videoHeight)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            GLES20.glUseProgram(blitProgram)

            vertexBuffer.position(0)
            GLES20.glVertexAttribPointer(locPosition, 2, GLES20.GL_FLOAT, false, STRIDE, vertexBuffer)
            GLES20.glEnableVertexAttribArray(locPosition)

            vertexBuffer.position(2)
            GLES20.glVertexAttribPointer(locTexCoord, 2, GLES20.GL_FLOAT, false, STRIDE, vertexBuffer)
            GLES20.glEnableVertexAttribArray(locTexCoord)

            // Bind the FBO 2D texture (standard GL_TEXTURE_2D)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTextureId)
            GLES20.glUniform1i(locTexture, 0)

            GLES20.glUniformMatrix4fv(locMVP, 1, false, mvpIdentity, 0)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            GLES20.glDisableVertexAttribArray(locPosition)
            GLES20.glDisableVertexAttribArray(locTexCoord)

            // Submit frame to encoder
            val swapOk = EGL14.eglSwapBuffers(eglDisplay, eglRecordSurface)
            if (swapOk) {
                framesSubmittedToEncoder++
            } else {
                Log.w(TAG, "renderFboFrame: eglSwapBuffers falhou (0x${Integer.toHexString(EGL14.eglGetError())})")
            }

            // Backpressure guarantee: non-blocking drain (timeout 0)
            drainVideoCodec(false)

        } catch (e: Exception) {
            Log.w(TAG, "renderFboFrame error: ${e.message}")
        } finally {
            // Restore caller's original EGL surface
            if (origDrawSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglMakeCurrent(eglDisplay, origDrawSurface, origReadSurface, eglContext)
            }
        }
    }

    /**
     * Stop recording and finalize MP4 file.
     * MUST be called from the GL thread.
     */
    fun stop() {
        if (!isRecording.compareAndSet(true, false)) return
        Log.i(TAG, "stop: framesSubmittedToEncoder=$framesSubmittedToEncoder, videoTrackAdded=$videoTrackAdded, videoSamples=$videoSamplesWritten, audioSamples=$audioSamplesWritten")

        try {
            if (eglRecordSurface != EGL14.EGL_NO_SURFACE) {
                val origDraw = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
                val origRead = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)
                val makeOk = EGL14.eglMakeCurrent(eglDisplay, eglRecordSurface, eglRecordSurface, eglContext)
                if (makeOk) {
                    drainVideoCodec(true)
                }
                if (origDraw != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglMakeCurrent(eglDisplay, origDraw, origRead, eglContext)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "stop: drain error: ${e.message}")
        } finally {
            // Immediately destroy GL surface and program on GL thread so GL preview is never stalled
            try {
                if (eglRecordSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(eglDisplay, eglRecordSurface)
                    eglRecordSurface = EGL14.EGL_NO_SURFACE
                }
            } catch (ignored: Exception) {}
            try {
                if (blitProgram != 0) {
                    GLES20.glDeleteProgram(blitProgram)
                    blitProgram = 0
                }
            } catch (ignored: Exception) {}
        }

        // Finalize audio, muxer, validation and callbacks asynchronously on a background thread.
        // The GL thread returns immediately so camera preview continues rendering smoothly!
        Thread({
            finalizeRecordingAsync()
        }, "GlRecPipeline-Finalize").apply {
            isDaemon = true
            start()
        }
    }

    private fun finalizeRecordingAsync() {
        // Wait for audio pipeline completion (non-blocking for GL thread)
        try {
            audioThread?.join(1500)
        } catch (ignored: InterruptedException) {}

        val file = outputFile
        try {
            synchronized(muxerLock) {
                val m = muxer
                if (m != null) {
                    if (!muxerStarted.get() && videoTrackAdded) {
                        // Force-start muxer (video-only if audio wasn't added) to flush queued frames
                        startMuxerLocked(m)
                    }
                    if (muxerStarted.getAndSet(false)) {
                        try {
                            if (samplesWritten > 0L) {
                                Log.i(TAG, "[MUXER_STOP] Finalizando MediaMuxer com sucesso ($samplesWritten samples [$videoSamplesWritten vídeo, $audioSamplesWritten áudio], $totalBytesWritten bytes)")
                                m.stop()
                            } else {
                                Log.w(TAG, "[MUXER_STOP] MediaMuxer iniciado porém 0 samples foram escritos ($samplesWritten). Ignorando m.stop().")
                            }
                        } catch (stopEx: Exception) {
                            Log.w(TAG, "Aviso ao parar MediaMuxer: ${stopEx.message}")
                        }
                    } else {
                        Log.w(TAG, "[MUXER_NEVER_STARTED] MediaMuxer nunca foi iniciado")
                    }
                    try {
                        m.release()
                    } catch (relEx: Exception) {
                        Log.w(TAG, "Aviso ao liberar MediaMuxer: ${relEx.message}")
                    }
                }
                muxer = null
                pendingFrames.clear()
            }

            val isValid = file != null && file.exists() && file.length() > 1024L && videoSamplesWritten > 0L
            if (isValid) {
                DebugCenter.log(LogModule.CameraX, LogLevel.INFO, "GlRecordingPipeline: vídeo salvo com sucesso (${file!!.length()} bytes, $videoSamplesWritten amostras de vídeo, $audioSamplesWritten amostras de áudio)")
                val savedUri = Uri.fromFile(file)
                onVideoSaved?.invoke(savedUri)
            } else {
                val len = file?.length() ?: 0L
                try { file?.delete() } catch (ignored: Exception) {}
                val rootEx = firstFatalError
                val reason = when {
                    framesSubmittedToEncoder == 0 -> "Nenhum frame submetido ao encoder (falha no pipeline GL ou eglMakeCurrent)."
                    videoSamplesWritten == 0L -> "MediaCodec não gerou amostras de vídeo graváveis no muxer."
                    else -> "Arquivo final com tamanho insuficiente ($len bytes <= 1024B)."
                }
                val ex = RuntimeException("Gravação de vídeo inválida: $reason (tamanho=$len bytes, videoSamples=$videoSamplesWritten, audioSamples=$audioSamplesWritten, framesSubmitted=$framesSubmittedToEncoder, formatChanged=$formatChanged).", rootEx)
                DebugCenter.log(LogModule.CameraX, LogLevel.ERROR, ex.message ?: "Arquivo vazio", errorCode = "#208")
                onError?.invoke(ex)
            }
        } catch (e: Exception) {
            DebugCenter.logAndToastError(context, LogModule.CameraX, "#R02", "Erro ao finalizar gravação: ${e.message}", e)
            onError?.invoke(e)
        } finally {
            releaseInternal()
        }
    }


    // ─────────────────────────────────────────────────────────────────────
    // Video Encoder Drain (Non-blocking for backpressure protection)
    // ─────────────────────────────────────────────────────────────────────

    private fun drainVideoCodec(endOfStream: Boolean) {
        val codec = videoCodec ?: return

        if (endOfStream) {
            try {
                codec.signalEndOfInputStream()
                Log.i(TAG, "drainVideoCodec: signalEndOfInputStream enviado")
            } catch (ignored: Exception) {}
        }

        val bufferInfo = MediaCodec.BufferInfo()
        var consecutiveEmptyCycles = 0
        val maxEmptyCycles = if (endOfStream) 60 else 1

        while (true) {
            val timeoutUs = if (endOfStream) 10_000L else 0L
            val encoderStatus = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)

            when {
                encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) {
                        // Backpressure protection: don't loop or stall, return to GL render loop immediately
                        break
                    } else {
                        consecutiveEmptyCycles++
                        if (consecutiveEmptyCycles >= maxEmptyCycles) {
                            Log.w(TAG, "drainVideoCodec: flush finalizado por timeout (${consecutiveEmptyCycles * 10}ms)")
                            break
                        }
                    }
                }
                encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val newFormat = codec.outputFormat
                    formatChanged = true
                    Log.i(TAG, "[FORMAT_CHANGED] Video track format detectado: $newFormat")
                    synchronized(muxerLock) {
                        val m = muxer ?: return@synchronized
                        if (!videoTrackAdded) {
                            videoTrackIndex = m.addTrack(newFormat)
                            videoTrackAdded = true
                            Log.i(TAG, "[TRACK_ADDED] Video track adicionado ao MediaMuxer (index: $videoTrackIndex)")
                            checkStartMuxerLocked()
                        }
                    }
                }
                encoderStatus >= 0 -> {
                    consecutiveEmptyCycles = 0
                    val encodedData = codec.getOutputBuffer(encoderStatus)
                    if (encodedData == null) {
                        codec.releaseOutputBuffer(encoderStatus, false)
                        continue
                    }

                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        bufferInfo.size = 0
                    }

                    if (bufferInfo.size > 0) {
                        encodedData.position(bufferInfo.offset)
                        encodedData.limit(bufferInfo.offset + bufferInfo.size)

                        // Normalize video PTS relative to first video frame
                        if (firstVideoPtsUs < 0L) {
                            firstVideoPtsUs = bufferInfo.presentationTimeUs
                        }
                        var pts = bufferInfo.presentationTimeUs - firstVideoPtsUs
                        if (pts <= lastVideoPtsUs) {
                            pts = lastVideoPtsUs + 1000L // Ensure monotonic increase
                        }
                        lastVideoPtsUs = pts
                        bufferInfo.presentationTimeUs = pts

                        synchronized(muxerLock) {
                            if (muxerStarted.get() && videoTrackIndex >= 0) {
                                muxer?.writeSampleData(videoTrackIndex, encodedData, bufferInfo)
                                videoSamplesWritten++
                                totalBytesWritten += bufferInfo.size
                            } else if (!muxerStarted.get()) {
                                // Cache early frames (including keyframes) until muxer starts
                                val copy = ByteBuffer.allocateDirect(bufferInfo.size)
                                copy.put(encodedData)
                                copy.flip()
                                val infoCopy = MediaCodec.BufferInfo().apply {
                                    set(0, bufferInfo.size, pts, bufferInfo.flags)
                                }
                                pendingFrames.add(CachedFrame(isVideo = true, data = copy, info = infoCopy))
                                checkStartMuxerLocked()
                            }
                        }
                    }

                    codec.releaseOutputBuffer(encoderStatus, false)

                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        eosReceived = true
                        Log.i(TAG, "[EOS_RECEIVED] Video MediaCodec output stream reached EOS com sucesso")
                        break
                    }
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Audio Pipeline (Dedicated thread with synced timestamps & fallback)
    // ─────────────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun startAudioPipeline() {
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            DebugCenter.log(LogModule.CameraX, LogLevel.WARN, "Permissão de áudio não concedida. Gravando somente vídeo.")
            hasAudio = false
            return
        }

        try {
            var channelConfig = AudioFormat.CHANNEL_IN_STEREO
            var channels = 2
            var minBufSize = AudioRecord.getMinBufferSize(
                AUDIO_SAMPLE_RATE,
                channelConfig,
                AudioFormat.ENCODING_PCM_16BIT
            )

            var ar: AudioRecord? = null
            if (minBufSize > 0) {
                try {
                    val testAr = AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        AUDIO_SAMPLE_RATE,
                        channelConfig,
                        AudioFormat.ENCODING_PCM_16BIT,
                        (minBufSize * 4).coerceAtLeast(8192)
                    )
                    if (testAr.state == AudioRecord.STATE_INITIALIZED) {
                        ar = testAr
                    } else {
                        testAr.release()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Stereo AudioRecord falhou: ${e.message}")
                }
            }

            if (ar == null) {
                // Fallback para MONO
                channelConfig = AudioFormat.CHANNEL_IN_MONO
                channels = 1
                minBufSize = AudioRecord.getMinBufferSize(
                    AUDIO_SAMPLE_RATE,
                    channelConfig,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                if (minBufSize > 0) {
                    try {
                        val testAr = AudioRecord(
                            MediaRecorder.AudioSource.MIC,
                            AUDIO_SAMPLE_RATE,
                            channelConfig,
                            AudioFormat.ENCODING_PCM_16BIT,
                            (minBufSize * 4).coerceAtLeast(8192)
                        )
                        if (testAr.state == AudioRecord.STATE_INITIALIZED) {
                            ar = testAr
                        } else {
                            testAr.release()
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Mono AudioRecord falhou: ${e.message}")
                    }
                }
            }

            if (ar == null || ar.state != AudioRecord.STATE_INITIALIZED) {
                DebugCenter.log(LogModule.CameraX, LogLevel.WARN, "AudioRecord não pôde ser inicializado (nem stereo nem mono). Gravando somente vídeo.")
                ar?.release()
                hasAudio = false
                return
            }

            val actualBufSize = (minBufSize * 4).coerceAtLeast(8192)
            val audioFormat = MediaFormat.createAudioFormat(AUDIO_MIME, AUDIO_SAMPLE_RATE, channels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, if (channels == 1) 96_000 else AUDIO_BITRATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, actualBufSize)
            }

            val ac = MediaCodec.createEncoderByType(AUDIO_MIME)
            ac.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            ac.start()
            audioCodec = ac
            audioRecord = ar
            hasAudio = true

            val channelCount = channels
            audioThread = Thread({
                try {
                    ar.startRecording()
                } catch (e: Exception) {
                    Log.w(TAG, "Falha ao iniciar AudioRecord: ${e.message}")
                    hasAudio = false
                    return@Thread
                }

                val pcmBuffer = ByteBuffer.allocateDirect(actualBufSize)
                val bufInfo = MediaCodec.BufferInfo()
                val bytesPerSample = 2 * channelCount
                val usPerByte = 1_000_000.0 / (AUDIO_SAMPLE_RATE * bytesPerSample)
                var currentPtsUs = 0L

                try {
                    while (isRecording.get()) {
                        pcmBuffer.clear()
                        val bytesRead = ar.read(pcmBuffer, minBufSize)
                        if (bytesRead <= 0) continue

                        val inputIndex = ac.dequeueInputBuffer(10_000L)
                        if (inputIndex >= 0) {
                            val inputBuf = ac.getInputBuffer(inputIndex)
                            if (inputBuf != null) {
                                inputBuf.clear()
                                pcmBuffer.position(0)
                                pcmBuffer.limit(bytesRead)
                                inputBuf.put(pcmBuffer)
                                ac.queueInputBuffer(inputIndex, 0, bytesRead, currentPtsUs, 0)
                                currentPtsUs += (bytesRead * usPerByte).toLong()
                            }
                        }

                        drainAudioCodec(ac, bufInfo, false)
                    }

                    // Final drain
                    val inputIndex = ac.dequeueInputBuffer(10_000L)
                    if (inputIndex >= 0) {
                        ac.queueInputBuffer(inputIndex, 0, 0, currentPtsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    }
                    drainAudioCodec(ac, bufInfo, true)
                } catch (e: Exception) {
                    Log.w(TAG, "Audio thread error: ${e.message}")
                } finally {
                    try { ar.stop() } catch (ignored: Exception) {}
                    try { ar.release() } catch (ignored: Exception) {}
                    try { ac.stop() } catch (ignored: Exception) {}
                    try { ac.release() } catch (ignored: Exception) {}
                    audioRecord = null
                    audioCodec = null
                }
            }, "GlRecPipeline-Audio").apply {
                isDaemon = true
                start()
            }
        } catch (e: Exception) {
            DebugCenter.log(LogModule.CameraX, LogLevel.WARN, "Falha ao configurar áudio: ${e.message}. Gravando somente vídeo.")
            hasAudio = false
        }
    }

    private fun drainAudioCodec(codec: MediaCodec, bufInfo: MediaCodec.BufferInfo, endOfStream: Boolean) {
        var consecutiveEmptyCycles = 0
        val maxEmptyCycles = if (endOfStream) 40 else 1

        while (true) {
            val timeoutUs = if (endOfStream) 10_000L else 0L
            val status = codec.dequeueOutputBuffer(bufInfo, timeoutUs)
            when {
                status == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) {
                        break
                    } else {
                        consecutiveEmptyCycles++
                        if (consecutiveEmptyCycles >= maxEmptyCycles) {
                            Log.w(TAG, "drainAudioCodec: flush finalizado por timeout (${consecutiveEmptyCycles * 10}ms)")
                            break
                        }
                    }
                }
                status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val newFormat = codec.outputFormat
                    synchronized(muxerLock) {
                        val m = muxer ?: return@synchronized
                        if (!audioTrackAdded) {
                            if (!muxerStarted.get()) {
                                audioTrackIndex = m.addTrack(newFormat)
                                audioTrackAdded = true
                                Log.i(TAG, "Audio track adicionado ao MediaMuxer (index: $audioTrackIndex)")
                                checkStartMuxerLocked()
                            } else {
                                Log.w(TAG, "Audio format changed after muxer already started. Descartando audio track para evitar crash.")
                                hasAudio = false
                            }
                        }
                    }
                }
                status >= 0 -> {
                    consecutiveEmptyCycles = 0
                    val encodedData = codec.getOutputBuffer(status)
                    if (encodedData == null) {
                        codec.releaseOutputBuffer(status, false)
                        continue
                    }

                    if (bufInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        bufInfo.size = 0
                    }

                    if (bufInfo.size > 0) {
                        encodedData.position(bufInfo.offset)
                        encodedData.limit(bufInfo.offset + bufInfo.size)

                        if (firstAudioPtsUs < 0L) {
                            firstAudioPtsUs = bufInfo.presentationTimeUs
                        }
                        var pts = bufInfo.presentationTimeUs - firstAudioPtsUs
                        if (pts <= lastAudioPtsUs) {
                            pts = lastAudioPtsUs + 1000L
                        }
                        lastAudioPtsUs = pts
                        bufInfo.presentationTimeUs = pts

                        synchronized(muxerLock) {
                            if (muxerStarted.get() && audioTrackIndex >= 0) {
                                muxer?.writeSampleData(audioTrackIndex, encodedData, bufInfo)
                                audioSamplesWritten++
                                totalBytesWritten += bufInfo.size
                            } else if (!muxerStarted.get()) {
                                val copy = ByteBuffer.allocateDirect(bufInfo.size)
                                copy.put(encodedData)
                                copy.flip()
                                val infoCopy = MediaCodec.BufferInfo().apply {
                                    set(0, bufInfo.size, pts, bufInfo.flags)
                                }
                                pendingFrames.add(CachedFrame(isVideo = false, data = copy, info = infoCopy))
                                checkStartMuxerLocked()
                            }
                        }
                    }

                    codec.releaseOutputBuffer(status, false)
                    if (bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        Log.i(TAG, "[EOS_RECEIVED] Audio MediaCodec output stream reached EOS com sucesso")
                        break
                    }
                }
            }
        }
    }

    private fun checkStartMuxerLocked() {
        val m = muxer ?: return
        if (muxerStarted.get()) return

        val audioReady = audioTrackAdded || !hasAudio || (SystemClock.uptimeMillis() - recordingStartTimeMs > AUDIO_TIMEOUT_MS)
        if (videoTrackAdded && audioReady) {
            startMuxerLocked(m)
        }
    }

    private fun startMuxerLocked(m: MediaMuxer) {
        if (muxerStarted.get()) return
        try {
            m.start()
            muxerStarted.set(true)
            DebugCenter.log(
                LogModule.CameraX,
                LogLevel.INFO,
                "GlRecordingPipeline: MediaMuxer iniciado (vídeo=${videoTrackIndex}, áudio=${audioTrackIndex}, frames pendentes=${pendingFrames.size})."
            )

            for (frame in pendingFrames) {
                val trackIdx = if (frame.isVideo) videoTrackIndex else audioTrackIndex
                if (trackIdx >= 0) {
                    m.writeSampleData(trackIdx, frame.data, frame.info)
                    if (frame.isVideo) {
                        videoSamplesWritten++
                    } else {
                        audioSamplesWritten++
                    }
                    totalBytesWritten += frame.info.size
                }
            }
            if (pendingFrames.isNotEmpty()) {
                Log.i(TAG, "[SAMPLE_WRITTEN] Despejados ${pendingFrames.size} frames pendentes ao iniciar MediaMuxer")
            }
            pendingFrames.clear()
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao iniciar MediaMuxer: ${e.message}", e)
        }
    }

    private fun releaseInternal() {
        try {
            if (blitProgram != 0) {
                GLES20.glDeleteProgram(blitProgram)
                blitProgram = 0
            }
        } catch (ignored: Exception) {}

        try {
            if (eglRecordSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglRecordSurface)
                eglRecordSurface = EGL14.EGL_NO_SURFACE
            }
        } catch (ignored: Exception) {}

        try {
            videoCodec?.stop()
            videoCodec?.release()
        } catch (ignored: Exception) {}
        videoCodec = null

        try {
            videoCodecSurface?.release()
        } catch (ignored: Exception) {}
        videoCodecSurface = null
    }

    private fun checkEglError(msg: String) {
        val error = EGL14.eglGetError()
        if (error != EGL14.EGL_SUCCESS) {
            throw RuntimeException("$msg: EGL error 0x${Integer.toHexString(error)}")
        }
    }
}

