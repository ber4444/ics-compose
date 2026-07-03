package com.livingpresence.mediakit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LadderSynthesizerTest {

    // The four measured production renditions for event14 (chunklist URIs
    // rotate; these are representative).
    private val p720 = ProbedRendition(
        tier = RenditionTier.P720,
        bandwidthBitsPerSecond = 1_000_000,
        width = 1280,
        height = 720,
        codecs = "avc1.42c01f,mp4a.40.2",
        chunklistUri = "https://host/live/event14/chunklist_w1_DVR.m3u8",
    )
    private val p360 = ProbedRendition(
        tier = RenditionTier.P360,
        bandwidthBitsPerSecond = 507_000,
        width = 640,
        height = 360,
        codecs = "avc1.4d401e,mp4a.40.2",
        chunklistUri = "https://host/live/event14_360p/chunklist_w2_DVR.m3u8",
    )
    private val p160 = ProbedRendition(
        tier = RenditionTier.P160,
        bandwidthBitsPerSecond = 262_000,
        width = 284,
        height = 160,
        codecs = "avc1.42c015,mp4a.40.2",
        chunklistUri = "https://host/live/event14_160p/chunklist_w3_DVR.m3u8",
    )
    private val audio = ProbedRendition(
        tier = RenditionTier.AUDIO,
        bandwidthBitsPerSecond = 51_000,
        width = 0,
        height = 0,
        codecs = "mp4a.40.2",
        chunklistUri = "https://host/live/event14_aac/chunklist_w4_DVR.m3u8",
    )

    @Test
    fun synthesize_emitsSpecCorrectMultivariantPlaylist() {
        val playlist = LadderSynthesizer.synthesize(listOf(p720, p360, p160, audio))

        // Golden file: deterministic header, version 6, four variants sorted by
        // descending bandwidth, each with absolute chunklist URI on its own line.
        val expected = """
            #EXTM3U
            #EXT-X-VERSION:6

            #EXT-X-STREAM-INF:BANDWIDTH=1000000,AVERAGE-BANDWIDTH=900000,RESOLUTION=1280x720,CODECS="avc1.42c01f,mp4a.40.2"
            https://host/live/event14/chunklist_w1_DVR.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=507000,AVERAGE-BANDWIDTH=456300,RESOLUTION=640x360,CODECS="avc1.4d401e,mp4a.40.2"
            https://host/live/event14_360p/chunklist_w2_DVR.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=262000,AVERAGE-BANDWIDTH=235800,RESOLUTION=284x160,CODECS="avc1.42c015,mp4a.40.2"
            https://host/live/event14_160p/chunklist_w3_DVR.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=51000,AVERAGE-BANDWIDTH=45900,CODECS="mp4a.40.2"
            https://host/live/event14_aac/chunklist_w4_DVR.m3u8
        """.trimIndent() + "\n"

        assertEquals(expected, playlist)
    }

    @Test
    fun synthesize_sortsByDescendingBandwidthRegardlessOfInputOrder() {
        val playlist = LadderSynthesizer.synthesize(listOf(p160, p720, audio, p360))

        val bandwidths = PlaylistInspector.parseMaster(playlist)
            .map { it.bandwidthBitsPerSecond }

        assertEquals(bandwidths.sortedDescending(), bandwidths)
    }

    @Test
    fun synthesize_dropsRenditionsWithZeroOrMissingBandwidth() {
        val broken = p160.copy(bandwidthBitsPerSecond = 0)
        val noUri = p360.copy(chunklistUri = "")

        val playlist = LadderSynthesizer.synthesize(listOf(p720, broken, noUri, audio))
        val variants = PlaylistInspector.parseMaster(playlist)

        // Only p720 and audio survive.
        assertEquals(2, variants.size)
        assertEquals(1_000_000, variants[0].bandwidthBitsPerSecond)
        assertEquals(51_000, variants[1].bandwidthBitsPerSecond)
    }

    @Test
    fun synthesize_dropsDuplicateTiers() {
        val duplicate = p720.copy(chunklistUri = "https://host/other.m3u8")

        val playlist = LadderSynthesizer.synthesize(listOf(p720, duplicate, p360))
        val variants = PlaylistInspector.parseMaster(playlist)

        assertEquals(2, variants.size)
    }

    @Test
    fun synthesize_emptyInputProducesValidEmptyMaster() {
        val playlist = LadderSynthesizer.synthesize(emptyList())

        assertTrue(playlist.startsWith("#EXTM3U"))
        assertTrue(playlist.contains("#EXT-X-VERSION:6"))
        // No STREAM-INF lines.
        assertEquals(0, PlaylistInspector.parseMaster(playlist).size)
    }

    @Test
    fun synthesize_audioOnlyTierHasNoResolutionAttribute() {
        val playlist = LadderSynthesizer.synthesize(listOf(p720, audio))
        val variants = PlaylistInspector.parseMaster(playlist)

        val audioVariant = variants.last()
        assertEquals(null, audioVariant.resolution)
        assertEquals("mp4a.40.2", audioVariant.codecs)
    }

    @Test
    fun synthesize_outputIsRoundTrippableByInspector() {
        val playlist = LadderSynthesizer.synthesize(listOf(p720, p360, p160, audio))

        // The synthesized master must parse back into the same rendition count
        // with the expected attributes — this is the contract ExoPlayer relies
        // on when consuming the data: URI.
        val variants = PlaylistInspector.parseMaster(playlist)
        assertEquals(4, variants.size)
        variants.forEach { v ->
            assertTrue(v.uri.startsWith("https://"))
            assertTrue(v.bandwidthBitsPerSecond > 0)
        }
    }
}
