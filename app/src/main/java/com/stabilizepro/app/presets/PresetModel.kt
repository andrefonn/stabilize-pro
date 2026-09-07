package com.stabilizepro.app.presets

import java.util.UUID

data class PresetModel(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val category: String = "Geral",
    val iconName: String = "sparkles",
    val colorHex: String = "#00D2FF",
    val isBuiltIn: Boolean = false,
    val params: ColorGradingParams = ColorGradingParams()
)
