package com.livingpresence.inner.circle.squared

import android.util.Log
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse

@Inject
@SingleIn(AppScope::class)
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
            } catch (error: Exception) {
                Log.w(MainActivity.TAG, "Error checking event $eventNumber: ${error.message}")
            }
        }

        return availableVideos
    }
}

