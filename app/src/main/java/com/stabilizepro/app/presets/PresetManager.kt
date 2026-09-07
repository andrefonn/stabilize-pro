package com.stabilizepro.app.presets

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.stabilizepro.app.logs.DebugCenter
import com.stabilizepro.app.logs.LogLevel
import com.stabilizepro.app.logs.LogModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

class PresetManager(private val context: Context) {

    companion object {
        private const val TAG = "PresetManager"
        private const val PRESETS_FILE_NAME = "stabilize_presets.json"

        val BUILT_IN_PRESETS = listOf(
            PresetModel(
                id = "builtin_cinematic",
                name = "Cinemático",
                category = "Cinema",
                iconName = "movie",
                colorHex = "#FFB300",
                isBuiltIn = true,
                params = ColorGradingParams(
                    exposure = 0.05f,
                    contrast = 1.18f,
                    shadows = -0.15f,
                    highlights = -0.10f,
                    brightness = -0.05f,
                    blackPoint = 0.05f,
                    saturation = 0.90f,
                    vibrance = 0.20f,
                    temperature = 0.15f,
                    tint = -0.05f,
                    sharpness = 0.40f,
                    definition = 0.35f,
                    vignette = 0.30f
                )
            ),
            PresetModel(
                id = "builtin_vlog",
                name = "Vlog",
                category = "Social",
                iconName = "videocam",
                colorHex = "#00E676",
                isBuiltIn = true,
                params = ColorGradingParams(
                    exposure = 0.10f,
                    contrast = 1.05f,
                    shadows = 0.20f,
                    highlights = -0.05f,
                    brightness = 0.05f,
                    saturation = 1.15f,
                    vibrance = 0.30f,
                    temperature = 0.05f,
                    sharpness = 0.30f,
                    definition = 0.20f
                )
            ),
            PresetModel(
                id = "builtin_produto",
                name = "Produto",
                category = "Comercial",
                iconName = "shopping_bag",
                colorHex = "#2979FF",
                isBuiltIn = true,
                params = ColorGradingParams(
                    exposure = 0.0f,
                    contrast = 1.10f,
                    shadows = 0.10f,
                    highlights = 0.0f,
                    brightness = 0.05f,
                    saturation = 1.05f,
                    vibrance = 0.10f,
                    temperature = 0.0f,
                    sharpness = 0.60f,
                    definition = 0.50f,
                    noiseReduction = 0.20f
                )
            ),
            PresetModel(
                id = "builtin_retrato",
                name = "Retrato",
                category = "Pessoas",
                iconName = "face",
                colorHex = "#FF4081",
                isBuiltIn = true,
                params = ColorGradingParams(
                    exposure = 0.10f,
                    contrast = 0.95f,
                    shadows = 0.15f,
                    highlights = -0.15f,
                    brightness = 0.05f,
                    saturation = 0.95f,
                    vibrance = 0.15f,
                    temperature = 0.12f,
                    tint = 0.05f,
                    sharpness = 0.20f,
                    definition = -0.10f,
                    vignette = 0.20f
                )
            ),
            PresetModel(
                id = "builtin_noite",
                name = "Noite",
                category = "Baixa Luz",
                iconName = "bedtime",
                colorHex = "#7C4DFF",
                isBuiltIn = true,
                params = ColorGradingParams(
                    exposure = 0.20f,
                    contrast = 1.20f,
                    shadows = 0.30f,
                    highlights = -0.25f,
                    blackPoint = 0.10f,
                    saturation = 1.10f,
                    vibrance = 0.20f,
                    temperature = -0.15f,
                    noiseReduction = 0.50f,
                    sharpness = 0.25f,
                    vignette = 0.15f
                )
            ),
            PresetModel(
                id = "builtin_natural",
                name = "Natural",
                category = "Neutro",
                iconName = "auto_awesome",
                colorHex = "#00D2FF",
                isBuiltIn = true,
                params = ColorGradingParams()
            )
        )
    }

    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO)
    private val _presets = MutableStateFlow<List<PresetModel>>(BUILT_IN_PRESETS)
    val presets: StateFlow<List<PresetModel>> = _presets.asStateFlow()

    init {
        loadPresets()
    }

    fun loadPresets() {
        try {
            val file = File(context.filesDir, PRESETS_FILE_NAME)
            if (file.exists()) {
                val json = file.readText()
                val type = object : TypeToken<List<PresetModel>>() {}.type
                val savedList: List<PresetModel> = gson.fromJson(json, type) ?: emptyList()
                val merged = BUILT_IN_PRESETS + savedList.filter { !it.isBuiltIn }
                _presets.value = merged
            } else {
                _presets.value = BUILT_IN_PRESETS
            }
        } catch (e: Exception) {
            DebugCenter.log(
                module = LogModule.General,
                level = LogLevel.ERROR,
                message = "Erro ao carregar presets: ${e.message}",
                errorCode = "#801",
                throwable = e
            )
            _presets.value = BUILT_IN_PRESETS
        }
    }

    fun saveCustomPreset(preset: PresetModel) {
        scope.launch {
            try {
                val current = _presets.value.toMutableList()
                val existingIndex = current.indexOfFirst { it.id == preset.id }
                if (existingIndex >= 0) {
                    current[existingIndex] = preset.copy(isBuiltIn = false)
                } else {
                    current.add(preset.copy(isBuiltIn = false))
                }
                _presets.value = current
                persistCustomPresets(current.filter { !it.isBuiltIn })
            } catch (e: Exception) {
                DebugCenter.log(
                    module = LogModule.General,
                    level = LogLevel.ERROR,
                    message = "Erro ao salvar preset: ${e.message}",
                    errorCode = "#802",
                    throwable = e
                )
            }
        }
    }

    fun deleteCustomPreset(presetId: String) {
        scope.launch {
            try {
                val current = _presets.value.toMutableList()
                current.removeAll { it.id == presetId && !it.isBuiltIn }
                _presets.value = current
                persistCustomPresets(current.filter { !it.isBuiltIn })
            } catch (e: Exception) {
                DebugCenter.log(
                    module = LogModule.General,
                    level = LogLevel.ERROR,
                    message = "Erro ao deletar preset: ${e.message}",
                    errorCode = "#803",
                    throwable = e
                )
            }
        }
    }

    private fun persistCustomPresets(customList: List<PresetModel>) {
        val file = File(context.filesDir, PRESETS_FILE_NAME)
        val json = gson.toJson(customList)
        file.writeText(json)
    }

    fun getPresetById(id: String): PresetModel? {
        return _presets.value.find { it.id == id }
    }
}
