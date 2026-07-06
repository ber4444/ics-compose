package com.livingpresence.inner.circle.squared

import android.app.Activity
import android.graphics.Bitmap
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.paint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.livingpresence.inner.circle.squared.generated.resources.Res
import com.livingpresence.inner.circle.squared.generated.resources.background_image
import org.jetbrains.compose.resources.painterResource
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
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlinx.coroutines.delay

private const val APP_TAG = "InnerCircleSquared"
private const val CONTROLS_AUTO_HIDE_MS = 3_000L
private const val LIVE_EDGE_THRESHOLD_MS = 3_000L

/**
 * What the service-owned player is currently asked to load. The production path
 * resolves an event into a ladder-synthesized media-item URI; a demo source
 * (plan.md FU-4, debug-only) is an arbitrary URL with no ladder synthesis.
 */
internal sealed interface PlaybackLoadRequest {
    /** Stable identity so the load effect only re-runs when the source changes. */
    val loadKey: String
}

/** The production event stream, resolved via [LadderMediaSourceBuilder]. */
internal class ProductionSource(val resolvedMediaItemUri: String) : PlaybackLoadRequest {
    override val loadKey: String = "prod:$resolvedMediaItemUri"
}

/** A debug demo source (bipbop / vertical / …) loaded as a plain media item. */
internal class DemoLoadRequest(val source: DemoSource) : PlaybackLoadRequest {
    override val loadKey: String = "demo:${source.name}:${source.url}"
}

/**
 * Scrub-preview tunables (plan.md FU-1, Scrutiny #1).
 */
private const val SCRUB_DEBOUNCE_MS = 200L
private val ScrubPreviewWidth = 160.dp
private val ScrubPreviewHeight = 90.dp
/** Material3 Slider thumb radius; used to map fraction → thumb center x. */
private val SliderThumbRadius = 10.dp

actual fun createHttpClient(): HttpClient = HttpClient()

actual fun eventsPassword(): String = com.livingpresence.inner.circle.squared.BuildConfig.EVENTS_PASSWORD

@Composable
actual fun loginBackgroundModifier(): Modifier = Modifier.paint(
    painter = painterResource(Res.drawable.background_image),
    contentScale = ContentScale.Crop,
)

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

    // What the player is currently asked to play. Defaults to the production
    // (ladder-resolved) source; the debug "Demo" menu swaps in an arbitrary URL
    // (plan.md FU-4) by switching this state, bypassing ladder synthesis.
    var activeLoad by remember(url) {
        mutableStateOf<PlaybackLoadRequest>(ProductionSource(url))
    }

    val resolvedItem = itemResult
    if (controller == null || resolvedItem == null) {
        PlayerLoadingState(onClose = onClose)
        return
    }

    ExoPlayerScreen(
        player = controller,
        renditions = if (activeLoad is ProductionSource) resolvedItem.renditions else null,
        loadRequest = activeLoad,
        url = url,
        eventNumber = eventNumber,
        onClose = onClose,
        onSelectDemoSource = { source -> activeLoad = DemoLoadRequest(source) },
        onSelectProduction = { activeLoad = ProductionSource(url) },
    )
}

