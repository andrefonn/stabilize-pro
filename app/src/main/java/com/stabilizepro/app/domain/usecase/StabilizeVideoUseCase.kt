package com.stabilizepro.app.domain.usecase

import android.net.Uri
import com.stabilizepro.app.domain.model.StabilizationConfig
import com.stabilizepro.app.domain.model.StabilizationProgress
import com.stabilizepro.app.domain.model.StabilizationResult
import com.stabilizepro.app.domain.repository.VideoRepository

class StabilizeVideoUseCase(private val repository: VideoRepository) {
    suspend operator fun invoke(
        inputUri: Uri,
        config: StabilizationConfig,
        onProgress: (StabilizationProgress) -> Unit
    ): StabilizationResult {
        return repository.stabilizeVideo(inputUri, config, onProgress)
    }
}
