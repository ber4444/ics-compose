package com.livingpresence.inner.circle.squared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf

/**
 * The bridge between the pure Android application module (:androidApp) and the
 * shared Kotlin Multiplatform UI module (:composeApp).
 */
object HostBridge {
    var verticalDemoUrl: () -> String = { "" }
    var isDebug: () -> Boolean = { false }

    /** Whether the activity is currently in PiP, observed by the player UI. */
    val inPipState = mutableStateOf(false)
    
    private var frameEngineForTrim: PreviewFrameEngine? = null

    @Composable
    fun HostApp(
        pipController: PipController
    ) {
        val previewFrameEngine = rememberPreviewFrameEngine()
        frameEngineForTrim = previewFrameEngine
        CompositionLocalProvider(
            LocalPreviewFrameEngine provides previewFrameEngine,
            LocalPipController provides pipController,
        ) {
            App()
        }
    }

    fun onTrimMemory(level: Int) {
        frameEngineForTrim?.let {
            MemoryGovernor.onTrim(level, it.asTrimTarget())
        }
    }
}
