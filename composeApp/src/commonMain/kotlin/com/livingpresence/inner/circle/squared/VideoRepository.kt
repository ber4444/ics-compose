package com.livingpresence.inner.circle.squared

import com.livingpresence.mediakit.EventCatalog
import com.livingpresence.mediakit.EventInfo
import com.livingpresence.mediakit.MediaKitConfig
import io.ktor.client.HttpClient

/**
 * Adapter that exposes the available events to the app's ViewModel, backed by
 * the `:mediakit` SDK's [EventCatalog].
 *
 * The actual probing (parallel fetch, 404 exclusion, live/duration metadata
 * extraction via playlist inspection) lives in [EventCatalog]. This wrapper
 * keeps the app's [MainViewModel] decoupled from the SDK's HTTP client wiring.
 */
class VideoRepository(
    httpClient: HttpClient,
    private val catalog: EventCatalog = EventCatalog(httpClient, MediaKitConfig.Default),
) {
    /** The full probed event list (event number, isLive, duration). */
    suspend fun loadEvents(): List<EventInfo> = catalog.loadEvents()

    /** Just the event numbers, for any call site that still needs them. */
    suspend fun getAvailableVideos(): List<Int> = loadEvents().map { it.eventNumber }
}
