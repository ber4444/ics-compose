package com.livingpresence.mediakit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaylistInspectorTest {

    @Test
    fun parseMaster_readsSingleVariantMasterLikeProductionServer() {
        // The production server advertises exactly one 720p variant.
        val master = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-STREAM-INF:BANDWIDTH=1000000,CODECS="avc1.42c01f,mp4a.40.2",RESOLUTION=1280x720
            chunklist_w123456_DVR.m3u8
        """.trimIndent()

        val variants = PlaylistInspector.parseMaster(master)

        assertEquals(1, variants.size)
        val v = variants.single()
        assertEquals("chunklist_w123456_DVR.m3u8", v.uri)
        assertEquals(1_000_000, v.bandwidthBitsPerSecond)
        assertEquals("1280x720", v.resolution)
        assertEquals("avc1.42c01f,mp4a.40.2", v.codecs)
        assertFalse(v.isIFrameOnly)
    }

    @Test
    fun parseMaster_parsesMultipleVariantsInOrder() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=1280x720,CODECS="avc1.42c01f,mp4a.40.2"
            high.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=500000,RESOLUTION=640x360,CODECS="avc1.4d401e,mp4a.40.2"
            mid.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=260000,RESOLUTION=284x160,CODECS="avc1.42c015,mp4a.40.2"
            low.m3u8
        """.trimIndent()

        val variants = PlaylistInspector.parseMaster(master)

        assertEquals(3, variants.size)
        assertEquals("high.m3u8", variants[0].uri)
        assertEquals(1_000_000, variants[0].bandwidthBitsPerSecond)
        assertEquals("mid.m3u8", variants[1].uri)
        assertEquals("low.m3u8", variants[2].uri)
    }

    @Test
    fun parseMaster_handlesIframeStreamInf() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=1280x720,CODECS="avc1.42c01f"
            high.m3u8
            #EXT-X-I-FRAME-STREAM-INF:BANDWIDTH=80000,URI="iframes.m3u8",RESOLUTION=1280x720
        """.trimIndent()

        val variants = PlaylistInspector.parseMaster(master)

        assertEquals(2, variants.size)
        assertFalse(variants[0].isIFrameOnly)
        assertTrue(variants[1].isIFrameOnly)
        assertEquals("iframes.m3u8", variants[1].uri)
    }

    @Test
    fun parseMaster_emptyPlaylistReturnsEmpty() {
        assertEquals(emptyList<PlaylistInspector.Variant>(), PlaylistInspector.parseMaster("#EXTM3U"))
    }

    @Test
    fun parseMediaPlaylist_boundedPlaylistIsNotLive() {
        // A completed Wowza nDVR recording ends with #EXT-X-ENDLIST → VOD.
        val chunklist = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:2
            #EXT-X-MEDIA-SEQUENCE:0
            #EXTINF:2.0,
            segment_0.ts
            #EXTINF:2.0,
            segment_1.ts
            #EXTINF:2.0,
            segment_2.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        val media = PlaylistInspector.parseMediaPlaylist(chunklist)

        assertFalse(media.isLive)
        assertEquals(2, media.targetDurationSeconds)
        assertEquals(3, media.segmentCount)
        assertEquals(6.0, media.durationSeconds)
    }

    @Test
    fun parseMediaPlaylist_missingEndListMeansLive() {
        // During a live window ENDLIST is absent → that *is* the live signal.
        val chunklist = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:2
            #EXT-X-MEDIA-SEQUENCE:1670
            #EXTINF:2.0,
            segment_1670.ts
            #EXTINF:2.0,
            segment_1671.ts
        """.trimIndent()

        val media = PlaylistInspector.parseMediaPlaylist(chunklist)

        assertTrue(media.isLive)
        assertEquals(4.0, media.durationSeconds)
        assertEquals(2, media.segmentCount)
    }

    @Test
    fun parseMediaPlaylist_handlesFractionalExtinfDurations() {
        val chunklist = """
            #EXTM3U
            #EXT-X-TARGETDURATION:4
            #EXTINF:3.5,
            a.ts
            #EXTINF:4.0,
            b.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        val media = PlaylistInspector.parseMediaPlaylist(chunklist)

        assertEquals(7.5, media.durationSeconds)
        assertEquals(4, media.targetDurationSeconds)
    }

    @Test
    fun parseMaster_averageBandwidthAndFrameRateParsedWhenPresent() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=1000000,AVERAGE-BANDWIDTH=900000,FRAME-RATE=30.0,RESOLUTION=1280x720,CODECS="avc1.42c01f"
            high.m3u8
        """.trimIndent()

        val v = PlaylistInspector.parseMaster(master).single()

        assertEquals(900_000, v.averageBandwidthBitsPerSecond)
        assertEquals(30.0, v.frameRate)
    }

    @Test
    fun parseMaster_missingOptionalAttributesYieldNulls() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=51000,CODECS="mp4a.40.2"
            audio.m3u8
        """.trimIndent()

        val v = PlaylistInspector.parseMaster(master).single()

        assertNull(v.resolution)
        assertNull(v.frameRate)
        assertNull(v.averageBandwidthBitsPerSecond)
    }

    // --- Real production excerpts (captured 2026-07-03 during a live event on
    // the Wowza server, event10 was live / event14 had already ended) ---

    @Test
    fun parseMediaPlaylist_liveExcerptFromProductionServerHasNoEndList() {
        // event10 chunklist mid-broadcast: MEDIA-SEQUENCE stuck at 0 while the
        // segment count kept growing (706 -> 767 across a 60s re-fetch) — a
        // growing, not sliding, DVR window. No #EXT-X-ENDLIST anywhere.
        val chunklist = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:4
            #EXT-X-MEDIA-SEQUENCE:0
            #EXTINF:2.002,
            media_w227929621_DVR_0.ts
            #EXTINF:2.002,
            media_w227929621_DVR_1.ts
            #EXTINF:2.002,
            media_w227929621_DVR_2.ts
            #EXTINF:2.002,
            media_w227929621_DVR_764.ts
            #EXTINF:2.002,
            media_w227929621_DVR_765.ts
            #EXTINF:2.002,
            media_w227929621_DVR_766.ts
        """.trimIndent()

        val media = PlaylistInspector.parseMediaPlaylist(chunklist)

        assertTrue(media.isLive)
        assertEquals(4, media.targetDurationSeconds)
        assertEquals(6, media.segmentCount)
    }

    @Test
    fun parseMediaPlaylist_boundedExcerptFromProductionServerHasEndList() {
        // event14 chunklist after the broadcast ended: 1,675 segments totalling
        // ~58 minutes, terminated by #EXT-X-ENDLIST.
        val chunklist = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:4
            #EXT-X-MEDIA-SEQUENCE:0
            #EXTINF:2.002,
            media_w1679678732_DVR_0.ts
            #EXTINF:2.002,
            media_w1679678732_DVR_1.ts
            #EXTINF:2.002,
            media_w1679678732_DVR_1673.ts
            #EXTINF:2.002,
            media_w1679678732_DVR_1674.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        val media = PlaylistInspector.parseMediaPlaylist(chunklist)

        assertFalse(media.isLive)
        assertEquals(4, media.segmentCount)
        assertEquals(8.008, media.durationSeconds)
    }

    @Test
    fun parseMediaPlaylist_malformedInputDoesNotThrow() {
        val results = listOf(
            "",
            "not an m3u8 at all",
            "#EXTM3U\n#EXT-X-TARGETDURATION:not-a-number\n#EXTINF:not-a-number,\nsegment.ts",
            "\n\n\n",
        ).map { PlaylistInspector.parseMediaPlaylist(it) }

        for (media in results) {
            // Absence of ENDLIST in garbage input still reads as "live" per the
            // documented signal — no exception, sensible zeroed-out fallbacks.
            assertTrue(media.isLive)
            assertEquals(0, media.targetDurationSeconds)
        }
    }

    @Test
    fun parseMaster_malformedInputDoesNotThrow() {
        assertEquals(emptyList<PlaylistInspector.Variant>(), PlaylistInspector.parseMaster(""))
        assertEquals(emptyList<PlaylistInspector.Variant>(), PlaylistInspector.parseMaster("garbage\nmore garbage"))
    }
}
