package com.stabilizepro.app.ui.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.stabilizepro.app.data.repository.VideoRepositoryImpl
import com.stabilizepro.app.domain.model.StabilizationConfig
import com.stabilizepro.app.domain.model.StabilizationIntensity
import com.stabilizepro.app.domain.model.StabilizationProgress
import com.stabilizepro.app.domain.model.StabilizationResult
import com.stabilizepro.app.domain.model.VideoInfo
import com.stabilizepro.app.domain.repository.VideoRepository
import com.stabilizepro.app.domain.usecase.ExtractVideoInfoUseCase
import com.stabilizepro.app.domain.usecase.SaveToGalleryUseCase
import com.stabilizepro.app.domain.usecase.StabilizeVideoUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

enum class MainTab {
    CAMERA,
    STABILIZER,
    QUEUE
}

sealed class AppScreen {
    data object Splash : AppScreen()
    data class Main(val activeTab: MainTab = MainTab.STABILIZER) : AppScreen()
    data object Processing : AppScreen()
    data class Result(val result: StabilizationResult) : AppScreen()
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: VideoRepository = VideoRepositoryImpl(application.applicationContext)
    private val extractVideoInfoUseCase = ExtractVideoInfoUseCase(repository)
    private val stabilizeVideoUseCase = StabilizeVideoUseCase(repository)
    private val saveToGalleryUseCase = SaveToGalleryUseCase(repository)

    private val _currentScreen = MutableStateFlow<AppScreen>(AppScreen.Splash)
    val currentScreen: StateFlow<AppScreen> = _currentScreen.asStateFlow()

    private val _selectedVideo = MutableStateFlow<VideoInfo?>(null)
    val selectedVideo: StateFlow<VideoInfo?> = _selectedVideo.asStateFlow()

    private val _config = MutableStateFlow(StabilizationConfig())
    val config: StateFlow<StabilizationConfig> = _config.asStateFlow()

    private val _progress = MutableStateFlow(StabilizationProgress())
    val progress: StateFlow<StabilizationProgress> = _progress.asStateFlow()

    private val _saveGalleryStatus = MutableStateFlow<String?>(null)
    val saveGalleryStatus: StateFlow<String?> = _saveGalleryStatus.asStateFlow()

    private var pendingTab: MainTab? = null

    fun onSplashFinished() {
        val target = pendingTab ?: MainTab.CAMERA
        _currentScreen.value = AppScreen.Main(target)
    }

    fun selectTab(tab: MainTab) {
        pendingTab = tab
        if (_currentScreen.value !is AppScreen.Splash) {
            _currentScreen.value = AppScreen.Main(tab)
        }
    }

    fun onVideoSelected(uri: Uri) {
        viewModelScope.launch {
            try {
                val info = extractVideoInfoUseCase(uri)
                _selectedVideo.value = info
            } catch (e: Exception) {
                // If metadata extraction fails, still set fallback info
                _selectedVideo.value = VideoInfo(
                    uri = uri,
                    name = "video.mp4",
                    durationMs = 0L,
                    width = 1280,
                    height = 720,
                    fps = 30f,
                    sizeBytes = 0L
                )
            }
        }
    }

    fun onIntensitySelected(intensity: StabilizationIntensity) {
        _config.value = _config.value.copy(intensity = intensity)
    }

    fun updateConfig(newConfig: StabilizationConfig) {
        _config.value = newConfig
    }

    private var stabilizationJob: kotlinx.coroutines.Job? = null

    fun startStabilization() {
        val video = _selectedVideo.value ?: return
        _currentScreen.value = AppScreen.Processing

        stabilizationJob = viewModelScope.launch {
            try {
                val stabilizationResult = stabilizeVideoUseCase(
                    inputUri = video.uri,
                    config = _config.value,
                    onProgress = { currentProgress ->
                        _progress.value = currentProgress
                    }
                )
                _currentScreen.value = AppScreen.Result(stabilizationResult)
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    _progress.value = _progress.value.copy(
                        errorMessage = e.localizedMessage ?: "Erro ao estabilizar vídeo"
                    )
                }
            }
        }
    }

    fun cancelStabilization() {
        stabilizationJob?.cancel()
        stabilizationJob = null
        val video = _selectedVideo.value
        if (video != null) {
            viewModelScope.launch {
                try {
                    saveToGalleryUseCase(video.uri, video.name)
                } catch (ignored: Exception) {}
            }
        }
        _currentScreen.value = AppScreen.Main(MainTab.STABILIZER)
        _progress.value = StabilizationProgress()
    }

    fun saveToGallery(result: StabilizationResult) {
        viewModelScope.launch {
            val file = File(result.outputFilePath)
            val saveResult = saveToGalleryUseCase(file)
            saveResult.onSuccess {
                _saveGalleryStatus.value = "Vídeo salvo na galeria com sucesso!"
            }.onFailure { error ->
                _saveGalleryStatus.value = "Erro ao salvar: ${error.localizedMessage}"
            }
        }
    }

    fun clearSaveGalleryStatus() {
        _saveGalleryStatus.value = null
    }

    fun shareVideo(context: Context, result: StabilizationResult) {
        val intent = repository.createShareIntent(result.stabilizedUri)
        val chooser = android.content.Intent.createChooser(intent, "Compartilhar vídeo estabilizado")
        chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }

    fun retryStabilization() {
        _progress.value = StabilizationProgress()
        startStabilization()
    }

    fun resetToHome() {
        _currentScreen.value = AppScreen.Main(MainTab.STABILIZER)
        _selectedVideo.value = null
        _progress.value = StabilizationProgress()
        _saveGalleryStatus.value = null
    }

    fun openResultFromUri(outputUri: Uri) {
        viewModelScope.launch {
            try {
                val info = extractVideoInfoUseCase(outputUri)
                val result = StabilizationResult(
                    originalUri = outputUri,
                    stabilizedUri = outputUri,
                    durationMs = info.durationMs,
                    fps = info.fps,
                    width = info.width,
                    height = info.height,
                    originalSizeBytes = info.sizeBytes,
                    stabilizedSizeBytes = info.sizeBytes,
                    outputFilePath = outputUri.path ?: ""
                )
                _currentScreen.value = AppScreen.Result(result)
            } catch (e: Exception) {
                val result = StabilizationResult(
                    originalUri = outputUri,
                    stabilizedUri = outputUri,
                    durationMs = 5000L,
                    fps = 30f,
                    width = 1280,
                    height = 720,
                    originalSizeBytes = 0L,
                    stabilizedSizeBytes = 0L,
                    outputFilePath = outputUri.path ?: ""
                )
                _currentScreen.value = AppScreen.Result(result)
            }
        }
    }
}
