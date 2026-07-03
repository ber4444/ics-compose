package com.livingpresence.inner.circle.squared

import androidx.compose.runtime.Composable
import com.livingpresence.mediakit.MediaKitConfig
import io.ktor.client.HttpClient

expect fun createHttpClient(): HttpClient

@Composable
expect fun PlatformPlayerScreen(
    url: String,
    onClose: () -> Unit,
)

/**
 * The base (720p) playlist URL for [eventNumber]. Delegates to [MediaKitConfig]
 * so the production host lives in one place (the `:mediakit` SDK) rather than
 * being sprinkled through app code.
 */
fun getUrl(eventNumber: Int): String =
    MediaKitConfig.Default.eventUrl(eventNumber)
