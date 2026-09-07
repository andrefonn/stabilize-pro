package com.stabilizepro.app.ui.components

import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.stabilizepro.app.ui.theme.ElectricBlue
import com.stabilizepro.app.ui.theme.SurfaceBorder
import com.stabilizepro.app.ui.theme.SurfaceDark
import com.stabilizepro.app.ui.theme.TextPrimary
import kotlin.math.roundToInt

@OptIn(UnstableApi::class)
@Composable
fun VideoComparisonSlider(
    originalUri: Uri,
    stabilizedUri: Uri,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var sliderFraction by remember { mutableFloatStateOf(0.5f) }

    // ExoPlayer for Original Video (audio disabled to prevent device codec conflict)
    val originalPlayer = remember {
        try {
            ExoPlayer.Builder(context).build().apply {
                repeatMode = Player.REPEAT_MODE_ALL
                volume = 0f
                trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_AUDIO, true)
                    .build()
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        android.util.Log.w("VideoComparisonSlider", "Original player warning: ${error.message}")
                    }
                })
                setMediaItem(MediaItem.fromUri(originalUri))
                prepare()
                playWhenReady = true
            }
        } catch (e: Throwable) {
            android.util.Log.w("VideoComparisonSlider", "Could not init original player: ${e.message}")
            null
        }
    }

    // ExoPlayer for Stabilized Video
    val stabilizedPlayer = remember {
        try {
            ExoPlayer.Builder(context).build().apply {
                repeatMode = Player.REPEAT_MODE_ALL
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        android.util.Log.w("VideoComparisonSlider", "Stabilized player warning: ${error.message}")
                    }
                })
                setMediaItem(MediaItem.fromUri(stabilizedUri))
                prepare()
                playWhenReady = true
            }
        } catch (e: Throwable) {
            android.util.Log.e("VideoComparisonSlider", "Could not init stabilized player: ${e.message}")
            null
        }
    }

    // Sync playback position between players
    DisposableEffect(Unit) {
        onDispose {
            try { originalPlayer?.release() } catch (ignored: Throwable) {}
            try { stabilizedPlayer?.release() } catch (ignored: Throwable) {}
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(280.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(SurfaceDark)
            .border(1.dp, SurfaceBorder, RoundedCornerShape(20.dp))
    ) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        val density = LocalDensity.current

        // Bottom Layer: Stabilized Video (Full Container)
        if (stabilizedPlayer != null) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                        player = stabilizedPlayer
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // Top Layer: Original Video (Clipped to slider fraction)
        if (originalPlayer != null) {
            val clipWidthDp = with(density) { (widthPx * sliderFraction).toDp() }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds()
            ) {
                Box(
                    modifier = Modifier
                        .width(clipWidthDp)
                        .fillMaxSize()
                        .clipToBounds()
                ) {
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                useController = false
                                setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                                player = originalPlayer
                            }
                        },
                        modifier = Modifier.size(
                            width = with(density) { widthPx.toDp() },
                            height = with(density) { heightPx.toDp() }
                        )
                    )
                }
            }
        }

        // Dividers & Badges
        val dividerOffsetPx = widthPx * sliderFraction

        // Vertical divider line
        Box(
            modifier = Modifier
                .offset { IntOffset(dividerOffsetPx.roundToInt() - with(density) { 1.dp.roundToPx() }, 0) }
                .width(2.dp)
                .fillMaxSize()
                .background(Color.White)
        )

        // Draggable Circular Handle in the Center
        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        (dividerOffsetPx - with(density) { 20.dp.roundToPx() }).roundToInt(),
                        (heightPx / 2f - with(density) { 20.dp.roundToPx() }).roundToInt()
                    )
                }
                .size(40.dp)
                .clip(CircleShape)
                .background(ElectricBlue)
                .border(2.dp, Color.White, CircleShape)
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        val newFraction = (sliderFraction + (dragAmount.x / widthPx)).coerceIn(0.05f, 0.95f)
                        sliderFraction = newFraction
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.SwapHoriz,
                contentDescription = null,
                tint = TextPrimary,
                modifier = Modifier.size(22.dp)
            )
        }

        // Badges: "Antes" and "Depois"
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.65f))
                .padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Text(
                text = "Antes (Original)",
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(ElectricBlue.copy(alpha = 0.85f))
                .padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Text(
                text = "Depois (Estável)",
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
