package com.livingpresence.inner.circle.squared

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.ktor.client.HttpClient
import kotlinx.browser.window

actual fun createHttpClient(): HttpClient = HttpClient()

@Composable
actual fun PlatformPlayerScreen(
    url: String,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text("Open the live video stream in a new tab.")
        Button(onClick = { window.open(url, "_blank") }) {
            Text("Open stream")
        }
        Button(onClick = onClose) {
            Text("Back")
        }
    }
}

@Composable
actual fun LiveEventThumbnail(
    eventNumber: Int,
    contentDescription: String?,
    modifier: Modifier,
) {
    // wasmJs Phase 2: a poster placeholder. The hover-to-play <video> overlay
    // (hls.js, ≤1 active stream, bounds via onGloballyPositioned) is a later
    // polish item; CORS is verified open so it will work when added.
    Box(
        modifier = modifier.background(
            Brush.linearGradient(listOf(Color(0xFF37474F), Color(0xFF263238))),
        ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "event $eventNumber",
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

