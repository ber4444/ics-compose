package com.livingpresence.inner.circle.squared

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.livingpresence.mediakit.ProbedRendition

/**
 * Debug stats overlay showing the live adaptation state — the rendition the
 * player has selected, its height/bitrate, and the buffered position. Toggled
 * from the controls; in debug builds it makes the ABR ladder *visible* — the
 * adaptation is observable, not just claimed.
 */
@Composable
internal fun StatsOverlay(
    player: Player,
    state: PlayerState,
    renditions: List<ProbedRendition>?,
    modifier: Modifier = Modifier,
) {
    val currentHeight = state.videoSize.height.takeIf { it > 0 }
    val activeRendition = if (renditions != null && currentHeight != null) {
        renditions.filter { !it.isAudioOnly }
            .minByOrNull { kotlin.math.abs(it.height - currentHeight) }
    } else {
        null
    }

    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.66f))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
        val renditionLine = activeRendition?.let {
            "${it.height}p · ${it.bandwidthBitsPerSecond / 1000} kbps"
        } ?: currentHeight?.let { "${it}p" } ?: "ABR: Auto"
        Text(renditionLine, color = Color(0xFFB9F6CA), style = mono)

        val bufferedAfter = (player.bufferedPosition - player.currentPosition).coerceAtLeast(0L)
        Text("buffer: +${bufferedAfter / 1000}s", color = Color.White, style = mono)

        if (renditions != null) {
            Text("ladder: ${renditions.size} tiers", color = Color.White.copy(alpha = 0.7f), style = mono)
        }
    }
}
