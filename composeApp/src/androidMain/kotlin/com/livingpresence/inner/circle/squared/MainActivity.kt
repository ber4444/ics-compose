package com.livingpresence.inner.circle.squared

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.view.WindowCompat

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            // One shared PreviewFrameEngine for the whole app session — every feed
            // tile (and Phase 3's scrub previews) reuse a single decoder + LRU
            // bitmap cache instead of minting a player per tile.
            val previewFrameEngine = rememberPreviewFrameEngine()
            CompositionLocalProvider(LocalPreviewFrameEngine provides previewFrameEngine) {
                App()
            }
        }
    }
}
