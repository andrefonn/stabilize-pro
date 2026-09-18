package com.stabilizepro.app.renderer

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.stabilizepro.app.camera.CameraAspectRatio
import com.stabilizepro.app.logs.DebugCenter
import com.stabilizepro.app.logs.LogLevel
import com.stabilizepro.app.logs.LogModule
import com.stabilizepro.app.presets.ColorGradingParams
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * High-performance OpenGL ES Renderer with dual-pass Framebuffer Object (FBO) architecture:
 *
 * Pass 1: Camera2 OES Texture → ColorGradingShader (14 uniforms) → FBO
 * Pass 2A: FBO Texture (GL_TEXTURE_2D) → GLSurfaceView (Preview with letterbox/pillarbox)
 * Pass 2B: FBO Texture (GL_TEXTURE_2D) → MediaCodec Surface (Recording via [GlRecordingPipeline])
 *
 * Guarantees pixel-for-pixel identity between preview and exported MP4.
 */
class CameraGlRenderer(
    private val onSurfaceTextureCreated: (SurfaceTexture) -> Unit,
    var onRequestRender: (() -> Unit)? = null
) : GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

    companion object {
        private const val TAG = "CameraGlRenderer"
        private const val FLOAT_SIZE_BYTES = 4
        private const val VERTICES_DATA_STRIDE_BYTES = 4 * FLOAT_SIZE_BYTES
        private const val VERTICES_DATA_POS_OFFSET = 0
        private const val VERTICES_DATA_UV_OFFSET = 2

        private val QUAD_VERTICES_DATA = floatArrayOf(
            // X, Y, U, V
            -1.0f, -1.0f, 0.0f, 0.0f,
             1.0f, -1.0f, 1.0f, 0.0f,
            -1.0f,  1.0f, 0.0f, 1.0f,
             1.0f,  1.0f, 1.0f, 1.0f
        )
    }

    private val vertexBuffer: FloatBuffer = ByteBuffer.allocateDirect(
        QUAD_VERTICES_DATA.size * FLOAT_SIZE_BYTES
    ).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(QUAD_VERTICES_DATA)
        position(0)
    }

    private val stMatrix = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }
    private val mvpMatrix = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }
    private val identityMatrix = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }

    // Pass 1: OES Texture & Color Grading Program
    private var program = 0
    private var textureId = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var updateSurface = false

    private var aPositionLoc = -1
    private var aTextureCoordLoc = -1
    private var uMVPMatrixLoc = -1
    private var uSTMatrixLoc = -1
    private var sTextureLoc = -1
    private var uExposureLoc = -1
    private var uContrastLoc = -1
    private var uShadowsLoc = -1
    private var uHighlightsLoc = -1
    private var uBrightnessLoc = -1
    private var uBlackPointLoc = -1
    private var uSaturationLoc = -1
    private var uVibranceLoc = -1
    private var uTemperatureLoc = -1
    private var uTintLoc = -1
    private var uSharpnessLoc = -1
    private var uDefinitionLoc = -1
    private var uNoiseReductionLoc = -1
    private var uVignetteLoc = -1
    private var uTexelSizeLoc = -1

    // Pass 2: 2D Texture Blit Program (FBO → Screen / MediaCodec)
    private var blitProgram = 0
    private var blitPositionLoc = -1
    private var blitTexCoordLoc = -1
    private var blitMVPLoc = -1
    private var blitTextureLoc = -1

    // FBO objects
    private val fboId = IntArray(1)
    private val fboTextureId = IntArray(1)
    private var fboWidth = 1080
    private var fboHeight = 1920
    private var pendingFboWidth = 1080
    private var pendingFboHeight = 1920
    private var fboNeedsRecreate = false

    // State
    @Volatile
    var currentParams: ColorGradingParams = ColorGradingParams()
        private set

    @Volatile
    private var currentAspectRatio: CameraAspectRatio = CameraAspectRatio.RATIO_16_9

    private var viewportWidth = 1080
    private var viewportHeight = 1920

    // Recording pipeline reference (set when recording is active)
    @Volatile
    var recordingPipeline: GlRecordingPipeline? = null

    /** Exposes the current FBO texture so the first encoder frame can be submitted synchronously. */
    val currentFboTextureId: Int get() = fboTextureId[0]

    @Volatile
    var activeEglContext: android.opengl.EGLContext = android.opengl.EGL14.EGL_NO_CONTEXT
        private set

    @Volatile
    var activeEglDisplay: android.opengl.EGLDisplay = android.opengl.EGL14.EGL_NO_DISPLAY
        private set

    // FPS calculation
    private var frameCount = 0
    private var lastFpsCalcTime = System.currentTimeMillis()

    fun updateColorGrading(params: ColorGradingParams) {
        currentParams = params
    }

    fun updateAspectRatio(ratio: CameraAspectRatio) {
        currentAspectRatio = ratio
    }

    private var cameraTextureWidth = 1920
    private var cameraTextureHeight = 1080

    fun setFboResolution(width: Int, height: Int) {
        if (width > 0 && height > 0 && (width != fboWidth || height != fboHeight)) {
            pendingFboWidth = width
            pendingFboHeight = height
            fboNeedsRecreate = true
        }
    }

    fun setCameraTextureSize(width: Int, height: Int) {
        if (width > 0 && height > 0) {
            cameraTextureWidth = width
            cameraTextureHeight = height
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        activeEglContext = android.opengl.EGL14.eglGetCurrentContext()
        activeEglDisplay = android.opengl.EGL14.eglGetCurrentDisplay()

        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)

        // 1. Compile OES Color Grading Program (Pass 1: OES → FBO)
        program = ColorGradingShader.createProgram(
            ColorGradingShader.VERTEX_SHADER,
            ColorGradingShader.FRAGMENT_SHADER_OES
        )

        if (program == 0) {
            DebugCenter.log(LogModule.CameraX, LogLevel.ERROR, "Programa GL OES não pôde ser criado.", "#203")
            return
        }

        aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition")
        aTextureCoordLoc = GLES20.glGetAttribLocation(program, "aTextureCoord")
        uMVPMatrixLoc = GLES20.glGetUniformLocation(program, "uMVPMatrix")
        uSTMatrixLoc = GLES20.glGetUniformLocation(program, "uSTMatrix")
        sTextureLoc = GLES20.glGetUniformLocation(program, "sTexture")

        uExposureLoc = GLES20.glGetUniformLocation(program, "uExposure")
        uContrastLoc = GLES20.glGetUniformLocation(program, "uContrast")
        uShadowsLoc = GLES20.glGetUniformLocation(program, "uShadows")
        uHighlightsLoc = GLES20.glGetUniformLocation(program, "uHighlights")
        uBrightnessLoc = GLES20.glGetUniformLocation(program, "uBrightness")
        uBlackPointLoc = GLES20.glGetUniformLocation(program, "uBlackPoint")
        uSaturationLoc = GLES20.glGetUniformLocation(program, "uSaturation")
        uVibranceLoc = GLES20.glGetUniformLocation(program, "uVibrance")
        uTemperatureLoc = GLES20.glGetUniformLocation(program, "uTemperature")
        uTintLoc = GLES20.glGetUniformLocation(program, "uTint")
        uSharpnessLoc = GLES20.glGetUniformLocation(program, "uSharpness")
        uDefinitionLoc = GLES20.glGetUniformLocation(program, "uDefinition")
        uNoiseReductionLoc = GLES20.glGetUniformLocation(program, "uNoiseReduction")
        uVignetteLoc = GLES20.glGetUniformLocation(program, "uVignette")
        uTexelSizeLoc = GLES20.glGetUniformLocation(program, "uTexelSize")

        // 2. Compile 2D Blit Program (Pass 2: FBO → Screen / MediaCodec)
        blitProgram = ColorGradingShader.createProgram(
            ColorGradingShader.BLIT_VERTEX_SHADER,
            ColorGradingShader.BLIT_FRAGMENT_SHADER
        )
        blitPositionLoc = GLES20.glGetAttribLocation(blitProgram, "aPosition")
        blitTexCoordLoc = GLES20.glGetAttribLocation(blitProgram, "aTextureCoord")
        blitMVPLoc      = GLES20.glGetUniformLocation(blitProgram, "uMVPMatrix")
        blitTextureLoc  = GLES20.glGetUniformLocation(blitProgram, "sTexture")

        // 3. Generate texture for CameraX SurfaceTexture
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val mainHandler = Handler(Looper.getMainLooper())
        surfaceTexture = SurfaceTexture(textureId).apply {
            // Deliver onFrameAvailable on the main looper so requestRender() is always
            // called from a stable context. The GL thread is woken by requestRender().
            setOnFrameAvailableListener(this@CameraGlRenderer, mainHandler)
            onSurfaceTextureCreated(this)
        }

        // 4. Create FBO
        setupFbo(fboWidth, fboHeight)

        DebugCenter.activePipeline = "Câmera Preview (OpenGL ES FBO Pipeline)"
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = width
        viewportHeight = height
    }

    override fun onDrawFrame(gl: GL10?) {
        // Recreate FBO if resolution changed
        if (fboNeedsRecreate) {
            setupFbo(pendingFboWidth, pendingFboHeight)
        }

        var isNewFrame = false
        synchronized(this) {
            if (updateSurface) {
                try {
                    surfaceTexture?.updateTexImage()
                    surfaceTexture?.getTransformMatrix(stMatrix)
                    isNewFrame = true
                } catch (e: Exception) {
                    Log.w(TAG, "updateTexImage failed: ${e.message}")
                }
                updateSurface = false
            }
        }
        val frameTimestampNs = surfaceTexture?.timestamp ?: 0L

        // FPS tracking
        frameCount++
        val now = System.currentTimeMillis()
        val elapsed = now - lastFpsCalcTime
        if (elapsed >= 1000) {
            DebugCenter.currentFps = (frameCount * 1000f) / elapsed
            frameCount = 0
            lastFpsCalcTime = now
        }

        if (program == 0 || blitProgram == 0 || fboId[0] == 0) return

        // ─────────────────────────────────────────────────────────────
        // PASS 1: Render Camera OES → FBO (All 14 Color Grading Shaders)
        // ─────────────────────────────────────────────────────────────
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId[0])
        GLES20.glViewport(0, 0, fboWidth, fboHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glUseProgram(program)

        vertexBuffer.position(VERTICES_DATA_POS_OFFSET)
        GLES20.glVertexAttribPointer(
            aPositionLoc, 2, GLES20.GL_FLOAT, false,
            VERTICES_DATA_STRIDE_BYTES, vertexBuffer
        )
        GLES20.glEnableVertexAttribArray(aPositionLoc)

        vertexBuffer.position(VERTICES_DATA_UV_OFFSET)
        GLES20.glVertexAttribPointer(
            aTextureCoordLoc, 2, GLES20.GL_FLOAT, false,
            VERTICES_DATA_STRIDE_BYTES, vertexBuffer
        )
        GLES20.glEnableVertexAttribArray(aTextureCoordLoc)

        // Bind OES Texture
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(sTextureLoc, 0)

        // Matrix transforms: stMatrix rotates camera sensor to upright portrait, MVP is identity
        GLES20.glUniformMatrix4fv(uSTMatrixLoc, 1, false, stMatrix, 0)
        Matrix.setIdentityM(identityMatrix, 0)
        GLES20.glUniformMatrix4fv(uMVPMatrixLoc, 1, false, identityMatrix, 0)

        // Apply all 14 color grading uniforms to FBO
        val params = currentParams
        GLES20.glUniform1f(uExposureLoc, params.exposure)
        GLES20.glUniform1f(uContrastLoc, params.contrast)
        GLES20.glUniform1f(uShadowsLoc, params.shadows)
        GLES20.glUniform1f(uHighlightsLoc, params.highlights)
        GLES20.glUniform1f(uBrightnessLoc, params.brightness)
        GLES20.glUniform1f(uBlackPointLoc, params.blackPoint)
        GLES20.glUniform1f(uSaturationLoc, params.saturation)
        GLES20.glUniform1f(uVibranceLoc, params.vibrance)
        GLES20.glUniform1f(uTemperatureLoc, params.temperature)
        GLES20.glUniform1f(uTintLoc, params.tint)
        GLES20.glUniform1f(uSharpnessLoc, params.sharpness)
        GLES20.glUniform1f(uDefinitionLoc, params.definition)
        GLES20.glUniform1f(uNoiseReductionLoc, params.noiseReduction)
        GLES20.glUniform1f(uVignetteLoc, params.vignette)

        // Texel size corresponds directly to the camera texture sampled in Pass 1
        val tw = 1.0f / cameraTextureWidth.coerceAtLeast(1)
        val th = 1.0f / cameraTextureHeight.coerceAtLeast(1)
        GLES20.glUniform2f(uTexelSizeLoc, tw, th)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPositionLoc)
        GLES20.glDisableVertexAttribArray(aTextureCoordLoc)

        // Unbind FBO
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

        // ─────────────────────────────────────────────────────────────
        // PASS 2A: Render FBO Texture → MediaCodec Surface (Recording)
        // Rendered first so that any offscreen surface switching is completed
        // before the preview frame is composed on the GLSurfaceView.
        // ─────────────────────────────────────────────────────────────
        val pipeline = recordingPipeline
        if (pipeline != null && pipeline.isRecording.get() && isNewFrame) {
            pipeline.renderFboFrame(fboTextureId[0], frameTimestampNs)
        }

        // ─────────────────────────────────────────────────────────────
        // PASS 2B: Render FBO Texture → GLSurfaceView (Preview)
        // Guaranteed to leave the preview surface current with the exact
        // screen viewport (0, 0, viewportWidth, viewportHeight) and letterbox.
        // ─────────────────────────────────────────────────────────────
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, viewportWidth, viewportHeight)
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        GLES20.glUseProgram(blitProgram)

        vertexBuffer.position(VERTICES_DATA_POS_OFFSET)
        GLES20.glVertexAttribPointer(
            blitPositionLoc, 2, GLES20.GL_FLOAT, false,
            VERTICES_DATA_STRIDE_BYTES, vertexBuffer
        )
        GLES20.glEnableVertexAttribArray(blitPositionLoc)

        vertexBuffer.position(VERTICES_DATA_UV_OFFSET)
        GLES20.glVertexAttribPointer(
            blitTexCoordLoc, 2, GLES20.GL_FLOAT, false,
            VERTICES_DATA_STRIDE_BYTES, vertexBuffer
        )
        GLES20.glEnableVertexAttribArray(blitTexCoordLoc)

        // Bind standard 2D FBO texture
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTextureId[0])
        GLES20.glUniform1i(blitTextureLoc, 0)

        // Letterbox / pillarbox MVP matrix to fit the screen aspect ratio
        Matrix.setIdentityM(mvpMatrix, 0)
        val screenAspect = viewportWidth.toFloat() / viewportHeight.coerceAtLeast(1).toFloat()
        val targetAspect = currentAspectRatio.ratioValue

        val scaleX: Float
        val scaleY: Float

        if (screenAspect < targetAspect) {
            scaleX = 1.0f
            scaleY = screenAspect / targetAspect
        } else {
            scaleX = targetAspect / screenAspect
            scaleY = 1.0f
        }
        Matrix.scaleM(mvpMatrix, 0, scaleX, scaleY, 1.0f)
        GLES20.glUniformMatrix4fv(blitMVPLoc, 1, false, mvpMatrix, 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(blitPositionLoc)
        GLES20.glDisableVertexAttribArray(blitTexCoordLoc)
    }

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {
        synchronized(this) {
            updateSurface = true
        }
        onRequestRender?.invoke()
    }

    private fun setupFbo(width: Int, height: Int) {
        val maxTexSize = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maxTexSize, 0)
        val limit = if (maxTexSize[0] > 0) maxTexSize[0] else 2048
        val safeW = width.coerceIn(16, limit)
        val safeH = height.coerceIn(16, limit)

        if (fboTextureId[0] != 0) {
            GLES20.glDeleteTextures(1, fboTextureId, 0)
            fboTextureId[0] = 0
        }
        if (fboId[0] != 0) {
            GLES20.glDeleteFramebuffers(1, fboId, 0)
            fboId[0] = 0
        }

        GLES20.glGenTextures(1, fboTextureId, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTextureId[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
            safeW, safeH, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
        )

        GLES20.glGenFramebuffers(1, fboId, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId[0])
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, fboTextureId[0], 0
        )

        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            DebugCenter.log(LogModule.CameraX, LogLevel.ERROR, "FBO incompleto: status = $status", "#210")
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

        fboWidth = safeW
        fboHeight = safeH
        fboNeedsRecreate = false
        DebugCenter.log(LogModule.CameraX, LogLevel.INFO, "FBO configurado: ${safeW}×${safeH}")
    }

    fun release() {
        try {
            if (fboTextureId[0] != 0) {
                GLES20.glDeleteTextures(1, fboTextureId, 0)
                fboTextureId[0] = 0
            }
            if (fboId[0] != 0) {
                GLES20.glDeleteFramebuffers(1, fboId, 0)
                fboId[0] = 0
            }
            if (textureId != 0) {
                val textures = intArrayOf(textureId)
                GLES20.glDeleteTextures(1, textures, 0)
                textureId = 0
            }
            if (program != 0) {
                GLES20.glDeleteProgram(program)
                program = 0
            }
            if (blitProgram != 0) {
                GLES20.glDeleteProgram(blitProgram)
                blitProgram = 0
            }
            surfaceTexture?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Aviso ao liberar CameraGlRenderer: ${e.message}")
        } finally {
            surfaceTexture = null
        }
    }
}
