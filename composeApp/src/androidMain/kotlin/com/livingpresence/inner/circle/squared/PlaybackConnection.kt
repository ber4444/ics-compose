package com.livingpresence.inner.circle.squared

import android.content.ComponentName
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.livingpresence.mediakit.MediaKitConfig

/**
 * Connects to [PlaybackService] and exposes its [MediaController] (which
 * implements [Player], so it can drive a surface). The controller is shared for
 * the composition's lifetime; released on disposal.
 *
 * Returns null until the connection resolves; the caller shows a loading state
 * in the meantime.
 */
@Composable
fun rememberPlaybackController(context: Context): MediaController? {
    var controller by remember { mutableStateOf<MediaController?>(null) }

    DisposableEffect(context) {
        val sessionToken = SessionToken(
            context,
            ComponentName(context, PlaybackService::class.java),
        )
        val future = MediaController.Builder(context, sessionToken).buildAsync()
        future.addListener(
            Runnable {
                runCatching { future.get() }.onSuccess { controller = it }
            },
            MoreExecutors.directExecutor(),
        )
        onDispose {
            runCatching {
                future.cancel(true)
                controller?.release()
            }
            controller = null
        }
    }

    return controller
}

/**
 * Loads an event into the [controller] (if not already loaded), building the
 * ABR media item via the [MediaKitConfig] base URL. The synthesized ladder is
 * resolved by the service-side player through ExoPlayer's normal HLS handling;
 * for explicit ladder synthesis across renditions, see LadderMediaSourceBuilder
 * (the controller path uses a plain media item so the service owns the source).
 */
internal fun playbackMediaItem(url: String): MediaItem =
    MediaItem.Builder()
        .setUri(url)
        .setMimeType(MimeTypes.APPLICATION_M3U8)
        .build()
