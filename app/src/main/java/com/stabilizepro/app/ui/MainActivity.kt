package com.stabilizepro.app.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stabilizepro.app.presets.PresetManager
import com.stabilizepro.app.ui.screens.camera.CameraScreen
import com.stabilizepro.app.ui.screens.home.HomeScreen
import com.stabilizepro.app.ui.screens.processing.ProcessingScreen
import com.stabilizepro.app.ui.screens.queue.QueueScreen
import com.stabilizepro.app.ui.screens.result.ResultScreen
import com.stabilizepro.app.ui.screens.splash.SplashScreen
import com.stabilizepro.app.ui.theme.BackgroundDark
import com.stabilizepro.app.ui.theme.ElectricBlue
import com.stabilizepro.app.ui.theme.ElectricBlueGlow
import com.stabilizepro.app.ui.theme.SurfaceDark
import com.stabilizepro.app.ui.theme.StabilizeProTheme
import com.stabilizepro.app.ui.theme.TextMuted
import com.stabilizepro.app.ui.theme.TextPrimary
import com.stabilizepro.app.ui.viewmodel.AppScreen
import com.stabilizepro.app.ui.viewmodel.MainTab
import android.content.Intent
import com.stabilizepro.app.ui.viewmodel.MainViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleNavigationIntent(intent)

        setContent {
            StabilizeProTheme {
                val currentScreen by viewModel.currentScreen.collectAsStateWithLifecycle()
                val selectedVideo by viewModel.selectedVideo.collectAsStateWithLifecycle()
                val config by viewModel.config.collectAsStateWithLifecycle()
                val progress by viewModel.progress.collectAsStateWithLifecycle()
                val saveStatus by viewModel.saveGalleryStatus.collectAsStateWithLifecycle()

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(BackgroundDark)
                ) {
                    AnimatedContent(
                        targetState = currentScreen,
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        label = "screen_navigation_transition"
                    ) { screen ->
                        when (screen) {
                            is AppScreen.Splash -> {
                                SplashScreen(
                                    onTimeout = { viewModel.onSplashFinished() }
                                )
                            }
                            is AppScreen.Main -> {
                                Scaffold(
                                    bottomBar = {
                                        NavigationBar(
                                            containerColor = SurfaceDark,
                                            contentColor = TextPrimary,
                                            tonalElevation = 8.dp
                                        ) {
                                            NavigationBarItem(
                                                selected = screen.activeTab == MainTab.CAMERA,
                                                onClick = { viewModel.selectTab(MainTab.CAMERA) },
                                                icon = {
                                                    Icon(
                                                        imageVector = Icons.Default.PhotoCamera,
                                                        contentDescription = "Câmera"
                                                    )
                                                },
                                                label = { Text("Câmera") },
                                                colors = NavigationBarItemDefaults.colors(
                                                    selectedIconColor = Color.White,
                                                    selectedTextColor = ElectricBlueGlow,
                                                    indicatorColor = ElectricBlue,
                                                    unselectedIconColor = TextMuted,
                                                    unselectedTextColor = TextMuted
                                                )
                                            )
                                            NavigationBarItem(
                                                selected = screen.activeTab == MainTab.STABILIZER,
                                                onClick = { viewModel.selectTab(MainTab.STABILIZER) },
                                                icon = {
                                                    Icon(
                                                        imageVector = Icons.Default.AutoAwesome,
                                                        contentDescription = "Estabilizador"
                                                    )
                                                },
                                                label = { Text("Estabilizador") },
                                                colors = NavigationBarItemDefaults.colors(
                                                    selectedIconColor = Color.White,
                                                    selectedTextColor = ElectricBlueGlow,
                                                    indicatorColor = ElectricBlue,
                                                    unselectedIconColor = TextMuted,
                                                    unselectedTextColor = TextMuted
                                                )
                                            )
                                            NavigationBarItem(
                                                selected = screen.activeTab == MainTab.QUEUE,
                                                onClick = { viewModel.selectTab(MainTab.QUEUE) },
                                                icon = {
                                                    Icon(
                                                        imageVector = Icons.Default.HourglassEmpty,
                                                        contentDescription = "Processamentos"
                                                    )
                                                },
                                                label = { Text("Processamentos") },
                                                colors = NavigationBarItemDefaults.colors(
                                                    selectedIconColor = Color.White,
                                                    selectedTextColor = ElectricBlueGlow,
                                                    indicatorColor = ElectricBlue,
                                                    unselectedIconColor = TextMuted,
                                                    unselectedTextColor = TextMuted
                                                )
                                            )
                                        }
                                    }
                                ) { innerPadding ->
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(innerPadding)
                                    ) {
                                        when (screen.activeTab) {
                                            MainTab.CAMERA -> {
                                                CameraScreen(
                                                    onNavigateToQueue = { viewModel.selectTab(MainTab.QUEUE) }
                                                )
                                            }
                                            MainTab.STABILIZER -> {
                                                HomeScreen(
                                                    selectedVideo = selectedVideo,
                                                    config = config,
                                                    onVideoSelected = { uri -> viewModel.onVideoSelected(uri) },
                                                    onIntensitySelected = { intensity -> viewModel.onIntensitySelected(intensity) },
                                                    onConfigChange = { newConfig -> viewModel.updateConfig(newConfig) },
                                                    onStartStabilization = { viewModel.startStabilization() },
                                                    onNavigateToQueue = { viewModel.selectTab(MainTab.QUEUE) }
                                                )
                                            }
                                            MainTab.QUEUE -> {
                                                QueueScreen(
                                                    onOpenResult = { uri -> viewModel.openResultFromUri(uri) }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            is AppScreen.Processing -> {
                                ProcessingScreen(
                                    progress = progress,
                                    onRetry = { viewModel.retryStabilization() },
                                    onBackToHome = { viewModel.resetToHome() },
                                    onCancel = { viewModel.cancelStabilization() }
                                )
                            }
                            is AppScreen.Result -> {
                                ResultScreen(
                                    result = screen.result,
                                    saveStatus = saveStatus,
                                    onSaveToGallery = { viewModel.saveToGallery(screen.result) },
                                    onShare = { viewModel.shareVideo(this@MainActivity, screen.result) },
                                    onNewVideo = { viewModel.resetToHome() },
                                    onClearSaveStatus = { viewModel.clearSaveGalleryStatus() }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNavigationIntent(intent)
    }

    private fun handleNavigationIntent(intent: Intent?) {
        if (intent == null) return

        // Regular deep-link navigation (e.g. from notification)
        val target = intent.getStringExtra("EXTRA_NAVIGATE_TO")
        if (target == "QUEUE") {
            viewModel.selectTab(MainTab.QUEUE)
            return
        }

        // Handle .sppreset import via ACTION_VIEW or ACTION_SEND
        val uri = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            else -> null
        } ?: return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Copy the incoming URI stream to a temp file so PresetManager can read it
                val tempFile = File(cacheDir, "import_${System.currentTimeMillis()}.sppreset")
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                }

                val presetManager = PresetManager(applicationContext)
                val result = presetManager.importPresetFromFile(tempFile)
                tempFile.delete()

                withContext(Dispatchers.Main) {
                    result.fold(
                        onSuccess = { preset ->
                            presetManager.saveCustomPreset(preset)
                            viewModel.selectTab(MainTab.CAMERA)
                            Toast.makeText(
                                this@MainActivity,
                                "Preset \"${preset.name}\" importado com sucesso!",
                                Toast.LENGTH_LONG
                            ).show()
                        },
                        onFailure = { error ->
                            Toast.makeText(
                                this@MainActivity,
                                "Erro ao importar preset: ${error.message}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    )
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "Erro ao ler arquivo de preset: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }
}
