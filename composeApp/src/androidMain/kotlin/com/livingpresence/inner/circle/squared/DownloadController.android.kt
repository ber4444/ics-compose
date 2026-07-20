package com.livingpresence.inner.circle.squared

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import com.livingpresence.mediakit.EventInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Android [DownloadController] backed by [DownloadCenter]. Mirrors the
 * [DownloadManager] download index into a [StateFlow] the UI observes.
 */
@UnstableApi
class AndroidDownloadController(
    private val context: Context,
) : DownloadController {
    override val isSupported: Boolean = true

    private val center by lazy { DownloadCenter.get(context) }

    private val _states = MutableStateFlow<Map<Int, EventDownloadState>>(emptyMap())
    override val states: StateFlow<Map<Int, EventDownloadState>> = _states.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var pollingJob: Job? = null

    private fun startPollingIfNeeded() {
        if (pollingJob?.isActive == true) return
        pollingJob = scope.launch {
            while (isActive) {
                refresh()
                val hasActive = _states.value.values.any { 
                    it.state == DownloadStatus.DOWNLOADING || it.state == DownloadStatus.QUEUED 
                }
                if (!hasActive) break
                delay(500) // Poll every 500ms
            }
        }
    }

    private val listener = object : DownloadManager.Listener {
        override fun onDownloadChanged(
            downloadManager: DownloadManager,
            download: Download,
            finalException: Exception?,
        ) {
            refresh()
            startPollingIfNeeded()
        }

        override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
            refresh()
        }

        override fun onIdle(downloadManager: DownloadManager) {
            refresh()
        }
    }

    init {
        center.downloadManager.addListener(listener)
        refresh()
        startPollingIfNeeded()
    }

    override fun enqueue(event: EventInfo, tier: DownloadQuality) {
        if (event.isLive) return
        center.enqueue(event, tier.toRenditionTier())
        refresh()
        startPollingIfNeeded()
    }

    private fun DownloadQuality.toRenditionTier(): com.livingpresence.mediakit.RenditionTier =
        when (this) {
            DownloadQuality.P720 -> com.livingpresence.mediakit.RenditionTier.P720
            DownloadQuality.P360 -> com.livingpresence.mediakit.RenditionTier.P360
            DownloadQuality.P160 -> com.livingpresence.mediakit.RenditionTier.P160
            DownloadQuality.AUDIO -> com.livingpresence.mediakit.RenditionTier.AUDIO
        }

    override fun remove(eventNumber: Int) {
        center.remove(eventNumber)
        refresh()
    }

    override fun refresh() {
        _states.value = center.snapshot().mapValues { (_, v) ->
            EventDownloadState(
                eventNumber = v.eventNumber,
                state = v.state.toCommon(),
                percent = v.percent,
            )
        }
    }
}

private fun DownloadCenter.DownloadState.toCommon(): DownloadStatus = when (this) {
    DownloadCenter.DownloadState.QUEUED -> DownloadStatus.QUEUED
    DownloadCenter.DownloadState.DOWNLOADING -> DownloadStatus.DOWNLOADING
    DownloadCenter.DownloadState.COMPLETED -> DownloadStatus.COMPLETED
    DownloadCenter.DownloadState.FAILED -> DownloadStatus.FAILED
    DownloadCenter.DownloadState.REMOVING -> DownloadStatus.REMOVING
}

/** Remembers an [AndroidDownloadController] for the composition. */
@Composable
actual fun rememberDownloadController(): DownloadController {
    val context = androidx.compose.ui.platform.LocalContext.current
    return remember { AndroidDownloadController(context) }
}
