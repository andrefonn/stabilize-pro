package com.stabilizepro.app.worker

import android.content.Context
import android.net.Uri
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.stabilizepro.app.domain.model.StabilizationConfig
import com.stabilizepro.app.export.ExportQuality
import com.stabilizepro.app.logs.DebugCenter
import com.stabilizepro.app.logs.LogLevel
import com.stabilizepro.app.logs.LogModule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.util.UUID
import java.util.concurrent.Executors

data class QueueTaskPersistDto(
    val id: String,
    val inputUriString: String,
    val videoTitle: String,
    val configJson: String?,
    val presetId: String?,
    val qualityName: String,
    val statusName: String,
    val progressPercent: Int,
    val stageName: String,
    val estimatedSecondsRemaining: Long,
    val outputUriString: String?,
    val outputFilePath: String?,
    val errorMessage: String?,
    val createdAt: Long,
    val completedAt: Long?
)

object StabilizationQueueManager {

    private const val TAG = "StabilizationQueue"
    private const val UNIQUE_QUEUE_NAME = "stabilizepro_background_queue"
    private const val TASKS_FILE_NAME = "stabilize_queue_tasks.json"

    val KEY_TASK_ID = "key_task_id"
    val KEY_INPUT_URI = "key_input_uri"
    val KEY_VIDEO_TITLE = "key_video_title"
    val KEY_CONFIG_JSON = "key_config_json"
    val KEY_PRESET_ID = "key_preset_id"
    val KEY_QUALITY_NAME = "key_quality_name"

    private val gson = Gson()
    private val diskExecutor = Executors.newSingleThreadExecutor()

    @Volatile
    private var appContext: Context? = null

    private val _tasks = MutableStateFlow<List<StabilizationTask>>(emptyList())
    val tasks: StateFlow<List<StabilizationTask>> = _tasks.asStateFlow()

    fun init(context: Context) {
        if (appContext != null && _tasks.value.isNotEmpty()) return
        appContext = context.applicationContext
        loadTasksFromDisk()
    }

    private fun loadTasksFromDisk() {
        val ctx = appContext ?: return
        try {
            val file = File(ctx.filesDir, TASKS_FILE_NAME)
            if (file.exists()) {
                FileReader(file).use { reader ->
                    val type = object : TypeToken<List<QueueTaskPersistDto>>() {}.type
                    val dtoList: List<QueueTaskPersistDto>? = gson.fromJson(reader, type)
                    if (dtoList != null) {
                        val models = dtoList.map { dto ->
                            val parsedConfig = try {
                                if (!dto.configJson.isNullOrBlank()) {
                                    gson.fromJson(dto.configJson, StabilizationConfig::class.java)
                                } else StabilizationConfig()
                            } catch (e: Exception) {
                                StabilizationConfig()
                            }

                            val parsedQuality = try {
                                ExportQuality.valueOf(dto.qualityName)
                            } catch (e: Exception) {
                                ExportQuality.ORIGINAL
                            }

                            val parsedStatus = try {
                                QueueStatus.valueOf(dto.statusName)
                            } catch (e: Exception) {
                                QueueStatus.AGUARDANDO
                            }

                            StabilizationTask(
                                id = dto.id,
                                inputUri = if (dto.inputUriString.isNotBlank()) Uri.parse(dto.inputUriString) else Uri.EMPTY,
                                videoTitle = dto.videoTitle,
                                config = parsedConfig,
                                presetId = dto.presetId,
                                quality = parsedQuality,
                                status = parsedStatus,
                                progressPercent = dto.progressPercent,
                                stageName = dto.stageName,
                                estimatedSecondsRemaining = dto.estimatedSecondsRemaining,
                                outputUri = dto.outputUriString?.let { if (it.isNotBlank()) Uri.parse(it) else null },
                                outputFilePath = dto.outputFilePath,
                                errorMessage = dto.errorMessage,
                                createdAt = dto.createdAt,
                                completedAt = dto.completedAt
                            )
                        }
                        _tasks.value = models
                        DebugCenter.log(
                            LogModule.WorkManager,
                            LogLevel.INFO,
                            "Fila restaurada do disco: ${models.size} tarefas encontradas."
                        )
                    }
                }
            }
        } catch (e: Exception) {
            DebugCenter.log(
                LogModule.WorkManager,
                LogLevel.WARN,
                "Falha ao ler tarefas salvas no disco: ${e.message}",
                throwable = e
            )
        }
    }

