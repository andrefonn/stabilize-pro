package com.stabilizepro.app.worker

import android.content.Context
import android.net.Uri
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.gson.Gson
import com.stabilizepro.app.domain.model.StabilizationConfig
import com.stabilizepro.app.export.ExportQuality
import com.stabilizepro.app.logs.DebugCenter
import com.stabilizepro.app.logs.LogLevel
import com.stabilizepro.app.logs.LogModule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

object StabilizationQueueManager {

    private const val TAG = "StabilizationQueue"
    private const val UNIQUE_QUEUE_NAME = "stabilizepro_background_queue"

    val KEY_TASK_ID = "key_task_id"
    val KEY_INPUT_URI = "key_input_uri"
    val KEY_VIDEO_TITLE = "key_video_title"
    val KEY_CONFIG_JSON = "key_config_json"
    val KEY_PRESET_ID = "key_preset_id"
    val KEY_QUALITY_NAME = "key_quality_name"

    private val gson = Gson()
    private val _tasks = MutableStateFlow<List<StabilizationTask>>(emptyList())
    val tasks: StateFlow<List<StabilizationTask>> = _tasks.asStateFlow()

    fun enqueueTask(
        context: Context,
        inputUri: Uri,
        videoTitle: String,
        config: StabilizationConfig,
        presetId: String? = null,
        quality: ExportQuality = ExportQuality.ORIGINAL
    ): StabilizationTask {
        val taskId = UUID.randomUUID().toString()

        val newTask = StabilizationTask(
            id = taskId,
            inputUri = inputUri,
            videoTitle = videoTitle,
            config = config,
            presetId = presetId,
            quality = quality,
            status = QueueStatus.AGUARDANDO,
            stageName = "Aguardando na fila...",
            progressPercent = 0
        )

        // Add to reactive tasks flow
        _tasks.value = listOf(newTask) + _tasks.value

        // Prepare WorkManager request
        val inputData = Data.Builder()
            .putString(KEY_TASK_ID, taskId)
            .putString(KEY_INPUT_URI, inputUri.toString())
            .putString(KEY_VIDEO_TITLE, videoTitle)
            .putString(KEY_CONFIG_JSON, gson.toJson(config))
            .putString(KEY_PRESET_ID, presetId ?: "")
            .putString(KEY_QUALITY_NAME, quality.name)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<StabilizationWorker>()
            .setInputData(inputData)
            .addTag("stabilize_task_$taskId")
            .build()

        // Append to sequential unique queue so recording multiple videos processes them in order
        WorkManager.getInstance(context)
            .beginUniqueWork(
                UNIQUE_QUEUE_NAME,
                ExistingWorkPolicy.APPEND,
                workRequest
            )
            .enqueue()

        DebugCenter.log(
            LogModule.WorkManager,
            LogLevel.INFO,
            "Tarefa enfileirada: $videoTitle ($taskId)"
        )

        return newTask
    }

    fun updateTaskProgress(
        taskId: String,
        progressPercent: Int,
        stageName: String,
        secondsRemaining: Long
    ) {
        _tasks.value = _tasks.value.map { task ->
            if (task.id == taskId) {
                task.copy(
                    status = QueueStatus.PROCESSANDO,
                    progressPercent = progressPercent,
                    stageName = stageName,
                    estimatedSecondsRemaining = secondsRemaining
                )
            } else {
                task
            }
        }
    }

    fun markTaskCompleted(
        taskId: String,
        outputUri: Uri,
        outputFilePath: String
    ) {
        _tasks.value = _tasks.value.map { task ->
            if (task.id == taskId) {
                task.copy(
                    status = QueueStatus.CONCLUIDO,
                    progressPercent = 100,
                    stageName = "Concluído",
                    estimatedSecondsRemaining = 0L,
                    outputUri = outputUri,
                    outputFilePath = outputFilePath,
                    completedAt = System.currentTimeMillis()
                )
            } else {
                task
            }
        }
    }

    fun markTaskFailed(taskId: String, errorMessage: String) {
        _tasks.value = _tasks.value.map { task ->
            if (task.id == taskId) {
                task.copy(
                    status = QueueStatus.FALHOU,
                    stageName = "Falhou",
                    errorMessage = errorMessage,
                    completedAt = System.currentTimeMillis()
                )
            } else {
                task
            }
        }
    }

    fun getTaskById(taskId: String): StabilizationTask? {
        return _tasks.value.find { it.id == taskId }
    }

    fun clearCompleted() {
        _tasks.value = _tasks.value.filter { it.status != QueueStatus.CONCLUIDO }
    }
}
