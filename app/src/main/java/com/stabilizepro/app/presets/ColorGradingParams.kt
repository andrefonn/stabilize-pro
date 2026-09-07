package com.stabilizepro.app.presets

/**
 * Full suite of real-time color grading and enhancement parameters
 * evaluated in real-time by OpenGL ES fragment shaders and video export pipelines.
 */
data class ColorGradingParams(
    val exposure: Float = 0.0f,          // -2.0f .. 2.0f
    val contrast: Float = 1.0f,          // 0.5f .. 1.5f
    val shadows: Float = 0.0f,           // -1.0f .. 1.0f
    val highlights: Float = 0.0f,        // -1.0f .. 1.0f
    val brightness: Float = 0.0f,        // -1.0f .. 1.0f
    val blackPoint: Float = 0.0f,        // -0.5f .. 0.5f
    val saturation: Float = 1.0f,        // 0.0f .. 2.0f
    val vibrance: Float = 0.0f,          // -1.0f .. 1.0f
    val temperature: Float = 0.0f,       // -1.0f .. 1.0f (Cool <-> Warm)
    val tint: Float = 0.0f,              // -1.0f .. 1.0f (Green <-> Magenta)
    val sharpness: Float = 0.0f,         // 0.0f .. 2.0f
    val definition: Float = 0.0f,        // 0.0f .. 2.0f (Clarity / Midtone contrast)
    val noiseReduction: Float = 0.0f,    // 0.0f .. 1.0f
    val vignette: Float = 0.0f           // 0.0f .. 1.0f
) {
    fun isNeutral(): Boolean {
        return exposure == 0.0f &&
                contrast == 1.0f &&
                shadows == 0.0f &&
                highlights == 0.0f &&
                brightness == 0.0f &&
                blackPoint == 0.0f &&
                saturation == 1.0f &&
                vibrance == 0.0f &&
                temperature == 0.0f &&
                tint == 0.0f &&
                sharpness == 0.0f &&
                definition == 0.0f &&
                noiseReduction == 0.0f &&
                vignette == 0.0f
    }
}
