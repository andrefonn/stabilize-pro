package com.stabilizepro.app.domain.model

enum class StabilizationIntensity(
    val title: String,
    val subtitle: String,
    val cropPercent: Int,
    val smoothingRadius: Int
) {
    LOW(
        title = "Baixa",
        subtitle = "Suavização leve • Crop máx. 3% • Preserva enquadramento",
        cropPercent = 3,
        smoothingRadius = 20
    ),
    MEDIUM(
        title = "Média",
        subtitle = "Equilíbrio Pro • Crop máx. 7% • Sem bordas",
        cropPercent = 7,
        smoothingRadius = 35
    ),
    HIGH(
        title = "Máxima",
        subtitle = "Estabilidade máxima • Crop máx. 12% • Ideal para esportes",
        cropPercent = 12,
        smoothingRadius = 60
    )
}

data class StabilizationConfig(
    val intensity: StabilizationIntensity = StabilizationIntensity.MEDIUM,
    val preserveOriginalFps: Boolean = true,
    val preserveOriginalResolution: Boolean = true,
    val autoCrop: Boolean = true,
    val removeBlackBorders: Boolean = true,
    val keepOriginalAudio: Boolean = true,
    val preset: com.stabilizepro.app.presets.ColorGradingParams? = null
)
