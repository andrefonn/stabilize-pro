package com.stabilizepro.app.renderer

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
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

class CameraGlRenderer(
    private val onSurfaceTextureCreated: (SurfaceTexture) -> Unit
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

    private var program = 0
    private var textureId = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var updateSurface = false

    // Uniform locations
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

    @Volatile
    private var currentParams: ColorGradingParams = ColorGradingParams()
    @Volatile
    private var currentAspectRatio: CameraAspectRatio = CameraAspectRatio.RATIO_16_9

    private var viewportWidth = 1080
    private var viewportHeight = 1920

    // FPS calculation
    private var frameCount = 0
    private var lastFpsCalcTime = System.currentTimeMillis()

    fun updateColorGrading(params: ColorGradingParams) {
        currentParams = params
    }

    fun updateAspectRatio(ratio: CameraAspectRatio) {
        currentAspectRatio = ratio
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.04f, 0.04f, 0.05f, 1.0f)

        program = ColorGradingShader.createProgram(
            ColorGradingShader.VERTEX_SHADER,
            ColorGradingShader.FRAGMENT_SHADER_OES
        )

        if (program == 0) {
            DebugCenter.log(LogModule.CameraX, LogLevel.ERROR, "Programa GL não pôde ser criado.", "#203")
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

        // Generate texture for CameraX SurfaceTexture
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        surfaceTexture = SurfaceTexture(textureId).apply {
            setOnFrameAvailableListener(this@CameraGlRenderer)
            onSurfaceTextureCreated(this)
        }

        DebugCenter.activePipeline = "Câmera Preview (OpenGL ES Real-time)"
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = width
        viewportHeight = height
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        synchronized(this) {
            if (updateSurface) {
                surfaceTexture?.updateTexImage()
                surfaceTexture?.getTransformMatrix(stMatrix)
                updateSurface = false
            }
        }

        // FPS tracking
        frameCount++
        val now = System.currentTimeMillis()
        val elapsed = now - lastFpsCalcTime
        if (elapsed >= 1000) {
            DebugCenter.currentFps = (frameCount * 1000f) / elapsed
            frameCount = 0
            lastFpsCalcTime = now
        }

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        if (program == 0) return

        GLES20.glUseProgram(program)

        // Set Vertex attributes
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

        // Pass transform and aspect ratio MVP matrices
        GLES20.glUniformMatrix4fv(uSTMatrixLoc, 1, false, stMatrix, 0)

        Matrix.setIdentityM(mvpMatrix, 0)
        val viewAspect = viewportWidth.toFloat() / viewportHeight.coerceAtLeast(1).toFloat()
        val targetAspect = currentAspectRatio.ratioValue

        if (viewAspect < targetAspect) {
            val scale = targetAspect / viewAspect
            Matrix.scaleM(mvpMatrix, 0, scale, 1.0f, 1.0f)
        } else {
            val scale = viewAspect / targetAspect
            Matrix.scaleM(mvpMatrix, 0, 1.0f, scale, 1.0f)
        }
        GLES20.glUniformMatrix4fv(uMVPMatrixLoc, 1, false, mvpMatrix, 0)

        // Pass all 14 color grading uniforms
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

        // Texel size for kernel filters
        val tw = 1.0f / viewportWidth.coerceAtLeast(1)
        val th = 1.0f / viewportHeight.coerceAtLeast(1)
        GLES20.glUniform2f(uTexelSizeLoc, tw, th)

        // Draw full-screen quad
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPositionLoc)
        GLES20.glDisableVertexAttribArray(aTextureCoordLoc)
    }

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {
        synchronized(this) {
            updateSurface = true
        }
    }

    fun release() {
        try {
            if (textureId != 0) {
                val textures = intArrayOf(textureId)
                GLES20.glDeleteTextures(1, textures, 0)
                textureId = 0
            }
            if (program != 0) {
                GLES20.glDeleteProgram(program)
                program = 0
            }
            surfaceTexture?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Aviso ao liberar CameraGlRenderer: ${e.message}")
        } finally {
            surfaceTexture = null
        }
    }
}
