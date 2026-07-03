package com.livingpresence.inner.circle.squared

import android.app.Activity
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import androidx.media3.ui.compose.material3.buttons.PlayPauseButton
import com.livingpresence.mediakit.LadderResolver
import com.livingpresence.mediakit.MediaKitConfig
import com.livingpresence.mediakit.ProbedRendition
import io.ktor.client.HttpClient
import kotlin.math.roundToLong
import kotlinx.coroutines.delay

private const val APP_TAG = "InnerCircleSquared"
private const val CONTROLS_AUTO_HIDE_MS = 3_000L
private const val LIVE_EDGE_THRESHOLD_MS = 3_000L

actual fun createHttpClient(): HttpClient = HttpClient()

@Composable
actual fun PlatformPlayerScreen(
    url: String,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val httpClient = remember { HttpClient() }
    val eventNumber = remember(url) { parseEventNumber(url) }

    // Connect to the service-owned player. It survives config changes and
    // drives background audio / PiP; the composable renders to its surface via
    // the controller (which implements Player).
    val controller = rememberPlaybackController(context)

    // Resolve the ABR ladder (if any) just-in-time, producing a media-item URI
    // (data: URI for the synthesized multivariant playlist, or the plain URL).
    val mediaSourceBuilder = remember { LadderMediaSourceBuilder(context, MediaKitConfig.Default) }
    val ladderResolver = remember(httpClient) { LadderResolver(httpClient, MediaKitConfig.Default) }
    var itemResult by remember(url) { mutableStateOf<LadderMediaSourceBuilder.ItemResult?>(null) }

    LaunchedEffect(url) {
        itemResult = if (eventNumber != null) {
            runCatching { mediaSourceBuilder.resolveForEvent(eventNumber, ladderResolver) }
                .getOrNull()
                ?: LadderMediaSourceBuilder.ItemResult(url, renditions = null)
        } else {
            LadderMediaSourceBuilder.ItemResult(url, renditions = null)
        }
    }

    val resolvedItem = itemResult
    if (controller == null || resolvedItem == null) {
        PlayerLoadingState(onClose = onClose)
        return
    }

    ExoPlayerScreen(
        player = controller,
        renditions = resolvedItem.renditions,
        mediaItemUri = resolvedItem.mediaItemUri,
        url = url,
        onClose = onClose,
    )
}

