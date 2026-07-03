package com.livingpresence.mediakit

/**
 * Builds a spec-correct multivariant (master) playlist from the unadvertised
 * sibling renditions the Wowza server exposes per event. The server's own
 * master advertises only the 720p variant; this turns 720p/360p/160p/audio-only
 * into a real ABR ladder that ExoPlayer's `AdaptiveTrackSelection` adapts across.
 *
 * Output is consumed via a `data:` URI handed to `HlsMediaSource`
 * (Phase 3). Audio-only renditions are emitted as both a video variant (with no
 * `RESOLUTION`) and — when it is the sole audio source — referenced implicitly;
 * per Apple's ladder guidance the audio-only tier is the bottom of the ladder.
 */
public object LadderSynthesizer {

    /**
     * Pure synthesis: emit multivariant playlist text for [renditions].
     *
     * Renditions are sorted highest-bandwidth-first (per RFC 8216 §6.3.1
     * guidance that variants appear in decreasing bandwidth). Renditions with
     * missing/zero bandwidth are dropped, as are duplicate tiers. The result is
     * a deterministic string suitable for golden-file comparison.
     */
    public fun synthesize(renditions: List<ProbedRendition>): String {
        val uniqueByTier = renditions
            .filter { it.bandwidthBitsPerSecond > 0 && it.chunklistUri.isNotBlank() }
            .distinctBy { it.tier }
            .sortedByDescending { it.bandwidthBitsPerSecond }

        if (uniqueByTier.isEmpty()) return emptyMaster()

        return buildString {
            appendLine(HEADER)
            appendLine(VERSION)
            appendLine()
            for (rendition in uniqueByTier) {
                append(streamInfLine(rendition))
                appendLine(rendition.chunklistUri)
            }
        }
    }

    private fun streamInfLine(rendition: ProbedRendition): String {
        val attrs = buildList {
            add("BANDWIDTH=${rendition.bandwidthBitsPerSecond}")
            rendition.averageBandwidth()?.let { add("AVERAGE-BANDWIDTH=$it") }
            rendition.resolutionAttribute?.let { add("RESOLUTION=$it") }
            add("CODECS=\"${rendition.codecs}\"")
        }
        return "#EXT-X-STREAM-INF:${attrs.joinToString(",")}\n"
    }

    private fun ProbedRendition.averageBandwidth(): Int? =
        (bandwidthBitsPerSecond * 0.9).toInt().takeIf { it > 0 }

    private fun emptyMaster(): String =
        buildString {
            appendLine(HEADER)
            appendLine(VERSION)
        }

    private const val HEADER = "#EXTM3U"
    private const val VERSION = "#EXT-X-VERSION:6"
}
