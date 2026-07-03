package com.livingpresence.inner.circle.squared

import androidx.compose.runtime.Composable
import com.livingpresence.mediakit.EventInfo
import kotlinx.coroutines.flow.StateFlow

/**
 * Per-event download state surfaced to the UI (common shape across platforms).
 */
data class EventDownloadState(
    val eventNumber: Int,
    val state: DownloadStatus,
    val percent: Float,
)

enum class DownloadStatus { QUEUED, DOWNLOADING, COMPLETED, FAILED, REMOVING, NOT_DOWNLOADED }

/**
 * Platform abstraction over offline downloads. Android backs this with the
 * `DownloadCenter` (DownloadService + WorkManager + SimpleCache); wasmJs is a
 * no-op (web has no offline-download path in this phase).
 *
 * Only bounded (non-live) events are downloadable — [EventInfo.isLive] gates
 * the download affordance in the UI.
 */
interface DownloadController {
    /** A map of event number → download state, kept current. */
    val states: StateFlow<Map<Int, EventDownloadState>>

    /** Enqueue a download for [event]. No-op for live events. */
    fun enqueue(event: EventInfo)

    /** Remove a downloaded event. */
    fun remove(eventNumber: Int)

    /** Refresh the state snapshot from the underlying download manager. */
    fun refresh()
}

/** Platform download-controller factory. Android wires the DownloadCenter; wasmJs is a no-op. */
@Composable
expect fun rememberDownloadController(): DownloadController
