package com.livingpresence.mediakit

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

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
 * @param maxConcurrency Bounds concurrent probes to avoid hammering the server
 *   when probing ~20 events at once.
 */
public class EventCatalog(
    private val httpClient: HttpClient,
    private val config: MediaKitConfig = MediaKitConfig.Default,
    private val maxConcurrency: Int = DEFAULT_MAX_CONCURRENCY,
) {
    /**
     * Probe [MediaKitConfig.maxEventNumber] events in parallel, returning the
     * ones that exist, ordered by event number descending (newest first —
     * matches the prior `VideoRepository` ordering).
     */
    public suspend fun loadEvents(): List<EventInfo> {
        val gate = Semaphore(maxConcurrency)
        val eventNumbers = (1..config.maxEventNumber).toList()

        return coroutineScope {
            eventNumbers
                .map { eventNumber ->
                    async {
                        gate.withPermit { probeEvent(eventNumber) }
                    }
                }
                .awaitAll()
                .filterNotNull()
                .sortedByDescending { it.eventNumber }
        }
    }

    /** Probe a single event. Returns null on 404 or any fetch/parse failure. */
    public suspend fun probeEvent(eventNumber: Int): EventInfo? {
        val response: HttpResponse = runCatching {
            httpClient.get(config.eventUrl(eventNumber))
        }.getOrNull() ?: return null

        // 404 (or any non-success) = missing event → no tile.
        if (!response.status.isSuccess()) return null

        val playlistText = runCatching { response.bodyAsText() }.getOrNull() ?: return null
        val media = PlaylistInspector.parseMediaPlaylist(playlistText)
        return EventInfo(
            eventNumber = eventNumber,
            isLive = media.isLive,
            durationMs = (media.durationSeconds * 1000).toLong(),
        )
    }

    public companion object {
        /** Bound parallelism to ~5 concurrent probes. */
        public const val DEFAULT_MAX_CONCURRENCY: Int = 5
    }
}
