package com.livingpresence.inner.circle.squared

import androidx.compose.runtime.Composable
import io.ktor.client.HttpClient

expect fun createHttpClient(): HttpClient

@Composable
expect fun PlatformPlayerScreen(
    url: String,
    onClose: () -> Unit,
)

fun getUrl(eventNumber: Int): String =
    "https://65e54f30ec73c.streamlock.net:443/live/event$eventNumber/playlist.m3u8?DVR"
