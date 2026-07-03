package com.livingpresence.inner.circle.squared

import android.content.Context
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.scheduler.Requirements
import com.livingpresence.mediakit.EventInfo
import com.livingpresence.mediakit.MediaKitConfig
import com.livingpresence.mediakit.RenditionTier
import java.io.File

private const val TAG = "DownloadCenter"
private const val DOWNLOAD_CONTENT_DIR = "media_downloads"

/**
 * Offline downloads: a [DownloadManager] + [SimpleCache] backed by
 * [WorkManagerScheduler] (so unmet-requirement restarts go through WorkManager),
 * with [Requirements.NETWORK_UNMETERED] = wifi-only.
 *
 * Eligibility: only bounded (non-live) events are downloadable — a live window's
 * playlist has no `#EXT-X-ENDLIST`, so it isn't a finite download. Live events
 * get a LIVE badge and no download affordance.
 *
 * Downloads target a concrete rendition URL (default `_360p`) rather than the
 * synthesized `data:`-URI ladder — this keeps `DownloadManager` cache keys
 * stable and sidesteps chunklist `w`-token rotation for stored content.
 *
 * The cache is shared with playback via [cacheDataSourceFactory], so a
 * downloaded event plays straight from disk (airplane-mode playback is the
 * acceptance test).
 */
@UnstableApi
class DownloadCenter private constructor(
    private val context: Context,
    val config: MediaKitConfig,
    internal val downloadManager: DownloadManager,
    val cache: SimpleCache,
) {
    /** Per-event download state observed by the UI. */
    data class EventDownloadState(
        val eventNumber: Int,
        val state: DownloadState,
        val percent: Float,
    )

    enum class DownloadState { QUEUED, DOWNLOADING, COMPLETED, FAILED, REMOVING }

    /** Snapshot of all known download states, keyed by event number. */
    fun snapshot(): Map<Int, EventDownloadState> {
        val result = mutableMapOf<Int, EventDownloadState>()
        val cursor = downloadManager.downloadIndex.getDownloads()
        while (cursor.moveToNext()) {
            val download = cursor.download
            val eventNumber = download.request.id.toIntOrNull() ?: continue
            result[eventNumber] = EventDownloadState(
                eventNumber = eventNumber,
                state = download.toUiState(),
                percent = download.percentDownloaded.coerceIn(0f, 100f),
            )
        }
        cursor.close()
        return result
    }

    /** Enqueue a download for [event] at [tier] (default 360p for size). */
    fun enqueue(event: EventInfo, tier: RenditionTier = RenditionTier.P360) {
        require(!event.isLive) { "Cannot download a live (unbounded) event." }
        val url = config.renditionUrl(event.eventNumber, tier)
        val request = DownloadRequest.Builder(event.eventNumber.toString(), android.net.Uri.parse(url)).build()
        downloadManager.addDownload(request)
    }

    /** Remove a downloaded event from the cache + index. */
    fun remove(eventNumber: Int) {
        downloadManager.removeDownload(eventNumber.toString())
    }

    /** A [CacheDataSource.Factory] that prefers the download cache, then HTTP. */
    fun cacheDataSourceFactory(): CacheDataSource.Factory = CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory(
            DefaultDataSource.Factory(context, DefaultHttpDataSource.Factory()),
        )
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    companion object {
        @Volatile
        private var instance: DownloadCenter? = null

        fun get(context: Context, config: MediaKitConfig = MediaKitConfig.Default): DownloadCenter =
            instance ?: synchronized(this) {
                instance ?: create(context, config).also { instance = it }
            }

        @UnstableApi
        private fun create(context: Context, config: MediaKitConfig): DownloadCenter {
            val appContext = context.applicationContext
            val cacheDir = File(appContext.cacheDir, DOWNLOAD_CONTENT_DIR)
            val databaseProvider = StandaloneDatabaseProvider(appContext)
            val cache = SimpleCache(
                cacheDir,
                LeastRecentlyUsedCacheEvictor(MAX_CACHE_BYTES),
                databaseProvider,
            )

            val dataSourceFactory = DefaultDataSource.Factory(
                appContext,
                DefaultHttpDataSource.Factory(),
            )

            val downloadManager = DownloadManager(
                appContext,
                databaseProvider,
                cache,
                dataSourceFactory,
                /* executor = */ java.util.concurrent.Executors.newSingleThreadExecutor(),
            ).apply {
                // Wifi-only (unmetered); WorkManager restarts the service when the
                // requirement is later met (after process death too).
                requirements = Requirements(Requirements.NETWORK_UNMETERED)
            }

            return DownloadCenter(
                context = appContext,
                config = config,
                downloadManager = downloadManager,
                cache = cache,
            ).also {
                // Start the foreground service so pending downloads resume; it owns
                // the WorkManagerScheduler so requirement-based restarts route
                // through WorkManager (survives process death).
                runCatching {
                    androidx.media3.exoplayer.offline.DownloadService.start(
                        appContext,
                        DownloadsService::class.java,
                    )
                }
            }
        }

        private const val DOWNLOAD_WORK_ID = "ics-download-work"
        private const val MAX_CACHE_BYTES = 1L * 1024 * 1024 * 1024 // 1 GB
    }
}

/** Maps a media3 [Download.STATE_*] to the UI-facing [DownloadCenter.DownloadState]. */
@UnstableApi
private fun Download.toUiState(): DownloadCenter.DownloadState = when (state) {
    Download.STATE_QUEUED -> DownloadCenter.DownloadState.QUEUED
    Download.STATE_DOWNLOADING -> DownloadCenter.DownloadState.DOWNLOADING
    Download.STATE_COMPLETED -> DownloadCenter.DownloadState.COMPLETED
    Download.STATE_FAILED -> DownloadCenter.DownloadState.FAILED
    Download.STATE_REMOVING -> DownloadCenter.DownloadState.REMOVING
    Download.STATE_RESTARTING -> DownloadCenter.DownloadState.QUEUED
    else -> DownloadCenter.DownloadState.QUEUED
}
