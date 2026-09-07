package com.stabilizepro.app.ui.screens.home

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ChangeCircle
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import com.stabilizepro.app.domain.model.StabilizationConfig
import com.stabilizepro.app.domain.model.StabilizationIntensity
import com.stabilizepro.app.domain.model.VideoInfo
import com.stabilizepro.app.export.ExportQuality
import com.stabilizepro.app.logs.DebugCenterDialog
import com.stabilizepro.app.presets.PresetManager
import com.stabilizepro.app.presets.PresetModel
import com.stabilizepro.app.ui.components.AdvancedSettingsCard
import com.stabilizepro.app.ui.components.IntensitySegmentedControl
import com.stabilizepro.app.ui.theme.BackgroundDark
import com.stabilizepro.app.ui.theme.ElectricBlue
import com.stabilizepro.app.ui.theme.ElectricBlueDark
import com.stabilizepro.app.ui.theme.ElectricBlueGlow
import com.stabilizepro.app.ui.theme.SurfaceBorder
import com.stabilizepro.app.ui.theme.SurfaceDark
import com.stabilizepro.app.ui.theme.SurfaceElevated
import com.stabilizepro.app.ui.theme.TextMuted
import com.stabilizepro.app.ui.theme.TextPrimary
import com.stabilizepro.app.ui.theme.TextSecondary
import com.stabilizepro.app.worker.StabilizationQueueManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    selectedVideo: VideoInfo?,
    config: StabilizationConfig,
    onVideoSelected: (Uri) -> Unit,
    onIntensitySelected: (StabilizationIntensity) -> Unit,
    onConfigChange: (StabilizationConfig) -> Unit,
    onStartStabilization: () -> Unit,
    onNavigateToQueue: () -> Unit = {}
) {
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    val presetManager = remember { PresetManager(context) }
    val presets by presetManager.presets.collectAsStateWithLifecycle()
    var selectedPreset by remember { mutableStateOf<PresetModel?>(null) }

    var showDebugCenter by remember { mutableStateOf(false) }
    var holdJob by remember { mutableStateOf<Job?>(null) }
    var isHoldingLogo by remember { mutableStateOf(false) }

    // Native Photo Picker launcher
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onVideoSelected(uri)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDark)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            // Header with 5-second long-press detection on logo
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                isHoldingLogo = true
                                holdJob = coroutineScope.launch {
                                    delay(5000)
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    showDebugCenter = true
                                    isHoldingLogo = false
                                }
                                tryAwaitRelease()
                                holdJob?.cancel()
                                isHoldingLogo = false
                            }
                        )
                    }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "STABILIZE PRO",
                                color = if (isHoldingLogo) ElectricBlue else TextPrimary,
                                fontSize = 26.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 1.sp
                            )
                            if (isHoldingLogo) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("(Segure por 5s...)", color = ElectricBlueGlow, fontSize = 11.sp)
                            }
                        }
                        Text(
                            text = "Estabilização profissional offline & Shaders",
                            color = ElectricBlueGlow,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    // Direct debug icon shortcut
                    IconButton(
                        onClick = { showDebugCenter = true },
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(SurfaceDark)
                    ) {
                        Icon(
                            imageVector = Icons.Default.BugReport,
                            contentDescription = "Debug Center",
                            tint = TextMuted,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // Central Video Card / Thumbnail Preview & Metadata (ETAPA 6)
            AnimatedContent(
                targetState = selectedVideo,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "video_card_transition"
            ) { video ->
                if (video == null) {
                    // Empty State Card
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .background(SurfaceDark)
                            .border(1.5.dp, SurfaceBorder, RoundedCornerShape(24.dp))
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                photoPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                                )
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(46.dp)
                                        .clip(CircleShape)
                                        .background(SurfaceElevated),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Smartphone,
                                        contentDescription = null,
                                        tint = ElectricBlue,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .size(46.dp)
                                        .clip(CircleShape)
                                        .background(SurfaceElevated),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Videocam,
                                        contentDescription = null,
                                        tint = ElectricBlueGlow,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            Text(
                                text = "Importar vídeo da Galeria",
                                color = TextPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Toque para abrir o seletor com miniaturas",
                                color = TextMuted,
                                fontSize = 12.sp
                            )
                        }
                    }
                } else {
                    // Selected Video Card with Full 6 Metadata Points (Thumbnail, Duração, FPS, Resolução, Codec, Tamanho)
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, SurfaceBorder, RoundedCornerShape(22.dp)),
                        shape = RoundedCornerShape(22.dp),
                        colors = CardDefaults.cardColors(containerColor = SurfaceDark)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            // 1. Thumbnail
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(170.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(SurfaceElevated)
                            ) {
                                AsyncImage(
                                    model = ImageRequest.Builder(context)
                                        .data(video.uri)
                                        .videoFrameMillis(1000)
                                        .crossfade(true)
                                        .build(),
                                    contentDescription = "Thumbnail do vídeo",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )

                                Box(
                                    modifier = Modifier
                                        .align(Alignment.Center)
                                        .size(46.dp)
                                        .clip(CircleShape)
                                        .background(BackgroundDark.copy(alpha = 0.65f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PlayCircle,
                                        contentDescription = null,
                                        tint = TextPrimary,
                                        modifier = Modifier.size(30.dp)
                                    )
                                }

                                // Quick button to swap video
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(8.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color(0xCC000000))
                                        .clickable {
                                            photoPickerLauncher.launch(
                                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                                            )
                                        }
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.ChangeCircle,
                                            contentDescription = null,
                                            tint = ElectricBlue,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Text("Trocar", color = TextPrimary, fontSize = 11.sp)
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            Text(
                                text = video.name,
                                color = TextPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            // 2-6: Detailed Metadata Grid (Duração, FPS, Resolução, Codec, Tamanho)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                MetadataPill(
                                    label = "Duração",
                                    value = video.durationFormatted,
                                    modifier = Modifier.weight(1f)
                                )
                                MetadataPill(
                                    label = "FPS",
                                    value = "${video.fps.toInt()} FPS",
                                    modifier = Modifier.weight(1f)
                                )
                                MetadataPill(
                                    label = "Resolução",
                                    value = "${video.width}x${video.height}",
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                MetadataPill(
                                    label = "Codec",
                                    value = video.codec,
                                    modifier = Modifier.weight(1f)
                                )
                                MetadataPill(
                                    label = "Tamanho",
                                    value = video.sizeFormatted,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }

            // Presets Selector Section (ETAPA 4)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "APLICAR PRESET",
                        color = TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                    if (selectedPreset != null) {
                        Text(
                            text = "Limpar",
                            color = ElectricBlue,
                            fontSize = 12.sp,
                            modifier = Modifier.clickable {
                                selectedPreset = null
                                onConfigChange(config.copy(preset = null))
                            }
                        )
                    }
                }

                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(presets) { preset ->
                        val isChosen = selectedPreset?.id == preset.id
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (isChosen) ElectricBlue.copy(alpha = 0.35f) else SurfaceDark)
                                .border(
                                    1.dp,
                                    if (isChosen) ElectricBlue else SurfaceBorder,
                                    RoundedCornerShape(14.dp)
                                )
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    selectedPreset = preset
                                    onConfigChange(config.copy(preset = preset.params))
                                    Toast.makeText(context, "Preset '${preset.name}' selecionado", Toast.LENGTH_SHORT).show()
                                }
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AutoAwesome,
                                    contentDescription = null,
                                    tint = if (isChosen) ElectricBlueGlow else TextMuted,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = preset.name,
                                    color = if (isChosen) Color.White else TextSecondary,
                                    fontSize = 12.sp,
                                    fontWeight = if (isChosen) FontWeight.Bold else FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }

            // Intensity Selector
            IntensitySegmentedControl(
                selectedIntensity = config.intensity,
                onIntensitySelected = onIntensitySelected
            )

            // Advanced Settings Expandable Card
            AdvancedSettingsCard(
                config = config,
                onConfigChange = onConfigChange
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Action Buttons: Estabilizar & Fila em Segundo Plano
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Primary CTA: Estabilizar Vídeo (tela de progresso direta)
                Button(
                    onClick = {
                        if (selectedVideo != null) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onStartStabilization()
                        }
                    },
                    enabled = selectedVideo != null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ElectricBlue,
                        contentColor = TextPrimary,
                        disabledContainerColor = SurfaceElevated,
                        disabledContentColor = TextMuted
                    )
                ) {
                    val label = if (selectedPreset != null) {
                        "ESTABILIZAR + PRESET '${selectedPreset?.name?.uppercase()}'"
                    } else {
                        "ESTABILIZAR VÍDEO"
                    }
                    Text(
                        text = label,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                }

                // Secondary CTA: Enviar para Fila do WorkManager (em segundo plano)
                OutlinedButton(
                    onClick = {
                        if (selectedVideo != null) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            StabilizationQueueManager.enqueueTask(
                                context = context,
                                inputUri = selectedVideo.uri,
                                videoTitle = selectedVideo.name,
                                config = config.copy(preset = selectedPreset?.params),
                                presetId = selectedPreset?.id,
                                quality = ExportQuality.ORIGINAL
                            )
                            Toast.makeText(
                                context,
                                "Vídeo enviado para processamento em segundo plano!",
                                Toast.LENGTH_LONG
                            ).show()
                            onNavigateToQueue()
                        }
                    },
                    enabled = selectedVideo != null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = ElectricBlueGlow)
                ) {
                    Icon(
                        imageVector = Icons.Default.PlaylistAdd,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Processar na Fila (Segundo Plano)",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        // Hidden Debug Center Modal Dialog (ETAPA 2)
        if (showDebugCenter) {
            DebugCenterDialog(
                onDismissRequest = { showDebugCenter = false }
            )
        }
    }
}

@Composable
private fun MetadataPill(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(SurfaceElevated)
            .border(0.5.dp, SurfaceBorder, RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Column {
            Text(
                text = label,
                color = TextMuted,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = value,
                color = TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
    }
}
