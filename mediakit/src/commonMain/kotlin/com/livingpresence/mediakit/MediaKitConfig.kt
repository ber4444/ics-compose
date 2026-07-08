package com.livingpresence.mediakit

/**
 * Configuration for the playback SDK. The base host is injectable so the
 * hardcoded production server is no longer sprinkled through app code and the
 * URL builder is unit-testable.
 *
 * @param baseStreamUrl  Absolute base that ends in `/live/event`, e.g.
 *   `https://host:443/live/event`. The event number and rendition suffix are
 *   appended, then `/playlist.m3u8?DVR`.
 * @param maxEventNumber The largest event number to probe (inclusive). The
 *   production server currently serves events 1..20.
 */
public data class MediaKitConfig(
    public val baseStreamUrl: String,
    public val maxEventNumber: Int = DEFAULT_MAX_EVENT_NUMBER,
) {
    public constructor(
        host: String,
    ) : this(
        baseStreamUrl = "$host/live/event",
        maxEventNumber = DEFAULT_MAX_EVENT_NUMBER,
    )

    /** The host portion (scheme + authority), without a trailing slash. */
    public val host: String
        get() = baseStreamUrl.substringBefore("/live/event")

    /**
     * The playlist URL for [eventNumber] at rendition [tier]. `DVR` query keeps
     * the Wowza nDVR window for seekable live/recorded playback.
     */
    public fun renditionUrl(eventNumber: Int, tier: RenditionTier): String =
        "$baseStreamUrl${eventNumber}${tier.urlSuffix}/playlist.m3u8?DVR"

    /** Convenience: the base (720p) playlist URL for [eventNumber]. */
    public fun eventUrl(eventNumber: Int): String =
        renditionUrl(eventNumber, RenditionTier.P720)

    public companion object {
        /** The production Wowza server (verified 2026-07-03). */
        public const val DEFAULT_HOST: String = "https://65e54f30ec73c.streamlock.net:443"

        /** Events 1..20 are served by the production server. */
        public const val DEFAULT_MAX_EVENT_NUMBER: Int = 20

        /** A [MediaKitConfig] pointed at the production server. */
        public val Default: MediaKitConfig = MediaKitConfig(DEFAULT_HOST)
    }
}
// CodeQL trigger
