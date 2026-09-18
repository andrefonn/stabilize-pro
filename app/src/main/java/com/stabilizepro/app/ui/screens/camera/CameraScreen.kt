package com.stabilizepro.app.ui.screens.camera

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Preview
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stabilizepro.app.camera.CameraAspectRatio
import com.stabilizepro.app.camera.CameraManager
import com.stabilizepro.app.camera.CameraSettings
import com.stabilizepro.app.camera.CaptureMode
import com.stabilizepro.app.camera.LensType
import com.stabilizepro.app.camera.PhotoProcessor
import com.stabilizepro.app.camera.VideoQualityOption
import com.stabilizepro.app.data.repository.VideoRepositoryImpl
import com.stabilizepro.app.domain.model.StabilizationConfig
import com.stabilizepro.app.domain.model.StabilizationIntensity
import com.stabilizepro.app.export.ExportQuality
import com.stabilizepro.app.presets.ColorGradingParams
import com.stabilizepro.app.presets.PresetManager
import com.stabilizepro.app.presets.PresetModel
import com.stabilizepro.app.renderer.CameraPreviewGl
import com.stabilizepro.app.ui.theme.BackgroundDark
import com.stabilizepro.app.ui.theme.ElectricBlue
import com.stabilizepro.app.ui.theme.ElectricBlueGlow
import com.stabilizepro.app.ui.theme.SurfaceBorder
import com.stabilizepro.app.ui.theme.SurfaceElevated
import com.stabilizepro.app.ui.theme.TextMuted
import com.stabilizepro.app.ui.theme.TextPrimary
import com.stabilizepro.app.ui.theme.TextSecondary
import com.stabilizepro.app.worker.StabilizationQueueManager
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


