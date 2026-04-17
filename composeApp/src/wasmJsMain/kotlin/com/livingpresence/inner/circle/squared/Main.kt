package com.livingpresence.inner.circle.squared

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    document.title = "Inner Circle Squared"
    ComposeViewport(document.body!!) {
        App()
    }
}
