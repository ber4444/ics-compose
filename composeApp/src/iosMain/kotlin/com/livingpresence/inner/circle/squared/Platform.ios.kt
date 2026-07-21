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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.paint
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import org.jetbrains.compose.resources.painterResource
import com.livingpresence.inner.circle.squared.generated.resources.Res
import com.livingpresence.inner.circle.squared.generated.resources.background_image
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitView
import cnames.supported.AVPlayerBridge
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import kotlinx.cinterop.CValue
import platform.AVFoundation.AVPlayerLayer
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


actual fun onEventClick(eventNumber: Int, defaultAction: () -> Unit) {
    defaultAction()
}

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

    DisposableEffect(url) {
        bridge.play()

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
                factory = { UIView() },
                update = { view -> bridge.layoutInSuperview(view) },
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
                },
            contentAlignment = Alignment.BottomCenter,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                if (durationMs > 0L) {
                    val fraction = if (isScrubbing) {
                        scrubFraction
                    } else {
                        (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                    }
                    Slider(
                        value = fraction,
                        onValueChange = {
                            isScrubbing = true
                            scrubFraction = it
                        },
                        onValueChangeFinished = {
                            val target = (durationMs * scrubFraction)
                                .toLong()
                                .coerceIn(0L, durationMs)
                            bridge.seekToTime(CMTimeMakeWithSeconds(target / 1000.0, 600))
                            isScrubbing = false
                        },
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = formatPlaybackTime(positionMs),
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = if (durationMs > 0L) formatPlaybackTime(durationMs) else "Live",
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        // Top bar: PiP (when supported).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (pipController != null) {
                TextButton(onClick = {
                    if (pipController.isPictureInPictureActive()) {
                        pipController.stopPictureInPicture()
                    } else {
                        pipController.startPictureInPicture()
                    }
                }) { Text("PiP", color = Color.White) }
            }
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

private fun formatPlaybackTime(timeMs: Long): String {
    val totalSeconds = timeMs.coerceAtLeast(0L) / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) "$hours:${pad2(minutes)}:${pad2(seconds)}"
    else "$minutes:${pad2(seconds)}"
}

private fun pad2(value: Long): String = if (value < 10L) "0$value" else value.toString()

@Composable
actual fun LiveEventThumbnail(
    eventNumber: Int,
    contentDescription: String?,
    modifier: Modifier,
) {
    val config = com.livingpresence.mediakit.MediaKitConfig.Default
    var image by remember(eventNumber) { mutableStateOf<CGImageRef?>(null) }
    
    LaunchedEffect(eventNumber) {
        withContext(Dispatchers.Default) {
            val url = config.renditionUrl(eventNumber, com.livingpresence.mediakit.RenditionTier.P160)
            val nsUrl = NSURL.URLWithString(url) ?: return@withContext
            val asset = AVURLAsset.assetWithURL(nsUrl)
            val generator = AVAssetImageGenerator(asset)
            generator.appliesPreferredTrackTransform = true
            
            val time = CMTimeMakeWithSeconds(1.0, 600)
            val cgImage = generator.copyCGImageAtTime(time, null, null)
            image = cgImage
        }
    }

    if (image != null) {
        UIKitView(
            factory = { 
                UIImageView().apply { 
                    contentMode = UIViewContentMode.UIViewContentModeScaleAspectFill
                    clipsToBounds = true
                }
            },
            update = { 
                it.image = UIImage.imageWithCGImage(image) 
            },
            modifier = modifier
        )
    } else {
        Box(
            modifier = modifier.background(
                Brush.linearGradient(listOf(Color(0xFF37474F), Color(0xFF263238))),
            ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "event $eventNumber",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}
