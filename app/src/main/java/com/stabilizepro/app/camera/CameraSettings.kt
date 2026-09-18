package com.stabilizepro.app.camera

import android.hardware.camera2.CameraMetadata

enum class CaptureMode {
    PHOTO,
    VIDEO
}

enum class LensType(val displayName: String) {
    ULTRA_WIDE("0.5x Ultra Wide"),
    WIDE("1x Principal"),
    TELEPHOTO("2x / 3x Telefoto"),
    FRONT("Frontal")
}

enum class VideoQualityOption(val displayName: String) {
    AUTO_MAX("Máxima Suportada (Auto)"),
    UHD_4K("4K (2160p)"),
    QHD_2K("2K (1440p)"),
    FHD_1080P("Full HD (1080p)"),
    HD_720P("HD (720p)")
}

enum class CameraAspectRatio(val displayName: String, val ratioValue: Float) {
    RATIO_16_9("9:16", 9f / 16f),
    RATIO_4_3("3:4", 3f / 4f),
    RATIO_1_1("1:1", 1.0f)
}

data class CameraSettings(
    val captureMode: CaptureMode = CaptureMode.VIDEO,
    val selectedLens: LensType = LensType.WIDE,
    val quality: VideoQualityOption = VideoQualityOption.FHD_1080P,
    val targetFps: Int = 30, // 30 or 60 FPS
    val aspectRatio: CameraAspectRatio = CameraAspectRatio.RATIO_16_9,
    val iso: Int = 0, // 0 = Auto
    val shutterSpeedNs: Long = 0L, // 0 = Auto
    val evCompensation: Int = 0, // In EV steps
    val whiteBalanceMode: Int = CameraMetadata.CONTROL_AWB_MODE_AUTO,
    val focusDistance: Float = -1.0f, // -1.0f = Continuous Auto Focus (AF)
    val isHdrEnabled: Boolean = false,
    val isRecording: Boolean = false,
    val recordingDurationSec: Long = 0L,
    val autoStabilizeAfterRecording: Boolean = true
)
