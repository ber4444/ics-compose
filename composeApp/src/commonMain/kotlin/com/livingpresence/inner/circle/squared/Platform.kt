package com.livingpresence.inner.circle.squared

import androidx.compose.runtime.Composable
import io.ktor.client.HttpClient

expect fun createHttpClient(): HttpClient

@Composable
expect fun PlatformPlayerScreen(
    url: String,
    audioOnly: Boolean,
    onClose: () -> Unit,
)

fun getUrl(eventNumber: Int, audioOnly: Boolean): String {
    val audioSuffix = if (audioOnly) "_aac" else ""
    return "https://65e54f30ec73c.streamlock.net:443/live/event$eventNumber$audioSuffix/playlist.m3u8?DVR"
}
