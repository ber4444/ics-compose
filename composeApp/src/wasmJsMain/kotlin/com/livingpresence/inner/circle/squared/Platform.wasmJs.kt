package com.livingpresence.inner.circle.squared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.ktor.client.HttpClient
import kotlinx.browser.window

actual fun createHttpClient(): HttpClient = HttpClient()

@Composable
actual fun PlatformPlayerScreen(
    url: String,
    audioOnly: Boolean,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text(
            if (audioOnly) {
                "Open the live audio stream in a new tab."
            } else {
                "Open the live video stream in a new tab."
            },
        )
        Button(onClick = { window.open(url, "_blank") }) {
            Text("Open stream")
        }
        Button(onClick = onClose) {
            Text("Back")
        }
    }
}