@Composable
private fun ExoPlayerScreen(
    player: Player,
    renditions: List<ProbedRendition>?,
    mediaItemUri: String,
    url: String,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val videoTapInteractionSource = remember { MutableInteractionSource() }

    // Hand the resolved media item to the service-owned player once.
    LaunchedEffect(player, mediaItemUri) {
        player.setMediaItem(playbackMediaItem(mediaItemUri))
        player.prepare()
        player.playWhenReady = true
    }
    val state = rememberPlayerState(player)

    var isScrubbing by remember(player) { mutableStateOf(false) }
    var sliderFraction by remember(player) { mutableStateOf(0f) }
    var showVideoControls by remember(player) { mutableStateOf(true) }
    var showStats by remember(player) { mutableStateOf(false) }

    val canJumpToLive by remember(state, isScrubbing, sliderFraction) {
        derivedStateOf {
            val duration = state.duration
            if (!state.isLive || !state.isSeekable || duration <= 0L) {
                false
            } else {
                val effectivePosition = if (isScrubbing) (duration * sliderFraction).roundToLong()
                else state.currentPosition
                effectivePosition < (duration - LIVE_EDGE_THRESHOLD_MS).coerceAtLeast(0L)
            }
        }
    }

    // Auto-hide controls after inactivity (tap toggles, scrub/interaction resets the timer).
    ControlsAutoHide(
        visible = showVideoControls,
        isScrubbing = isScrubbing,
        isPlaying = state.isPlaying,
        onHide = { showVideoControls = false },
    )

    // Immersive mode: hide system bars while the player is on screen (not in PiP).
    ImmersiveSystemBars(active = MainActivity.InPipState.value.let { !it })

    // Report video size + playing state for PiP params, and collapse controls in PiP.
    val pipController = LocalPipController.current
    LaunchedEffect(state.videoSize, state.isPlaying) {
        val size = state.videoSize
        if (size.width > 0 && size.height > 0) {
            pipController?.updateVideoSize(size.width, size.height)
        }
        pipController?.setPlaying(state.isPlaying)
    }
    if (MainActivity.InPipState.value) {
        showVideoControls = false
        showStats = false
    }

    // Backgrounding policy (per plan Scrutiny #9): when the app is backgrounded
    // / in PiP, the video surface is gone but audio keeps playing. With muxed
    // HLS, disabling the video renderer alone would keep downloading full-bitrate
    // segments; constraining track selection to the ladder's audio-only tier
    // cuts the stream to ~51 kbps. On return to foreground, restore video.
    BackgroundAudioPolicy(player = player, isVideoVisible = !MainActivity.InPipState.value)

    val playbackError = state.playbackError
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            val containerAspectRatio = maxWidth.value / maxHeight.value
            val surfaceModifier = videoSurfaceModifier(
                videoAspectRatio = state.videoAspectRatio,
                containerAspectRatio = containerAspectRatio,
                resizeMode = state.resizeMode,
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = videoTapInteractionSource,
                        indication = null,
                    ) { showVideoControls = !showVideoControls },
                contentAlignment = Alignment.Center,
            ) {
                Box(modifier = surfaceModifier) {
                    PlayerSurface(
                        player = player,
                        surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                // Buffering spinner while the first frame hasn't rendered.
                if (!state.firstFrameRendered && playbackError == null) {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = Color.White,
                    )
                }

                // Top bar: close, resize toggle, quality, stats — auto-hides.
                AnimatedVisibility(
                    visible = showVideoControls,
                    modifier = Modifier.align(Alignment.TopCenter),
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.Black.copy(alpha = 0.55f))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = onClose) { Text("Close", color = Color.White) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (isPortraitVideo(state.videoAspectRatio)) {
                                TextButton(onClick = {
                                    state.resizeMode = nextPortraitResizeMode(state.resizeMode)
                                }) { Text("Fit", color = Color.White) }
                            }
                            ResizeToggleButton(
                                resizeMode = state.resizeMode,
                                onCycle = { state.resizeMode = nextResizeMode(state.resizeMode) },
                            )
                            QualityMenu(player = player, renditions = renditions)
                            TextButton(onClick = { showStats = !showStats }) {
                                Text("Stats", color = Color.White)
                            }
                        }
                    }
                }

                // Bottom controls: timeline + transport.
                AnimatedVisibility(
                    visible = showVideoControls,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    PlayerControlPanel(
                        player = player,
                        state = state,
                        isScrubbing = isScrubbing,
                        sliderFraction = sliderFraction,
                        canJumpToLive = canJumpToLive,
                        onSliderValueChange = {
                            isScrubbing = true
                            sliderFraction = it
                        },
                        onSliderValueChangeFinished = {
                            val duration = state.duration
                            if (duration > 0L) {
                                val newPosition = (duration * sliderFraction).roundToLong()
                                    .coerceIn(0L, duration)
                                player.seekTo(newPosition)
                                state.currentPosition = newPosition
                            }
                            isScrubbing = false
                        },
                        onJumpToLive = {
                            player.seekToDefaultPosition()
                            player.play()
                            state.currentPosition = state.duration
                            sliderFraction = 1f
                            isScrubbing = false
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.Black.copy(alpha = 0.55f))
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }

                // Debug stats overlay (toggle).
                if (showStats) {
                    StatsOverlay(
                        player = player,
                        state = state,
                        renditions = renditions,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(top = 56.dp, start = 8.dp),
                    )
                }
            }
        }

        // Error surface with retry (previously log-only).
        if (playbackError != null) {
            PlayerErrorOverlay(
                error = playbackError,
                onRetry = { player.prepare(); player.play() },
                onClose = onClose,
            )
        }
    }
}

/** Loading state while the ABR ladder is being resolved. */
@Composable
private fun PlayerLoadingState(onClose: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = Color.White)
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            TextButton(onClick = onClose) { Text("Close", color = Color.White) }
        }
    }
}

