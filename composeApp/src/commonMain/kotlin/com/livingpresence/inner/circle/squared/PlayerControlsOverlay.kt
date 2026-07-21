package com.livingpresence.inner.circle.squared

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.roundToLong

@Composable
fun PlayerControlsOverlay(
    modifier: Modifier = Modifier,
    isPlaying: Boolean,
    durationMs: Long,
    positionMs: Long,
    isLive: Boolean,
    isSeekable: Boolean,
    isScrubbing: Boolean,
    sliderFraction: Float,
    onSliderValueChange: (Float) -> Unit,
    onSliderValueChangeFinished: (Float) -> Unit,
    onPlayPauseToggle: () -> Unit,
    onJumpToLive: () -> Unit,
    onClose: () -> Unit,
    onThumbCenterXChanged: (Float) -> Unit = {},
    onTopBarBottomChanged: (Float) -> Unit = {},
    onBottomBarTopChanged: (Float) -> Unit = {},
    topRightControls: @Composable RowScope.() -> Unit = {},
) {
    val density = LocalDensity.current
    val thumbRadiusPx = with(density) { 10.dp.toPx() } // SliderThumbRadius

    Box(modifier = modifier) {
        // Top Bar
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .onGloballyPositioned { onTopBarBottomChanged(it.boundsInWindow().bottom) }
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onClose) {
                Text("Close", color = Color.White)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                topRightControls()
            }
        }

        // Bottom Controls
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .onGloballyPositioned { onBottomBarTopChanged(it.boundsInWindow().top) }
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            if (isSeekable && durationMs > 0L) {
                Slider(
                    value = sliderFraction,
                    onValueChange = onSliderValueChange,
                    onValueChangeFinished = { onSliderValueChangeFinished(sliderFraction) },
                    valueRange = 0f..1f,
                    modifier = Modifier.fillMaxWidth().onGloballyPositioned { coords ->
                        val w = coords.size.width
                        val rootX = coords.positionInRoot().x
                        val innerStart = rootX + thumbRadiusPx
                        val innerSpan = (w - 2 * thumbRadiusPx).coerceAtLeast(0f)
                        onThumbCenterXChanged(innerStart + sliderFraction * innerSpan)
                    }
                )
            }

            val displayedPosition = if (isScrubbing && durationMs > 0L) {
                (durationMs * sliderFraction).roundToLong()
            } else {
                positionMs
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = formatPlaybackTime(displayedPosition),
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = when {
                        isSeekable && durationMs > 0L -> formatPlaybackTime(durationMs)
                        isLive -> "Live"
                        else -> "—"
                    },
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onPlayPauseToggle) {
                    Text(if (isPlaying) "Pause" else "Play", color = Color.White)
                }
                if (isLive && isSeekable && durationMs > 0L) {
                    Spacer(modifier = Modifier.width(16.dp))
                    FilledTonalButton(
                        onClick = onJumpToLive,
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = Color.White.copy(alpha = 0.22f)
                        )
                    ) {
                        Text("Jump to live")
                    }
                }
            }
        }
    }
}

internal fun formatPlaybackTime(timeMs: Long): String {
    val totalSeconds = timeMs.coerceAtLeast(0L) / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) "$hours:${pad2(minutes)}:${pad2(seconds)}"
    else "$minutes:${pad2(seconds)}"
}

private fun pad2(value: Long): String = if (value < 10L) "0$value" else value.toString()
