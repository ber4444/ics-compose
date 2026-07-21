package com.livingpresence.inner.circle.squared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.media3.common.Player
import com.livingpresence.inner.circle.squared.transcription.TranscriberStatus
import com.livingpresence.inner.circle.squared.transcription.TranscriptionProvider
import com.livingpresence.inner.circle.squared.transcription.TranscriptionSettings
import kotlinx.coroutines.flow.StateFlow

/**
 * UI-facing controller for live captions: the CC toggle, the selected streaming
 * provider (Deepgram/Soniox), and the caption/status streams.
 *
 * Recognition runs in [CaptionAudioRouter] (a process singleton fed by the
 * service player's PCM tap); this holder just starts/stops it and switches the
 * provider. Cloud ASR needs no player position clock, so unlike the old on-device
 * engine there's no position wiring here.
 *
 * @param enabled whether the user has toggled CC on.
 * @param provider the active streaming ASR provider.
 * @param status the router's connection/lifecycle state.
 * @param error the router's last error (missing key, connection failure, …).
 * @param captions the router's caption stream.
 * @param onToggle flips [enabled].
 * @param onSelectProvider switches the streaming provider.
 */
internal class CaptionController(
    val enabled: Boolean,
    val provider: TranscriptionProvider,
    val status: StateFlow<TranscriberStatus>,
    val error: StateFlow<String?>,
    val captions: StateFlow<List<CaptionCue>>,
    val onToggle: () -> Unit,
    val onSelectProvider: (TranscriptionProvider) -> Unit,
)

/**
 * Remembers a [CaptionController]. Starts streaming to the selected provider on
 * enable (and when the provider changes); stops on disable or when the screen leaves.
 *
 * [player] is currently unused (cloud ASR is driven by the PCM tap, not the player
 * clock) but kept in the signature for parity with the on-device path.
 */
@Composable
internal fun rememberCaptionController(@Suppress("UNUSED_PARAMETER") player: Player?): CaptionController {
    val router = remember { CaptionAudioRouter.get() }
    var enabled by remember { mutableStateOf(false) }
    val provider by TranscriptionSettings.provider.collectAsState()

    // Enable → start (or switch to) the selected provider; disable → stop.
    // Re-runs when the provider changes while enabled, switching the live stream.
    LaunchedEffect(enabled, provider) {
        if (enabled) router.enable(provider) else router.disable()
    }

    // Stop streaming if the player screen leaves the composition.
    DisposableEffect(Unit) {
        onDispose { router.disable() }
    }

    return remember(router, enabled, provider) {
        CaptionController(
            enabled = enabled,
            provider = provider,
            status = router.status,
            error = router.error,
            captions = router.captions,
            onToggle = { enabled = !enabled },
            onSelectProvider = { TranscriptionSettings.select(it) },
        )
    }
}
