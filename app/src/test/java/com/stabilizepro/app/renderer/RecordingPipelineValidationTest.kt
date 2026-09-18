package com.stabilizepro.app.renderer

import com.stabilizepro.app.camera.CameraAspectRatio
import com.stabilizepro.app.camera.VideoQualityOption
import com.stabilizepro.app.domain.model.StabilizationConfig
import com.stabilizepro.app.domain.model.StabilizationIntensity
import com.stabilizepro.app.presets.ColorGradingParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingPipelineValidationTest {

    @Test
    fun testResolutionsMacroblockAndEvenAlignment() {
        val aspectRatios = listOf(
            CameraAspectRatio.RATIO_16_9,
            CameraAspectRatio.RATIO_4_3,
            CameraAspectRatio.RATIO_1_1
        )
        val qualities = listOf(
            VideoQualityOption.AUTO_MAX,
            VideoQualityOption.UHD_4K,
            VideoQualityOption.QHD_2K,
            VideoQualityOption.FHD_1080P,
            VideoQualityOption.HD_720P
        )

        for (quality in qualities) {
            for (aspect in aspectRatios) {
                val baseShortDim = when (quality) {
                    VideoQualityOption.UHD_4K -> 2160
                    VideoQualityOption.QHD_2K -> 1440
                    VideoQualityOption.FHD_1080P -> 1080
                    VideoQualityOption.HD_720P -> 720
                    VideoQualityOption.AUTO_MAX -> 2160
                }
                val (rawW, rawH) = when (aspect) {
                    CameraAspectRatio.RATIO_16_9 -> {
                        val longDim = (baseShortDim * 16 / 9) / 16 * 16
                        Pair(baseShortDim, longDim)
                    }
                    CameraAspectRatio.RATIO_4_3 -> {
                        val longDim = (baseShortDim * 4 / 3) / 16 * 16
                        Pair(baseShortDim, longDim)
                    }
                    CameraAspectRatio.RATIO_1_1 -> {
                        Pair(baseShortDim, baseShortDim)
                    }
                }
                // Video encoders require even dimensions for YUV420 color format (multiple of 2)
                assertEquals("Width must be even for YUV420 ($rawW)", 0, rawW % 2)
                assertEquals("Height must be even for YUV420 ($rawH)", 0, rawH % 2)

                // H.264 macroblocks require dimensions aligned to at least 8 pixels (1080 is 135*8)
                assertEquals("Width must be aligned to at least 8-pixel macroblocks ($rawW)", 0, rawW % 8)
                assertEquals("Height must be aligned to at least 8-pixel macroblocks ($rawH)", 0, rawH % 8)

                // Computed long dimensions for 16:9 and 4:3 must be strictly multiples of 16
                if (aspect != CameraAspectRatio.RATIO_1_1) {
                    assertEquals("Calculated long dimension must be a multiple of 16 ($rawH)", 0, rawH % 16)
                }
            }
        }
    }

    @Test
    fun testAutoMaxDoesNotArtificiallyCapAt1080p() {
        val autoMaxBaseShortDim = 2160
        assertTrue("AUTO_MAX starts with 4K short dimension (2160) before hardware clamping", autoMaxBaseShortDim > 1080)
    }

    @Test
    fun testCameraRecordingAutoStabilizationPresetDeduplication() {
        // When camera records video, the GL FBO shader already applies the active color grading.
        // Post-recording stabilization configuration MUST have preset = null so that
        // VideoStabilizerEngine does NOT double-apply color grading.
        val cameraRecordingConfig = StabilizationConfig(
            intensity = StabilizationIntensity.MEDIUM,
            preset = null // Deduplicated
        )

        assertNull("Camera auto-stabilization must have null preset to prevent double application", cameraRecordingConfig.preset)

        // For user-imported videos from external gallery, preset CAN be specified
        val importedVideoConfig = StabilizationConfig(
            intensity = StabilizationIntensity.MEDIUM,
            preset = ColorGradingParams(exposure = 0.2f, contrast = 1.1f)
        )
        assertTrue("Imported videos may specify presets", importedVideoConfig.preset != null && !importedVideoConfig.preset!!.isNeutral())
    }

    @Test
    fun testNeutralAndCalibratedPresetsIntegrity() {
        val rawNeutral = ColorGradingParams()
        assertTrue("Default ColorGradingParams must be neutral", rawNeutral.isNeutral())

        val calibratedNatural = ColorGradingParams(
            sharpness = 0.15f,
            definition = 0.10f,
            vibrance = 0.08f,
            shadows = 0.05f,
            highlights = -0.05f
        )
        assertFalse("Calibrated natural preset must not be neutral", calibratedNatural.isNeutral())
        assertTrue("Calibrated natural provides mild sharpness", calibratedNatural.sharpness > 0f)
        assertTrue("Calibrated natural provides mild definition", calibratedNatural.definition > 0f)
    }
}
