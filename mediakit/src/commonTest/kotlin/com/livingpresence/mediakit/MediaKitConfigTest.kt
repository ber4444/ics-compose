package com.livingpresence.mediakit

import kotlin.test.Test
import kotlin.test.assertEquals

class MediaKitConfigTest {

    private val config = MediaKitConfig(host = "https://example.test:443")

    @Test
    fun eventUrl_buildsBaseRenditionUrl() {
        assertEquals(
            "https://example.test:443/live/event7/playlist.m3u8?DVR",
            config.eventUrl(7),
        )
    }

    @Test
    fun renditionUrl_appendsSuffixPerTier() {
        assertEquals(
            "https://example.test:443/live/event3/playlist.m3u8?DVR",
            config.renditionUrl(3, RenditionTier.P720),
        )
        assertEquals(
            "https://example.test:443/live/event3_360p/playlist.m3u8?DVR",
            config.renditionUrl(3, RenditionTier.P360),
        )
        assertEquals(
            "https://example.test:443/live/event3_160p/playlist.m3u8?DVR",
            config.renditionUrl(3, RenditionTier.P160),
        )
        assertEquals(
            "https://example.test:443/live/event3_aac/playlist.m3u8?DVR",
            config.renditionUrl(3, RenditionTier.AUDIO),
        )
    }

    @Test
    fun host_extractsSchemeAndAuthority() {
        assertEquals("https://example.test:443", config.host)
    }

    @Test
    fun defaultConfig_pointsAtProductionServer() {
        assertEquals("https://65e54f30ec73c.streamlock.net:443", MediaKitConfig.Default.host)
        assertEquals(20, MediaKitConfig.Default.maxEventNumber)
    }
}
