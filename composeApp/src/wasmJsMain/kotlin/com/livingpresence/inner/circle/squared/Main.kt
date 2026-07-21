package com.livingpresence.inner.circle.squared

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.livingpresence.inner.circle.squared.transcription.TranscriptionSecrets
import kotlinx.browser.document

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    document.title = "Inner Circle Squared"
    // Live-caption keys are baked into the bundle from secrets.properties at build
    // time (see composeApp/build.gradle.kts). Empty when unset — the caption clients
    // then surface a "missing key" error and the CC button shows `CC!`.
    TranscriptionSecrets.deepgramApiKey = TranscriptionKeys.DEEPGRAM_API_KEY
    TranscriptionSecrets.sonioxApiKey = TranscriptionKeys.SONIOX_API_KEY
    ComposeViewport(document.body!!) {
        App()
    }
}
