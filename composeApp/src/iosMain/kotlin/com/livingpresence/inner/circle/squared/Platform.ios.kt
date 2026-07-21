@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.livingpresence.inner.circle.squared

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitView
import cnames.supported.AVPlayerBridge
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import kotlinx.cinterop.CValue
import platform.AVFoundation.AVAssetImageGenerator
import platform.AVFoundation.AVPlayerLayer
import platform.AVFoundation.AVURLAsset
import platform.AVKit.AVPictureInPictureController
import platform.CoreMedia.CMTime
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.Foundation.NSURL
import platform.UIKit.UIView
import platform.UIKit.UIImageView
import platform.UIKit.UIImage
import platform.UIKit.UIViewContentMode
import platform.CoreGraphics.CGImageRef
import kotlin.math.roundToLong

/**
 * iOS actuals (Phase 7): AVPlayer-backed playback with background audio and
 * Picture-in-Picture parity, plus the shared HTTP/password/download seams.
 *
 * HLS + ABR are native to AVPlayer (no equivalent of the Android ladder
 * synthesis is needed here). Offline download and transcription are out of
 * scope for this phase (see plan.md Phase 7).
 *
 * AVPlayer is driven through [AVPlayerBridge] — a tiny Obj-C interop wrapper
 * (see `native/avplayer/cinterop/`). Against the Xcode 26.5 SDK, Kotlin/Native
 * cinterop does not merge AVPlayer's Obj-C category methods (play/pause/rate/
 * seek/time-observation) onto the generated class, so they are unreachable from
 * Kotlin directly. The bridge re-exposes them as members of a plain NSObject.
 */
actual fun createHttpClient(): HttpClient = HttpClient(Darwin)

// onEventClick / loginBackgroundModifier are shared with web in nonAndroidMain.

@Composable
actual fun PlatformPlayerScreen(
    url: String,
    onClose: () -> Unit,
) {
    // One AVPlayer (via the Obj-C bridge) per screen. `remember` keeps it across
    // recompositions; the DisposableEffect below releases it on exit.
    val nsUrl = remember(url) { NSURL.URLWithString(url) ?: NSURL.URLWithString("")!! }
    val bridge = remember(url) {
        configureBackgroundAudio()
        AVPlayerBridge(uRL = nsUrl)
    }
    val playerLayer = remember { bridge.playerLayer!! }

    var isReady by remember(url) { mutableStateOf(false) }
    var isPlaying by remember(url) { mutableStateOf(false) }
    var positionMs by remember(url) { mutableStateOf(0L) }
    var durationMs by remember(url) { mutableStateOf(0L) }
    var scrubFraction by remember(url) { mutableStateOf(0f) }
    var isScrubbing by remember(url) { mutableStateOf(false) }

    val captionController = rememberCaptionController()
    var showStats by remember(url) { mutableStateOf(false) }
    var renditions by remember(url) { mutableStateOf<List<com.livingpresence.mediakit.ProbedRendition>?>(null) }

        LaunchedEffect(url) {
            val eventNumber = parseEventNumber(url)
            if (eventNumber != null) {
                val ladderResolver = com.livingpresence.mediakit.LadderResolver(createHttpClient(), com.livingpresence.mediakit.MediaKitConfig.Default)
                val ladder = try { ladderResolver.resolve(eventNumber) } catch (e: Exception) { null }
                renditions = ladder?.renditions
            }
        }
    
        DisposableEffect(url) {
            bridge.play()
            bridge.installAudioTapWithCallback { pcmData, numFrames, numChannels, sampleRate ->
                CaptionAudioRouter.get().onPcm(pcmData, numFrames, numChannels, sampleRate)
            }
    
            // ~4 Hz position + status pump. `queue = null` → main run loop, safe to
            // touch Compose state directly from the block.
            val observer = bridge.addPeriodicTimeObserverForInterval(
                interval = CMTimeMakeWithSeconds(0.25, 600),
                queue = null,
            ) { time: CValue<CMTime> ->
                val seconds = CMTimeGetSeconds(time)
                if (!seconds.isNaN()) positionMs = (seconds * 1000.0).roundToLong()
                val rate = bridge.rate()
                isPlaying = rate > 0f
                val durSeconds = CMTimeGetSeconds(bridge.duration())
                if (!durSeconds.isNaN() && durSeconds > 0.0) {
                    durationMs = (durSeconds * 1000.0).roundToLong()
                    isReady = true
                }
            }
    
            onDispose {
                bridge.removeTimeObserver(observer)
                bridge.replaceCurrentItemWithItem(null)
            }
        }
    
        val pipController = rememberPipController(playerLayer)
    
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            BoxWithConstraints(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                // AVPlayerLayer interop: a plain UIView whose layer hosts the
                // bridge's player sublayer (added + sized in `update`).
                // videoGravity = aspect-fit mirrors the Android default. The bridge's
                // layoutInSuperview lowers the host view's layer zPosition so the
                // native view renders *below* the Compose surface — without that, CMP's
                // UIKitView places the video above the control overlays (the known
                // z-order sharp edge), making them untappable.
                UIKitView(
                    factory = { bridge.createPlayerView()!! },
                    modifier = Modifier.fillMaxSize(),
                    onRelease = { bridge.replaceCurrentItemWithItem(null) },
                )
    
                if (!isReady) {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = Color.White,
                    )
                }
            }
    
            // Tap toggles play/pause. A plain Box (no background) sits above the
            // video surface for input while letting the video show through; the
            // control overlays are children so they receive their own taps.
            val tapSource = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(interactionSource = tapSource, indication = null) {
                        if (isPlaying) bridge.pause() else bridge.play()
                    }
            ) {
                PlayerControlsOverlay(
                    modifier = Modifier.fillMaxSize(),
                    isPlaying = isPlaying,
                    durationMs = durationMs,
                    positionMs = positionMs,
                    isLive = durationMs == 0L, // approximate
                    isSeekable = durationMs > 0L,
                    isScrubbing = isScrubbing,
                    sliderFraction = scrubFraction,
                    onSliderValueChange = {
                        isScrubbing = true
                        scrubFraction = it
                    },
                    onSliderValueChangeFinished = {
                        val target = (durationMs * scrubFraction)
                            .toLong()
                            .coerceIn(0L, durationMs)
                        bridge.seekToTime(CMTimeMakeWithSeconds(target / 1000.0, 600))
                        isScrubbing = false
                    },
                    onPlayPauseToggle = {
                        if (isPlaying) bridge.pause() else bridge.play()
                    },
                    onJumpToLive = {
                        bridge.seekToTime(CMTimeMakeWithSeconds(durationMs / 1000.0, 600))
                        bridge.play()
                    },
                    onClose = onClose,
                    topRightControls = {
                        PlayerTopRightControls(
                            captionController = captionController,
                            onToggleStats = { showStats = !showStats },
                            qualityMenu = {
                                QualityMenu(
                                    renditions = renditions,
                                    onSetAuto = {},
                                    onPinToRendition = {},
                                    onDisableVideo = {}
                                )
                            },
                            trailingControls = {
                                if (pipController != null) {
                                    TextButton(onClick = {
                                        if (pipController.isPictureInPictureActive()) {
                                            pipController.stopPictureInPicture()
                                        } else {
                                            pipController.startPictureInPicture()
                                        }
                                    }) { Text("PiP", color = Color.White) }
                                }
                            },
                        )
                    }
                )

                if (showStats) {
                    StatsOverlay(
                        currentHeight = null,
                        bufferedAfterMs = 0L,
                        renditions = renditions,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(top = 56.dp, start = 8.dp),
                    )
                }

                CaptionOverlay(
                    captions = captionController.captions,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 120.dp)
                )
            }
    }
}

