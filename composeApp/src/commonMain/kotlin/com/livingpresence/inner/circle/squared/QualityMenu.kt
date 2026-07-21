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
import com.livingpresence.mediakit.ProbedRendition

/**
 * Quality menu: Auto (let ABR adapt) or a concrete rendition tier from the
 * resolved ladder. Selecting a tier pins `maxVideoSize` to that tier's
 * resolution (and disables video for the audio-only tier); Auto clears the
 * constraint so `AdaptiveTrackSelection` runs freely.
 */
@Composable
internal fun QualityMenu(
    renditions: List<ProbedRendition>?,
    onSetAuto: () -> Unit,
    onPinToRendition: (ProbedRendition) -> Unit,
    onDisableVideo: () -> Unit,
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
                    onSetAuto()
                    expanded = false
                },
            )
            if (renditions != null) {
                renditions.filter { !it.isAudioOnly }.forEach { rendition ->
                    DropdownMenuItem(
                        text = { Text("${rendition.height}p") },
                        onClick = {
                            onPinToRendition(rendition)
                            expanded = false
                        },
                    )
                }
                renditions.firstOrNull { it.isAudioOnly }?.let { audio ->
                    DropdownMenuItem(
                        text = { Text("Audio only") },
                        onClick = {
                            onDisableVideo()
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}
