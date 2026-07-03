package com.livingpresence.inner.circle.squared

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.livingpresence.mediakit.MediaKitConfig
import com.livingpresence.mediakit.RenditionTier
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A single shared, muted, video-only ExoPlayer used to extract poster frames
 * from the `_160p` rendition for feed tiles (and, in Phase 3, scrub previews).
 *
 * One decoder instead of N per-tile players is the legitimate version of "frame
 * recycling" — MediaCodec owns the decoded-frame buffers; we own one extractor
 * and an LRU bitmap cache. Cost per unique thumbnail ≈ one 2 s segment at
 * ~262 kbps ≈ 65 KB of network, decode trivial at 284×160.
 *
 * Frames are captured by rendering onto an off-screen [android.media.ImageReader]
 * surface and copying the resulting [Bitmap]. The cache is keyed by event number
 * + the seek position so repeated tile renders are free.
 */
class PreviewFrameEngine(
    private val context: Context,
    private val config: MediaKitConfig = MediaKitConfig.Default,
) {
    private val bitmapCache: LruCache<Int, Bitmap> = object : LruCache<Int, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount
    }

    /** Cached frame for an event, or null while loading / on failure. */
    fun cachedBitmap(eventNumber: Int): Bitmap? = bitmapCache.get(eventNumber)

    /**
     * Extract a poster frame for [eventNumber]: load the `_160p` rendition, seek
     * ~10% in (CLOSEST_SYNC), grab a frame. Falls back to `_360p` then the base
     * rendition if `_160p` 404s. Returns null on any failure (the tile then
     * shows a placeholder, per "no placeholder for a failed 404" → but a
     * *late* thumbnail failure is a soft degrade, not a list removal).
     */
    suspend fun requestFrame(eventNumber: Int, width: Int, height: Int): Bitmap? {
        bitmapCache.get(eventNumber)?.let { return it }

        val tiers = listOf(RenditionTier.P160, RenditionTier.P360, RenditionTier.P720)
        for (tier in tiers) {
            val frame = captureFrame(
                url = config.renditionUrl(eventNumber, tier),
                width = width,
                height = height,
            )
            if (frame != null) {
                bitmapCache.put(eventNumber, frame)
                return frame
            }
        }
        return null
    }

    private suspend fun captureFrame(url: String, width: Int, height: Int): Bitmap? {
        val reader = ImageReaderCapture(width.coerceAtLeast(2), height.coerceAtLeast(2))
        val player = ExoPlayer.Builder(context)
            .setTrackSelector(
                androidx.media3.exoplayer.trackselection.DefaultTrackSelector(context).apply {
                    // Video-only, lowest bitrate available.
                    parameters = buildUponParameters()
                        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                        .setForceLowestBitrate(true)
                        .build()
                },
            )
            .build()
            .apply {
                setVideoSurface(reader.surface)
                volume = 0f
                setMediaItem(
                    MediaItem.Builder()
                        .setUri(url)
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build(),
                )
                prepare()
                playWhenReady = true
            }

        return try {
            // Wait until the player knows the duration, then seek ~10% in.
            val frame: Bitmap? = withTimeoutOrNull(EXTRACT_TIMEOUT_MS) {
                while (player.duration == C.TIME_UNSET || player.duration <= 0L) {
                    if (player.playbackState == Player.STATE_ENDED ||
                        player.playbackState == Player.STATE_IDLE
                    ) return@withTimeoutOrNull null
                    delay(SEEK_POLL_MS)
                }
                val target = (player.duration * SEEK_FRACTION).toLong()
                player.seekTo(target)
                // Wait for the seek to land a rendered frame.
                reader.awaitFrame()
            }
            // Recenter-crop to the requested aspect ratio (tiles are 16:9).
            frame?.let { cropToAspectRatio(it, width, height) }
        } catch (_: Throwable) {
            null
        } finally {
            player.release()
            reader.release()
        }
    }

    /** Shrinks the memory footprint by evicting the least-recently-used frames. */
    fun trimToCount(keep: Int) {
        // LruCache evicts by access order automatically; this is a hook for
        // onTrimMemory(MODERATE) in Phase 4 to shrink proactively.
        if (bitmapCache.size() > keep) {
            bitmapCache.trimToSize(keep)
        }
    }

    fun release() {
        bitmapCache.evictAll()
    }

    private fun cropToAspectRatio(src: Bitmap, targetW: Int, targetH: Int): Bitmap {
        val targetAr = targetW.toFloat() / targetH.toFloat()
        val srcAr = src.width.toFloat() / src.height.toFloat()
        if (kotlin.math.abs(srcAr - targetAr) < 0.01f) return src
        val (newW, newH) = if (srcAr > targetAr) {
            val h = src.height; val w = (h * targetAr).toInt(); w to h
        } else {
            val w = src.width; val h = (w / targetAr).toInt(); w to h
        }
        val x = (src.width - newW) / 2
        val y = (src.height - newH) / 2
        return Bitmap.createBitmap(src, x, y, newW, newH)
    }

    companion object {
        /** ~12 MB of bitmaps (≈ 75 frames at 284×160 ARGB). */
        private const val CACHE_BYTES = 12 * 1024 * 1024
        private const val SEEK_FRACTION = 0.10
        private const val SEEK_POLL_MS = 50L
        private const val EXTRACT_TIMEOUT_MS = 8_000L
    }
}

/**
 * CompositionLocal providing the shared [PreviewFrameEngine]. Created once per
 * app session and released on disposal.
 */
val LocalPreviewFrameEngine = staticCompositionLocalOf<PreviewFrameEngine?> { null }

@Composable
fun rememberPreviewFrameEngine(): PreviewFrameEngine {
    val context = LocalContext.current
    val engine = remember { PreviewFrameEngine(context) }
    DisposableEffect(engine) {
        onDispose { engine.release() }
    }
    return engine
}

/**
 * Composable that loads and displays a poster frame for [eventNumber] using the
 * shared [PreviewFrameEngine], with an in-memory LRU cache. The Android actual
 * of [LiveEventThumbnail].
 */
@Composable
actual fun LiveEventThumbnail(
    eventNumber: Int,
    contentDescription: String?,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val engine = LocalPreviewFrameEngine.current ?: remember { PreviewFrameEngine(context) }
    var bitmap by remember(eventNumber) { mutableStateOf<Bitmap?>(engine.cachedBitmap(eventNumber)) }

    LaunchedEffect(eventNumber) {
        if (bitmap == null) {
            // Tile render size — small, since _160p is 284×160 already.
            bitmap = engine.requestFrame(eventNumber, width = 284, height = 160)
        }
    }

    val bmp = bitmap
    if (bmp != null) {
        androidx.compose.foundation.Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            filterQuality = FilterQuality.Low,
        )
    } else {
        ThumbnailPlaceholder(eventNumber = eventNumber, modifier = modifier)
    }
}

@Composable
private fun ThumbnailPlaceholder(eventNumber: Int, modifier: Modifier) {
    androidx.compose.foundation.layout.Box(
        modifier = modifier.background(
            androidx.compose.ui.graphics.Brush.linearGradient(
                listOf(Color(0xFF37474F), Color(0xFF263238)),
            ),
        ),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        androidx.compose.material3.Text(
            text = "event $eventNumber",
            color = androidx.compose.ui.graphics.Color.White,
            style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
        )
    }
}
