package com.stabilizepro.app.presets

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetManagerTest {

    @Test
    fun testBuiltInPresetsCoverage() {
        val builtIns = PresetManager.BUILT_IN_PRESETS
        val names = builtIns.map { it.name }

        assertTrue("Cinemático must be present", names.contains("Cinemático"))
        assertTrue("Vlog must be present", names.contains("Vlog"))
        assertTrue("Produto must be present", names.contains("Produto"))
        assertTrue("Retrato must be present", names.contains("Retrato"))
        assertTrue("Noite must be present", names.contains("Noite"))
        assertTrue("Natural must be present", names.contains("Natural"))
        assertTrue("Neutro (Raw) must be present", names.contains("Neutro (Raw)"))

        val neutral = builtIns.first { it.name == "Neutro (Raw)" }
        assertTrue("Neutro (Raw) preset must be strictly neutral", neutral.params.isNeutral())

        val natural = builtIns.first { it.name == "Natural" }
        assertFalse("Natural preset provides subtle auto-enhancement", natural.params.isNeutral())
        assertTrue("Natural has calibrated sharpness", natural.params.sharpness > 0f)

        val cinematic = builtIns.first { it.name == "Cinemático" }
        assertFalse("Cinemático preset must not be neutral", cinematic.params.isNeutral())
        assertTrue("Cinemático has vignette", cinematic.params.vignette > 0f)
        assertTrue("Cinemático has contrast boost", cinematic.params.contrast > 1.0f)
    }

    @Test
    fun testPresetJsonSerialization() {
        val gson = Gson()
        val customPreset = PresetModel(
            name = "Meus Tons",
            category = "Custom",
            iconName = "brush",
            colorHex = "#FF5722",
            isBuiltIn = false,
            params = ColorGradingParams(
                exposure = 0.25f,
                contrast = 1.15f,
                sharpness = 0.5f,
                vignette = 0.2f
            )
        )

        val json = gson.toJson(listOf(customPreset))
        val type = object : TypeToken<List<PresetModel>>() {}.type
        val deserialized: List<PresetModel> = gson.fromJson(json, type)

        assertNotNull(deserialized)
        assertEquals(1, deserialized.size)
        assertEquals("Meus Tons", deserialized[0].name)
        assertEquals(0.25f, deserialized[0].params.exposure, 0.001f)
        assertEquals(1.15f, deserialized[0].params.contrast, 0.001f)
        assertEquals(0.5f, deserialized[0].params.sharpness, 0.001f)
        assertEquals(0.2f, deserialized[0].params.vignette, 0.001f)
    }
}
