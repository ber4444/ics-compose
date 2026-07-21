package com.livingpresence.mediakit

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Probes the event streams and returns those that exist, with live/duration
 * metadata extracted by playlist inspection. Replaces the app's sequential
 * `VideoRepository` loop with bounded parallelism.
 *
 * - Missing events (HTTP 404) never enter the list — the "no placeholder for
 *   404" requirement.
 * - Only the base (720p) rendition is probed for existence; the full ladder is
 *   resolved lazily at playback/preview time.
 * - [isLive] / [EventInfo.durationMs] come from parsing the base chunklist's
 *   `#EXT-X-ENDLIST` presence and `#EXTINF` durations.
 *
 * ## Caching (FU-3)
 * A successful [loadEvents] result is cached for [cacheTtl], so repeated gallery
 * opens don't re-hit the server. Pass `forceRefresh = true` (or call
 * [invalidate]) to bypass the cache. The staleness check uses the injected
 * [timeSource] (wall-clock [TimeSource.Monotonic] by default), which makes the
 * TTL deterministic in tests via [kotlin.time.TestTimeSource].
 *
 * ## Retry (FU-3)
 * Transient probe failures (transport exceptions, 5xx, body-read errors) are
 * retried up to [maxProbeAttempts] times with a [retryBackoff] delay, so a
 * single network blip doesn't drop a tile. A genuinely-missing event (4xx) is
 * *not* retried — it is excluded on the first attempt.
 *
 * @param httpClient The Ktor client used for playlist probes.
 * @param config SDK config; controls the event-number range and URL layout.
 * @param maxConcurrency Bounds concurrent probes to avoid hammering the server
 *   when probing ~20 events at once.
 * @param cacheTtl How long a successful [loadEvents] result is reused before
 *   the next call re-probes. `Duration.ZERO` disables caching (always probe).
 * @param timeSource Monotonic clock used to measure cache staleness. Inject a
 *   [kotlin.time.TestTimeSource] in tests to advance time deterministically.
 * @param maxProbeAttempts Max attempts per event on *transient* failures
 *   (default 2: one try + one retry). 4xx "missing" outcomes are never retried.
 * @param retryBackoff Delay between probe retries. Set to
 *   [Duration.ZERO] to retry immediately.
 */
