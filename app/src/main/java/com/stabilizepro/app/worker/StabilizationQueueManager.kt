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
    val id: String? = null,
    val inputUriString: String? = null,
    val videoTitle: String? = null,
    val configJson: String? = null,
    val presetId: String? = null,
    val qualityName: String? = null,
    val statusName: String? = null,
    val progressPercent: Int? = null,
    val stageName: String? = null,
    val estimatedSecondsRemaining: Long? = null,
    val outputUriString: String? = null,
    val outputFilePath: String? = null,
    val errorMessage: String? = null,
    val createdAt: Long? = null,
    val completedAt: Long? = null
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
                        val models = dtoList.mapNotNull { dto ->
                            try {
                                val parsedConfig = try {
                                    if (!dto.configJson.isNullOrBlank()) {
                                        gson.fromJson(dto.configJson, StabilizationConfig::class.java)
                                    } else StabilizationConfig()
                                } catch (e: Exception) {
                                    StabilizationConfig()
                                }

                                val parsedQuality = try {
                                    dto.qualityName?.let { ExportQuality.valueOf(it) } ?: ExportQuality.ORIGINAL
                                } catch (e: Exception) {
                                    ExportQuality.ORIGINAL
                                }

                                val parsedStatus = try {
                                    dto.statusName?.let { QueueStatus.valueOf(it) } ?: QueueStatus.AGUARDANDO
                                } catch (e: Exception) {
                                    QueueStatus.AGUARDANDO
                                }

                                StabilizationTask(
                                    id = dto.id ?: UUID.randomUUID().toString(),
                                    inputUri = dto.inputUriString?.let { if (it.isNotBlank()) Uri.parse(it) else Uri.EMPTY } ?: Uri.EMPTY,
                                    videoTitle = dto.videoTitle ?: "Vídeo",
                                    config = parsedConfig,
                                    presetId = dto.presetId,
                                    quality = parsedQuality,
                                    status = parsedStatus,
                                    progressPercent = (dto.progressPercent ?: 0).coerceIn(0, 100),
                                    stageName = dto.stageName ?: "Na fila",
                                    estimatedSecondsRemaining = dto.estimatedSecondsRemaining ?: 0L,
                                    outputUri = dto.outputUriString?.let { if (it.isNotBlank()) Uri.parse(it) else null },
                                    outputFilePath = dto.outputFilePath,
                                    errorMessage = dto.errorMessage,
                                    createdAt = dto.createdAt ?: System.currentTimeMillis(),
                                    completedAt = dto.completedAt
                                )
                            } catch (e: Exception) {
                                null
                            }
                        }
                        _tasks.value = models.distinctBy { it.id }
                        DebugCenter.log(
                            LogModule.WorkManager,
                            LogLevel.INFO,
                            "Fila restaurada do disco: ${_tasks.value.size} tarefas encontradas."
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
                ExistingWorkPolicy.APPEND_OR_REPLACE,
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
        saveTasksToDiskThrottled()
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

    private var lastSaveDiskTime = 0L

    private fun saveTasksToDiskThrottled() {
        val now = System.currentTimeMillis()
        if (now - lastSaveDiskTime > 1500L) {
            lastSaveDiskTime = now
            saveTasksToDisk()
        }
    }

    fun removeTask(taskId: String) {
        _tasks.value = _tasks.value.filter { it.id != taskId }
        saveTasksToDisk()
    }

    fun cancelTask(context: Context, taskId: String) {
        init(context)
        try {
            WorkManager.getInstance(context).cancelAllWorkByTag("stabilize_task_$taskId")
        } catch (e: Exception) {
            DebugCenter.log(
                LogModule.WorkManager,
                LogLevel.WARN,
                "Falha ao cancelar WorkManager para tarefa $taskId: ${e.message}"
            )
        }

        val targetTask = _tasks.value.find { it.id == taskId }

        // Save original video to gallery if inputUri is present
        if (targetTask != null && targetTask.inputUri != Uri.EMPTY) {
            diskExecutor.execute {
                try {
                    val repo = com.stabilizepro.app.data.repository.VideoRepositoryImpl(context)
                    kotlinx.coroutines.runBlocking {
                        repo.saveVideoToGallery(targetTask.inputUri, targetTask.videoTitle)
                    }
                    DebugCenter.log(
                        LogModule.WorkManager,
                        LogLevel.INFO,
                        "Vídeo original salvo na galeria com sucesso após interrupção: ${targetTask.videoTitle}"
                    )
                } catch (e: Exception) {
                    DebugCenter.log(
                        LogModule.WorkManager,
                        LogLevel.WARN,
                        "Aviso ao salvar vídeo na galeria no cancelamento: ${e.message}"
                    )
                }
            }
        }

        // Remove from list so it doesn't stay as failure or block subsequent tasks
        removeTask(taskId)
    }

    fun getTaskById(taskId: String): StabilizationTask? {
        return _tasks.value.find { it.id == taskId }
    }

    fun clearCompleted() {
        _tasks.value = _tasks.value.filter { it.status == QueueStatus.PROCESSANDO || it.status == QueueStatus.AGUARDANDO }
        saveTasksToDisk()
    }
}
