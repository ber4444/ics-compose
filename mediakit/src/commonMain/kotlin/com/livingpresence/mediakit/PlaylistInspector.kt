package com.livingpresence.mediakit

/**
 * Pure-Kotlin HLS (M3U8) tag parser. No I/O — operates on playlist text so it is
 * unit-testable in commonTest. Handles the subset the production Wowza server
 * emits plus the multivariant playlists [LadderSynthesizer] builds.
 *
 * Spec reference: RFC 8216.
 */
public object PlaylistInspector {

    /** One variant advertised in a multivariant (master) playlist. */
    public data class Variant(
        public val uri: String,
        public val bandwidthBitsPerSecond: Int,
        public val averageBandwidthBitsPerSecond: Int?,
        public val resolution: String?,
        public val codecs: String?,
        public val frameRate: Double?,
        public val isIFrameOnly: Boolean,
    )

    /** A parsed media (chunklist) playlist. */
    public data class MediaPlaylist(
        public val isLive: Boolean,
        public val durationSeconds: Double,
        public val targetDurationSeconds: Int,
        public val segmentCount: Int,
        public val segmentUris: List<String>,
    )

    /**
     * Parse a multivariant (master) playlist into its variants.
     *
     * Recognizes `#EXT-X-STREAM-INF` (regular variants) and
     * `#EXT-X-I-FRAME-STREAM-INF` (I-frame-only variants). The URI of a regular
     * variant is the line following its `#EXT-X-STREAM-INF` tag; an I-frame
     * variant carries its URI inline in the `URI=` attribute.
     */
    public fun parseMaster(playlistText: String): List<Variant> {
        val variants = mutableListOf<Variant>()
        val lines = playlistText.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith(TAG_STREAM_INF)) {
                val attrs = parseAttributes(line.substringAfter(TAG_STREAM_INF))
                val uri = if (i + 1 < lines.size) lines[i + 1].trim() else ""
                variants += Variant(
                    uri = uri,
                    bandwidthBitsPerSecond = attrs["BANDWIDTH"]?.toIntOrNull() ?: 0,
                    averageBandwidthBitsPerSecond = attrs["AVERAGE-BANDWIDTH"]?.toIntOrNull(),
                    resolution = attrs["RESOLUTION"],
                    codecs = attrs["CODECS"]?.let(::unquote),
                    frameRate = attrs["FRAME-RATE"]?.toDoubleOrNull(),
                    isIFrameOnly = false,
                )
                i += 2
                continue
            }
            if (line.startsWith(TAG_I_FRAME_STREAM_INF)) {
                val attrs = parseAttributes(line.substringAfter(TAG_I_FRAME_STREAM_INF))
                variants += Variant(
                    uri = attrs["URI"]?.let(::unquote) ?: "",
                    bandwidthBitsPerSecond = attrs["BANDWIDTH"]?.toIntOrNull() ?: 0,
                    averageBandwidthBitsPerSecond = attrs["AVERAGE-BANDWIDTH"]?.toIntOrNull(),
                    resolution = attrs["RESOLUTION"],
                    codecs = attrs["CODECS"]?.let(::unquote),
                    frameRate = attrs["FRAME-RATE"]?.toDoubleOrNull(),
                    isIFrameOnly = true,
                )
            }
            i += 1
        }
        return variants
    }

    /**
     * Parse a media (chunklist) playlist.
     *
     * - [MediaPlaylist.isLive] is `true` when `#EXT-X-ENDLIST` is **absent**
     *   (the sliding-window / ongoing live signal per RFC 8216 §6.2.1). When
     *   present the playlist is bounded → effectively VOD → downloadable.
     * - [MediaPlaylist.durationSeconds] is the sum of all `#EXTINF` durations.
     * - [MediaPlaylist.targetDurationSeconds] comes from `#EXT-X-TARGETDURATION`.
     */
    public fun parseMediaPlaylist(playlistText: String): MediaPlaylist {
        val lines = playlistText.lines()
        var hasEndList = false
        var targetDuration = 0
        var totalDuration = 0.0
        var segmentCount = 0
        val segmentUris = mutableListOf<String>()

        for (raw in lines) {
            val line = raw.trim()
            when {
                line.startsWith(TAG_ENDLIST) -> hasEndList = true
                line.startsWith(TAG_TARGET_DURATION) -> {
                    targetDuration = line.substringAfter(TAG_TARGET_DURATION)
                        .substringBefore(',')
                        .trim()
                        .toIntOrNull() ?: 0
                }
                line.startsWith(TAG_EXTINF) -> {
                    val durationStr = line.substringAfter(TAG_EXTINF)
                        .substringBefore(',')
                        .trim()
                    totalDuration += durationStr.toDoubleOrNull() ?: 0.0
                    segmentCount += 1
                }
                line.isNotEmpty() && !line.startsWith("#") -> {
                    segmentUris.add(line)
                }
            }
        }

        return MediaPlaylist(
            isLive = !hasEndList,
            durationSeconds = totalDuration,
            targetDurationSeconds = targetDuration,
            segmentCount = segmentCount,
            segmentUris = segmentUris,
        )
    }

    /**
     * Parse a comma-separated attribute list into a map, honoring quoted values.
     * e.g. `BANDWIDTH=1000,CODECS="avc1,mp4a",RESOLUTION=1280x720`.
     */
    private fun parseAttributes(text: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val key = buildString {
                while (i < text.length && text[i] != '=') {
                    append(text[i]); i += 1
                }
            }
            if (i >= text.length) break
            i += 1 // skip '='
            sb.clear()
            if (i < text.length && text[i] == '"') {
                i += 1 // skip opening quote
                while (i < text.length && text[i] != '"') {
                    sb.append(text[i]); i += 1
                }
                if (i < text.length) i += 1 // skip closing quote
            } else {
                while (i < text.length && text[i] != ',') {
                    sb.append(text[i]); i += 1
                }
            }
            if (i < text.length && text[i] == ',') i += 1
            if (key.isNotEmpty()) result[key] = sb.toString()
        }
        return result
    }

    private fun unquote(value: String): String =
        value.removeSurrounding("\"")

    private const val TAG_STREAM_INF = "#EXT-X-STREAM-INF:"
    private const val TAG_I_FRAME_STREAM_INF = "#EXT-X-I-FRAME-STREAM-INF:"
    private const val TAG_ENDLIST = "#EXT-X-ENDLIST"
    private const val TAG_TARGET_DURATION = "#EXT-X-TARGETDURATION:"
    private const val TAG_EXTINF = "#EXTINF:"
}
