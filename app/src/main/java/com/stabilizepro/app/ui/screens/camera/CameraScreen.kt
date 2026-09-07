package com.stabilizepro.app.ui.screens.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Preview
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
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
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.HdrOn
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stabilizepro.app.camera.CameraManager
import com.stabilizepro.app.camera.CameraSettings
import com.stabilizepro.app.camera.CaptureMode
import com.stabilizepro.app.camera.LensType
import com.stabilizepro.app.camera.VideoQualityOption
import com.stabilizepro.app.domain.model.StabilizationConfig
import com.stabilizepro.app.domain.model.StabilizationIntensity
import com.stabilizepro.app.export.ExportQuality
import com.stabilizepro.app.presets.ColorGradingParams
import com.stabilizepro.app.presets.PresetManager
import com.stabilizepro.app.presets.PresetModel
import com.stabilizepro.app.renderer.CameraPreviewGl
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
import java.io.File
import java.util.Locale

@Composable
fun CameraScreen(
    onNavigateToQueue: () -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptic = LocalHapticFeedback.current

    val cameraManager = remember { CameraManager(context) }
    val presetManager = remember { PresetManager(context) }

    val cameraSettings by cameraManager.settings.collectAsStateWithLifecycle()
    val availableLenses by cameraManager.availableLenses.collectAsStateWithLifecycle()
    val presets by presetManager.presets.collectAsStateWithLifecycle()

    var colorGradingParams by remember { mutableStateOf(ColorGradingParams()) }
    var selectedPresetId by remember { mutableStateOf<String?>("builtin_natural") }

    var showShaderDrawer by remember { mutableStateOf(false) }
    var showCamera2Drawer by remember { mutableStateOf(false) }
    var showSavePresetDialog by remember { mutableStateOf(false) }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasCameraPermission = permissions[Manifest.permission.CAMERA] == true
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            )
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            cameraManager.release()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { delta ->
                    // Gesture swipe between Photo and Video
                    if (delta < -35 && cameraSettings.captureMode == CaptureMode.PHOTO) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        cameraManager.updateSettings(cameraSettings.copy(captureMode = CaptureMode.VIDEO))
                    } else if (delta > 35 && cameraSettings.captureMode == CaptureMode.VIDEO) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        cameraManager.updateSettings(cameraSettings.copy(captureMode = CaptureMode.PHOTO))
                    }
                }
            )
    ) {
        if (hasCameraPermission) {
            // Real-time OpenGL ES Surface
            CameraPreviewGl(
                modifier = Modifier.fillMaxSize(),
                colorGradingParams = colorGradingParams,
                onSurfaceProviderReady = { surfaceProvider ->
                    cameraManager.initialize(lifecycleOwner, surfaceProvider)
                }
            )
        } else {
            // Permission placeholder
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Acesso à câmera é obrigatório", color = TextPrimary)
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = {
                            permissionLauncher.launch(
                                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
                            )
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue)
                    ) {
                        Text("Conceder Permissão")
                    }
                }
            }
        }

        // Top HUD Overlay
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 20.dp)
                .align(Alignment.TopCenter),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Lens Switcher
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0x88000000))
                    .border(1.dp, SurfaceBorder, RoundedCornerShape(20.dp))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                availableLenses.forEach { lens ->
                    val isSelected = cameraSettings.selectedLens == lens
                    val label = when (lens) {
                        LensType.ULTRA_WIDE -> "0.5x"
                        LensType.WIDE -> "1x"
                        LensType.TELEPHOTO -> "2x"
                        LensType.FRONT -> "Selfie"
                    }
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(if (isSelected) ElectricBlue else Color.Transparent)
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                cameraManager.updateSettings(cameraSettings.copy(selectedLens = lens))
                            }
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = label,
                            color = if (isSelected) Color.White else TextSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Top action buttons: Shaders (Color Grading) & Camera2 Pro
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        showCamera2Drawer = !showCamera2Drawer
                        if (showCamera2Drawer) showShaderDrawer = false
                    },
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(if (showCamera2Drawer) ElectricBlue else Color(0x88000000))
                ) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = "Controles Pro Camera2",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }

                IconButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        showShaderDrawer = !showShaderDrawer
                        if (showShaderDrawer) showCamera2Drawer = false
                    },
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(if (showShaderDrawer) ElectricBlue else Color(0x88000000))
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Shaders em Tempo Real",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // Recording duration indicator badge (when recording)
        if (cameraSettings.isRecording) {
            val min = cameraSettings.recordingDurationSec / 60
            val sec = cameraSettings.recordingDurationSec % 60
            val timeStr = String.format(Locale.US, "%02d:%02d", min, sec)

            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 70.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xCCFF1744))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                )
                Text(
                    text = timeStr,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Bottom Controls Overlay
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color(0x99000000))
                .padding(bottom = 24.dp)
        ) {
            // Presets Quick Carousel Strip
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items(presets) { preset ->
                    val isSelected = selectedPresetId == preset.id
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (isSelected) ElectricBlue.copy(alpha = 0.35f) else Color(0x55222222))
                            .border(
                                1.dp,
                                if (isSelected) ElectricBlue else SurfaceBorder,
                                RoundedCornerShape(16.dp)
                            )
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                selectedPresetId = preset.id
                                colorGradingParams = preset.params
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = preset.name,
                            color = if (isSelected) ElectricBlueGlow else TextSecondary,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }

            // Mode Selector: FOTO / VÍDEO
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "FOTO",
                    color = if (cameraSettings.captureMode == CaptureMode.PHOTO) ElectricBlueGlow else TextMuted,
                    fontSize = 13.sp,
                    fontWeight = if (cameraSettings.captureMode == CaptureMode.PHOTO) FontWeight.ExtraBold else FontWeight.Medium,
                    modifier = Modifier
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            cameraManager.updateSettings(cameraSettings.copy(captureMode = CaptureMode.PHOTO))
                        }
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                )

                Text(
                    text = "VÍDEO",
                    color = if (cameraSettings.captureMode == CaptureMode.VIDEO) ElectricBlueGlow else TextMuted,
                    fontSize = 13.sp,
                    fontWeight = if (cameraSettings.captureMode == CaptureMode.VIDEO) FontWeight.ExtraBold else FontWeight.Medium,
                    modifier = Modifier
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            cameraManager.updateSettings(cameraSettings.copy(captureMode = CaptureMode.VIDEO))
                        }
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Main Trigger Button Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Resolution Selector
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0x66222222))
                        .clickable {
                            val nextQuality = when (cameraSettings.quality) {
                                VideoQualityOption.AUTO_MAX -> VideoQualityOption.UHD_4K
                                VideoQualityOption.UHD_4K -> VideoQualityOption.QHD_2K
                                VideoQualityOption.QHD_2K -> VideoQualityOption.FHD_1080P
                                VideoQualityOption.FHD_1080P -> VideoQualityOption.HD_720P
                                VideoQualityOption.HD_720P -> VideoQualityOption.AUTO_MAX
                            }
                            cameraManager.updateSettings(cameraSettings.copy(quality = nextQuality))
                            Toast.makeText(context, nextQuality.displayName, Toast.LENGTH_SHORT).show()
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    val label = when (cameraSettings.quality) {
                        VideoQualityOption.AUTO_MAX -> "AUTO"
                        VideoQualityOption.UHD_4K -> "4K"
                        VideoQualityOption.QHD_2K -> "2K"
                        VideoQualityOption.FHD_1080P -> "1080p"
                        VideoQualityOption.HD_720P -> "720p"
                    }
                    Text(
                        text = label,
                        color = ElectricBlue,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Shutter / Record Button
                if (cameraSettings.captureMode == CaptureMode.PHOTO) {
                    // PHOTO CAPTURE BUTTON
                    Box(
                        modifier = Modifier
                            .size(76.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                val photoFile = File(
                                    context.cacheDir,
                                    "photo_${System.currentTimeMillis()}.jpg"
                                )
                                cameraManager.takePhoto(
                                    outputFile = photoFile,
                                    onPhotoSaved = { uri ->
                                        Toast.makeText(context, "Foto capturada com sucesso!", Toast.LENGTH_SHORT).show()
                                    },
                                    onError = { ex ->
                                        Toast.makeText(context, "Erro ao capturar foto: ${ex.message}", Toast.LENGTH_SHORT).show()
                                    }
                                )
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .border(2.dp, Color.Black, CircleShape)
                        )
                    }
                } else {
                    // VIDEO RECORD BUTTON
                    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                    val pulseScale by infiniteTransition.animateFloat(
                        initialValue = 1.0f,
                        targetValue = if (cameraSettings.isRecording) 1.15f else 1.0f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(600),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "recording_pulse"
                    )

                    Box(
                        modifier = Modifier
                            .size(76.dp)
                            .scale(pulseScale)
                            .clip(CircleShape)
                            .border(4.dp, if (cameraSettings.isRecording) Color(0xFFFF1744) else Color.White, CircleShape)
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                if (cameraSettings.isRecording) {
                                    cameraManager.stopRecording()
                                } else {
                                    val videoFile = File(
                                        context.cacheDir,
                                        "rec_${System.currentTimeMillis()}.mp4"
                                    )
                                    cameraManager.startRecording(
                                        outputFile = videoFile,
                                        onVideoSaved = { uri ->
                                            // AUTOMATICALLY ENQUEUE TO WORKMANAGER QUEUE!
                                            StabilizationQueueManager.enqueueTask(
                                                context = context,
                                                inputUri = uri,
                                                videoTitle = videoFile.name,
                                                config = StabilizationConfig(
                                                    intensity = StabilizationIntensity.MEDIUM,
                                                    preset = colorGradingParams
                                                ),
                                                presetId = selectedPresetId,
                                                quality = ExportQuality.ORIGINAL
                                            )

                                            Toast.makeText(
                                                context,
                                                "Gravação salva! Estabilização iniciada na fila.",
                                                Toast.LENGTH_LONG
                                            ).show()
                                        },
                                        onError = { ex ->
                                            Toast.makeText(
                                                context,
                                                "Erro ao gravar vídeo: ${ex.message}",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    )
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(if (cameraSettings.isRecording) 28.dp else 58.dp)
                                .clip(if (cameraSettings.isRecording) RoundedCornerShape(6.dp) else CircleShape)
                                .background(if (cameraSettings.isRecording) Color(0xFFFF1744) else Color(0xFFFF5252))
                        )
                    }
                }

                // Camera Switch (Front <-> Back)
                IconButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        val nextLens = if (cameraSettings.selectedLens == LensType.FRONT) LensType.WIDE else LensType.FRONT
                        cameraManager.updateSettings(cameraSettings.copy(selectedLens = nextLens))
                    },
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(Color(0x66222222))
                ) {
                    Icon(
                        imageVector = Icons.Default.Cameraswitch,
                        contentDescription = "Trocar câmera",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }

        // Real-time OpenGL Shaders Drawer Panel
        AnimatedVisibility(
            visible = showShaderDrawer,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(420.dp),
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                color = BackgroundDark,
                border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceBorder)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "SHADERS EM TEMPO REAL",
                            color = TextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            IconButton(onClick = { colorGradingParams = ColorGradingParams() }) {
                                Icon(
                                    imageVector = Icons.Default.RestartAlt,
                                    contentDescription = "Resetar",
                                    tint = TextSecondary
                                )
                            }
                            IconButton(onClick = { showSavePresetDialog = true }) {
                                Icon(
                                    imageVector = Icons.Default.Save,
                                    contentDescription = "Salvar Preset",
                                    tint = ElectricBlue
                                )
                            }
                        }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        ShaderSlider("Exposição", colorGradingParams.exposure, -2.0f..2.0f) {
                            colorGradingParams = colorGradingParams.copy(exposure = it)
                        }
                        ShaderSlider("Contraste", colorGradingParams.contrast, 0.5f..1.5f) {
                            colorGradingParams = colorGradingParams.copy(contrast = it)
                        }
                        ShaderSlider("Sombras", colorGradingParams.shadows, -1.0f..1.0f) {
                            colorGradingParams = colorGradingParams.copy(shadows = it)
                        }
                        ShaderSlider("Altas Luzes", colorGradingParams.highlights, -1.0f..1.0f) {
                            colorGradingParams = colorGradingParams.copy(highlights = it)
                        }
                        ShaderSlider("Brilho", colorGradingParams.brightness, -1.0f..1.0f) {
                            colorGradingParams = colorGradingParams.copy(brightness = it)
                        }
                        ShaderSlider("Ponto Preto", colorGradingParams.blackPoint, -0.5f..0.5f) {
                            colorGradingParams = colorGradingParams.copy(blackPoint = it)
                        }
                        ShaderSlider("Saturação", colorGradingParams.saturation, 0.0f..2.0f) {
                            colorGradingParams = colorGradingParams.copy(saturation = it)
                        }
                        ShaderSlider("Vivacidade", colorGradingParams.vibrance, -1.0f..1.0f) {
                            colorGradingParams = colorGradingParams.copy(vibrance = it)
                        }
                        ShaderSlider("Temperatura", colorGradingParams.temperature, -1.0f..1.0f) {
                            colorGradingParams = colorGradingParams.copy(temperature = it)
                        }
                        ShaderSlider("Matiz", colorGradingParams.tint, -1.0f..1.0f) {
                            colorGradingParams = colorGradingParams.copy(tint = it)
                        }
                        ShaderSlider("Nitidez", colorGradingParams.sharpness, 0.0f..2.0f) {
                            colorGradingParams = colorGradingParams.copy(sharpness = it)
                        }
                        ShaderSlider("Definição", colorGradingParams.definition, 0.0f..2.0f) {
                            colorGradingParams = colorGradingParams.copy(definition = it)
                        }
                        ShaderSlider("Redução de Ruído", colorGradingParams.noiseReduction, 0.0f..1.0f) {
                            colorGradingParams = colorGradingParams.copy(noiseReduction = it)
                        }
                        ShaderSlider("Vinheta", colorGradingParams.vignette, 0.0f..1.0f) {
                            colorGradingParams = colorGradingParams.copy(vignette = it)
                        }
                    }
                }
            }
        }

        // Camera2 Pro Controls Drawer Panel
        AnimatedVisibility(
            visible = showCamera2Drawer,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(340.dp),
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                color = BackgroundDark,
                border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceBorder)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = "CONTROLES NATIVOS CAMERA2",
                        color = TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )

                    // ISO Slider
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("ISO: ${if (cameraSettings.iso == 0) "Auto" else cameraSettings.iso}", color = TextSecondary, fontSize = 12.sp)
                        Button(
                            onClick = {
                                val nextIso = when (cameraSettings.iso) {
                                    0 -> 100
                                    100 -> 200
                                    200 -> 400
                                    400 -> 800
                                    800 -> 1600
                                    1600 -> 3200
                                    else -> 0
                                }
                                cameraManager.updateSettings(cameraSettings.copy(iso = nextIso))
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = SurfaceElevated)
                        ) {
                            Text(if (cameraSettings.iso == 0) "Auto" else "ISO ${cameraSettings.iso}", color = ElectricBlue)
                        }
                    }

                    // EV Compensation Slider
                    Column {
                        Text("Compensação EV: ${cameraSettings.evCompensation}", color = TextSecondary, fontSize = 12.sp)
                        Slider(
                            value = cameraSettings.evCompensation.toFloat(),
                            onValueChange = {
                                cameraManager.updateSettings(cameraSettings.copy(evCompensation = it.toInt()))
                            },
                            valueRange = -4f..4f,
                            steps = 7,
                            colors = SliderDefaults.colors(thumbColor = ElectricBlue, activeTrackColor = ElectricBlue)
                        )
                    }

                    // Manual Focus vs Continuous Focus
                    Column {
                        val isManual = cameraSettings.focusDistance >= 0f
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Foco: ${if (isManual) String.format(Locale.US, "%.1f dioptrias", cameraSettings.focusDistance) else "Contínuo (AF)"}", color = TextSecondary, fontSize = 12.sp)
                            Button(
                                onClick = {
                                    val nextFocus = if (isManual) -1.0f else 2.0f
                                    cameraManager.updateSettings(cameraSettings.copy(focusDistance = nextFocus))
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = SurfaceElevated)
                            ) {
                                Text(if (isManual) "Manual" else "Auto AF", color = ElectricBlue)
                            }
                        }
                        if (isManual) {
                            Slider(
                                value = cameraSettings.focusDistance,
                                onValueChange = {
                                    cameraManager.updateSettings(cameraSettings.copy(focusDistance = it))
                                },
                                valueRange = 0f..10f,
                                colors = SliderDefaults.colors(thumbColor = ElectricBlue, activeTrackColor = ElectricBlue)
                            )
                        }
                    }

                    // HDR Toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("HDR (High Dynamic Range)", color = TextSecondary, fontSize = 12.sp)
                        Switch(
                            checked = cameraSettings.isHdrEnabled,
                            onCheckedChange = { enabled ->
                                cameraManager.updateSettings(cameraSettings.copy(isHdrEnabled = enabled))
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = ElectricBlue)
                        )
                    }
                }
            }
        }
    }

    // Save Custom Preset Dialog
    if (showSavePresetDialog) {
        var presetName by remember { mutableStateOf("") }
        var presetCategory by remember { mutableStateOf("Personalizado") }

        AlertDialog(
            onDismissRequest = { showSavePresetDialog = false },
            containerColor = BackgroundDark,
            title = { Text("Salvar Novo Preset", color = TextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Salvar parâmetros atuais de cor e realce:", color = TextSecondary, fontSize = 12.sp)
                    OutlinedTextField(
                        value = presetName,
                        onValueChange = { presetName = it },
                        label = { Text("Nome do Preset") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ElectricBlue,
                            unfocusedBorderColor = SurfaceBorder,
                            focusedTextColor = TextPrimary
                        )
                    )
                    OutlinedTextField(
                        value = presetCategory,
                        onValueChange = { presetCategory = it },
                        label = { Text("Categoria") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ElectricBlue,
                            unfocusedBorderColor = SurfaceBorder,
                            focusedTextColor = TextPrimary
                        )
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (presetName.isNotBlank()) {
                            val newPreset = PresetModel(
                                name = presetName.trim(),
                                category = presetCategory.trim(),
                                isBuiltIn = false,
                                params = colorGradingParams
                            )
                            presetManager.saveCustomPreset(newPreset)
                            selectedPresetId = newPreset.id
                            showSavePresetDialog = false
                            Toast.makeText(context, "Preset salvo!", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue)
                ) {
                    Text("Salvar")
                }
            },
            dismissButton = {
                Button(
                    onClick = { showSavePresetDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = SurfaceElevated)
                ) {
                    Text("Cancelar", color = TextSecondary)
                }
            }
        )
    }
}

@Composable
private fun ShaderSlider(
    name: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(name, color = TextSecondary, fontSize = 12.sp)
            Text(String.format(Locale.US, "%.2f", value), color = ElectricBlueGlow, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = ElectricBlue,
                activeTrackColor = ElectricBlue,
                inactiveTrackColor = SurfaceElevated
            )
        )
    }
}
