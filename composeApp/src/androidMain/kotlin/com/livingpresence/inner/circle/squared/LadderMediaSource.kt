package com.livingpresence.inner.circle.squared

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DataSchemeDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import com.livingpresence.mediakit.LadderResolver
import com.livingpresence.mediakit.MediaKitConfig
import com.livingpresence.mediakit.RenditionTier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Builds the playback [MediaSource], preferring a synthesized ABR ladder
 * (resolved just-in-time from the event's unadvertised sibling renditions) and
 * falling back to the plain base-URL HLS source if probing fails.
 *
 * The synthesized multivariant playlist is fed via a `data:` URI through
 * [DataSchemeDataSource] → [HlsMediaSource], so `AdaptiveTrackSelection` adapts
 * across 720p/360p/160p/audio-only on the production streams — genuine ABR
 * where the server's own master advertises only one variant.
 */
internal class LadderMediaSourceBuilder(
    private val context: Context,
    private val config: MediaKitConfig = MediaKitConfig.Default,
) {
    /**
     * Resolve (if possible) and build the media source for [eventNumber].
     * Returns the source plus the rendition tiers that made it into the ladder
     * (for the quality menu), or a plain fallback source + null tiers.
     */
    suspend fun buildForEvent(
        eventNumber: Int,
        resolver: LadderResolver,
    ): BuildResult = withContext(Dispatchers.IO) {
        val ladder = runCatching { resolver.resolve(eventNumber) }.getOrNull()
        if (ladder != null && ladder.renditions.size > 1) {
            val dataUri = buildDataUri(ladder.masterPlaylistText)
            BuildResult(
                mediaSource = hlsSourceFromUri(dataUri),
                renditions = ladder.renditions,
            )
        } else {
            BuildResult(
                mediaSource = plainHlsSource(config.eventUrl(eventNumber)),
                renditions = null,
            )
        }
    }

    private fun buildDataUri(playlistText: String): String =
        "data:" + MimeTypes.APPLICATION_M3U8 + "," + java.net.URLEncoder.encode(
            playlistText,
            Charsets.UTF_8.name(),
        )

    private fun hlsSourceFromUri(uri: String): MediaSource {
        // data: scheme → a DataSource.Factory that mints DataSchemeDataSource
        // instances, wrapped so non-data URIs in the playlist still resolve via
        // the default HTTP factory.
        val dataSchemeFactory = DataSource.Factory { DataSchemeDataSource() }
        val factory = DefaultMediaSourceFactory(context)
            .setDataSourceFactory(
                DefaultDataSource.Factory(context, dataSchemeFactory),
            )
        val mediaItem = MediaItem.Builder()
            .setUri(uri)
            .setMimeType(MimeTypes.APPLICATION_M3U8)
            .build()
        return factory.createMediaSource(mediaItem)
    }

    private fun plainHlsSource(url: String): MediaSource {
        val factory = DefaultMediaSourceFactory(context)
        val mediaItem = MediaItem.Builder()
            .setUri(url)
            .setMimeType(MimeTypes.APPLICATION_M3U8)
            .build()
        return factory.createMediaSource(mediaItem)
    }

    /** A plain HLS source for [url], used when ladder resolution fails. */
    fun fallbackSource(url: String): MediaSource = plainHlsSource(url)

    /** The resolved media source, plus the ladder renditions for the quality menu. */
    internal data class BuildResult(
        val mediaSource: MediaSource,
        /** Non-null only when a multi-rendition ladder was synthesized. */
        val renditions: List<com.livingpresence.mediakit.ProbedRendition>?,
    )
}

/** A user-facing label for a rendition tier (used in the quality menu). */
internal fun tierLabel(tier: RenditionTier): String = when (tier) {
    RenditionTier.P720 -> "720p"
    RenditionTier.P360 -> "360p"
    RenditionTier.P160 -> "160p"
    RenditionTier.AUDIO -> "Audio only"
}
