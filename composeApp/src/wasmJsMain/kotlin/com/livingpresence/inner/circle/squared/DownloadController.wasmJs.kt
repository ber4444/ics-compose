package com.livingpresence.inner.circle.squared

import androidx.compose.runtime.Composable
import com.livingpresence.mediakit.EventInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * wasmJs has no offline-download path in this phase — a no-op controller so the
 * shared UI compiles. The download affordance is gated by platform capability.
 */
private class NoopDownloadController : DownloadController {
    private val _states = MutableStateFlow<Map<Int, EventDownloadState>>(emptyMap())
    override val states: StateFlow<Map<Int, EventDownloadState>> = _states.asStateFlow()
    override fun enqueue(event: EventInfo, tier: DownloadQuality) {}
    override fun remove(eventNumber: Int) {}
    override fun refresh() {}
}

@Composable
actual fun rememberDownloadController(): DownloadController = NoopDownloadController()
