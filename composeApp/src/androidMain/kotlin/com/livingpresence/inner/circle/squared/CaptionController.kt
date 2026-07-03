package com.livingpresence.inner.circle.squared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.Player
import kotlinx.coroutines.flow.StateFlow

/**
 * Holds the in-app transcription state for a player screen: the CC toggle and
 * the engine's caption stream.
 *
 * The engine itself is a process singleton owned by [PlaybackService] (it taps
 * PCM via the service player's [TranscriptionRenderersFactory]); this holder is
 * the UI-facing controller that starts/stops recognition and surfaces captions.
 *
 * Recognition is started/stopped against the *controller* player's position
 * clock. Because the controller fronts the service player, its
 * `currentPosition` mirrors content position — good enough to stamp cues.
 *
 * @param enabled whether the user has toggled CC on.
 * @param ready the engine's model-ready stream.
 * @param loadError the engine's model-load error stream.
 * @param captions the engine's caption stream.
 * @param onToggle flips [enabled].
 */
internal class CaptionController(
    val enabled: Boolean,
    val ready: StateFlow<Boolean>,
    val loadError: StateFlow<String?>,
    val captions: StateFlow<List<CaptionCue>>,
    val onToggle: () -> Unit,
)

/**
 * Remembers a [CaptionController] bound to [player]. Lazily loads the model on
 * first enable; stops recognition on disable/leave.
 */
@Composable
internal fun rememberCaptionController(player: Player?): CaptionController {
    val context = LocalContext.current
    val engine = remember { TranscriptionEngine.get(context) }

    var enabled by remember { mutableStateOf(false) }

    // Toggle: enable → ensure model + start; disable → stop.
    LaunchedEffect(enabled, player) {
        if (player == null) return@LaunchedEffect
        if (enabled) {
            engine.loadModel()
            engine.start(playerPositionProvider = { player.currentPosition.coerceAtLeast(0L) })
        } else {
            engine.stop()
        }
    }

    // Stop recognition if the player leaves the composition (screen closed).
    DisposableEffect(player) {
        onDispose {
            if (enabled) engine.stop()
        }
    }

    return remember(engine, enabled) {
        CaptionController(
            enabled = enabled,
            ready = engine.ready,
            loadError = engine.loadError,
            captions = engine.captions,
            onToggle = { enabled = !enabled },
        )
    }
}
