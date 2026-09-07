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

data class CameraSettings(
    val captureMode: CaptureMode = CaptureMode.VIDEO,
    val selectedLens: LensType = LensType.WIDE,
    val quality: VideoQualityOption = VideoQualityOption.AUTO_MAX,
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
