package com.livingpresence.inner.circle.squared

import androidx.compose.runtime.Composable
import com.livingpresence.mediakit.EventInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.*
import platform.CoreMedia.CMTimeRange
import platform.Foundation.*
import platform.darwin.NSObject
import com.livingpresence.mediakit.MediaKitConfig
import com.livingpresence.mediakit.RenditionTier
import androidx.compose.runtime.remember

@OptIn(ExperimentalForeignApi::class)
private class DownloadDelegate(
    private val onProgress: (NSURLSessionTask, Double) -> Unit,
    private val onComplete: (NSURLSessionTask, NSURL?) -> Unit
) : NSObject(), AVAssetDownloadDelegateProtocol {
    override fun URLSession(session: NSURLSession, assetDownloadTask: AVAssetDownloadTask, didLoadTimeRange: CValue<CMTimeRange>, totalTimeRangesLoaded: List<*>, timeRangeExpectedToLoad: CValue<CMTimeRange>) {
        onProgress(assetDownloadTask, 0.5)
    }
    
    override fun URLSession(session: NSURLSession, assetDownloadTask: AVAssetDownloadTask, didFinishDownloadingToURL: NSURL) {
        onComplete(assetDownloadTask, didFinishDownloadingToURL)
    }
}

@OptIn(ExperimentalForeignApi::class)
private class IOSDownloadController : DownloadController {
    override val isSupported: Boolean = true
    private val _states = MutableStateFlow<Map<Int, EventDownloadState>>(emptyMap())
    override val states: StateFlow<Map<Int, EventDownloadState>> = _states.asStateFlow()
    
    private var session: AVAssetDownloadURLSession? = null
    private val tasks = mutableMapOf<Int, AVAssetDownloadTask>()
    private val config = MediaKitConfig.Default
    
    init {
        val configuration = NSURLSessionConfiguration.backgroundSessionConfigurationWithIdentifier("com.livingpresence.downloads")
        val delegate = DownloadDelegate(
            onProgress = { task, progress -> 
                val eventNumber = tasks.entries.firstOrNull { it.value == task }?.key
                if (eventNumber != null) {
                    updateState(eventNumber, DownloadStatus.DOWNLOADING, progress.toFloat())
                }
            },
            onComplete = { task, url -> 
                val eventNumber = tasks.entries.firstOrNull { it.value == task }?.key
                if (eventNumber != null) {
                    updateState(eventNumber, DownloadStatus.COMPLETED, 1.0f)
                }
            }
        )
        // Note: AVAssetDownloadURLSession.sessionWithConfiguration is the factory in Obj-C
        session = AVAssetDownloadURLSession.sessionWithConfiguration(
            configuration = configuration,
            assetDownloadDelegate = delegate,
            delegateQueue = NSOperationQueue.mainQueue
        ) as? AVAssetDownloadURLSession
    }

    override fun enqueue(event: EventInfo, tier: DownloadQuality) {
        val tierEnum = when(tier) {
            DownloadQuality.P720 -> RenditionTier.P720
            DownloadQuality.P360 -> RenditionTier.P360
            DownloadQuality.P160 -> RenditionTier.P160
            DownloadQuality.AUDIO -> RenditionTier.AUDIO
        }
        val url = config.renditionUrl(event.eventNumber, tierEnum)
        val nsUrl = NSURL.URLWithString(url) ?: return
        val asset = AVURLAsset.assetWithURL(nsUrl)
        
        val task = session?.assetDownloadTaskWithURLAsset(
            URLAsset = asset,
            assetTitle = "Event ${event.eventNumber}",
            assetArtworkData = null,
            options = null
        )
        task?.let {
            tasks[event.eventNumber] = it
            it.resume()
            updateState(event.eventNumber, DownloadStatus.DOWNLOADING, 0f)
        }
    }

    override fun remove(eventNumber: Int) {
        tasks[eventNumber]?.cancel()
        tasks.remove(eventNumber)
        updateState(eventNumber, DownloadStatus.NOT_DOWNLOADED, 0f)
    }

    override fun refresh() {}
    
    private fun updateState(eventNumber: Int, status: DownloadStatus, percent: Float) {
        val current = _states.value.toMutableMap()
        current[eventNumber] = EventDownloadState(eventNumber, status, percent)
        _states.value = current
    }
}

@Composable
actual fun rememberDownloadController(): DownloadController = remember { IOSDownloadController() }