@OptIn(ExperimentalFoundationApi::class)
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
    val ultraWideLabel by cameraManager.ultraWideLabel.collectAsStateWithLifecycle()
    val presets by presetManager.presets.collectAsStateWithLifecycle()

    val coroutineScope = rememberCoroutineScope()
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var isFocusLocked by remember { mutableStateOf(false) }
    var isFocusVisible by remember { mutableStateOf(false) }
    var exposureBiasNorm by remember { mutableFloatStateOf(0f) }
    var hideFocusJob by remember { mutableStateOf<Job?>(null) }
    val focusAnimScale = remember { Animatable(1.35f) }

    val initialPreset = remember {
        presetManager.presets.value.find { it.id == "builtin_natural" }
    }
    var colorGradingParams by remember { mutableStateOf(initialPreset?.params ?: ColorGradingParams()) }
    var selectedPresetId by remember { mutableStateOf<String?>("builtin_natural") }

    var showShaderDrawer by remember { mutableStateOf(false) }
    var showCamera2Drawer by remember { mutableStateOf(false) }
    var showSavePresetDialog by remember { mutableStateOf(false) }
    var showPresetStrip by remember { mutableStateOf(false) }

    // Preset to be deleted — null means no dialog shown
    var presetToDelete by remember { mutableStateOf<PresetModel?>(null) }

    // Launcher for importing a .sppreset file from device storage
    val importPresetLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val tempFile = File(context.cacheDir, "import_${System.currentTimeMillis()}.sppreset")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    java.io.FileOutputStream(tempFile).use { out -> input.copyTo(out) }
                }
                val result = presetManager.importPresetFromFile(tempFile)
                tempFile.delete()
                withContext(Dispatchers.Main) {
                    result.fold(
                        onSuccess = { preset ->
                            presetManager.saveCustomPreset(preset)
                            selectedPresetId = preset.id
                            colorGradingParams = preset.params
                            Toast.makeText(context, "Preset \"${preset.name}\" importado!", Toast.LENGTH_SHORT).show()
                        },
                        onFailure = { error ->
                            Toast.makeText(context, "Erro ao importar: ${error.message}", Toast.LENGTH_LONG).show()
                        }
                    )
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Erro ao ler arquivo: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

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
                aspectRatio = cameraSettings.aspectRatio,
                onSurfaceReady = { glView, surfaceProvider ->
                    cameraManager.initialize(lifecycleOwner, glView, surfaceProvider)
                }
            )

            // Aspect ratio framing guide for 3:4 and 1:1
            if (cameraSettings.aspectRatio != CameraAspectRatio.RATIO_16_9) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(cameraSettings.aspectRatio.ratioValue)
                        .align(Alignment.Center)
                        .border(1.dp, Color(0x33FFFFFF))
                )
            }

            var viewSize by remember { mutableStateOf(IntSize.Zero) }

            // Tap-to-Focus, AE/AF Lock & Exposure Drag Touch Layer
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { viewSize = it }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { offset ->
                                focusPoint = offset
                                isFocusLocked = false
                                isFocusVisible = true
                                exposureBiasNorm = 0f
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)

                                cameraManager.focusAndMeter(
                                    offset.x,
                                    offset.y,
                                    viewSize.width,
                                    viewSize.height,
                                    isLock = false
                                )

                                hideFocusJob?.cancel()
                                hideFocusJob = coroutineScope.launch {
                                    focusAnimScale.snapTo(1.35f)
                                    focusAnimScale.animateTo(1.0f, tween(250, easing = FastOutSlowInEasing))
                                    delay(3500)
                                    if (!isFocusLocked) {
                                        isFocusVisible = false
                                    }
                                }
                            },
                            onLongPress = { offset ->
                                focusPoint = offset
                                isFocusLocked = true
                                isFocusVisible = true
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)

                                cameraManager.focusAndMeter(
                                    offset.x,
                                    offset.y,
                                    viewSize.width,
                                    viewSize.height,
                                    isLock = true
                                )

                                hideFocusJob?.cancel()
                                coroutineScope.launch {
                                    focusAnimScale.snapTo(1.4f)
                                    focusAnimScale.animateTo(1.0f, tween(300, easing = FastOutSlowInEasing))
                                }
                            }
                        )
                    }
            ) {
                if (isFocusVisible && focusPoint != null) {
                    val pt = focusPoint!!
                    val density = LocalDensity.current
                    val ringSize = 68.dp
                    val ringSizePx = with(density) { ringSize.toPx() }

                    val clampedX = (pt.x - ringSizePx / 2).coerceIn(16f, (viewSize.width - ringSizePx - 50).coerceAtLeast(16f))
                    val clampedY = (pt.y - ringSizePx / 2).coerceIn(70f, (viewSize.height - ringSizePx - 130).coerceAtLeast(70f))

                    Box(
                        modifier = Modifier
                            .offset { IntOffset(clampedX.toInt(), clampedY.toInt()) }
                            .scale(focusAnimScale.value)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                if (isFocusLocked) {
                                    Row(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(Color(0xD9000000))
                                            .border(1.dp, Color(0xFFFFD700), RoundedCornerShape(4.dp))
                                            .clickable {
                                                isFocusLocked = false
                                                isFocusVisible = false
                                                cameraManager.unlockFocusAndMetering()
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            }
                                            .padding(horizontal = 6.dp, vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Lock,
                                            contentDescription = "Trava AF/AE",
                                            tint = Color(0xFFFFD700),
                                            modifier = Modifier.size(10.dp)
                                        )
                                        Text(
                                            text = "TRAVA AF/AE",
                                            color = Color(0xFFFFD700),
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                }

                                Box(
                                    modifier = Modifier
                                        .size(ringSize)
                                        .border(
                                            width = if (isFocusLocked) 2.dp else 1.5.dp,
                                            color = if (isFocusLocked) Color(0xFFFFD700) else Color(0xEEFFFFFF),
                                            shape = CircleShape
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(4.dp)
                                            .clip(CircleShape)
                                            .background(if (isFocusLocked) Color(0xFFFFD700) else Color.White)
                                    )
                                }
                            }

                            // Vertical Exposure Compensation Slider (Drag up/down)
                            Column(
                                modifier = Modifier
                                    .height(90.dp)
                                    .width(36.dp)
                                    .pointerInput(Unit) {
                                        detectVerticalDragGestures { change, dragAmount ->
                                            change.consume()
                                            exposureBiasNorm = (exposureBiasNorm - (dragAmount / 120f)).coerceIn(-1f, 1f)
                                            val range = cameraManager.getExposureCompensationRange()
                                            val targetIndex = if (exposureBiasNorm >= 0) {
                                                (exposureBiasNorm * range.upper).toInt()
                                            } else {
                                                (-exposureBiasNorm * range.lower).toInt()
                                            }
                                            cameraManager.setExposureCompensation(targetIndex)
                                        }
                                    },
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.WbSunny,
                                    contentDescription = "Ajuste de Exposição",
                                    tint = if (exposureBiasNorm > 0.05f) Color(0xFFFFD700) else Color.White.copy(alpha = 0.85f),
                                    modifier = Modifier
                                        .size(18.dp)
                                        .offset(y = (-exposureBiasNorm * 25).dp)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Box(
                                    modifier = Modifier
                                        .width(2.dp)
                                        .height(48.dp)
                                        .background(Color(0x55FFFFFF), RoundedCornerShape(1.dp))
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.Center)
                                            .offset(y = (-exposureBiasNorm * 20).dp)
                                            .size(width = 6.dp, height = 3.dp)
                                            .background(Color(0xFFFFD700), RoundedCornerShape(1.dp))
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // Camera permission not yet granted — show clear, actionable UI
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.padding(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PhotoCamera,
                        contentDescription = null,
                        tint = ElectricBlue,
                        modifier = Modifier.size(72.dp)
                    )
                    Text(
                        text = "Permissão de câmera necessária",
                        color = TextPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Para usar a câmera do Stabilize Pro, é preciso autorizar o acesso à câmera e ao microfone deste dispositivo.",
                        color = TextSecondary,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                    Button(
                        onClick = {
                            permissionLauncher.launch(
                                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
                            )
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.PhotoCamera,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Conceder Permissão", fontWeight = FontWeight.Bold)
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
                        LensType.ULTRA_WIDE -> ultraWideLabel
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
                                val lensMsg = when (lens) {
                                    LensType.ULTRA_WIDE -> "Câmera $ultraWideLabel (Ultra Wide)"
                                    LensType.WIDE -> "Câmera 1x (Principal)"
                                    LensType.TELEPHOTO -> "Câmera 2x (Telefoto)"
                                    LensType.FRONT -> "Câmera Frontal"
                                }
                                Toast.makeText(context, lensMsg, Toast.LENGTH_SHORT).show()
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

            // Top action buttons: Auto Stabilize, Camera2 Pro & Shaders
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Auto Stabilize Quick Toggle
                IconButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        val newState = !cameraSettings.autoStabilizeAfterRecording
                        cameraManager.updateSettings(cameraSettings.copy(autoStabilizeAfterRecording = newState))
                        Toast.makeText(
                            context,
                            if (newState) "Estabilização pós-gravação: ATIVADA" else "Estabilização pós-gravação: DESATIVADA",
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(if (cameraSettings.autoStabilizeAfterRecording) ElectricBlue else Color(0x88000000))
                ) {
                    Icon(
                        imageVector = if (cameraSettings.autoStabilizeAfterRecording) Icons.Default.AutoAwesome else Icons.Default.Block,
                        contentDescription = "Estabilização Pós-Gravação",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }

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
            // State for the long-press context popup (which preset was long-pressed)
            var longPressedPreset by remember { mutableStateOf<PresetModel?>(null) }

            AnimatedVisibility(
                visible = showPresetStrip,
                enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
                exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 })
            ) {
                Column {
                    // Header row: "PRESETS" label + Import button
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Presets • Segure para opções",
                            color = TextMuted,
                            fontSize = 10.sp
                        )
                        // Import preset button
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0x55222222))
                                .clickable {
                                    importPresetLauncher.launch(
                                        arrayOf("application/octet-stream", "*/*")
                                    )
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = "Importar Preset",
                                tint = ElectricBlue,
                                modifier = Modifier.size(11.dp)
                            )
                            Text(
                                text = "IMPORTAR",
                                color = ElectricBlue,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
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
                                    .combinedClickable(
                                        onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            selectedPresetId = preset.id
                                            colorGradingParams = preset.params
                                        },
                                        onLongClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            longPressedPreset = preset
                                        }
                                    )
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
                }
            }

            // Long-press context dialog for a preset
            longPressedPreset?.let { preset ->
                AlertDialog(
                    onDismissRequest = { longPressedPreset = null },
                    containerColor = BackgroundDark,
                    title = {
                        Text(
                            text = preset.name,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    text = {
                        Text(
                            text = if (preset.isBuiltIn) "Preset padrão do aplicativo." else "Preset personalizado.",
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                    },
                    confirmButton = {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            // Share button — available for all presets
                            Button(
                                onClick = {
                                    longPressedPreset = null
                                    coroutineScope.launch(Dispatchers.IO) {
                                        try {
                                            val file = presetManager.exportPresetToFile(preset)
                                            val fileUri = FileProvider.getUriForFile(
                                                context,
                                                "${context.packageName}.fileprovider",
                                                file
                                            )
                                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                                type = "application/octet-stream"
                                                putExtra(Intent.EXTRA_STREAM, fileUri)
                                                putExtra(Intent.EXTRA_SUBJECT, "Preset: ${preset.name}")
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }
                                            val chooser = Intent.createChooser(shareIntent, "Compartilhar preset \"${preset.name}\"")
                                            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            withContext(Dispatchers.Main) {
                                                context.startActivity(chooser)
                                            }
                                        } catch (e: Exception) {
                                            withContext(Dispatchers.Main) {
                                                Toast.makeText(context, "Erro ao exportar preset: ${e.message}", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = SurfaceElevated),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Share,
                                    contentDescription = null,
                                    tint = ElectricBlue,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Compartilhar", color = ElectricBlue)
                            }
                            // Delete — only for custom (non built-in) presets
                            if (!preset.isBuiltIn) {
                                Button(
                                    onClick = {
                                        longPressedPreset = null
                                        presetToDelete = preset
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB71C1C)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Apagar preset", color = Color.White, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    },
                    dismissButton = {
                        Button(
                            onClick = { longPressedPreset = null },
                            colors = ButtonDefaults.buttonColors(containerColor = SurfaceElevated)
                        ) {
                            Text("Cancelar", color = TextSecondary)
                        }
                    }
                )
            }

            // Delete confirmation dialog
            presetToDelete?.let { preset ->
                AlertDialog(
                    onDismissRequest = { presetToDelete = null },
                    containerColor = BackgroundDark,
                    title = { Text("Apagar preset?", color = TextPrimary, fontWeight = FontWeight.Bold) },
                    text = {
                        Text(
                            text = "\"${preset.name}\" será apagado permanentemente. Esta ação não pode ser desfeita.",
                            color = TextSecondary,
                            fontSize = 13.sp
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                presetManager.deleteCustomPreset(preset.id)
                                if (selectedPresetId == preset.id) {
                                    // Fall back to Natural preset when active preset is deleted
                                    selectedPresetId = "builtin_natural"
                                    val natural = presets.find { it.id == "builtin_natural" }
                                    colorGradingParams = natural?.params ?: ColorGradingParams()
                                }
                                presetToDelete = null
                                Toast.makeText(context, "Preset apagado.", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB71C1C))
                        ) {
                            Text("Apagar", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        Button(
                            onClick = { presetToDelete = null },
                            colors = ButtonDefaults.buttonColors(containerColor = SurfaceElevated)
                        ) {
                            Text("Cancelar", color = TextSecondary)
                        }
                    }
                )
            }

            // Mode Selector: FOTO / VÍDEO
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
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

            // Pro Quick Settings Bar: Resolução | FPS | Proporção | Toggle Presets
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Resolution Selector
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
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
                        .padding(horizontal = 8.dp, vertical = 5.dp)
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

                Spacer(modifier = Modifier.width(6.dp))

                // FPS Selector (30 FPS vs 60 FPS)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0x66222222))
                        .clickable {
                            val nextFps = if (cameraSettings.targetFps == 30) 60 else 30
                            cameraManager.updateSettings(cameraSettings.copy(targetFps = nextFps))
                            Toast.makeText(context, "$nextFps FPS", Toast.LENGTH_SHORT).show()
                        }
                        .padding(horizontal = 8.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = "${cameraSettings.targetFps} FPS",
                        color = ElectricBlue,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                // Aspect Ratio Selector (9:16, 3:4, 1:1)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0x66222222))
                        .clickable {
                            val nextAspect = when (cameraSettings.aspectRatio) {
                                CameraAspectRatio.RATIO_16_9 -> CameraAspectRatio.RATIO_4_3
                                CameraAspectRatio.RATIO_4_3 -> CameraAspectRatio.RATIO_1_1
                                CameraAspectRatio.RATIO_1_1 -> CameraAspectRatio.RATIO_16_9
                            }
                            cameraManager.updateSettings(cameraSettings.copy(aspectRatio = nextAspect))
                            val aspectDesc = when (nextAspect) {
                                CameraAspectRatio.RATIO_16_9 -> "Proporção 9:16 (Vertical)"
                                CameraAspectRatio.RATIO_4_3 -> "Proporção 3:4 (com moldura preta)"
                                CameraAspectRatio.RATIO_1_1 -> "Proporção 1:1 (Quadrado)"
                            }
                            Toast.makeText(context, aspectDesc, Toast.LENGTH_SHORT).show()
                        }
                        .padding(horizontal = 8.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = cameraSettings.aspectRatio.displayName,
                        color = ElectricBlue,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                // Presets Toggle Button
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (showPresetStrip) ElectricBlue else Color(0x66222222))
                        .clickable {
                            showPresetStrip = !showPresetStrip
                        }
                        .padding(horizontal = 8.dp, vertical = 5.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Palette,
                            contentDescription = "Presets",
                            tint = if (showPresetStrip) Color.White else ElectricBlue,
                            modifier = Modifier.size(13.dp)
                        )
                        Text(
                            text = "PRESETS",
                            color = if (showPresetStrip) Color.White else ElectricBlue,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Main Trigger Button Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {

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
                                    onPhotoSaved = { _ ->
                                        CoroutineScope(Dispatchers.IO).launch {
                                            try {
                                                // Apply active color grading parameters to full-resolution photo
                                                val processedFileResult = PhotoProcessor.processPhoto(photoFile, colorGradingParams)
                                                val fileToSave = processedFileResult.getOrDefault(photoFile)
                                                val repo = VideoRepositoryImpl(context)
                                                val saveResult = repo.savePhotoToGallery(fileToSave)
                                                withContext(Dispatchers.Main) {
                                                    if (saveResult.isSuccess) {
                                                        try { photoFile.delete() } catch (ignored: Exception) {}
                                                        Toast.makeText(context, "Foto salva na galeria com sucesso!", Toast.LENGTH_SHORT).show()
                                                    } else {
                                                        val err = saveResult.exceptionOrNull()?.message ?: "Falha ao salvar no MediaStore"
                                                        Toast.makeText(context, "Erro ao salvar na galeria: $err (foto mantida em cache)", Toast.LENGTH_LONG).show()
                                                    }
                                                }
                                            } catch (e: Exception) {
                                                withContext(Dispatchers.Main) {
                                                    Toast.makeText(context, "Erro ao processar foto: ${e.message}", Toast.LENGTH_LONG).show()
                                                }
                                            }
                                        }
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
                                            if (cameraSettings.autoStabilizeAfterRecording) {
                                                // Camera GL FBO recording already applied the shader to all frames.
                                                // Setting preset = null avoids applying the color grading a second time during motion stabilization.
                                                StabilizationQueueManager.enqueueTask(
                                                    context = context,
                                                    inputUri = uri,
                                                    videoTitle = videoFile.name,
                                                    config = StabilizationConfig(
                                                        intensity = StabilizationIntensity.MEDIUM,
                                                        preset = null
                                                    ),
                                                    presetId = selectedPresetId,
                                                    quality = ExportQuality.ORIGINAL
                                                )

                                                Toast.makeText(
                                                    context,
                                                    "Gravação salva! Estabilização iniciada na fila em segundo plano.",
                                                    Toast.LENGTH_LONG
                                                ).show()
                                            } else {
                                                // Save directly to gallery without post-stabilization
                                                CoroutineScope(Dispatchers.IO).launch {
                                                    try {
                                                        val repo = VideoRepositoryImpl(context)
                                                        val saveResult = repo.saveVideoToGallery(videoFile)
                                                        withContext(Dispatchers.Main) {
                                                            if (saveResult.isSuccess) {
                                                                try { videoFile.delete() } catch (ignored: Exception) {}
                                                                Toast.makeText(
                                                                    context,
                                                                    "Vídeo salvo diretamente na galeria!",
                                                                    Toast.LENGTH_SHORT
                                                                ).show()
                                                            } else {
                                                                val err = saveResult.exceptionOrNull()?.message ?: "Falha ao salvar"
                                                                Toast.makeText(
                                                                    context,
                                                                    "Erro ao salvar na galeria: $err (arquivo preservado: ${videoFile.name})",
                                                                    Toast.LENGTH_LONG
                                                                ).show()
                                                            }
                                                        }
                                                    } catch (e: Exception) {
                                                        withContext(Dispatchers.Main) {
                                                            Toast.makeText(
                                                                context,
                                                                "Erro ao salvar: ${e.message} (arquivo preservado: ${videoFile.name})",
                                                                Toast.LENGTH_LONG
                                                            ).show()
                                                        }
                                                    }
                                                }
                                            }
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

                    // Auto Stabilization Post-Recording Toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Estabilização Pós-Gravação", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Text("Processar vídeos gravados na fila em segundo plano", color = TextSecondary, fontSize = 11.sp)
                        }
                        Switch(
                            checked = cameraSettings.autoStabilizeAfterRecording,
                            onCheckedChange = { enabled ->
                                cameraManager.updateSettings(cameraSettings.copy(autoStabilizeAfterRecording = enabled))
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = ElectricBlue)
                        )
                    }
                }
            }
        }
    }

    // Smart Save Preset Dialog
    if (showSavePresetDialog) {
        // Determine if the currently selected preset is a custom (editable) one
        val activeCustomPreset = remember(selectedPresetId, presets) {
            selectedPresetId?.let { id ->
                presets.find { it.id == id && !it.isBuiltIn }
            }
        }

        if (activeCustomPreset != null) {
            // Active preset is a custom one → offer Overwrite OR Save as New
            var showNewPresetForm by remember { mutableStateOf(false) }

            if (!showNewPresetForm) {
                AlertDialog(
                    onDismissRequest = { showSavePresetDialog = false },
                    containerColor = BackgroundDark,
                    title = { Text("Salvar preset", color = TextPrimary, fontWeight = FontWeight.Bold) },
                    text = {
                        Text(
                            text = "Você editou \"${activeCustomPreset.name}\". O que deseja fazer?",
                            color = TextSecondary,
                            fontSize = 13.sp
                        )
                    },
                    confirmButton = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            // Option 1: Overwrite the active preset
                            Button(
                                onClick = {
                                    val updated = activeCustomPreset.copy(params = colorGradingParams)
                                    presetManager.saveCustomPreset(updated)
                                    showSavePresetDialog = false
                                    Toast.makeText(context, "\"${activeCustomPreset.name}\" atualizado!", Toast.LENGTH_SHORT).show()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Salvar em \"${activeCustomPreset.name}\"", fontWeight = FontWeight.Bold)
                            }
                            // Option 2: Save as a brand new preset
                            Button(
                                onClick = { showNewPresetForm = true },
                                colors = ButtonDefaults.buttonColors(containerColor = SurfaceElevated),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Salvar como novo preset", color = TextPrimary)
                            }
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
            } else {
                // New preset form (user chose "Save as new")
                var presetName by remember { mutableStateOf("") }
                var presetCategory by remember { mutableStateOf(activeCustomPreset.category) }
                SaveNewPresetDialog(
                    presetName = presetName,
                    presetCategory = presetCategory,
                    onPresetNameChange = { presetName = it },
                    onPresetCategoryChange = { presetCategory = it },
                    onSave = {
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
                            Toast.makeText(context, "Preset \"${newPreset.name}\" salvo!", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onCancel = { showSavePresetDialog = false }
                )
            }
        } else {
            // No custom preset active → go straight to new preset form
            var presetName by remember { mutableStateOf("") }
            var presetCategory by remember { mutableStateOf("Personalizado") }
            SaveNewPresetDialog(
                presetName = presetName,
                presetCategory = presetCategory,
                onPresetNameChange = { presetName = it },
                onPresetCategoryChange = { presetCategory = it },
                onSave = {
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
                        Toast.makeText(context, "Preset \"${newPreset.name}\" salvo!", Toast.LENGTH_SHORT).show()
                    }
                },
                onCancel = { showSavePresetDialog = false }
            )
        }
    }
}

/** Reusable new-preset name + category form, extracted to avoid duplication. */
@Composable
private fun SaveNewPresetDialog(
    presetName: String,
    presetCategory: String,
    onPresetNameChange: (String) -> Unit,
    onPresetCategoryChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = BackgroundDark,
        title = { Text("Salvar Novo Preset", color = TextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Salvar parâmetros atuais de cor e realce:", color = TextSecondary, fontSize = 12.sp)
                OutlinedTextField(
                    value = presetName,
                    onValueChange = onPresetNameChange,
                    label = { Text("Nome do Preset") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = ElectricBlue,
                        unfocusedBorderColor = SurfaceBorder,
                        focusedTextColor = TextPrimary
                    )
                )
                OutlinedTextField(
                    value = presetCategory,
                    onValueChange = onPresetCategoryChange,
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
                onClick = onSave,
                colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue)
            ) {
                Text("Salvar")
            }
        },
        dismissButton = {
            Button(
                onClick = onCancel,
                colors = ButtonDefaults.buttonColors(containerColor = SurfaceElevated)
            ) {
                Text("Cancelar", color = TextSecondary)
            }
        }
    )
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