public class EventCatalog(
    private val httpClient: HttpClient,
    private val config: MediaKitConfig = MediaKitConfig.Default,
    private val maxConcurrency: Int = DEFAULT_MAX_CONCURRENCY,
    private val cacheTtl: Duration = DEFAULT_CACHE_TTL,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val maxProbeAttempts: Int = DEFAULT_MAX_PROBE_ATTEMPTS,
    private val retryBackoff: Duration = DEFAULT_RETRY_BACKOFF,
) {
    init {
        require(maxConcurrency > 0) { "maxConcurrency must be > 0" }
        require(cacheTtl >= Duration.ZERO) { "cacheTtl must be non-negative" }
        require(maxProbeAttempts > 0) { "maxProbeAttempts must be > 0" }
        require(retryBackoff >= Duration.ZERO) { "retryBackoff must be non-negative" }
    }

    /** The cached result and the mark captured when it was produced. */
    private var cached: List<EventInfo>? = null
    private var cachedAt: TimeMark? = null

    /**
     * Probe [MediaKitConfig.maxEventNumber] events in parallel, returning the
     * ones that exist, ordered by event number descending (newest first —
     * matches the prior `VideoRepository` ordering).
     *
     * Returns the cached result if one exists and is younger than [cacheTtl],
     * unless [forceRefresh] is `true` (the app's `retryLoadingVideos` path).
     * The freshly-probed result always replaces the cache, even when empty.
     *
     * @param forceRefresh When `true`, bypass the cache and re-probe the server.
     */
    public suspend fun loadEvents(forceRefresh: Boolean = false): List<EventInfo> {
        if (!forceRefresh) {
            val snapshot = cached
            val stampedAt = cachedAt
            if (snapshot != null && stampedAt != null && stampedAt.elapsedNow() < cacheTtl) {
                return snapshot
            }
        }
        val events = probeAll()
        cached = events
        cachedAt = timeSource.markNow()
        return events
    }

    /**
     * Drop any cached result. The next [loadEvents] call re-probes the server
     * regardless of the TTL. Useful after an error to recover immediately.
     */
    public fun invalidate() {
        cached = null
        cachedAt = null
    }

    private suspend fun probeAll(): List<EventInfo> {
        val gate = Semaphore(maxConcurrency)
        val eventNumbers = (1..config.maxEventNumber).toList()

        return coroutineScope {
            eventNumbers
                .map { eventNumber ->
                    async {
                        gate.withPermit { probeEventWithRetry(eventNumber) }
                    }
                }
                .awaitAll()
                .filterNotNull()
                .sortedByDescending { it.eventNumber }
        }
    }

    /**
     * Probe a single event once. Returns null on 404 or any fetch/parse failure
     * (no retry — see [loadEvents] for the retrying path).
     */
    public suspend fun probeEvent(eventNumber: Int): EventInfo? =
        when (val result = probeEventOnce(eventNumber)) {
            is ProbeResult.Found -> result.info
            ProbeResult.Missing, ProbeResult.Transient -> null
        }

    /**
     * Probe a single event, retrying transient failures up to [maxProbeAttempts]
     * times. A 4xx "missing" outcome short-circuits to null without retrying.
     */
    private suspend fun probeEventWithRetry(eventNumber: Int): EventInfo? {
        repeat(maxProbeAttempts) { attempt ->
            when (val result = probeEventOnce(eventNumber)) {
                is ProbeResult.Found -> return result.info
                ProbeResult.Missing -> return null
                ProbeResult.Transient -> {
                    if (attempt < maxProbeAttempts - 1 && retryBackoff > Duration.ZERO) {
                        delay(retryBackoff)
                    }
                }
            }
        }
        // Exhausted retries on repeated transient failures → exclude the tile.
        return null
    }

    private suspend fun probeEventOnce(eventNumber: Int): ProbeResult {
        val masterUrl = config.eventUrl(eventNumber)
        println("probeEventOnce($eventNumber): fetching $masterUrl")
        val response: HttpResponse = runCatching {
            httpClient.get(masterUrl)
        }.onFailure { println("probeEventOnce: master request failed: $it") }
         .getOrElse { return ProbeResult.Transient }

        // 4xx (incl. 404) = the event genuinely doesn't exist → no retry.
        // 5xx = server-side hiccup → transient, worth a retry.
        if (!response.status.isSuccess()) {
            return if (response.status.value in 400..499) ProbeResult.Missing else ProbeResult.Transient
        }

        val masterText = runCatching { response.bodyAsText() }
            .onFailure { println("probeEventOnce: master bodyAsText failed: $it") }
            .getOrElse { return ProbeResult.Transient }
            
        val variants = runCatching { PlaylistInspector.parseMaster(masterText) }
            .onFailure { println("probeEventOnce: parseMaster failed: $it") }
            .getOrElse { return ProbeResult.Transient }
            
        val primary = variants.firstOrNull { !it.isIFrameOnly } ?: run {
            println("probeEventOnce: no primary variant found in $variants")
            return ProbeResult.Transient
        }
        val chunklistUrl = resolveUri(masterUrl, primary.uri)
        println("probeEventOnce: resolved chunklist URL: $chunklistUrl")

        val chunklistResponse: HttpResponse = runCatching {
            httpClient.get(chunklistUrl)
        }.onFailure { println("probeEventOnce: chunklist request failed: $it") }
         .getOrElse { return ProbeResult.Transient }

        if (!chunklistResponse.status.isSuccess()) {
            println("probeEventOnce: chunklist response not success: ${chunklistResponse.status}")
            return ProbeResult.Transient
        }

        val chunklistText = runCatching { chunklistResponse.bodyAsText() }
            .onFailure { println("probeEventOnce: chunklist bodyAsText failed: $it") }
            .getOrElse { return ProbeResult.Transient }

        val media = runCatching { PlaylistInspector.parseMediaPlaylist(chunklistText) }
            .onFailure { println("probeEventOnce: parseMediaPlaylist failed: $it") }
            .getOrElse { return ProbeResult.Transient }
            
        println("probeEventOnce: success! isLive=${media.isLive}")
        return ProbeResult.Found(
            EventInfo(
                eventNumber = eventNumber,
                isLive = media.isLive,
                durationMs = (media.durationSeconds * 1000).toLong(),
            )
        )
    }

    private fun resolveUri(base: String, reference: String): String {
        if (reference.startsWith("http://") || reference.startsWith("https://")) return reference
        val baseDir = base.substringBeforeLast('/', "")
        return "$baseDir/$reference"
    }

    private sealed interface ProbeResult {
        data class Found(val info: EventInfo) : ProbeResult
        data object Missing : ProbeResult
        data object Transient : ProbeResult
    }

    public companion object {
        /** Bound parallelism to ~5 concurrent probes. */
        public const val DEFAULT_MAX_CONCURRENCY: Int = 5

        /** Fresh catalog results are reused for this long before re-probing. */
        public val DEFAULT_CACHE_TTL: Duration = 60.seconds

        /**
         * Max attempts per event on *transient* failures (one try + one retry).
         * Kept small so a genuinely-down server doesn't stall the gallery.
         */
        public const val DEFAULT_MAX_PROBE_ATTEMPTS: Int = 2

        /** Delay between probe retries. */
        public val DEFAULT_RETRY_BACKOFF: Duration = 200.milliseconds
    }
}