/**
 * AVPictureInPictureController for the player layer, or null if the platform
 * doesn't support PiP (e.g. simulator without the capability). Background audio
 * (`AVAudioSession(.playback)`, configured in [configureBackgroundAudio]) and an
 * `UIBackgroundModes: audio` Info.plist entry (set in the host Xcode project)
 * are prerequisites for PiP to actually engage.
 */
@Composable
private fun rememberPipController(layer: AVPlayerLayer): AVPictureInPictureController? =
    if (AVPictureInPictureController.isPictureInPictureSupported()) {
        remember(layer) { AVPictureInPictureController(playerLayer = layer) }
    } else {
        null
    }

/**
 * Activates the `.playback` audio session so audio continues when the app is
 * backgrounded. The accompanying `UIBackgroundModes: audio` capability lives in
 * the host Xcode project's Info.plist (not in this Kotlin module).
 *
 * Delegated to [AVPlayerBridge]'s class method for the same category-merge
 * reason as the transport calls: `AVAudioSession.setCategory/setActive` are
 * declared in AVFAudio categories that cinterop does not merge onto the
 * generated class under the Xcode 26.5 SDK.
 */
private fun configureBackgroundAudio() {
    AVPlayerBridge.configurePlaybackSession()
}



@Composable
actual fun LiveEventThumbnail(
    eventNumber: Int,
    contentDescription: String?,
    modifier: Modifier,
) {
    val config = com.livingpresence.mediakit.MediaKitConfig.Default
    val url = config.renditionUrl(eventNumber, com.livingpresence.mediakit.RenditionTier.P160)
    
    val nsUrl = remember(url) { NSURL.URLWithString(url) ?: NSURL.URLWithString("")!! }
    val bridge = remember(url) {
        AVPlayerBridge(uRL = nsUrl).apply {
            setMuted(true)
            play()
        }
    }

    DisposableEffect(url) {
        onDispose {
            bridge.replaceCurrentItemWithItem(null)
        }
    }

    UIKitView(
        factory = { 
            bridge.createPlayerView()!!.apply {
                clipsToBounds = true
            }
        },
        modifier = modifier
    )
}
