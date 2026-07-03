package com.livingpresence.inner.circle.squared

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.media3.common.C
import androidx.media3.common.Player
import com.livingpresence.mediakit.ProbedRendition

/**
 * Quality menu: Auto (let ABR adapt) or a concrete rendition tier from the
 * resolved ladder. Selecting a tier pins `maxVideoSize` to that tier's
 * resolution (and disables video for the audio-only tier); Auto clears the
 * constraint so `AdaptiveTrackSelection` runs freely.
 */
@Composable
internal fun QualityMenu(
    player: Player,
    renditions: List<ProbedRendition>?,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        TextButton(onClick = { expanded = true }) {
            Text("Quality", color = Color.White)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Auto") },
                onClick = {
                    setAuto(player)
                    expanded = false
                },
            )
            if (renditions != null) {
                renditions.filter { !it.isAudioOnly }.forEach { rendition ->
                    DropdownMenuItem(
                        text = { Text("${rendition.height}p") },
                        onClick = {
                            pinToHeight(player, rendition)
                            expanded = false
                        },
                    )
                }
                renditions.firstOrNull { it.isAudioOnly }?.let { audio ->
                    DropdownMenuItem(
                        text = { Text("Audio only") },
                        onClick = {
                            disableVideo(player)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

private fun setAuto(player: Player) {
    // Auto = let AdaptiveTrackSelection run: re-enable video and clear any
    // resolution pin a prior manual selection applied.
    player.trackSelectionParameters = player.trackSelectionParameters
        .buildUpon()
        .setMaxVideoSize(1920, 1080)
        .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
        .build()
}

private fun pinToHeight(player: Player, rendition: ProbedRendition) {
    player.trackSelectionParameters = player.trackSelectionParameters
        .buildUpon()
        .setMaxVideoSize(rendition.width, rendition.height)
        .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
        .build()
}

private fun disableVideo(player: Player) {
    player.trackSelectionParameters = player.trackSelectionParameters
        .buildUpon()
        .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
        .build()
}
