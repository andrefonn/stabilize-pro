package com.stabilizepro.app.logs

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import timber.log.Timber
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.Executors

enum class LogModule {
    CameraX,
    OpenCV,
    MediaCodec,
    Export,
    WorkManager,
    Memory,
    System,
    General
}

enum class LogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR
}

data class LogEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val module: LogModule,
    val level: LogLevel,
    val message: String,
    val errorCode: String? = null,
    val stackTrace: String? = null
) {
    fun format(): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        val formattedDate = dateFormat.format(Date(timestamp))
        val errorPart = if (errorCode != null) " [$errorCode]" else ""
        val sb = StringBuilder()
        sb.appendLine("[DATA] $formattedDate")
        sb.appendLine("[MÓDULO] ${module.name}$errorPart")
        sb.appendLine("[NÍVEL] ${level.name}")
        sb.appendLine("[MENSAGEM] $message")
        if (!stackTrace.isNullOrBlank()) {
            sb.appendLine("[STACK TRACE]")
            sb.appendLine(stackTrace.trimEnd())
        }
        return sb.toString()
    }
}

object DebugCenter {
    private const val TAG = "DebugCenter"
    private const val MAX_IN_MEMORY_LOGS = 250
    private val memoryLogBuffer = ConcurrentLinkedDeque<LogEntry>()
    private val fileExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler: Handler? by lazy {
        try {
            Handler(Looper.getMainLooper())
        } catch (e: Throwable) {
            null
        }
    }

    @Volatile
    private var appContext: Context? = null

    @Volatile
    var isOpenCvInitialized: Boolean = false

    @Volatile
    var activePipeline: String = "Ocioso"

    @Volatile
    var lastProcessingDurationMs: Long = 0L

    @Volatile
    var currentFps: Float = 0.0f

    private var previousUncaughtExceptionHandler: Thread.UncaughtExceptionHandler? = null

    fun init(context: Context) {
        appContext = context.applicationContext

        // Plant Timber custom tree
        Timber.plant(DebugCenterTimberTree())

        // Ensure logs directory exists
        val logDir = getLogsDirectory(context)
        if (!logDir.exists()) {
            logDir.mkdirs()
        }

        // Register uncaught exception handler
        previousUncaughtExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val stack = getStackTraceString(throwable)
            log(
                module = LogModule.System,
                level = LogLevel.ERROR,
                message = "Crash não tratado na thread: ${thread.name} - ${throwable.localizedMessage}",
                errorCode = "#999",
                throwable = throwable
            )
            previousUncaughtExceptionHandler?.uncaughtException(thread, throwable)
        }

