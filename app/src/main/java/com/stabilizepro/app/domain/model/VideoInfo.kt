package com.stabilizepro.app.domain.model

import android.net.Uri

data class VideoInfo(
    val uri: Uri,
    val name: String,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val fps: Float,
    val sizeBytes: Long,
    val bitrate: Long = 0L,
    val rotation: Int = 0,
    val codec: String = "H.264 (AVC)"
) {
    val durationFormatted: String
        get() {
            val totalSeconds = durationMs / 1000
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return String.format("%02d:%02d", minutes, seconds)
        }

    val sizeFormatted: String
        get() {
            val mb = sizeBytes.toDouble() / (1024 * 1024)
            return if (mb >= 1.0) {
                String.format("%.1f MB", mb)
            } else {
                val kb = sizeBytes.toDouble() / 1024
                String.format("%.0f KB", kb)
            }
        }

    val resolutionFormatted: String
        get() = "${width}x${height}"
}
