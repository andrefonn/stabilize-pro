package com.stabilizepro.app.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.gson.Gson
import com.stabilizepro.app.R
import com.stabilizepro.app.data.engine.VideoStabilizerEngine
import com.stabilizepro.app.domain.model.StabilizationConfig
import com.stabilizepro.app.export.ExportQuality
import com.stabilizepro.app.logs.DebugCenter
import com.stabilizepro.app.logs.LogLevel
import com.stabilizepro.app.logs.LogModule
import com.stabilizepro.app.presets.PresetManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class StabilizationWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "StabilizationWorker"
        private const val CHANNEL_ID = "stabilizepro_processing_channel"
        private const val NOTIFICATION_ID = 4040
    }

    private val notificationManager =
        appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val taskId = inputData.getString(StabilizationQueueManager.KEY_TASK_ID) ?: return@withContext Result.failure()
        val inputUriStr = inputData.getString(StabilizationQueueManager.KEY_INPUT_URI) ?: return@withContext Result.failure()
        val videoTitle = inputData.getString(StabilizationQueueManager.KEY_VIDEO_TITLE) ?: "video.mp4"
        val configJson = inputData.getString(StabilizationQueueManager.KEY_CONFIG_JSON)
        val presetId = inputData.getString(StabilizationQueueManager.KEY_PRESET_ID)
        val qualityName = inputData.getString(StabilizationQueueManager.KEY_QUALITY_NAME) ?: ExportQuality.ORIGINAL.name

        val config = try {
            if (!configJson.isNullOrBlank()) {
                Gson().fromJson(configJson, StabilizationConfig::class.java)
            } else {
                StabilizationConfig()
            }
        } catch (e: Exception) {
            StabilizationConfig()
        }

        val quality = try {
            ExportQuality.valueOf(qualityName)
        } catch (e: Exception) {
            ExportQuality.ORIGINAL
        }

        createNotificationChannel()

        // Start Foreground Service
        try {
            setForeground(createForegroundInfo(videoTitle, 0, "Iniciando análise...", 0L))
        } catch (e: Exception) {
            DebugCenter.log(
                module = LogModule.WorkManager,
                level = LogLevel.WARN,
                message = "Aviso ao iniciar foreground worker: ${e.message}",
                errorCode = "#402"
            )
        }

        DebugCenter.activePipeline = "Estabilização WorkManager: $videoTitle"
        val startTime = System.currentTimeMillis()

        try {
            val inputUri = Uri.parse(inputUriStr)
            val engine = VideoStabilizerEngine(appContext)

            val result = engine.stabilize(
                inputUri = inputUri,
                config = config,
                onProgress = { progress ->
                    val secRemaining = progress.estimatedSecondsRemaining
                    val stageStr = progress.stage.displayName
                    val percent = progress.progressPercent

                    StabilizationQueueManager.updateTaskProgress(
                        taskId = taskId,
                        progressPercent = percent,
                        stageName = stageStr,
                        secondsRemaining = secRemaining
                    )

                    // Update notification periodically
                    updateNotification(videoTitle, percent, stageStr, secRemaining)
                }
            )

            val durationMs = System.currentTimeMillis() - startTime
            DebugCenter.lastProcessingDurationMs = durationMs
            DebugCenter.activePipeline = "Ocioso"

            StabilizationQueueManager.markTaskCompleted(
                taskId = taskId,
                outputUri = result.stabilizedUri,
                outputFilePath = result.outputFilePath
            )

            showCompletionNotification(videoTitle, result.outputFilePath)

            DebugCenter.log(
                LogModule.WorkManager,
                LogLevel.INFO,
                "Processamento concluído com sucesso em ${durationMs / 1000}s: $videoTitle"
            )

            Result.success(
                workDataOf(
                    "output_uri" to result.stabilizedUri.toString(),
                    "output_path" to result.outputFilePath
                )
            )
        } catch (e: Exception) {
            DebugCenter.activePipeline = "Ocioso"
            val errorMsg = e.localizedMessage ?: "Erro desconhecido durante estabilização"

            StabilizationQueueManager.markTaskFailed(taskId, errorMsg)

            DebugCenter.logAndToastError(
                context = appContext,
                module = LogModule.WorkManager,
                errorCode = "#401",
                detailedMessage = "Falha no worker de estabilização: $errorMsg",
                throwable = e
            )

            showFailureNotification(videoTitle, errorMsg)
            Result.failure()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Estabilização em Segundo Plano",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Progresso da estabilização automática e manual de vídeos"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createForegroundInfo(
        title: String,
        progress: Int,
        stage: String,
        secondsRemaining: Long
    ): ForegroundInfo {
        val etaText = if (secondsRemaining > 0) " (~${secondsRemaining}s restantes)" else ""
        val content = "$stage • $progress%$etaText"

        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setContentTitle("Estabilizando: $title")
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setProgress(100, progress, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(
        title: String,
        progress: Int,
        stage: String,
        secondsRemaining: Long
    ) {
        try {
            val etaText = if (secondsRemaining > 0) " (~${secondsRemaining}s restantes)" else ""
            val content = "$stage • $progress%$etaText"

            val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
                .setContentTitle("Estabilizando: $title")
                .setContentText(content)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setProgress(100, progress, false)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()

            notificationManager.notify(NOTIFICATION_ID, notification)
        } catch (ignored: Exception) {}
    }

    private fun showCompletionNotification(title: String, outputPath: String) {
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setContentTitle("Estabilização Concluída!")
            .setContentText("$title foi estabilizado com sucesso.")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }

    private fun showFailureNotification(title: String, error: String) {
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setContentTitle("Falha na Estabilização")
            .setContentText("Não foi possível estabilizar $title: $error")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }
}
