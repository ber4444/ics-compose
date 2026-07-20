package com.livingpresence.inner.circle.squared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.livingpresence.mediakit.EventInfo
import com.livingpresence.mediakit.MediaKitConfig
import com.livingpresence.mediakit.PlaylistInspector
import com.livingpresence.mediakit.RenditionTier
import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job

@JsFun("""
function downloadTsSegments(jobId, filename, urlsJson, onProgress, onSuccess, onError) {
    window._downloadJobs = window._downloadJobs || {};
    const controller = new AbortController();
    window._downloadJobs[jobId] = controller;
    const signal = controller.signal;

    const urls = JSON.parse(urlsJson);
    const blobs = [];
    let completed = 0;
    
    async function doDownload() {
        try {
            for (const url of urls) {
                if (signal.aborted) throw new Error("AbortError");
                const response = await fetch(url, { signal: signal });
                if (!response.ok) throw new Error("Fetch failed: " + response.status);
                blobs.push(await response.blob());
                completed++;
                onProgress((completed / urls.length) * 100.0);
            }
            if (signal.aborted) throw new Error("AbortError");
            const finalBlob = new Blob(blobs, { type: 'video/mp2t' });
            const objectUrl = URL.createObjectURL(finalBlob);
            const a = document.createElement('a');
            a.href = objectUrl;
            a.download = filename;
            document.body.appendChild(a);
            a.click();
            document.body.removeChild(a);
            setTimeout(() => URL.revokeObjectURL(objectUrl), 10000);
            delete window._downloadJobs[jobId];
            onSuccess();
        } catch(e) {
            delete window._downloadJobs[jobId];
            onError(e.toString());
        }
    }
    doDownload();
}
""")
internal external fun downloadTsSegments(
    jobId: Int,
    filename: String,
    urlsJson: String,
    onProgress: (Double) -> Unit,
    onSuccess: () -> Unit,
    onError: (String) -> Unit
)

@JsFun("""
function cancelDownload(jobId) {
    if (window._downloadJobs && window._downloadJobs[jobId]) {
        window._downloadJobs[jobId].abort();
        delete window._downloadJobs[jobId];
    }
}
""")
internal external fun cancelDownload(jobId: Int)

private class WasmDownloadController : DownloadController {
    override val isSupported: Boolean = true
    private val _states = MutableStateFlow<Map<Int, EventDownloadState>>(emptyMap())
    override val states: StateFlow<Map<Int, EventDownloadState>> = _states.asStateFlow()
    private val scope = CoroutineScope(Dispatchers.Default)
    private val httpClient = HttpClient(Js)
    private val activeJobs = mutableMapOf<Int, Job>()

    override fun enqueue(event: EventInfo, tier: DownloadQuality) {
        if (event.isLive) return
        _states.update { it + (event.eventNumber to EventDownloadState(event.eventNumber, DownloadStatus.QUEUED, 0f)) }

        val job = scope.launch {
            try {
                _states.update { it + (event.eventNumber to EventDownloadState(event.eventNumber, DownloadStatus.DOWNLOADING, 0f)) }
                
                val tierRendition = when (tier) {
                    DownloadQuality.P720 -> RenditionTier.P720
                    DownloadQuality.P360 -> RenditionTier.P360
                    DownloadQuality.P160 -> RenditionTier.P160
                    DownloadQuality.AUDIO -> RenditionTier.AUDIO
                }
                val masterUrl = MediaKitConfig.Default.renditionUrl(event.eventNumber, tierRendition)
                val masterResponse = httpClient.get(masterUrl).bodyAsText()
                val variants = PlaylistInspector.parseMaster(masterResponse)
                val chunklistUri = variants.firstOrNull()?.uri ?: throw Exception("No variants found")
                
                val baseUrl = masterUrl.substringBeforeLast("/") + "/"
                val chunklistUrl = if (chunklistUri.startsWith("http")) chunklistUri else baseUrl + chunklistUri
                
                val chunklistResponse = httpClient.get(chunklistUrl).bodyAsText()
                val chunklist = PlaylistInspector.parseMediaPlaylist(chunklistResponse)
                
                val segmentBaseUrl = chunklistUrl.substringBeforeLast("/") + "/"
                val segmentUrls = chunklist.segmentUris.map { uri ->
                    if (uri.startsWith("http")) uri else segmentBaseUrl + uri
                }
                
                val urlsJson = "[" + segmentUrls.joinToString(",") { "\"$it\"" } + "]"
                val filename = "event_${event.eventNumber}_${tier.name.lowercase()}.ts"

                downloadTsSegments(
                    jobId = event.eventNumber,
                    filename = filename,
                    urlsJson = urlsJson,
                    onProgress = { progress ->
                        _states.update { it + (event.eventNumber to EventDownloadState(event.eventNumber, DownloadStatus.DOWNLOADING, progress.toFloat())) }
                    },
                    onSuccess = {
                        _states.update { it + (event.eventNumber to EventDownloadState(event.eventNumber, DownloadStatus.COMPLETED, 100f)) }
                    },
                    onError = { error ->
                        if (error.contains("AbortError")) {
                            println("Download aborted")
                        } else {
                            println("Download failed: $error")
                            _states.update { it + (event.eventNumber to EventDownloadState(event.eventNumber, DownloadStatus.FAILED, 0f)) }
                        }
                        activeJobs.remove(event.eventNumber)
                    }
                )
            } catch (e: Exception) {
                println("Download setup failed: ${e.message}")
                _states.update { it + (event.eventNumber to EventDownloadState(event.eventNumber, DownloadStatus.FAILED, 0f)) }
                activeJobs.remove(event.eventNumber)
            }
        }
        activeJobs[event.eventNumber] = job
    }

    override fun remove(eventNumber: Int) {
        cancelDownload(eventNumber)
        activeJobs[eventNumber]?.cancel()
        activeJobs.remove(eventNumber)
        _states.update { it - eventNumber }
    }

    override fun refresh() {}
}

@Composable
actual fun rememberDownloadController(): DownloadController = remember { WasmDownloadController() }
