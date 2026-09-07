package com.stabilizepro.app.domain.model

enum class StabilizationStage(val displayName: String) {
    READING_VIDEO("Lendo vídeo"),
    DETECTING_MOTION("Detectando movimento"),
    CALCULATING_TRAJECTORY("Calculando trajetória"),
    SMOOTHING_CAMERA("Suavizando câmera"),
    RECREATING_VIDEO("Recriando vídeo"),
    FINALIZING_MP4("Finalizando MP4")
}

data class StabilizationProgress(
    val stage: StabilizationStage = StabilizationStage.READING_VIDEO,
    val progressPercent: Int = 0,
    val currentFrame: Int = 0,
    val totalFrames: Int = 0,
    val estimatedSecondsRemaining: Long = 0L,
    val isCompleted: Boolean = false,
    val errorMessage: String? = null
)
