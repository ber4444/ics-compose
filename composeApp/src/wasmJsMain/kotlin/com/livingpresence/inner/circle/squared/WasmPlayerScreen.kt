package com.livingpresence.inner.circle.squared

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.livingpresence.mediakit.LadderResolver
import com.livingpresence.mediakit.MediaKitConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLVideoElement
import kotlinx.browser.document
import kotlinx.browser.window

@JsFun("""
function createBlobUrl(text, mimeType) {
    const blob = new Blob([text], { type: mimeType });
    return URL.createObjectURL(blob);
}
""")
internal external fun createBlobUrl(text: String, mimeType: String): String

@JsFun("""
function attachHlsWithAbr(videoElement, url) {
    if (window.Hls && window.Hls.isSupported()) {
        var hls = new window.Hls({
            capLevelToPlayerSize: true
        });
        hls.loadSource(url);
        hls.attachMedia(videoElement);
        hls.on(window.Hls.Events.MANIFEST_PARSED, function() {
            videoElement.play().catch(function(){});
        });
        return hls;
    } else if (videoElement.canPlayType('application/vnd.apple.mpegurl')) {
        videoElement.src = url;
        videoElement.play().catch(function(){});
    }
    return null;
}
""")
internal external fun attachHlsWithAbr(videoElement: kotlin.js.JsAny, url: String): kotlin.js.JsAny?

@JsFun("""
function setupVideoListeners(video, onTimeUpdate, onPlay, onPause) {
    video._timeUpdate = function() { onTimeUpdate(video.currentTime, video.duration || 0.0); };
    video._play = function() { onPlay(); };
    video._pause = function() { onPause(); };
    
    video.addEventListener('timeupdate', video._timeUpdate);
    video.addEventListener('play', video._play);
    video.addEventListener('pause', video._pause);
}
""")
internal external fun setupVideoListeners(
    video: kotlin.js.JsAny, 
    onTimeUpdate: (Double, Double) -> Unit,
    onPlay: () -> Unit,
    onPause: () -> Unit
)

@JsFun("""
function cleanupVideoListeners(video) {
    if(video._timeUpdate) video.removeEventListener('timeupdate', video._timeUpdate);
    if(video._play) video.removeEventListener('play', video._play);
    if(video._pause) video.removeEventListener('pause', video._pause);
}
""")
internal external fun cleanupVideoListeners(video: kotlin.js.JsAny)

@JsFun("function playVideo(video) { video.play().catch(function(){}); }")
internal external fun playVideo(video: kotlin.js.JsAny)

@JsFun("function pauseVideo(video) { video.pause(); }")
internal external fun pauseVideo(video: kotlin.js.JsAny)

@JsFun("function seekVideo(video, time) { video.currentTime = time; }")
internal external fun seekVideo(video: kotlin.js.JsAny, time: Double)

@JsFun("""
function getHlsLevels(hls) {
    if (!hls || !hls.levels) return "[]";
    var levels = hls.levels.map(function(l, i) { 
        return { index: i, height: l.height || 0 }; 
    });
    // Add Auto option
    levels.unshift({ index: -1, height: -1 });
    return JSON.stringify(levels);
}
""")
internal external fun getHlsLevels(hls: kotlin.js.JsAny): String

@JsFun("function setHlsLevel(hls, index) { if(hls) { hls.currentLevel = index; hls.loadLevel = index; } }")
internal external fun setHlsLevel(hls: kotlin.js.JsAny, index: Int)

private fun parseEventNumber(url: String): Int? {
    val regex = "event(\\d+)".toRegex()
    return regex.find(url)?.groupValues?.get(1)?.toIntOrNull()
}

