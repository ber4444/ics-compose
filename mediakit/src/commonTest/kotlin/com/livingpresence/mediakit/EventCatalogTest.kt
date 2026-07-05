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
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlin.time.TimeSource

class EventCatalogTest {

    private val config = MediaKitConfig(host = "https://test.local").copy(maxEventNumber = 3)

    private fun boundedChunklist(seconds: Int, live: Boolean): String {
        val segments = (0 until seconds step 2).joinToString("\n") {
            "#EXTINF:2.0,\nsegment_$it.ts"
        }
        val endList = if (live) "" else "#EXT-X-ENDLIST\n"
        return "#EXTM3U\n#EXT-X-TARGETDURATION:2\n$segments\n$endList".trimEnd() + "\n"
    }

    /**
     * Build a catalog backed by a counting mock so tests can assert cache hit /
     * miss and retry behavior. Defaults to an infinite TTL so that the original
     * tests (which call [EventCatalog.loadEvents] once) are unaffected.
     */
    private fun catalog(
        config: MediaKitConfig = this.config,
        timeSource: TimeSource = TimeSource.Monotonic,
        cacheTtl: Duration = Duration.INFINITE,
        maxProbeAttempts: Int = 2,
        retryBackoff: Duration = Duration.ZERO,
        responder: suspend (url: String) -> Pair<HttpStatusCode, String>,
    ): Pair<EventCatalog, MockEngine> {
        val engine = MockEngine { request ->
            val (status, body) = responder(request.url.toString())
            respond(
                content = body,
                status = status,
                headers = headersOf(HttpHeaders.ContentType, "application/vnd.apple.mpegurl"),
            )
        }
        val client = HttpClient(engine)
        val catalog = EventCatalog(
            httpClient = client,
            config = config,
            cacheTtl = cacheTtl,
            timeSource = timeSource,
            maxProbeAttempts = maxProbeAttempts,
            retryBackoff = retryBackoff,
        )
        return catalog to engine
    }

    // ---- Existing behavior (must keep passing) ------------------------------

