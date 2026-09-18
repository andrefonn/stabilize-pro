package com.stabilizepro.app.domain.repository

import android.content.Intent
import android.net.Uri
import com.stabilizepro.app.domain.model.StabilizationConfig
import com.stabilizepro.app.domain.model.StabilizationProgress
import com.stabilizepro.app.domain.model.StabilizationResult
import com.stabilizepro.app.domain.model.VideoInfo
import java.io.File

interface VideoRepository {
    suspend fun extractVideoInfo(uri: Uri): VideoInfo
    suspend fun stabilizeVideo(
        inputUri: Uri,
        config: StabilizationConfig,
        onProgress: (StabilizationProgress) -> Unit
    ): StabilizationResult
    suspend fun saveVideoToGallery(outputFile: File): Result<Uri>
    suspend fun saveVideoToGallery(uri: Uri, fileName: String? = null): Result<Uri>
    suspend fun savePhotoToGallery(photoFile: File, fileName: String? = null): Result<Uri>
    fun createShareIntent(uri: Uri): Intent
}
