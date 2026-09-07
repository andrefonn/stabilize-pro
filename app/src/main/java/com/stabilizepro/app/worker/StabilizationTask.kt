package com.stabilizepro.app.worker

import android.net.Uri
import com.stabilizepro.app.domain.model.StabilizationConfig
import com.stabilizepro.app.export.ExportQuality
import java.util.UUID

enum class QueueStatus(val label: String) {
    AGUARDANDO("Aguardando"),
    PROCESSANDO("Processando"),
    CONCLUIDO("Concluído"),
    FALHOU("Falhou")
}

data class StabilizationTask(
    val id: String = UUID.randomUUID().toString(),
    val inputUri: Uri,
    val videoTitle: String,
    val config: StabilizationConfig = StabilizationConfig(),
    val presetId: String? = null,
    val quality: ExportQuality = ExportQuality.ORIGINAL,
    val status: QueueStatus = QueueStatus.AGUARDANDO,
    val progressPercent: Int = 0,
    val stageName: String = "Na fila",
    val estimatedSecondsRemaining: Long = 0L,
    val outputUri: Uri? = null,
    val outputFilePath: String? = null,
    val errorMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null
)
