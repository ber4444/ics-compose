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
 *
 * The production streams are HLS, so the mime type is forced to
 * [MimeTypes.APPLICATION_M3U8] (a `data:` URI carrying the synthesized
 * multivariant playlist isn't obviously HLS from its scheme). For arbitrary
 * demo sources (e.g. a plain `.mp4`), use [demoMediaItem] instead — forcing
 * HLS on an MP4 breaks playback.
 */
internal fun playbackMediaItem(url: String): MediaItem =
    mediaItem(url, MimeTypes.APPLICATION_M3U8, titleFor(url))

/**
 * Builds a [MediaItem] for an arbitrary demo source (plan.md FU-4). The mime
 * type is *not* forced by default (ExoPlayer infers it from the URL/extension),
 * which is correct for `.mp4` clips and for HLS masters whose `.m3u8` path is
 * self-describing. Pass [mimeType] only when the type can't be inferred.
 */
internal fun demoMediaItem(url: String, title: String, mimeType: String? = null): MediaItem =
    mediaItem(url, mimeType, title)

private fun mediaItem(url: String, mimeType: String?, title: String): MediaItem {
    val builder = MediaItem.Builder()
        .setUri(url)
        .setMediaMetadata(
            androidx.media3.common.MediaMetadata.Builder()
                .setTitle(title)
                .setArtist("Inner Circle Squared")
                .setArtworkUri(android.net.Uri.parse("android.resource://com.livingpresence.inner.circle.squared/mipmap/ic_launcher"))
                .build(),
        )
    if (mimeType != null) builder.setMimeType(mimeType)
    return builder.build()
}

private fun titleFor(url: String): String {
    val eventNumber = parseEventNumber(url)
    return if (eventNumber != null) "Event $eventNumber" else "Inner Circle Squared"
}
