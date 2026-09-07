package com.stabilizepro.app.domain.usecase

import android.net.Uri
import com.stabilizepro.app.domain.model.VideoInfo
import com.stabilizepro.app.domain.repository.VideoRepository

class ExtractVideoInfoUseCase(private val repository: VideoRepository) {
    suspend operator fun invoke(uri: Uri): VideoInfo {
        return repository.extractVideoInfo(uri)
    }
}
