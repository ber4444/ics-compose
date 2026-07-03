package com.livingpresence.inner.circle.squared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import kotlinx.coroutines.delay

/** How the video frame is fitted into the player surface. */
enum class ResizeMode { FIT, FILL, ZOOM }

/**
 * The snapshot of player state the UI renders from. Hoisted out of the
 * composable so [PlatformPlayerScreen] stays declarative and the state is
 * unit-testable with a fake player.
 */
class PlayerState(initial: Player? = null) {
    var currentPosition by mutableLongStateOf(0L)
        internal set
    var duration by mutableLongStateOf(0L)
        internal set
    var isSeekable by mutableStateOf(false)
        internal set
    var isLive by mutableStateOf(false)
        internal set
    var isPlaying by mutableStateOf(false)
        internal set
    var isBuffering by mutableStateOf(false)
        internal set
    var playbackError by mutableStateOf<PlaybackException?>(null)
        internal set
    var videoSize by mutableStateOf(VideoSize.UNKNOWN)
        internal set

    /** Pixel-aspect-ratio-corrected display aspect ratio; 16:9 until measured. */
    var videoAspectRatio by mutableFloatStateOf(16f / 9f)
        internal set

    /** True once the first frame has rendered (controls the buffering spinner). */
    var firstFrameRendered by mutableStateOf(false)
        internal set

    var resizeMode by mutableStateOf(ResizeMode.FIT)

    val isReady: Boolean
        get() = firstFrameRendered && playbackError == null

    /** Tracks the player via [Player.Listener], updating this state in place. */
    internal fun bind(player: Player) {
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                isSeekable = player.isCurrentMediaItemSeekable
                isLive = player.isCurrentMediaItemLive
                isPlaying = player.isPlaying
                isBuffering = player.playbackState == Player.STATE_BUFFERING
                duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L

                val size = player.videoSize
                if (size.width > 0 && size.height > 0) {
                    videoSize = size
                    videoAspectRatio =
                        (size.width * size.pixelWidthHeightRatio) / size.height.toFloat()
                    firstFrameRendered = true
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                playbackError = error
            }
        })
    }
}

/**
 * Remembers a [PlayerState] bound to [player] for the composition's lifetime,
 * polling position every [pollIntervalMs] (the listener is push-based for
 * everything *except* smooth position updates, which need polling).
 */
@Composable
fun rememberPlayerState(player: Player?, pollIntervalMs: Long = 500L): PlayerState {
    val state = remember(player) { PlayerState(player) }

    DisposableEffect(player) {
        if (player != null) state.bind(player)
        onDispose { /* listener is tied to the remembered state's lifetime */ }
    }

    // Smooth position/duration polling — the listener handles discrete events.
    LaunchedEffect(player, state) {
        if (player == null) return@LaunchedEffect
        while (true) {
            state.isSeekable = player.isCurrentMediaItemSeekable
            state.isLive = player.isCurrentMediaItemLive
            state.duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L
            state.currentPosition = player.currentPosition.coerceAtLeast(0L)
            delay(pollIntervalMs)
        }
    }

    return state
}
