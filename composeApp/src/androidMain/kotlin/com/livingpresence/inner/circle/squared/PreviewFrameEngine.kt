package com.livingpresence.inner.circle.squared

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import androidx.media3.exoplayer.SeekParameters
import com.livingpresence.mediakit.MediaKitConfig
import com.livingpresence.mediakit.RenditionTier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * A single shared, muted, video-only ExoPlayer used to extract poster frames
 * from the `_160p` rendition for feed tiles and scrub-preview frames for the
 * player seekbar.
 *
 * One decoder instead of N per-tile players is the legitimate version of "frame
 * recycling" — MediaCodec owns the decoded-frame buffers; we own one extractor
 * and a two-tier poster cache (memory LRU → disk store). Cost per unique
 * thumbnail ≈ one 2 s segment at ~262 kbps ≈ 65 KB of network, decode trivial
 * at 284×160. The disk tier means a decoded poster survives process death and
 * is not re-decoded on relaunch (plan.md FU-2).
 *
 * Scrub-preview frames use a separate in-memory LRU keyed by event number +
 * position (rounded to the nearest keyframe — [KEYFRAME_GRANULARITY_MS] — so
 * repeated scrubs over the same ~2 s span are free, per plan.md Scrutiny #1).
 * The disk tier is intentionally poster-only (scrub frames are ephemeral).
 *
 * Frames are captured by rendering onto an off-screen [android.media.ImageReader]
 * surface and copying the resulting [Bitmap].
 *
 * Lookup order: memory LRU → disk → network decode. On a miss after decode, the
 * frame is written to both tiers. The disk store is bounded ([DISK_CACHE_BYTES])
 * with oldest-file LRU eviction; all disk I/O runs on [Dispatchers.IO].
 */
class PreviewFrameEngine(
    private val context: Context,
    private val config: MediaKitConfig = MediaKitConfig.Default,
) {
    private val bitmapCache: LruCache<Int, Bitmap> = object : LruCache<Int, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount
    }

    /** Scrub-preview frames: keyed by event number + keyframe bucket (plan.md FU-1). */
    private val scrubCache: LruCache<Long, Bitmap> = object : LruCache<Long, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: Long, value: Bitmap): Int = value.byteCount
    }

    /**
     * The two-tier disk store. File-based, keyed by event number; bounded by
     * [DISK_CACHE_BYTES] with oldest-last-modified LRU eviction. Guarded by
     * [diskMutex] so concurrent reads/writes/evictions stay consistent.
     */
    private val diskCache = DiskFrameCache(
        dir = File(context.cacheDir, DISK_CACHE_DIR),
        maxBytes = DISK_CACHE_BYTES,
    )

    /** Cached frame for an event, or null while loading / on failure. */
    fun cachedBitmap(eventNumber: Int): Bitmap? = bitmapCache.get(eventNumber)

    /**
     * Extract a poster frame for [eventNumber]: load the `_160p` rendition, seek
     * ~10% in (CLOSEST_SYNC), grab a frame. Falls back to `_360p` then the base
     * rendition if `_160p` 404s. Returns null on any failure (the tile then
     * shows a placeholder, per "no placeholder for a failed 404" → but a
     * *late* thumbnail failure is a soft degrade, not a list removal).
     *
     * Lookup order is memory LRU → disk → network decode; on a decode the frame
     * is written back to both tiers.
     */
    suspend fun requestFrame(eventNumber: Int, width: Int, height: Int): Bitmap? {
        // Tier 1: memory.
        bitmapCache.get(eventNumber)?.let { return it }

        // Tier 2: disk. A disk hit is promoted into memory so the next read is free.
        val fromDisk = diskCache.read(eventNumber)
        if (fromDisk != null) {
            bitmapCache.put(eventNumber, fromDisk)
            return fromDisk
        }

        // Tier 3: network decode.
        val tiers = listOf(RenditionTier.P160, RenditionTier.P360, RenditionTier.P720)
        for (tier in tiers) {
            val frame = captureFrame(
                url = config.renditionUrl(eventNumber, tier),
                width = width,
                height = height,
                positionMs = null,
            )
            if (frame != null) {
                bitmapCache.put(eventNumber, frame)
                diskCache.write(eventNumber, frame)
                return frame
            }
        }
        return null
    }

    /** Cached scrub-preview frame for [eventNumber] at [positionMs], or null. */
    fun cachedScrubBitmap(eventNumber: Int, positionMs: Long): Bitmap? =
        scrubCache.get(scrubCacheKey(eventNumber, positionMs))

    /**
     * Extract a scrub-preview frame at [positionMs] on the `_160p` rendition
     * (falling back to `_360p` then base). The position is snapped to the nearest
     * ~2 s keyframe (`SeekParameters.CLOSEST_SYNC`) and the cache key is bucketed
     * to match, so repeated scrubs over the same span reuse one frame (plan.md
     * Scrutiny #1 + FU-1). Cost per unique stop ≈ 65 KB.
     */
    suspend fun requestScrubFrame(
        eventNumber: Int,
        positionMs: Long,
        width: Int,
        height: Int,
    ): Bitmap? {
        val cacheKey = scrubCacheKey(eventNumber, positionMs)
        scrubCache.get(cacheKey)?.let { return it }

        val tiers = listOf(RenditionTier.P160, RenditionTier.P360, RenditionTier.P720)
        for (tier in tiers) {
            val frame = captureFrame(
                url = config.renditionUrl(eventNumber, tier),
                width = width,
                height = height,
                positionMs = bucketPosition(positionMs),
            )
            if (frame != null) {
                scrubCache.put(cacheKey, frame)
                return frame
            }
        }
        return null
    }

    /** Snaps [positionMs] to the nearest keyframe boundary ([KEYFRAME_GRANULARITY_MS]). */
    private fun bucketPosition(positionMs: Long): Long {
        val granularity = KEYFRAME_GRANULARITY_MS
        return (positionMs / granularity) * granularity
    }

    /** Packs event number + bucketed position into one cache key. */
    private fun scrubCacheKey(eventNumber: Int, positionMs: Long): Long {
        val bucket = bucketPosition(positionMs)
        return (eventNumber.toLong() shl EVENT_SHIFT) or (bucket and POSITION_MASK)
    }

    private suspend fun captureFrame(
        url: String,
        width: Int,
        height: Int,
        positionMs: Long?,
    ): Bitmap? {
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
                // CLOSEST_SYNC gives keyframe-accurate seeking (~2 s here); for
                // the poster path (10%) the difference is invisible at tile size.
                setSeekParameters(SeekParameters.CLOSEST_SYNC)
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
            val frame: Bitmap? = withTimeoutOrNull(EXTRACT_TIMEOUT_MS) {
                if (positionMs == null) {
                    // Poster path: wait until the player knows the duration, then
                    // seek ~10% in.
                    while (player.duration == C.TIME_UNSET || player.duration <= 0L) {
                        if (player.playbackState == Player.STATE_ENDED ||
                            player.playbackState == Player.STATE_IDLE
                        ) return@withTimeoutOrNull null
                        delay(SEEK_POLL_MS)
                    }
                    player.seekTo((player.duration * SEEK_FRACTION).toLong())
                } else {
                    // Scrub path: wait until prepared, then seek to the requested
                    // position (snapped to the nearest sync point above).
                    while (player.playbackState == Player.STATE_IDLE ||
                        player.playbackState == Player.STATE_BUFFERING
                    ) {
                        if (player.playbackState == Player.STATE_ENDED) return@withTimeoutOrNull null
                        delay(SEEK_POLL_MS)
                    }
                    player.seekTo(positionMs)
                }
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

    /**
     * Shrinks the *memory* footprint by evicting the least-recently-used frames.
     * Memory pressure is intentionally not a reason to drop the persistent disk
     * cache (it exists precisely so frames survive) — so this touches memory
     * only. The disk store is bounded and prunes itself on write.
     */
    fun trimToCount(keep: Int) {
        // LruCache evicts by access order automatically; this is a hook for
        // onTrimMemory(MODERATE) in Phase 4 to shrink proactively.
        if (bitmapCache.size() > keep) {
            bitmapCache.trimToSize(keep)
        }
        // Scrub frames are ephemeral and bulkier under active scrubbing — trim them
        // more aggressively (keep half) since they're cheap to re-extract.
        scrubCache.trimToSize(keep / 2)
    }

    /**
     * Releases all tiers. The memory LRU + scrub caches are cleared immediately;
     * the disk store is purged asynchronously (fire-and-forget — purge does not
     * block tile rendering, and [release] is called from a composable disposal
     * where blocking would be wrong).
     */
    fun release() {
        bitmapCache.evictAll()
        scrubCache.evictAll()
        diskCache.purge()
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

        /** Disk cache directory under [Context.getCacheDir] (system-managed, purged on low space). */
        private const val DISK_CACHE_DIR = "media_thumbnails"

        /** Disk cache cap: ~50 MB ≈ hundreds of JPEG thumbnails. */
        private const val DISK_CACHE_BYTES = 50L * 1024 * 1024

        /** Scrub-preview keyframe granularity: positions bucket to ~2 s (this server's GOP). */
        internal const val KEYFRAME_GRANULARITY_MS = 2_000L
        private const val EVENT_SHIFT = 40
        private const val POSITION_MASK = (1L shl EVENT_SHIFT) - 1L
    }
}

/**
 * The disk tier of [PreviewFrameEngine]'s frame cache: a bounded, file-based
 * store keyed by event number, surviving process death so frames aren't
 * re-decoded on relaunch (plan.md FU-2).
 *
 * - Format: JPEG quality 85 (thumbnails are ARGB_8888 284×160 — small, JPEG is
 *   fine and ~6× smaller than PNG). Files are named `frame_{eventNumber}.jpg`.
 * - Eviction: oldest by `lastModified` when the store exceeds [maxBytes]. A
 *   read touches `lastModified`, so reads count as LRU access.
 * - Thread-safety: every read/write/eviction runs under [diskMutex] on
 *   [Dispatchers.IO]; [purge] spawns a worker thread (fire-and-forget).
 */
private class DiskFrameCache(
    private val dir: File,
    private val maxBytes: Long,
) {
    init {
        dir.mkdirs()
    }

    private val diskMutex = Mutex()

    /** Decodes the cached JPEG for [eventNumber], or null on miss/decode error. */
    suspend fun read(eventNumber: Int): Bitmap? = withContext(Dispatchers.IO) {
        diskMutex.withLock {
            val file = fileFor(eventNumber)
            if (!file.exists()) return@withLock null
            runCatching {
                BitmapFactory.decodeFile(file.absolutePath)?.also {
                    // Touch lastModified so reads count as LRU access.
                    file.setLastModified(System.currentTimeMillis())
                }
            }.getOrNull()
        }
    }

    /** Compresses [bitmap] to JPEG and writes it, then prunes to [maxBytes]. */
    suspend fun write(eventNumber: Int, bitmap: Bitmap) = withContext(Dispatchers.IO) {
        diskMutex.withLock {
            val file = fileFor(eventNumber)
            runCatching {
                file.outputStream().use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                }
            }
            prune()
        }
    }

    /** Deletes the whole store (called from [PreviewFrameEngine.release]). */
    fun purge() {
        // Fire-and-forget on a worker thread; release() is invoked from
        // composable disposal where blocking the caller would be wrong, and any
        // leftover cache files are harmless (self-purging under [maxBytes] on
        // the next write).
        Thread { dir.listFiles()?.forEach { it.delete() } }.start()
    }

    private fun fileFor(eventNumber: Int): File = File(dir, "frame_$eventNumber.jpg")

    /** Drops oldest files (by lastModified) until total size ≤ [maxBytes]. */
    private fun prune() {
        val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (file in files) {
            if (total <= maxBytes) break
            total -= file.length()
            file.delete()
        }
    }

    private companion object {
        const val JPEG_QUALITY = 85
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