@Composable
private fun PlayerControlPanel(
    player: Player,
    state: PlayerState,
    isScrubbing: Boolean,
    sliderFraction: Float,
    canJumpToLive: Boolean,
    onSliderValueChange: (Float) -> Unit,
    onSliderValueChangeFinished: () -> Unit,
    onJumpToLive: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val displayedPosition = if (isScrubbing && state.duration > 0L) {
        (state.duration * sliderFraction).roundToLong()
    } else {
        state.currentPosition
    }

    Column(modifier = modifier) {
        if (state.isSeekable && state.duration > 0L) {
            Slider(
                value = sliderFraction.coerceIn(0f, 1f),
                onValueChange = onSliderValueChange,
                onValueChangeFinished = onSliderValueChangeFinished,
                valueRange = 0f..1f,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatPlaybackTime(displayedPosition),
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = when {
                    state.isSeekable && state.duration > 0L -> formatPlaybackTime(state.duration)
                    state.isLive -> "Live"
                    else -> "—"
                },
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlayPauseButton(player = player, modifier = Modifier.size(64.dp))
            Button(
                onClick = { if (state.isPlaying) player.pause() else player.play() },
                colors = ButtonDefaults.buttonColors(containerColor = Color.Black.copy(alpha = 0.45f)),
            ) {
                Text(if (state.isPlaying) "Pause" else "Unpause")
            }
            if (state.isLive && state.isSeekable && state.duration > 0L) {
                FilledTonalButton(
                    onClick = onJumpToLive,
                    enabled = canJumpToLive,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = Color.White.copy(alpha = 0.22f),
                    ),
                ) {
                    Text("Jump to live")
                }
            }
        }
    }
}

@Composable
private fun ResizeToggleButton(resizeMode: ResizeMode, onCycle: () -> Unit) {
    TextButton(onClick = onCycle) {
        Text(
            text = when (resizeMode) {
                ResizeMode.FIT -> "Fit"
                ResizeMode.FILL -> "Fill"
                ResizeMode.ZOOM -> "Zoom"
            },
            color = Color.White,
        )
    }
}

@Composable
private fun PlayerErrorOverlay(
    error: PlaybackException,
    onRetry: () -> Unit,
    onClose: () -> Unit,
) {
    Log.e(APP_TAG, "Playback error", error)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.8f))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text(
            text = "Playback error",
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = error.errorCodeName + ": " + (error.message ?: ""),
            color = Color.White.copy(alpha = 0.7f),
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onRetry) { Text("Retry") }
            TextButton(onClick = onClose) { Text("Close", color = Color.White) }
        }
    }
}

/**
 * Auto-hides the controls [CONTROLS_AUTO_HIDE_MS] after the last interaction,
 * only while playing. Paused/scrubbing keeps them visible.
 */
@Composable
private fun ControlsAutoHide(
    visible: Boolean,
    isScrubbing: Boolean,
    isPlaying: Boolean,
    onHide: () -> Unit,
) {
    if (!visible || isScrubbing || !isPlaying) return
    LaunchedEffect(visible, isScrubbing, isPlaying) {
        delay(CONTROLS_AUTO_HIDE_MS)
        onHide()
    }
}

/**
 * Hides system bars for an immersive player. Restores them on exit.
 */
@Composable
private fun ImmersiveSystemBars(active: Boolean) {
    val context = LocalContext.current
    DisposableEffect(active) {
        val activity = context.findActivity()
        val window = activity?.window
        val controller = window?.let {
            androidx.core.view.WindowCompat.getInsetsController(it, it.decorView)
        }
        controller?.let { it.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE }
        controller?.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        onDispose {
            controller?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
    }
}

private fun android.content.Context.findActivity(): Activity? {
    var ctx: android.content.Context = this
    while (ctx is android.content.ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

/** Cycles FIT → FILL → ZOOM → FIT. */
private fun nextResizeMode(mode: ResizeMode): ResizeMode = when (mode) {
    ResizeMode.FIT -> ResizeMode.FILL
    ResizeMode.FILL -> ResizeMode.ZOOM
    ResizeMode.ZOOM -> ResizeMode.FIT
}

/** For portrait content, cycles FIT → ZOOM → FIT (FILL distorts badly). */
private fun nextPortraitResizeMode(mode: ResizeMode): ResizeMode = when (mode) {
    ResizeMode.FIT -> ResizeMode.ZOOM
    else -> ResizeMode.FIT
}

private fun parseEventNumber(url: String): Int? =
    Regex("""event(\d+)""").find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()

private fun formatPlaybackTime(timeMs: Long): String {
    val totalSeconds = timeMs.coerceAtLeast(0L) / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) "$hours:${pad2(minutes)}:${pad2(seconds)}"
    else "$minutes:${pad2(seconds)}"
}

private fun pad2(value: Long): String = if (value < 10L) "0$value" else value.toString()
