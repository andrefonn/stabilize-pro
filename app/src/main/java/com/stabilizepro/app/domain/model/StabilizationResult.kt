package com.stabilizepro.app.domain.model

import android.net.Uri

data class StabilizationResult(
    val originalUri: Uri,
    val stabilizedUri: Uri,
    val durationMs: Long,
    val fps: Float,
    val width: Int,
    val height: Int,
    val originalSizeBytes: Long,
    val stabilizedSizeBytes: Long,
    val outputFilePath: String
) {
    val durationFormatted: String
        get() {
            val totalSeconds = durationMs / 1000
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return String.format("%02d:%02d", minutes, seconds)
        }

    val resolutionFormatted: String
        get() = "${width}x${height}"

    val originalSizeFormatted: String
        get() = formatBytes(originalSizeBytes)

    val stabilizedSizeFormatted: String
        get() = formatBytes(stabilizedSizeBytes)

    private fun formatBytes(bytes: Long): String {
        val mb = bytes.toDouble() / (1024 * 1024)
        return if (mb >= 1.0) {
            String.format("%.1f MB", mb)
        } else {
            val kb = bytes.toDouble() / 1024
            String.format("%.0f KB", kb)
        }
    }
}
