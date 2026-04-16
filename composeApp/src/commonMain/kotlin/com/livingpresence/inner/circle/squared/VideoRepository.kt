package com.livingpresence.inner.circle.squared

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse

class VideoRepository(
    private val httpClient: HttpClient,
) {
    suspend fun getAvailableVideos(): List<Int> {
        val availableVideos = mutableListOf<Int>()

        for (eventNumber in 20 downTo 1) {
            try {
                val response: HttpResponse = httpClient.get(getUrl(eventNumber, false))
                if (response.status.value != 404) {
                    availableVideos.add(eventNumber)
                }
            } catch (_: Exception) {
            }
        }

        return availableVideos
    }
}