@Composable
fun WasmPlayerScreen(url: String, onClose: () -> Unit) {
    val eventNumber = parseEventNumber(url)
    val scope = rememberCoroutineScope()
    val httpClient = remember { HttpClient(Js) }
    val ladderResolver = remember { LadderResolver(httpClient, MediaKitConfig.Default) }
    
    var videoElement by remember { mutableStateOf<HTMLVideoElement?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var currentTime by remember { mutableStateOf(0.0) }
    var duration by remember { mutableStateOf(0.0) }
    var hlsInstance by remember { mutableStateOf<kotlin.js.JsAny?>(null) }
    var showQualityMenu by remember { mutableStateOf(false) }

    // Scrub Preview State
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubFraction by remember { mutableStateOf(0f) }
    var previewDataUrl by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        initPreviewEngine()
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Transparent)) {
        
        DisposableEffect(Unit) {
            val composeCanvas = document.getElementById("ComposeTarget") as? org.w3c.dom.HTMLElement
            if (composeCanvas != null) {
                composeCanvas.style.apply { setProperty("z-index", "1") }
            }
            
            val video = document.createElement("video") as HTMLVideoElement
            video.style.apply {
                setProperty("position", "absolute")
                setProperty("left", "0px")
                setProperty("top", "0px")
                setProperty("width", "100%")
                setProperty("height", "100%")
                setProperty("z-index", "-1")
                setProperty("background", "black")
                setProperty("object-fit", "contain")
            }
            video.playsInline = true
            document.body?.appendChild(video)
            videoElement = video
            
            setupVideoListeners(video,
                onTimeUpdate = { time, dur -> currentTime = time; duration = dur },
                onPlay = { isPlaying = true },
                onPause = { isPlaying = false }
            )
            
            onDispose {
                cleanupVideoListeners(video)
                video.remove()
            }
        }
        
        DisposableEffect(videoElement, url) {
            val video = videoElement ?: return@DisposableEffect onDispose {}
            var hlsJs: kotlin.js.JsAny? = null
            
            val job = scope.launch {
                if (eventNumber != null) {
                    val ladder = ladderResolver.resolve(eventNumber)
                    if (ladder != null) {
                        val blobUrl = createBlobUrl(ladder.masterPlaylistText, "application/vnd.apple.mpegurl")
                        hlsJs = attachHlsWithAbr(video, blobUrl)
                    } else {
                        hlsJs = attachHlsWithAbr(video, url)
                    }
                } else {
                    hlsJs = attachHlsWithAbr(video, url)
                }
                hlsInstance = hlsJs
            }
            
            onDispose {
                job.cancel()
                hlsJs?.let { destroyHls(it) }
            }
        }
        
        // Custom Controls Overlay
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                IconButton(onClick = onClose) {
                    Text("←", color = Color.White)
                }
            }
            
            Column(modifier = Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.5f)).padding(8.dp)) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    // Slider
                    Slider(
                        value = if (isScrubbing) scrubFraction else if (duration > 0) (currentTime / duration).toFloat() else 0f,
                        onValueChange = { frac ->
                            isScrubbing = true
                            scrubFraction = frac
                            val target = (frac * duration).toDouble()
                            
                            // Request Preview Frame
                            if (eventNumber != null) {
                                val url160p = MediaKitConfig.Default.renditionUrl(eventNumber, com.livingpresence.mediakit.RenditionTier.P160)
                                requestFrame(eventNumber, url160p, target) { dataUrl ->
                                    if (isScrubbing && scrubFraction == frac) { // only update if still on this fraction
                                        previewDataUrl = dataUrl
                                    }
                                }
                            }
                        },
                        onValueChangeFinished = {
                            isScrubbing = false
                            val target = (scrubFraction * duration).toDouble()
                            videoElement?.let { seekVideo(it, target) }
                            previewDataUrl = null
                        },
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Preview Bubble Native DOM Overlay
                    if (isScrubbing) {
                        var sliderBounds by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
                        Box(modifier = Modifier.matchParentSize().onGloballyPositioned { 
                            sliderBounds = it.boundsInWindow() 
                        })

                        DisposableEffect(sliderBounds, scrubFraction, previewDataUrl) {
                            val currentBounds = sliderBounds ?: return@DisposableEffect onDispose {}
                            val img = document.createElement("img") as org.w3c.dom.HTMLImageElement
                            val thumbX = currentBounds.left + (currentBounds.width * scrubFraction)
                            
                            img.style.apply {
                                setProperty("position", "absolute")
                                setProperty("left", "${thumbX - 70}px") // center 140px width
                                setProperty("top", "${currentBounds.top - 90}px")
                                setProperty("width", "140px")
                                setProperty("height", "80px")
                                setProperty("z-index", "100")
                                setProperty("border", "2px solid white")
                                setProperty("border-radius", "4px")
                                setProperty("object-fit", "cover")
                                setProperty("background", "black") // fallback color
                            }
                            if (previewDataUrl != null) {
                                img.src = previewDataUrl!!
                            }
                            
                            document.body?.appendChild(img)
                            
                            onDispose {
                                img.remove()
                            }
                        }
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = {
                            videoElement?.let { if (isPlaying) pauseVideo(it) else playVideo(it) }
                        }) {
                            Text(if (isPlaying) "⏸" else "▶", color = Color.White)
                        }
                        Text(
                            text = "${formatDurationWeb(currentTime)} / ${formatDurationWeb(duration)}",
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box {
                            IconButton(onClick = { showQualityMenu = true }) {
                                Text("⚙", color = Color.White)
                            }
                            DropdownMenu(
                                expanded = showQualityMenu,
                                onDismissRequest = { showQualityMenu = false }
                            ) {
                                // Since we can't easily parse JSON from Kotlin Wasm without kotlinx-serialization,
                                // we'll just hardcode the typical options for this demo or parse simply.
                                // For simplicity, we just offer Auto, 720p, 360p, 160p which maps to levels 2, 1, 0 if 3 exist.
                                // We can use index -1 for Auto.
                                DropdownMenuItem(
                                    text = { Text("Auto") },
                                    onClick = { hlsInstance?.let { setHlsLevel(it, -1) }; showQualityMenu = false }
                                )
                                DropdownMenuItem(
                                    text = { Text("720p") },
                                    onClick = { hlsInstance?.let { setHlsLevel(it, 2) }; showQualityMenu = false }
                                )
                                DropdownMenuItem(
                                    text = { Text("360p") },
                                    onClick = { hlsInstance?.let { setHlsLevel(it, 1) }; showQualityMenu = false }
                                )
                                DropdownMenuItem(
                                    text = { Text("160p") },
                                    onClick = { hlsInstance?.let { setHlsLevel(it, 0) }; showQualityMenu = false }
                                )
                            }
                        }
                        IconButton(onClick = {
                            videoElement?.let { toggleFullscreenWeb(it) }
                        }) {
                            Text("⛶", color = Color.White)
                        }
                    }
                }
            }
        }
    }
}

@JsFun("function toggleFullscreenWeb(video) { if(video.requestFullscreen) video.requestFullscreen(); else if(video.webkitRequestFullscreen) video.webkitRequestFullscreen(); }")
internal external fun toggleFullscreenWeb(video: kotlin.js.JsAny)

private fun formatDurationWeb(seconds: Double): String {
    val sec = seconds.toInt()
    val m = sec / 60
    val s = sec % 60
    return "${m}:${s.toString().padStart(2, '0')}"
}