    @Test
    fun loadEvents_excludes404s() = runTest {
        val (catalog, _) = catalog { url ->
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
        val (catalog, _) = catalog { url ->
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
        val (catalog, _) = catalog { url ->
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
        val (catalog, _) = catalog { _ -> HttpStatusCode.NotFound to "404" }

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
        val (catalog, _) = catalog { _ -> HttpStatusCode.InternalServerError to "boom" }

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
        val (catalog, _) = catalog(config = config.copy(maxEventNumber = 1)) { _ ->
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
        val (catalog, _) = catalog(config = config.copy(maxEventNumber = 1)) { _ ->
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

    // ---- FU-3: TTL cache -----------------------------------------------------

    @Test
    fun loadEvents_returnsCacheWithinTtl_withoutReprobing() = runTest {
        val (catalog, engine) = catalog(cacheTtl = 60.seconds) { url ->
            when {
                url.contains("event1") -> HttpStatusCode.OK to boundedChunklist(4, live = false)
                url.contains("event2") -> HttpStatusCode.OK to boundedChunklist(6, live = false)
                url.contains("event3") -> HttpStatusCode.OK to boundedChunklist(8, live = false)
                else -> HttpStatusCode.NotFound to "404"
            }
        }

        catalog.loadEvents()
        val hitsAfterFirst = engine.requestHistory.size

        catalog.loadEvents() // within TTL → served from cache, no new requests
        catalog.loadEvents()

        assertEquals(hitsAfterFirst, engine.requestHistory.size)
    }

    @Test
    fun loadEvents_reprobesAfterTtlExpires() = runTest {
        val clock = TestTimeSource()
        val ttl = 60.seconds
        val (catalog, engine) = catalog(timeSource = clock, cacheTtl = ttl) { url ->
            when {
                url.contains("event1") -> HttpStatusCode.OK to boundedChunklist(4, live = false)
                url.contains("event2") -> HttpStatusCode.OK to boundedChunklist(6, live = false)
                url.contains("event3") -> HttpStatusCode.OK to boundedChunklist(8, live = false)
                else -> HttpStatusCode.NotFound to "404"
            }
        }

        catalog.loadEvents()
        val afterFirst = engine.requestHistory.size
        assertEquals(3, afterFirst) // one probe per configured event

        clock += (ttl - 1.seconds) // still fresh
        catalog.loadEvents()
        assertEquals(afterFirst, engine.requestHistory.size)

        clock += 2.seconds // now past TTL
        catalog.loadEvents()
        assertEquals(afterFirst * 2, engine.requestHistory.size)
    }

    @Test
    fun loadEvents_forceRefreshBypassesCache() = runTest {
        val (catalog, engine) = catalog(cacheTtl = Duration.INFINITE) { url ->
            when {
                url.contains("event1") -> HttpStatusCode.OK to boundedChunklist(4, live = false)
                url.contains("event2") -> HttpStatusCode.OK to boundedChunklist(6, live = false)
                url.contains("event3") -> HttpStatusCode.OK to boundedChunklist(8, live = false)
                else -> HttpStatusCode.NotFound to "404"
            }
        }

        catalog.loadEvents()
        val afterFirst = engine.requestHistory.size

        catalog.loadEvents(forceRefresh = true) // bypasses the (infinite) TTL
        assertEquals(afterFirst * 2, engine.requestHistory.size)
    }

    @Test
    fun invalidate_dropsCacheSoNextLoadReprobes() = runTest {
        val (catalog, engine) = catalog(cacheTtl = Duration.INFINITE) { url ->
            when {
                url.contains("event1") -> HttpStatusCode.OK to boundedChunklist(4, live = false)
                url.contains("event2") -> HttpStatusCode.OK to boundedChunklist(6, live = false)
                url.contains("event3") -> HttpStatusCode.OK to boundedChunklist(8, live = false)
                else -> HttpStatusCode.NotFound to "404"
            }
        }

        catalog.loadEvents()
        catalog.loadEvents() // cached
        val cached = engine.requestHistory.size
        assertEquals(3, cached)

        catalog.invalidate()
        catalog.loadEvents() // re-probes after invalidation
        assertEquals(cached * 2, engine.requestHistory.size)
    }

    @Test
    fun loadEvents_cachedResultIsTheSameInstance() = runTest {
        val (catalog, _) = catalog(cacheTtl = 60.seconds) { url ->
            when {
                url.contains("event1") -> HttpStatusCode.OK to boundedChunklist(4, live = false)
                url.contains("event2") -> HttpStatusCode.OK to boundedChunklist(6, live = false)
                url.contains("event3") -> HttpStatusCode.OK to boundedChunklist(8, live = false)
                else -> HttpStatusCode.NotFound to "404"
            }
        }

        val first = catalog.loadEvents()
        val second = catalog.loadEvents() // within TTL

        // Cache returns the same List reference (no copy/reprobe).
        assertSame(first, second)
    }

    @Test
    fun loadEvents_emptyResultIsCachedToo() = runTest {
        val (catalog, engine) = catalog(cacheTtl = 60.seconds) { _ ->
            HttpStatusCode.NotFound to "404"
        }

        catalog.loadEvents()
        val afterFirst = engine.requestHistory.size
        catalog.loadEvents() // empty list is still a valid cache entry

        assertEquals(afterFirst, engine.requestHistory.size)
    }

    @Test
    fun loadEvents_cacheTtlZeroAlwaysReprobes() = runTest {
        val (catalog, engine) = catalog(cacheTtl = Duration.ZERO) { url ->
            when {
                url.contains("event1") -> HttpStatusCode.OK to boundedChunklist(4, live = false)
                url.contains("event2") -> HttpStatusCode.OK to boundedChunklist(6, live = false)
                url.contains("event3") -> HttpStatusCode.OK to boundedChunklist(8, live = false)
                else -> HttpStatusCode.NotFound to "404"
            }
        }

        catalog.loadEvents()
        val afterFirst = engine.requestHistory.size
        catalog.loadEvents() // TTL=0 → never cached

        assertEquals(afterFirst * 2, engine.requestHistory.size)
    }

    // ---- FU-3: retry ---------------------------------------------------------

    @Test
    fun loadEvents_retriesTransient5xxThenSucceeds() = runTest {
        // event1 returns 500 once, then 200. With retry it should appear.
        val attempts = mutableMapOf<Int, Int>()
        val (catalog, _) = catalog(
            cacheTtl = Duration.INFINITE,
            maxProbeAttempts = 3,
            retryBackoff = Duration.ZERO,
        ) { url ->
            val eventNum = when {
                url.contains("event1") -> 1
                url.contains("event2") -> 2
                url.contains("event3") -> 3
                else -> 0
            }
            val n = attempts.getOrDefault(eventNum, 0)
            attempts[eventNum] = n + 1
            when {
                eventNum == 1 && n == 0 ->
                    HttpStatusCode.InternalServerError to "boom" // first attempt fails
                eventNum != 0 ->
                    HttpStatusCode.OK to boundedChunklist(4, live = false) // succeeds (incl. retry)
                else -> HttpStatusCode.NotFound to "404"
            }
        }

        val events = catalog.loadEvents().map { it.eventNumber }.toSet()

        // event1 recovered after one transient 500 → still present.
        assertEquals(setOf(1, 2, 3), events)
        // event1 was hit twice (failed once, succeeded on retry).
        assertEquals(2, attempts.getValue(1))
    }

    @Test
    fun loadEvents_retriesTransportFailureThenSucceeds() = runTest {
        val attempts = mutableMapOf<Int, Int>()
        // Separate engine so we can throw on the first attempt for event2.
        val engine = MockEngine { request ->
            val url = request.url.toString()
            val eventNum = when {
                url.contains("event1") -> 1
                url.contains("event2") -> 2
                url.contains("event3") -> 3
                else -> 0
            }
            val n = attempts.getOrDefault(eventNum, 0)
            attempts[eventNum] = n + 1
            when {
                eventNum == 2 && n == 0 -> throw IllegalStateException("transient blip")
                eventNum != 0 -> respond(
                    content = boundedChunklist(4, live = false),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/vnd.apple.mpegurl"),
                )
                else -> respond(
                    content = "404",
                    status = HttpStatusCode.NotFound,
                    headers = headersOf(HttpHeaders.ContentType, "text/plain"),
                )
            }
        }
        val catalog = EventCatalog(
            httpClient = HttpClient(engine),
            config = config,
            cacheTtl = Duration.INFINITE,
            maxProbeAttempts = 3,
            retryBackoff = Duration.ZERO,
        )

        val events = catalog.loadEvents().map { it.eventNumber }.toSet()

        assertEquals(setOf(1, 2, 3), events)
        // event2 was hit twice (failed once, succeeded on retry).
        assertEquals(2, attempts.getValue(2))
    }

    @Test
    fun loadEvents_doesNotRetry4xxMissingEvent() = runTest {
        val attempts = mutableMapOf<Int, Int>()
        val (catalog, _) = catalog(
            cacheTtl = Duration.INFINITE,
            maxProbeAttempts = 3,
            retryBackoff = Duration.ZERO,
        ) { url ->
            val eventNum = when {
                url.contains("event1") -> 1
                url.contains("event2") -> 2
                url.contains("event3") -> 3
                else -> 0
            }
            attempts[eventNum] = attempts.getOrDefault(eventNum, 0) + 1
            when {
                eventNum == 1 -> HttpStatusCode.NotFound to "404" // genuinely missing
                eventNum != 0 -> HttpStatusCode.OK to boundedChunklist(4, live = false)
                else -> HttpStatusCode.NotFound to "404"
            }
        }

        val events = catalog.loadEvents().map { it.eventNumber }

        // event1 is excluded (404) and was probed exactly once — no retry.
        assertEquals(listOf(3, 2), events)
        assertEquals(1, attempts.getValue(1))
    }

    @Test
    fun loadEvents_givesUpAfterMaxAttemptsOnPersistentTransient() = runTest {
        val attempts = mutableMapOf<Int, Int>()
        val (catalog, _) = catalog(
            cacheTtl = Duration.INFINITE,
            maxProbeAttempts = 2, // one try + one retry
            retryBackoff = Duration.ZERO,
        ) { url ->
            val eventNum = when {
                url.contains("event1") -> 1
                url.contains("event2") -> 2
                url.contains("event3") -> 3
                else -> 0
            }
            attempts[eventNum] = attempts.getOrDefault(eventNum, 0) + 1
            when {
                eventNum == 2 -> HttpStatusCode.InternalServerError to "boom" // always fails
                eventNum != 0 -> HttpStatusCode.OK to boundedChunklist(4, live = false)
                else -> HttpStatusCode.NotFound to "404"
            }
        }

        val events = catalog.loadEvents().map { it.eventNumber }

        // event2 fails every attempt → excluded; probed exactly maxProbeAttempts times.
        assertEquals(listOf(3, 1), events)
        assertEquals(2, attempts.getValue(2))
    }
}
