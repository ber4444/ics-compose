package com.livingpresence.mediakit

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Probes an event's sibling renditions just-in-time and resolves a synthesized
 * multivariant (ABR ladder) playlist for them.
 *
 * The production server advertises only one 720p variant in its master playlist,
 * but four segment-aligned siblings exist (`_360p`, `_160p`, `_aac`). This probes
 * each tier's playlist in parallel, extracts its measured attributes + the
 * fresh (rotating) chunklist URI, and hands them to [LadderSynthesizer]. The
 * result is a real ABR ladder the player adapts across.
 *
 * Per [plan.md] Ground truth: chunklist `w`-tokens rotate, so resolution happens
 * at playback start. Renditions that 404 are omitted (an event may degrade to
 * fewer tiers).
 */
public class LadderResolver(
    private val httpClient: HttpClient,
    private val config: MediaKitConfig = MediaKitConfig.Default,
) {
    /**
     * Resolve the ladder for [eventNumber], returning the multivariant playlist
     * text and the list of renditions that were successfully probed. Returns
     * null if no rendition could be probed (caller falls back to the base URL).
     */
    public suspend fun resolve(eventNumber: Int): ResolvedLadder? {
        val renditions = probeRenditions(eventNumber)
        if (renditions.isEmpty()) return null
        return ResolvedLadderBuilder.build(renditions)
    }

    /** Probe all rendition tiers in parallel; 404s/parse failures are dropped. */
    public suspend fun probeRenditions(eventNumber: Int): List<ProbedRendition> =
        coroutineScope {
            RenditionTier.entries
                .map { tier -> async { probeTier(eventNumber, tier) } }
                .awaitAll()
                .filterNotNull()
        }

    private suspend fun probeTier(
        eventNumber: Int,
        tier: RenditionTier,
    ): ProbedRendition? {
        val masterUrl = config.renditionUrl(eventNumber, tier)
        val response = runCatching { httpClient.get(masterUrl) }.getOrNull() ?: return null
        if (!response.status.isSuccess()) return null
        val masterText = runCatching { response.bodyAsText() }.getOrNull() ?: return null

        val variants = PlaylistInspector.parseMaster(masterText)
        val primary = variants.firstOrNull { !it.isIFrameOnly } ?: return null

        // Resolve the chunklist URI against the master's base URL (it may be
        // relative). Wowza emits relative names like chunklist_w<TOKEN>_DVR.m3u8.
        val resolvedChunklist = resolveUri(masterUrl, primary.uri)

        return ProbedRendition(
            tier = tier,
            bandwidthBitsPerSecond = primary.bandwidthBitsPerSecond.takeIf { it > 0 }
                ?: defaultBandwidth(tier),
            width = parseWidth(primary.resolution, tier),
            height = parseHeight(primary.resolution, tier),
            codecs = primary.codecs ?: defaultCodecs(tier),
            chunklistUri = resolvedChunklist,
        )
    }

    /** Resolve a possibly-relative [reference] URI against [base]. */
    private fun resolveUri(base: String, reference: String): String {
        if (reference.startsWith("http://") || reference.startsWith("https://")) return reference
        val baseDir = base.substringBeforeLast('/', "")
        return "$baseDir/$reference"
    }

    private fun defaultBandwidth(tier: RenditionTier): Int = when (tier) {
        RenditionTier.P720 -> 1_000_000
        RenditionTier.P360 -> 507_000
        RenditionTier.P160 -> 262_000
        RenditionTier.AUDIO -> 51_000
    }

    private fun defaultCodecs(tier: RenditionTier): String = when (tier) {
        RenditionTier.AUDIO -> "mp4a.40.2"
        else -> "avc1.42c01f,mp4a.40.2"
    }

    private fun parseWidth(resolution: String?, tier: RenditionTier): Int {
        if (tier == RenditionTier.AUDIO) return 0
        return resolution?.substringBefore('x')?.toIntOrNull() ?: when (tier) {
            RenditionTier.P720 -> 1280
            RenditionTier.P360 -> 640
            RenditionTier.P160 -> 284
            RenditionTier.AUDIO -> 0
        }
    }

    private fun parseHeight(resolution: String?, tier: RenditionTier): Int {
        if (tier == RenditionTier.AUDIO) return 0
        return resolution?.substringAfter('x')?.toIntOrNull() ?: when (tier) {
            RenditionTier.P720 -> 720
            RenditionTier.P360 -> 360
            RenditionTier.P160 -> 160
            RenditionTier.AUDIO -> 0
        }
    }
}

/** The synthesized ladder plus the renditions it was built from. */
public data class ResolvedLadder(
    public val masterPlaylistText: String,
    public val renditions: List<ProbedRendition>,
)

private object ResolvedLadderBuilder {
    fun build(renditions: List<ProbedRendition>): ResolvedLadder =
        ResolvedLadder(
            masterPlaylistText = LadderSynthesizer.synthesize(renditions),
            renditions = renditions.sortedByDescending { it.bandwidthBitsPerSecond },
        )
}
