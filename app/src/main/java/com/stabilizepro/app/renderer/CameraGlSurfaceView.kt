package com.stabilizepro.app.renderer

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLSurfaceView
import android.util.Size
import android.view.Surface
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.stabilizepro.app.logs.DebugCenter
import com.stabilizepro.app.logs.LogLevel
import com.stabilizepro.app.logs.LogModule
import com.stabilizepro.app.presets.ColorGradingParams

class CameraGlSurfaceView(
    context: Context,
    private val onRendererReady: (Preview.SurfaceProvider) -> Unit
) : GLSurfaceView(context) {

    private val renderer: CameraGlRenderer
    private var cameraSurfaceTexture: SurfaceTexture? = null

    init {
        setEGLContextClientVersion(2)
        renderer = CameraGlRenderer { surfaceTexture ->
            cameraSurfaceTexture = surfaceTexture
            post {
                val surfaceProvider = Preview.SurfaceProvider { request ->
                    val resolution = request.resolution
                    surfaceTexture.setDefaultBufferSize(resolution.width, resolution.height)
                    val surface = Surface(surfaceTexture)
                    request.provideSurface(surface, ContextCompat.getMainExecutor(context)) { result ->
                        surface.release()
                    }
                }
                onRendererReady(surfaceProvider)
            }
        }
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    fun updateColorGrading(params: ColorGradingParams) {
        renderer.updateColorGrading(params)
    }

    fun updateAspectRatio(ratio: com.stabilizepro.app.camera.CameraAspectRatio) {
        renderer.updateAspectRatio(ratio)
    }

    fun release() {
        try {
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
    aspectRatio: com.stabilizepro.app.camera.CameraAspectRatio = com.stabilizepro.app.camera.CameraAspectRatio.RATIO_16_9,
    onSurfaceProviderReady: (Preview.SurfaceProvider) -> Unit
) {
    var glView: CameraGlSurfaceView? = remember { null }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            CameraGlSurfaceView(ctx) { surfaceProvider ->
                onSurfaceProviderReady(surfaceProvider)
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
