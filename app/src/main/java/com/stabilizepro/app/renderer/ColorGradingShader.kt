package com.stabilizepro.app.renderer

import android.opengl.GLES20
import android.util.Log
import com.stabilizepro.app.logs.DebugCenter
import com.stabilizepro.app.logs.LogLevel
import com.stabilizepro.app.logs.LogModule
import com.stabilizepro.app.presets.ColorGradingParams

object ColorGradingShader {
    private const val TAG = "ColorGradingShader"

    const val VERTEX_SHADER = """
        attribute vec4 aPosition;
        attribute vec4 aTextureCoord;
        uniform mat4 uMVPMatrix;
        uniform mat4 uSTMatrix;
        varying vec2 vTextureCoord;

        void main() {
            gl_Position = uMVPMatrix * aPosition;
            vTextureCoord = (uSTMatrix * aTextureCoord).xy;
        }
    """

    const val BLIT_VERTEX_SHADER = """
        attribute vec4 aPosition;
        attribute vec2 aTextureCoord;
        uniform mat4 uMVPMatrix;
        varying vec2 vTextureCoord;

        void main() {
            gl_Position = uMVPMatrix * aPosition;
            vTextureCoord = aTextureCoord;
        }
    """

    const val BLIT_FRAGMENT_SHADER = """
        precision mediump float;
        varying vec2 vTextureCoord;
        uniform sampler2D sTexture;

        void main() {
            gl_FragColor = texture2D(sTexture, vTextureCoord);
        }
    """

    /**
     * High-performance OpenGL ES Fragment Shader with full 14-parameter real-time color grading.
     */
    const val FRAGMENT_SHADER_OES = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;

        varying vec2 vTextureCoord;
        uniform samplerExternalOES sTexture;

        uniform float uExposure;
        uniform float uContrast;
        uniform float uShadows;
        uniform float uHighlights;
        uniform float uBrightness;
        uniform float uBlackPoint;
        uniform float uSaturation;
        uniform float uVibrance;
        uniform float uTemperature;
        uniform float uTint;
        uniform float uSharpness;
        uniform float uDefinition;
        uniform float uNoiseReduction;
        uniform float uVignette;

        uniform vec2 uTexelSize;

