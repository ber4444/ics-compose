package com.livingpresence.mediakit

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EventCatalogTest {

    private val config = MediaKitConfig(host = "https://test.local").copy(maxEventNumber = 3)

    private fun boundedChunklist(seconds: Int, live: Boolean): String {
        val segments = (0 until seconds step 2).joinToString("\n") {
            "#EXTINF:2.0,\nsegment_$it.ts"
        }
        val endList = if (live) "" else "#EXT-X-ENDLIST\n"
        return "#EXTM3U\n#EXT-X-TARGETDURATION:2\n$segments\n$endList".trimEnd() + "\n"
    }

    private fun catalog(
        config: MediaKitConfig = this.config,
        responder: suspend (String) -> Pair<HttpStatusCode, String>,
    ): EventCatalog {
        val engine = MockEngine { request ->
            val (status, body) = responder(request.url.toString())
            respond(
                content = body,
                status = status,
                headers = headersOf(HttpHeaders.ContentType, "application/vnd.apple.mpegurl"),
            )
        }
        val client = HttpClient(engine)
        return EventCatalog(client, config)
    }

    @Test
    fun loadEvents_excludes404s() = runTest {
        val catalog = catalog { url ->
            when {
                url.contains("event1") && url.contains("/playlist.m3u8") ->
                    HttpStatusCode.NotFound to "404"
                url.contains("event2") && url.contains("/playlist.m3u8") ->
                    HttpStatusCode.OK to boundedChunklist(10, live = false)
                url.contains("event3") && url.contains("/playlist.m3u8") ->
                    HttpStatusCode.OK to boundedChunklist(6, live = false)
                else -> HttpStatusCode.NotFound to "404"
            }
        }

        val events = catalog.loadEvents()

        // event1 404s → excluded. Newest-first ordering.
        assertEquals(listOf(3, 2), events.map { it.eventNumber })
    }

    @Test
    fun loadEvents_returnsLiveAndDurationFromPlaylist() = runTest {
        val catalog = catalog { url ->
            when {
                url.contains("event1") -> HttpStatusCode.OK to boundedChunklist(4, live = true)
                url.contains("event2") -> HttpStatusCode.OK to boundedChunklist(10, live = false)
                url.contains("event3") -> HttpStatusCode.OK to boundedChunklist(6, live = false)
                else -> HttpStatusCode.NotFound to "404"
            }
        }

        val events = catalog.loadEvents().associateBy { it.eventNumber }

        // event1: no ENDLIST → live; 2 segments × 2s = 4s = 4000ms.
        assertTrue(events.getValue(1).isLive)
        assertEquals(4_000L, events.getValue(1).durationMs)
        // event2: ENDLIST → VOD; 5 segments × 2s = 10s.
        assertTrue(!events.getValue(2).isLive)
        assertEquals(10_000L, events.getValue(2).durationMs)
    }

    @Test
    fun loadEvents_probesAllConfiguredEventsInParallel() = runTest {
        // All three exist → all three returned, newest first.
        val catalog = catalog { url ->
            when {
                url.contains("event1") -> HttpStatusCode.OK to boundedChunklist(4, live = false)
                url.contains("event2") -> HttpStatusCode.OK to boundedChunklist(6, live = false)
                url.contains("event3") -> HttpStatusCode.OK to boundedChunklist(8, live = false)
                else -> HttpStatusCode.NotFound to "404"
            }
        }

        val events = catalog.loadEvents()

        assertEquals(listOf(3, 2, 1), events.map { it.eventNumber })
    }

    @Test
    fun loadEvents_returnsEmptyWhenAllMissing() = runTest {
        val catalog = catalog { _ -> HttpStatusCode.NotFound to "404" }

        assertEquals(emptyList(), catalog.loadEvents())
    }

    @Test
    fun probeEvent_returnsNullOnTransportFailure() = runTest {
        // A thrown exception (network failure) is caught as a transport error
        // → null → no tile. Uses a stdlib Throwable so the test is KMP-common.
        val engine = MockEngine { _ -> throw IllegalStateException("network down") }
        val catalog = EventCatalog(HttpClient(engine), config)

        assertNull(catalog.probeEvent(1))
    }

    @Test
    fun probeEvent_returnsNullOn5xx() = runTest {
        val catalog = catalog { _ -> HttpStatusCode.InternalServerError to "boom" }

        assertNull(catalog.probeEvent(2))
    }

    @Test
    fun probeEvent_isLivePropagatesFromRealProductionLiveExcerpt() = runTest {
        // Real event10 excerpt captured 2026-07-03 while it was on-air: no
        // #EXT-X-ENDLIST, MEDIA-SEQUENCE 0 with a growing segment count.
        val liveExcerpt = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:4
            #EXT-X-MEDIA-SEQUENCE:0
            #EXTINF:2.002,
            media_w227929621_DVR_0.ts
            #EXTINF:2.002,
            media_w227929621_DVR_1.ts
        """.trimIndent()
        val catalog = catalog(config = config.copy(maxEventNumber = 1)) { _ ->
            HttpStatusCode.OK to liveExcerpt
        }

        val event = assertNotNull(catalog.probeEvent(1))

        assertTrue(event.isLive)
        assertEquals(4_003L, event.durationMs)
    }

    @Test
    fun probeEvent_isLivePropagatesFromRealProductionBoundedExcerpt() = runTest {
        // Real event14 excerpt captured 2026-07-03 after the broadcast ended.
        val boundedExcerpt = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:4
            #EXT-X-MEDIA-SEQUENCE:0
            #EXTINF:2.002,
            media_w1679678732_DVR_0.ts
            #EXTINF:2.002,
            media_w1679678732_DVR_1.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        val catalog = catalog(config = config.copy(maxEventNumber = 1)) { _ ->
            HttpStatusCode.OK to boundedExcerpt
        }

        val event = assertNotNull(catalog.probeEvent(1))

        assertTrue(!event.isLive)
        assertEquals(4_003L, event.durationMs)
    }

    @Test
    fun probeEvent_returnsNullWhenClientThrowsOn4xx() = runTest {
        // A client configured with expectSuccess throws ClientRequestException on
        // 404; EventCatalog treats that as a transport failure (null) — still the
        // correct "no tile" outcome.
        val engine = MockEngine { _ ->
            respond(
                content = "404",
                status = HttpStatusCode.NotFound,
                headers = headersOf(HttpHeaders.ContentType, "text/plain"),
            )
        }
        val client = HttpClient(engine) { expectSuccess = true }
        val catalog = EventCatalog(client, config)

        assertNull(catalog.probeEvent(1))
    }
}
