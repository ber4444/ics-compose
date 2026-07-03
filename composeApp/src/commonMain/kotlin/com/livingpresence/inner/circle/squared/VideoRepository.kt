package com.livingpresence.inner.circle.squared

import com.livingpresence.mediakit.EventCatalog
import com.livingpresence.mediakit.MediaKitConfig
import io.ktor.client.HttpClient

/**
 * Adapter that exposes the available event numbers to the app's ViewModel.
 *
 * The actual probing (parallel fetch, 404 exclusion, live/duration metadata
 * extraction via playlist inspection) lives in the `:mediakit` SDK's
 * [EventCatalog]. This wrapper maps [com.livingpresence.mediakit.EventInfo] down
 * to the event numbers the current UI consumes; Phase 2's gallery will surface
 * the full [com.livingpresence.mediakit.EventInfo] (live badge, duration)
 * directly.
 */
class VideoRepository(
    httpClient: HttpClient,
    private val catalog: EventCatalog = EventCatalog(httpClient, MediaKitConfig.Default),
) {
    suspend fun getAvailableVideos(): List<Int> =
        catalog.loadEvents().map { it.eventNumber }
}
