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
 * Builds the [MediaItem] handed to the service-owned player. Includes
 * [MediaMetadata] so the media-session notification shows a title + artwork
 * (the launcher icon; a real event thumbnail would need async extraction into
 * the metadata, tracked as a follow-up). The event number is parsed from the
 * URL for the title.
 */
internal fun playbackMediaItem(url: String): MediaItem {
    val eventNumber = Regex("""event(\d+)""").find(url)?.groupValues?.getOrNull(1)
    return MediaItem.Builder()
        .setUri(url)
        .setMimeType(MimeTypes.APPLICATION_M3U8)
        .setMediaMetadata(
            androidx.media3.common.MediaMetadata.Builder()
                .setTitle(if (eventNumber != null) "Event $eventNumber" else "Inner Circle Squared")
                .setArtist("Inner Circle Squared")
                .setArtworkUri(android.net.Uri.parse("android.resource://com.livingpresence.inner.circle.squared/mipmap/ic_launcher"))
                .build(),
        )
        .build()
}