    private fun saveTasksToDisk() {
        val ctx = appContext ?: return
        val currentList = _tasks.value
        diskExecutor.execute {
            try {
                val dtos = currentList.map { model ->
                    QueueTaskPersistDto(
                        id = model.id,
                        inputUriString = model.inputUri.toString(),
                        videoTitle = model.videoTitle,
                        configJson = gson.toJson(model.config),
                        presetId = model.presetId,
                        qualityName = model.quality.name,
                        statusName = model.status.name,
                        progressPercent = model.progressPercent,
                        stageName = model.stageName,
                        estimatedSecondsRemaining = model.estimatedSecondsRemaining,
                        outputUriString = model.outputUri?.toString(),
                        outputFilePath = model.outputFilePath,
                        errorMessage = model.errorMessage,
                        createdAt = model.createdAt,
                        completedAt = model.completedAt
                    )
                }

                val file = File(ctx.filesDir, TASKS_FILE_NAME)
                val tempFile = File(ctx.filesDir, "$TASKS_FILE_NAME.tmp")
                FileWriter(tempFile).use { writer ->
                    gson.toJson(dtos, writer)
                }
                if (tempFile.exists()) {
                    if (file.exists()) file.delete()
                    tempFile.renameTo(file)
                }
            } catch (e: Exception) {
                DebugCenter.log(
                    LogModule.WorkManager,
                    LogLevel.ERROR,
                    "Falha ao persistir tarefas no disco: ${e.message}",
                    errorCode = "#403",
                    throwable = e
                )
            }
        }
    }

    fun enqueueTask(
        context: Context,
        inputUri: Uri,
        videoTitle: String,
        config: StabilizationConfig,
        presetId: String? = null,
        quality: ExportQuality = ExportQuality.ORIGINAL
    ): StabilizationTask {
        init(context)
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

        // Add to reactive tasks flow & persist
        _tasks.value = listOf(newTask) + _tasks.value.filter { it.id != taskId }
        saveTasksToDisk()

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
            "Tarefa enfileirada e persistida: $videoTitle ($taskId)"
        )

        return newTask
    }

    fun updateTaskProgress(
        taskId: String,
        progressPercent: Int,
        stageName: String,
        secondsRemaining: Long,
        videoTitle: String? = null
    ) {
        val currentList = _tasks.value
        val existing = currentList.find { it.id == taskId }

        val updatedList = if (existing != null) {
            currentList.map { task ->
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
        } else {
            // Task wasn't in memory yet (e.g. process restarted) -> Reconstruct and add!
            val reconstructed = StabilizationTask(
                id = taskId,
                inputUri = Uri.EMPTY,
                videoTitle = videoTitle ?: "Vídeo em Estabilização",
                status = QueueStatus.PROCESSANDO,
                progressPercent = progressPercent,
                stageName = stageName,
                estimatedSecondsRemaining = secondsRemaining
            )
            listOf(reconstructed) + currentList
        }

        _tasks.value = updatedList
        saveTasksToDisk()
    }

    fun markTaskCompleted(
        taskId: String,
        outputUri: Uri,
        outputFilePath: String,
        videoTitle: String? = null
    ) {
        val currentList = _tasks.value
        val existing = currentList.find { it.id == taskId }

        val updatedList = if (existing != null) {
            currentList.map { task ->
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
        } else {
            val completed = StabilizationTask(
                id = taskId,
                inputUri = Uri.EMPTY,
                videoTitle = videoTitle ?: "Vídeo Estabilizado",
                status = QueueStatus.CONCLUIDO,
                progressPercent = 100,
                stageName = "Concluído",
                estimatedSecondsRemaining = 0L,
                outputUri = outputUri,
                outputFilePath = outputFilePath,
                completedAt = System.currentTimeMillis()
            )
            listOf(completed) + currentList
        }

        _tasks.value = updatedList
        saveTasksToDisk()
    }

    fun markTaskFailed(taskId: String, errorMessage: String, videoTitle: String? = null) {
        val currentList = _tasks.value
        val existing = currentList.find { it.id == taskId }

        val updatedList = if (existing != null) {
            currentList.map { task ->
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
        } else {
            val failed = StabilizationTask(
                id = taskId,
                inputUri = Uri.EMPTY,
                videoTitle = videoTitle ?: "Vídeo",
                status = QueueStatus.FALHOU,
                stageName = "Falhou",
                errorMessage = errorMessage,
                completedAt = System.currentTimeMillis()
            )
            listOf(failed) + currentList
        }

        _tasks.value = updatedList
        saveTasksToDisk()
    }

    fun getTaskById(taskId: String): StabilizationTask? {
        return _tasks.value.find { it.id == taskId }
    }

    fun clearCompleted() {
        _tasks.value = _tasks.value.filter { it.status == QueueStatus.PROCESSANDO || it.status == QueueStatus.AGUARDANDO }
        saveTasksToDisk()
    }
}
