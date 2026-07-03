package com.livingpresence.mediakit

/**
 * The unadvertised sibling renditions the Wowza server exposes per event, keyed
 * by the URL suffix appended to `live/event{i}`. Measured 2026-07-03 against the
 * production server:
 *
 * | tier   | suffix  | resolution | bandwidth | codecs             |
 * |--------|---------|------------|-----------|--------------------|
 * | P720   | *(none)*| 1280×720   | ~1.0 Mbps | avc1.42c01f + mp4a |
 * | P360   | `_360p` | 640×360    | ~507 kbps | avc1.4d401e + mp4a |
 * | P160   | `_160p` | 284×160    | ~262 kbps | avc1.42c015 + mp4a |
 * | AUDIO  | `_aac`  | audio-only | ~51 kbps  | mp4a.40.2          |
 *
 * The base master playlist advertises only P720; [LadderSynthesizer] turns the
 * four siblings into a real ABR ladder client-side.
 */
public enum class RenditionTier(public val urlSuffix: String) {
    P720(""),
    P360("_360p"),
    P160("_160p"),
    AUDIO("_aac"),
}

/**
 * One event's catalog entry. [isLive] is derived from playlist inspection
 * (absence of `#EXT-X-ENDLIST`); when `false` the playlist is bounded and the
 * event is downloadable.
 */
public data class EventInfo(
    public val eventNumber: Int,
    public val isLive: Boolean,
    public val durationMs: Long,
)

/**
 * A rendition that has been probed from the server: its resolved chunklist URI
 * (rotating `w`-tokens mean this is captured just-in-time) plus the attributes
 * needed to emit a multivariant playlist.
 */
public data class ProbedRendition(
    public val tier: RenditionTier,
    public val bandwidthBitsPerSecond: Int,
    public val width: Int,
    public val height: Int,
    public val codecs: String,
    public val chunklistUri: String,
) {
    /** `true` for the audio-only `_aac` tier. */
    public val isAudioOnly: Boolean get() = tier == RenditionTier.AUDIO

    /** Resolution string for `RESOLUTION=` attribute, or null for audio-only. */
    public val resolutionAttribute: String?
        get() = if (isAudioOnly) null else "${width}x${height}"
}
