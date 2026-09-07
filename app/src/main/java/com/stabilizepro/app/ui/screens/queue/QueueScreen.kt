package com.stabilizepro.app.ui.screens.queue

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Sync
import android.widget.Toast
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stabilizepro.app.ui.theme.BackgroundDark
import com.stabilizepro.app.ui.theme.ElectricBlue
import com.stabilizepro.app.ui.theme.ElectricBlueGlow
import com.stabilizepro.app.ui.theme.SurfaceBorder
import com.stabilizepro.app.ui.theme.SurfaceDark
import com.stabilizepro.app.ui.theme.SurfaceElevated
import com.stabilizepro.app.ui.theme.TextMuted
import com.stabilizepro.app.ui.theme.TextPrimary
import com.stabilizepro.app.ui.theme.TextSecondary
import com.stabilizepro.app.worker.QueueStatus
import com.stabilizepro.app.worker.StabilizationQueueManager
import com.stabilizepro.app.worker.StabilizationTask
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun QueueScreen(
    onOpenResult: (Uri) -> Unit = {}
) {
    val context = LocalContext.current
    val tasks by StabilizationQueueManager.tasks.collectAsStateWithLifecycle()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDark)
            .padding(horizontal = 20.dp, vertical = 20.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "PROCESSAMENTOS",
                        color = TextPrimary,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = "Fila em segundo plano (${tasks.size})",
                        color = ElectricBlueGlow,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                if (tasks.any { it.status == QueueStatus.CONCLUIDO }) {
                    IconButton(
                        onClick = { StabilizationQueueManager.clearCompleted() },
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(SurfaceElevated)
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteSweep,
                            contentDescription = "Limpar concluídos",
                            tint = TextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            if (tasks.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 60.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(SurfaceDark)
                                .border(1.dp, SurfaceBorder, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.HourglassEmpty,
                                contentDescription = null,
                                tint = TextMuted,
                                modifier = Modifier.size(30.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Nenhum processamento na fila",
                            color = TextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Grave um vídeo na aba Câmera ou importe na aba Estabilizador.",
                            color = TextMuted,
                            fontSize = 13.sp
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(tasks.distinctBy { it.id }, key = { it.id }) { task ->
                        TaskItemCard(
                            task = task,
                            onPlayResult = { uri ->
                                onOpenResult(uri)
                            },
                            onCancel = {
                                StabilizationQueueManager.cancelTask(context, task.id)
                                Toast.makeText(context, "Processamento interrompido.", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskItemCard(
    task: StabilizationTask,
    onPlayResult: (Uri) -> Unit,
    onCancel: () -> Unit = {}
) {
    val animatedProgress by animateFloatAsState(
        targetValue = ((task.progressPercent ?: 0).coerceIn(0, 100)) / 100f,
        animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing),
        label = "progress_anim"
    )

    val dateFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
    val timeFormatted = dateFormat.format(Date(task.createdAt ?: System.currentTimeMillis()))

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, SurfaceBorder, RoundedCornerShape(18.dp)),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header row with Title and Status Chip
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = task.videoTitle ?: "Vídeo",
                        color = TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                    Text(
                        text = "Iniciado às $timeFormatted • ${task.quality?.title ?: "Original"}",
                        color = TextMuted,
                        fontSize = 11.sp
                    )
                }

                StatusChip(status = task.status ?: QueueStatus.AGUARDANDO)
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Stage info and ETA
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = task.stageName ?: "Na fila",
                    color = when (task.status) {
                        QueueStatus.PROCESSANDO -> ElectricBlueGlow
                        QueueStatus.CONCLUIDO -> Color(0xFF00E676)
                        QueueStatus.FALHOU -> Color(0xFFFF5252)
                        QueueStatus.AGUARDANDO -> TextMuted
                        null -> TextMuted
                    },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )

                if (task.status == QueueStatus.PROCESSANDO) {
                    val eta = if (task.estimatedSecondsRemaining > 0) {
                        "~${task.estimatedSecondsRemaining}s restantes"
                    } else {
                        "Calculando..."
                    }
                    Text(
                        text = "${task.progressPercent}% ($eta)",
                        color = ElectricBlue,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Progress Bar
            if (task.status == QueueStatus.PROCESSANDO || task.status == QueueStatus.AGUARDANDO) {
                LinearProgressIndicator(
                    progress = { if (task.status == QueueStatus.AGUARDANDO) 0f else animatedProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = ElectricBlue,
                    trackColor = SurfaceElevated
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Cancel / Interrupt button
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color(0xFFFF5252)
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x66FF5252))
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Interromper",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Interromper Processamento",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp
                    )
                }
            }

            // Error message if failed
            if (task.status == QueueStatus.FALHOU && !task.errorMessage.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = task.errorMessage,
                    color = Color(0xFFFF5252),
                    fontSize = 11.sp
                )
            }

            // Completed button: Open video
            if (task.status == QueueStatus.CONCLUIDO && task.outputUri != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = { onPlayResult(task.outputUri) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ElectricBlue,
                        contentColor = Color.White
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Reproduzir Vídeo Estabilizado",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusChip(status: QueueStatus) {
    val (bgColor, textColor, icon) = when (status) {
        QueueStatus.AGUARDANDO -> Triple(
            Color(0x33FFA726),
            Color(0xFFFFA726),
            Icons.Default.HourglassEmpty
        )
        QueueStatus.PROCESSANDO -> Triple(
            Color(0x330066FF),
            ElectricBlueGlow,
            Icons.Default.Sync
        )
        QueueStatus.CONCLUIDO -> Triple(
            Color(0x3300E676),
            Color(0xFF00E676),
            Icons.Default.CheckCircle
        )
        QueueStatus.FALHOU -> Triple(
            Color(0x33FF5252),
            Color(0xFFFF5252),
            Icons.Default.ErrorOutline
        )
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = textColor,
                modifier = Modifier.size(12.dp)
            )
            Text(
                text = status.label,
                color = textColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