@Composable
private fun ExoPlayerScreen(
    player: Player,
    renditions: List<ProbedRendition>?,
    loadRequest: PlaybackLoadRequest,
    url: String,
    eventNumber: Int?,
    onClose: () -> Unit,
    onSelectDemoSource: (DemoSource) -> Unit,
    onSelectProduction: () -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val videoTapInteractionSource = remember { MutableInteractionSource() }

    // Hand the media item to the service-owned player whenever the source
    // changes. Production sources use the ladder-resolved URI (forced HLS); demo
    // sources load the URL as a plain item so ExoPlayer infers the type.
    LaunchedEffect(player, loadRequest.loadKey) {
        val item = when (loadRequest) {
            is ProductionSource -> playbackMediaItem(loadRequest.resolvedMediaItemUri)
            is DemoLoadRequest -> demoMediaItem(
                url = loadRequest.source.url,
                title = loadRequest.source.label,
                mimeType = loadRequest.source.mimeAwareMimeType,
            )
        }
        player.setMediaItem(item)
        player.prepare()
        player.playWhenReady = true
    }
    val state = rememberPlayerState(player)

    // Rotate-to-fullscreen toggle (offered for landscape content).
    val fullscreen = rememberFullscreenToggle()

    // Phase 8: on-device transcription (CC). The RenderersFactory in the service
    // taps PCM; captions render via CaptionOverlay below. Lazily loads the Vosk
    // model on first enable (~50 MB, shipped as an asset, not bundled).
    val captionController = rememberCaptionController(player)

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

    // Keep the slider thumb tracking playback while not being dragged. Needed
    // for live streams in particular: the production Wowza nDVR window grows
    // (duration keeps increasing, MEDIA-SEQUENCE stays 0) rather than sliding,
    // so a fraction computed once would drift further from the true position
    // every time the window grows.
    LaunchedEffect(state.currentPosition, state.duration, isScrubbing) {
        if (!isScrubbing && state.duration > 0L) {
            sliderFraction = (state.currentPosition.toFloat() / state.duration.toFloat())
                .coerceIn(0f, 1f)
        }
    }

    // ── Scrub preview (plan.md FU-1, Scrutiny #1) ────────────────────────────
    // The shared PreviewFrameEngine seeks the `_160p` rendition to the scrubbed
    // position (CLOSEST_SYNC, ~2 s granularity) and caches by event + position
    // bucket. We show the cached frame above the thumb while dragging, falling
    // back to a time-only bubble if no frame is ready yet — scrubbing never
    // blocks on the network.
    val engine = LocalPreviewFrameEngine.current
    var scrubBitmap by remember { mutableStateOf<Bitmap?>(null) }
    // Thumb center x in root-window pixels, plus the bottom-controls box's root
    // origin/width, so the preview bubble can track the thumb horizontally.
    var thumbCenterRootX by remember { mutableFloatStateOf(0f) }
    var controlsBoxRootX by remember { mutableFloatStateOf(0f) }
    var controlsBoxWidthPx by remember { mutableFloatStateOf(0f) }

    val duration = state.duration
    val scrubTargetPositionMs by remember(state, isScrubbing, sliderFraction, duration) {
        derivedStateOf {
            if (isScrubbing && duration > 0L) {
                (duration * sliderFraction).roundToLong().coerceIn(0L, duration)
            } else {
                0L
            }
        }
    }

    // Reset the bubble when a scrub ends; otherwise debounce and request a frame.
    LaunchedEffect(isScrubbing, scrubTargetPositionMs) {
        if (!isScrubbing || duration <= 0L || eventNumber == null) {
            scrubBitmap = null
            return@LaunchedEffect
        }
        // Show any already-cached frame for this position instantly (free).
        scrubBitmap = engine?.cachedScrubBitmap(eventNumber, scrubTargetPositionMs)
        // Debounce so we don't seek on every pixel of drag (plan.md: ~200 ms).
        delay(SCRUB_DEBOUNCE_MS)
        // Capture the target we requested so a late frame after the drag moves
        // on is dropped rather than flashing a stale position.
        val requestedPosition = scrubTargetPositionMs
        val frame = engine?.requestScrubFrame(
            eventNumber = eventNumber,
            positionMs = requestedPosition,
            width = with(density) { ScrubPreviewWidth.roundToPx() },
            height = with(density) { ScrubPreviewHeight.roundToPx() },
        )
        if (frame != null && isScrubbing && scrubTargetPositionMs == requestedPosition) {
            scrubBitmap = frame
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
                Box(
                    modifier = surfaceModifier.onGloballyPositioned { coords ->
                        // Report the video's on-screen bounds for the PiP source-rect hint.
                        val pos = coords.positionInRoot()
                        val w = coords.size.width
                        val h = coords.size.height
                        if (w > 0 && h > 0) {
                            pipController?.updateSourceBounds(
                                left = pos.x.toInt(),
                                top = pos.y.toInt(),
                                right = (pos.x + w).toInt(),
                                bottom = (pos.y + h).toInt(),
                            )
                        }
                    },
                ) {
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
                            } else {
                                // Landscape content: offer rotate-to-fullscreen (plan.md FU-4).
                                RotateButton(toggle = fullscreen)
                            }
                            ResizeToggleButton(
                                resizeMode = state.resizeMode,
                                onCycle = { state.resizeMode = nextResizeMode(state.resizeMode) },
                            )
                            QualityMenu(player = player, renditions = renditions)
                            TextButton(onClick = { showStats = !showStats }) {
                                Text("Stats", color = Color.White)
                            }
                            // Phase 8: closed-captions toggle (on-device Vosk).
                            CaptionToggleButton(controller = captionController)
                            // Debug-only demo-sources menu (Apple bipbop ladder +
                            // a vertical sample + the production events), proving
                            // the player isn't hardwired to one server (FU-4).
                            if (com.livingpresence.inner.circle.squared.BuildConfig.DEBUG) {
                                DemoSourcesMenu(
                                    activeUrl = currentLoadUrl(loadRequest),
                                    onDemoSourceSelected = onSelectDemoSource,
                                    onProductionSelected = onSelectProduction,
                                )
                            }
                        }
                    }
                }

                // Bottom controls: scrub-preview bubble + timeline + transport.
                AnimatedVisibility(
                    visible = showVideoControls,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .onGloballyPositioned { coords ->
                                controlsBoxRootX = coords.positionInRoot().x
                                controlsBoxWidthPx = coords.size.width.toFloat()
                            },
                    ) {
                        // Floating preview bubble above the seekbar thumb. Shown
                        // only while actively scrubbing; the bubble centers on the
                        // thumb's measured x position.
                        if (isScrubbing && state.duration > 0L) {
                            ScrubPreviewBubble(
                                bitmap = scrubBitmap,
                                positionLabel = formatPlaybackTime(scrubTargetPositionMs),
                                thumbCenterRootX = thumbCenterRootX,
                                boxRootX = controlsBoxRootX,
                                boxWidthPx = controlsBoxWidthPx,
                            )
                        }
                        PlayerControlPanel(
                            player = player,
                            state = state,
                            isScrubbing = isScrubbing,
                            sliderFraction = sliderFraction,
                            canJumpToLive = canJumpToLive,
                            onThumbCenterXChanged = { thumbCenterRootX = it },
                            onSliderValueChange = {
                                isScrubbing = true
                                sliderFraction = it
                            },
                            onSliderValueChangeFinished = {
                                val dur = state.duration
                                if (dur > 0L) {
                                    val newPosition = (dur * sliderFraction).roundToLong()
                                        .coerceIn(0L, dur)
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

                // Phase 8: rolling transcription captions over the video.
                if (captionController.enabled) {
                    CaptionOverlay(
                        captions = captionController.captions,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 120.dp, start = 24.dp, end = 24.dp),
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

/**
 * The YouTube-style scrub-preview bubble: a floating card above the seekbar
 * showing the frame at the scrubbed position, or a time-only bubble when the
 * frame isn't ready yet (the graceful fallback — never blocks the drag).
 *
 * Positioning is done in root-window pixels: the slider reports the thumb's
 * center x in root space and this composable receives the bottom-controls box's
 * root origin, so the offset = thumb − box origin places the bubble correctly
 * regardless of where the controls sit. The bubble is clamped to stay within
 * the controls box width.
 *
 * @param bitmap The extracted frame, or null to show the time-only fallback.
 * @param positionLabel Formatted scrubbed position (e.g. "12:34") for the label.
 * @param thumbCenterRootX The slider thumb's center x in root-window pixels.
 * @param boxRootX The bottom-controls box's root x in pixels.
 * @param boxWidthPx The bottom-controls box's width in pixels (for clamping).
 */
@Composable
private fun ScrubPreviewBubble(
    bitmap: Bitmap?,
    positionLabel: String,
    thumbCenterRootX: Float,
    boxRootX: Float,
    boxWidthPx: Float,
) {
    val density = LocalDensity.current
    val bubbleWidthPx = with(density) { ScrubPreviewWidth.toPx() }
    val halfWidthPx = bubbleWidthPx / 2f
    // Thumb x relative to the controls box, then shift left by half the bubble
    // width so the bubble centers on the thumb. Clamp to keep it on screen.
    val rawLeft = (thumbCenterRootX - boxRootX) - halfWidthPx
    val maxLeft = (boxWidthPx - bubbleWidthPx).coerceAtLeast(0f)
    val xPx = rawLeft.coerceIn(0f, maxLeft).roundToInt()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .offset { IntOffset(x = xPx, y = 0) }
            .padding(bottom = 72.dp),
    ) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.85f))
                .padding(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (bitmap != null) {
                androidx.compose.foundation.Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Scrub preview frame",
                    modifier = Modifier
                        .width(ScrubPreviewWidth)
                        .height(ScrubPreviewHeight)
                        .clip(RoundedCornerShape(4.dp)),
                    contentScale = ContentScale.Crop,
                    filterQuality = FilterQuality.Low,
                )
            }
            Text(
                text = positionLabel,
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 2.dp),
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
    onThumbCenterXChanged: (Float) -> Unit,
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
    // Hoisted out of the onGloballyPositioned lambda below: reading a
    // CompositionLocal is a @Composable call, so it can't happen in that callback.
    val density = LocalDensity.current
    val thumbRadiusPx = with(density) { SliderThumbRadius.toPx() }

    Column(modifier = modifier) {
        if (state.isSeekable && state.duration > 0L) {
            Slider(
                value = sliderFraction.coerceIn(0f, 1f),
                onValueChange = onSliderValueChange,
                onValueChangeFinished = onSliderValueChangeFinished,
                valueRange = 0f..1f,
                modifier = Modifier.onGloballyPositioned { coords ->
                    // Map the slider fraction to the thumb's center x in root
                    // coordinates. Material3's Slider insets the active track by
                    // the thumb radius on each side; the thumb center travels
                    // that inner span. Accurate enough for bubble tracking, and
                    // the bubble's own clamp keeps it on screen regardless.
                    val w = coords.size.width
                    val rootX = coords.positionInRoot().x
                    val innerStart = rootX + thumbRadiusPx
                    val innerSpan = (w - 2 * thumbRadiusPx).coerceAtLeast(0f)
                    onThumbCenterXChanged(innerStart + sliderFraction * innerSpan)
                },
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
private fun CaptionToggleButton(controller: CaptionController) {
    val ready by controller.ready.collectAsState()
    val loadError by controller.loadError.collectAsState()
    val label = when {
        controller.enabled && loadError != null -> "CC!"
        controller.enabled && !ready -> "CC…"
        controller.enabled -> "CC●"
        else -> "CC"
    }
    TextButton(onClick = controller.onToggle) {
        Text(
            text = label,
            color = if (controller.enabled) Color.White else Color.White.copy(alpha = 0.7f),
        )
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

internal fun android.content.Context.findActivity(): Activity? {
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

private fun currentLoadUrl(loadRequest: PlaybackLoadRequest): String = when (loadRequest) {
    is DemoLoadRequest -> loadRequest.source.url
    is ProductionSource -> loadRequest.resolvedMediaItemUri
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
