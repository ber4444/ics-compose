package com.livingpresence.inner.circle.squared

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.StateFlow

/**
 * Renders the rolling in-app transcript along the bottom of the player.
 *
 * Backed by the live caption stream ([CaptionAudioRouter]); the active partial
 * (open) cue and the most recent finalized cues are shown so the user gets
 * continuous context (a single live line would lose the previous sentence). The
 * overlay is a plain no-op when captions are empty — its visibility is controlled
 * by the caller (shown only while the CC toggle is on).
 *
 * @param captions the router's caption stream.
 * @param maxLines how many recent cues to render (the accumulator caps its history).
 */
@Composable
internal fun CaptionOverlay(
    captions: StateFlow<List<CaptionCue>>,
    modifier: Modifier = Modifier,
    maxLines: Int = 3,
) {
    val cues by captions.collectAsState()
    if (cues.isEmpty()) return
    val visible = cues.takeLast(maxLines)
    val joined = visible.joinToString("\n") { it.text }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = joined,
            color = Color.White,
            textAlign = TextAlign.Center,
            style = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 20.sp),
        )
    }
}