        void main() {
            vec4 baseColor = texture2D(sTexture, vTextureCoord);
            vec3 rgb = baseColor.rgb;

            // 1. Noise reduction (adaptive 3x3 box smoothing)
            if (uNoiseReduction > 0.01) {
                vec3 blur = vec3(0.0);
                blur += texture2D(sTexture, vTextureCoord + vec2(-uTexelSize.x, -uTexelSize.y)).rgb;
                blur += texture2D(sTexture, vTextureCoord + vec2(0.0, -uTexelSize.y)).rgb;
                blur += texture2D(sTexture, vTextureCoord + vec2(uTexelSize.x, -uTexelSize.y)).rgb;
                blur += texture2D(sTexture, vTextureCoord + vec2(-uTexelSize.x, 0.0)).rgb;
                blur += rgb;
                blur += texture2D(sTexture, vTextureCoord + vec2(uTexelSize.x, 0.0)).rgb;
                blur += texture2D(sTexture, vTextureCoord + vec2(-uTexelSize.x, uTexelSize.y)).rgb;
                blur += texture2D(sTexture, vTextureCoord + vec2(0.0, uTexelSize.y)).rgb;
                blur += texture2D(sTexture, vTextureCoord + vec2(uTexelSize.x, uTexelSize.y)).rgb;
                blur /= 9.0;
                rgb = mix(rgb, blur, clamp(uNoiseReduction, 0.0, 0.8));
            }

            // 2. Sharpness & Definition (Laplacian convolution unsharp mask)
            if (uSharpness > 0.01 || uDefinition > 0.01) {
                vec3 n = texture2D(sTexture, vTextureCoord + vec2(0.0, -uTexelSize.y)).rgb;
                vec3 s = texture2D(sTexture, vTextureCoord + vec2(0.0, uTexelSize.y)).rgb;
                vec3 e = texture2D(sTexture, vTextureCoord + vec2(uTexelSize.x, 0.0)).rgb;
                vec3 w = texture2D(sTexture, vTextureCoord + vec2(-uTexelSize.x, 0.0)).rgb;
                vec3 edges = 4.0 * rgb - (n + s + e + w);
                
                float sharpFactor = uSharpness * 0.75;
                float defFactor = uDefinition * 0.45;
                rgb += edges * (sharpFactor + defFactor);
            }

            // 3. Exposure
            if (abs(uExposure) > 0.001) {
                rgb *= exp2(uExposure);
            }

            // 4. Brightness
            if (abs(uBrightness) > 0.001) {
                rgb += vec3(uBrightness * 0.2);
            }

            // 5. Black Point
            if (abs(uBlackPoint) > 0.001) {
                rgb = max(vec3(0.0), rgb - vec3(uBlackPoint * 0.25)) / (1.0 - uBlackPoint * 0.25);
            }

            // 6. Contrast
            if (abs(uContrast - 1.0) > 0.001) {
                rgb = (rgb - vec3(0.5)) * uContrast + vec3(0.5);
            }

            // Luminance calculation
            float lum = dot(rgb, vec3(0.2126, 0.7152, 0.0722));

            // 7. Shadows and Highlights
            if (abs(uShadows) > 0.001) {
                float shadowMask = clamp((0.5 - lum) * 2.0, 0.0, 1.0);
                rgb += vec3(uShadows * shadowMask * 0.35);
            }
            if (abs(uHighlights) > 0.001) {
                float highlightMask = clamp((lum - 0.5) * 2.0, 0.0, 1.0);
                rgb += vec3(uHighlights * highlightMask * 0.35);
            }

            // 8. Temperature & Tint
            if (abs(uTemperature) > 0.001) {
                rgb.r += uTemperature * 0.15;
                rgb.b -= uTemperature * 0.15;
            }
            if (abs(uTint) > 0.001) {
                rgb.g -= uTint * 0.12;
                rgb.r += uTint * 0.06;
                rgb.b += uTint * 0.06;
            }

            // Recompute lum after temp/tint
            lum = dot(rgb, vec3(0.2126, 0.7152, 0.0722));

            // 9. Saturation
            if (abs(uSaturation - 1.0) > 0.001) {
                rgb = mix(vec3(lum), rgb, uSaturation);
            }

            // 10. Vibrance (boosts less saturated colors, protects skin)
            if (abs(uVibrance) > 0.001) {
                float maxCol = max(rgb.r, max(rgb.g, rgb.b));
                float minCol = min(rgb.r, min(rgb.g, rgb.b));
                float curSat = maxCol - minCol;
                float vibranceWeight = (1.0 - curSat) * uVibrance * 0.7;
                rgb = mix(vec3(lum), rgb, 1.0 + vibranceWeight);
            }

            // 11. Vignette
            if (uVignette > 0.01) {
                float dist = distance(vTextureCoord, vec2(0.5, 0.5));
                float vig = smoothstep(0.85, 0.55 - uVignette * 0.35, dist);
                rgb *= mix(1.0, vig, uVignette);
            }

            gl_FragColor = vec4(clamp(rgb, 0.0, 1.0), baseColor.a);
        }
    """

    fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        if (vertexShader == 0) return 0
        val pixelShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        if (pixelShader == 0) return 0

        var program = GLES20.glCreateProgram()
        if (program != 0) {
            GLES20.glAttachShader(program, vertexShader)
            GLES20.glAttachShader(program, pixelShader)
            GLES20.glLinkProgram(program)
            val linkStatus = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] != GLES20.GL_TRUE) {
                val info = GLES20.glGetProgramInfoLog(program)
                DebugCenter.log(
                    LogModule.CameraX,
                    LogLevel.ERROR,
                    "Falha ao linkar programa GL: $info",
                    "#201"
                )
                GLES20.glDeleteProgram(program)
                program = 0
            }
        }
        return program
    }

    private fun loadShader(shaderType: Int, source: String): Int {
        var shader = GLES20.glCreateShader(shaderType)
        if (shader != 0) {
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val compiled = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
            if (compiled[0] == 0) {
                val info = GLES20.glGetShaderInfoLog(shader)
                DebugCenter.log(
                    LogModule.CameraX,
                    LogLevel.ERROR,
                    "Falha ao compilar shader tipo $shaderType: $info",
                    "#202"
                )
                GLES20.glDeleteShader(shader)
                shader = 0
            }
        }
        return shader
    }
}
