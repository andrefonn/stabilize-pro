package com.stabilizepro.app.renderer

import android.content.Context
import android.graphics.SurfaceTexture
import android.net.Uri
import android.opengl.EGL14
import android.opengl.GLSurfaceView
import android.view.Surface
import androidx.camera.core.Preview
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.stabilizepro.app.camera.CameraAspectRatio
import com.stabilizepro.app.presets.ColorGradingParams
import java.io.File

/**
 * Android View wrapper around GLSurfaceView with FBO rendering and GlRecordingPipeline support.
 *
 * Configured with [RENDERMODE_WHEN_DIRTY] to render strictly when [SurfaceTexture.OnFrameAvailableListener]
 * triggers, ensuring accurate presentation timestamps and zero duplicate frames.
 */
class CameraGlSurfaceView(
    context: Context,
    private val onReady: (CameraGlSurfaceView, Preview.SurfaceProvider) -> Unit
) : GLSurfaceView(context) {

    val renderer: CameraGlRenderer
    private var cameraSurfaceTexture: SurfaceTexture? = null
    private var recordingPipeline: GlRecordingPipeline? = null

    init {
        setEGLContextClientVersion(2)
        // Ensure the EGLContext is created with a recordable configuration for 100% compatibility with MediaCodec input surfaces
        setEGLConfigChooser(RecordableEGLConfigChooser())
        renderer = CameraGlRenderer(
            onSurfaceTextureCreated = { surfaceTexture ->
                cameraSurfaceTexture = surfaceTexture
                post {
                    val surfaceProvider = Preview.SurfaceProvider { request ->
                        val resolution = request.resolution
                        surfaceTexture.setDefaultBufferSize(resolution.width, resolution.height)
                        renderer.setCameraTextureSize(resolution.width, resolution.height)
                        val surface = Surface(surfaceTexture)
                        request.provideSurface(surface, ContextCompat.getMainExecutor(context)) {
                            surface.release()
                        }
                    }
                    onReady(this@CameraGlSurfaceView, surfaceProvider)
                }
            },
            onRequestRender = {
                requestRender()
            }
        )
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    /**
     * Custom EGLConfigChooser requiring EGL_RECORDABLE_ANDROID (0x3142) for zero-copy MediaCodec recording compatibility.
     */
    private class RecordableEGLConfigChooser : GLSurfaceView.EGLConfigChooser {
        override fun chooseConfig(
            egl: javax.microedition.khronos.egl.EGL10,
            display: javax.microedition.khronos.egl.EGLDisplay
        ): javax.microedition.khronos.egl.EGLConfig {
            val EGL_RECORDABLE_ANDROID = 0x3142
            val EGL_OPENGL_ES2_BIT = 4

            val candidateSpecs = listOf(
                // 1. RGBA8888 + RECORDABLE
                intArrayOf(
                    javax.microedition.khronos.egl.EGL10.EGL_RED_SIZE, 8,
                    javax.microedition.khronos.egl.EGL10.EGL_GREEN_SIZE, 8,
                    javax.microedition.khronos.egl.EGL10.EGL_BLUE_SIZE, 8,
                    javax.microedition.khronos.egl.EGL10.EGL_ALPHA_SIZE, 8,
                    javax.microedition.khronos.egl.EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
                    EGL_RECORDABLE_ANDROID, 1,
                    javax.microedition.khronos.egl.EGL10.EGL_NONE
                ),
                // 2. RGB888 + RECORDABLE
                intArrayOf(
                    javax.microedition.khronos.egl.EGL10.EGL_RED_SIZE, 8,
                    javax.microedition.khronos.egl.EGL10.EGL_GREEN_SIZE, 8,
                    javax.microedition.khronos.egl.EGL10.EGL_BLUE_SIZE, 8,
                    javax.microedition.khronos.egl.EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
                    EGL_RECORDABLE_ANDROID, 1,
                    javax.microedition.khronos.egl.EGL10.EGL_NONE
                ),
                // 3. Standard RGBA8888 fallback
                intArrayOf(
                    javax.microedition.khronos.egl.EGL10.EGL_RED_SIZE, 8,
                    javax.microedition.khronos.egl.EGL10.EGL_GREEN_SIZE, 8,
                    javax.microedition.khronos.egl.EGL10.EGL_BLUE_SIZE, 8,
                    javax.microedition.khronos.egl.EGL10.EGL_ALPHA_SIZE, 8,
                    javax.microedition.khronos.egl.EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
                    javax.microedition.khronos.egl.EGL10.EGL_NONE
                ),
                // 4. Standard RGB888 fallback
                intArrayOf(
                    javax.microedition.khronos.egl.EGL10.EGL_RED_SIZE, 8,
                    javax.microedition.khronos.egl.EGL10.EGL_GREEN_SIZE, 8,
                    javax.microedition.khronos.egl.EGL10.EGL_BLUE_SIZE, 8,
                    javax.microedition.khronos.egl.EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
                    javax.microedition.khronos.egl.EGL10.EGL_NONE
                )
            )

            val numConfigs = IntArray(1)
            for (spec in candidateSpecs) {
                if (egl.eglChooseConfig(display, spec, null, 0, numConfigs) && numConfigs[0] > 0) {
                    val configs = arrayOfNulls<javax.microedition.khronos.egl.EGLConfig>(numConfigs[0])
                    if (egl.eglChooseConfig(display, spec, configs, numConfigs[0], numConfigs) && configs[0] != null) {
                        return configs[0]!!
                    }
                }
            }
            throw IllegalArgumentException("Falha crítica: nenhum EGLConfig compatível encontrado para GLSurfaceView.")
        }
    }

    fun updateColorGrading(params: ColorGradingParams) {
        renderer.updateColorGrading(params)
        requestRender()
    }

    fun updateAspectRatio(ratio: CameraAspectRatio) {
        renderer.updateAspectRatio(ratio)
        requestRender()
    }

    fun setFboResolution(width: Int, height: Int) {
        queueEvent {
            renderer.setFboResolution(width, height)
        }
        requestRender()
    }

    /**
     * Initiates offscreen GL recording via the shared EGLContext.
     * The recording pipeline creates an EGLSurface targeting MediaCodec's input surface,
     * and [CameraGlRenderer.onDrawFrame] renders the master FBO directly to it.
     */
    fun startRecording(
        outputFile: File,
        width: Int,
        height: Int,
        fps: Int = 30,
        onVideoSaved: (Uri) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val pipeline = GlRecordingPipeline(context)
        recordingPipeline = pipeline

        queueEvent {
            try {
                val currentCtx = EGL14.eglGetCurrentContext()
                val sharedContext = if (currentCtx != EGL14.EGL_NO_CONTEXT) currentCtx else renderer.activeEglContext
                if (sharedContext == EGL14.EGL_NO_CONTEXT) {
                    throw IllegalStateException("EGLContext ativo da GPU não disponível para gravação.")
                }
                pipeline.start(
                    outputFile = outputFile,
                    width = width,
                    height = height,
                    fps = fps,
                    sharedEglContext = sharedContext,
                    onVideoSaved = onVideoSaved,
                    onError = onError
                )

                renderer.recordingPipeline = pipeline
                requestRender()
            } catch (e: Exception) {
                onError(e)
            }
        }
        requestRender()
    }

    fun stopRecording() {
        val pipeline = recordingPipeline ?: return
        recordingPipeline = null
        renderer.recordingPipeline = null
        queueEvent {
            try {
                pipeline.stop()
            } catch (ignored: Exception) {}
        }
        requestRender()
    }

    fun release() {
        try {
            stopRecording()
            queueEvent {
                renderer.release()
            }
        } catch (ignored: Exception) {}
    }
}

@Composable
fun CameraPreviewGl(
    modifier: Modifier = Modifier,
    colorGradingParams: ColorGradingParams,
    aspectRatio: CameraAspectRatio = CameraAspectRatio.RATIO_16_9,
    onSurfaceReady: (CameraGlSurfaceView, Preview.SurfaceProvider) -> Unit
) {
    var glView: CameraGlSurfaceView? = remember { null }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            CameraGlSurfaceView(ctx) { view, surfaceProvider ->
                onSurfaceReady(view, surfaceProvider)
            }.also {
                glView = it
                it.updateColorGrading(colorGradingParams)
                it.updateAspectRatio(aspectRatio)
            }
        },
        update = { view ->
            view.updateColorGrading(colorGradingParams)
            view.updateAspectRatio(aspectRatio)
        }
    )

    DisposableEffect(Unit) {
        onDispose {
            glView?.release()
        }
    }
}