        // Register memory trim callbacks
        context.registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                val levelDesc = when (level) {
                    ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> "RUNNING_CRITICAL"
                    ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> "RUNNING_LOW"
                    ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> "RUNNING_MODERATE"
                    ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> "BACKGROUND"
                    ComponentCallbacks2.TRIM_MEMORY_MODERATE -> "MODERATE"
                    ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> "COMPLETE"
                    else -> "LEVEL_$level"
                }
                log(
                    module = LogModule.Memory,
                    level = LogLevel.WARN,
                    message = "Aviso de memória baixa do sistema: $levelDesc (RAM usada: ${getUsedRamMb()} MB)",
                    errorCode = "#501"
                )
            }

            override fun onConfigurationChanged(newConfig: Configuration) {}

            override fun onLowMemory() {
                log(
                    module = LogModule.Memory,
                    level = LogLevel.ERROR,
                    message = "CRÍTICO: onLowMemory disparado pelo Android! Liberando buffers.",
                    errorCode = "#500"
                )
            }
        })

        log(LogModule.System, LogLevel.INFO, "DebugCenter inicializado com sucesso.")
    }

    fun getLogsDirectory(context: Context): File {
        val baseExternal = context.getExternalFilesDir(null)
        val logsDir = if (baseExternal != null) {
            // Android/data/com.stabilizepro.app/logs/
            File(baseExternal.parentFile, "logs")
        } else {
            File(context.filesDir, "logs")
        }
        if (!logsDir.exists()) {
            logsDir.mkdirs()
        }
        return logsDir
    }

    fun getCurrentLogFile(): File? {
        val ctx = appContext ?: return null
        val dir = getLogsDirectory(ctx)
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val fileName = "log_${dateFormat.format(Date())}.txt"
        return File(dir, fileName)
    }

    fun log(
        module: LogModule,
        level: LogLevel,
        message: String,
        errorCode: String? = null,
        throwable: Throwable? = null
    ) {
        val stackTrace = throwable?.let { getStackTraceString(it) }
        val entry = LogEntry(
            module = module,
            level = level,
            message = message,
            errorCode = errorCode,
            stackTrace = stackTrace
        )

        // Store in memory ring-buffer
        memoryLogBuffer.addFirst(entry)
        while (memoryLogBuffer.size > MAX_IN_MEMORY_LOGS) {
            memoryLogBuffer.pollLast()
        }

        // Print to logcat
        val formattedMsg = if (errorCode != null) "[$errorCode] $message" else message
        when (level) {
            LogLevel.DEBUG -> Log.d(module.name, formattedMsg, throwable)
            LogLevel.INFO -> Log.i(module.name, formattedMsg, throwable)
            LogLevel.WARN -> Log.w(module.name, formattedMsg, throwable)
            LogLevel.ERROR -> Log.e(module.name, formattedMsg, throwable)
        }

        // Asynchronously write to file
        fileExecutor.execute {
            try {
                val file = getCurrentLogFile() ?: return@execute
                FileWriter(file, true).use { writer ->
                    writer.write(entry.format())
                    writer.write("\n")
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Falha ao gravar log no arquivo: ${e.message}")
            }
        }
    }

    /**
     * Shows a short Toast with the standard error format and logs the incident.
     * Example: "Erro CameraX #204", "Erro Exportação #117", "Erro Estabilização #032"
     */
    fun logAndToastError(
        context: Context,
        module: LogModule,
        errorCode: String,
        detailedMessage: String,
        throwable: Throwable? = null
    ) {
        log(
            module = module,
            level = LogLevel.ERROR,
            message = detailedMessage,
            errorCode = errorCode,
            throwable = throwable
        )

        val moduleLabel = when (module) {
            LogModule.CameraX -> "CameraX"
            LogModule.Export -> "Exportação"
            LogModule.OpenCV, LogModule.General -> "Estabilização"
            LogModule.MediaCodec -> "MediaCodec"
            LogModule.WorkManager -> "Processamento"
            LogModule.Memory -> "Memória"
            LogModule.System -> "Sistema"
        }

        val toastText = "Erro $moduleLabel $errorCode"

        mainHandler?.post {
            try {
                Toast.makeText(context.applicationContext, toastText, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.w(TAG, "Não foi possível exibir Toast: ${e.message}")
            }
        }
    }

    fun getRecentLogs(): List<LogEntry> {
        return memoryLogBuffer.toList()
    }

    fun clearRecentLogs() {
        memoryLogBuffer.clear()
    }

    fun getUsedRamMb(): Long {
        val runtime = Runtime.getRuntime()
        val usedBytes = runtime.totalMemory() - runtime.freeMemory()
        return (usedBytes / (1024 * 1024))
    }

    fun getTotalAllocatedRamMb(): Long {
        return (Runtime.getRuntime().totalMemory() / (1024 * 1024))
    }

    fun getMaxRamMb(): Long {
        return (Runtime.getRuntime().maxMemory() / (1024 * 1024))
    }

    fun getCpuEstimateString(): String {
        val cores = Runtime.getRuntime().availableProcessors()
        return "$cores núcleos ativos"
    }

    fun createExportLogsIntent(context: Context): Intent? {
        val logFile = getCurrentLogFile() ?: return null
        if (!logFile.exists() || logFile.length() == 0L) {
            log(LogModule.System, LogLevel.WARN, "Nenhum arquivo de log para exportar.")
            return null
        }

        return try {
            val authority = "${context.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(context, authority, logFile)
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "STABILIZE PRO - Logs de Diagnóstico (${logFile.name})")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (e: Exception) {
            log(LogModule.System, LogLevel.ERROR, "Erro ao criar intent de exportação de log: ${e.message}", "#901", e)
            null
        }
    }

    private fun getStackTraceString(throwable: Throwable): String {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        throwable.printStackTrace(pw)
        return sw.toString()
    }

    private class DebugCenterTimberTree : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            val level = when (priority) {
                Log.DEBUG, Log.VERBOSE -> LogLevel.DEBUG
                Log.INFO -> LogLevel.INFO
                Log.WARN -> LogLevel.WARN
                Log.ERROR, Log.ASSERT -> LogLevel.ERROR
                else -> LogLevel.DEBUG
            }

            val tagStr = tag.orEmpty()
            val module = when {
                tagStr.contains("Camera", ignoreCase = true) -> LogModule.CameraX
                tagStr.contains("OpenCV", ignoreCase = true) -> LogModule.OpenCV
                tagStr.contains("Codec", ignoreCase = true) || tagStr.contains("Encoder", ignoreCase = true) -> LogModule.MediaCodec
                tagStr.contains("Export", ignoreCase = true) || tagStr.contains("Muxer", ignoreCase = true) -> LogModule.Export
                tagStr.contains("Worker", ignoreCase = true) || tagStr.contains("Work", ignoreCase = true) -> LogModule.WorkManager
                tagStr.contains("Memory", ignoreCase = true) -> LogModule.Memory
                else -> LogModule.General
            }

            // Only forward WARN and ERROR, or explicit diagnostic INFOs
            if (priority >= Log.WARN || (priority == Log.INFO && tag?.startsWith("Debug") == true)) {
                DebugCenter.log(
                    module = module,
                    level = level,
                    message = if (tag != null) "[$tag] $message" else message,
                    throwable = t
                )
            }
        }
    }
}
