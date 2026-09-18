package com.stabilizepro.app.domain.usecase

import android.net.Uri
import com.stabilizepro.app.domain.repository.VideoRepository
import java.io.File

class SaveToGalleryUseCase(private val repository: VideoRepository) {
    suspend operator fun invoke(file: File): Result<Uri> {
        return repository.saveVideoToGallery(file)
    }

    suspend operator fun invoke(uri: Uri, fileName: String? = null): Result<Uri> {
        return repository.saveVideoToGallery(uri, fileName)
    }
}
