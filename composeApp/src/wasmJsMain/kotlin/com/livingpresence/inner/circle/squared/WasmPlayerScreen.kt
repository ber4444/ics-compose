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

@JsFun("""
function attachAudioTap(videoElement, onAudioProcess) {
    if (!window.audioContext) {
        window.audioContext = new (window.AudioContext || window.webkitAudioContext)();
    }
    const ctx = window.audioContext;
    const source = ctx.createMediaElementSource(videoElement);
    const processor = ctx.createScriptProcessor(4096, 1, 1);
    
    source.connect(processor);
    processor.connect(ctx.destination);
    source.connect(ctx.destination);
    
    processor.onaudioprocess = function(e) {
        const inputData = e.inputBuffer.getChannelData(0);
        onAudioProcess(inputData, inputData.length, 1, ctx.sampleRate);
    };
    // Keep reference to prevent GC
    videoElement._audioProcessor = processor;
}
""")
internal external fun attachAudioTap(videoElement: kotlin.js.JsAny, onAudioProcess: (kotlin.js.JsAny, Int, Int, Int) -> Unit)

@JsFun("""
function getFloat32ArrayElement(jsFloat32Array, index) {
    return jsFloat32Array[index];
}
""")
internal external fun getFloat32ArrayElement(jsArray: kotlin.js.JsAny, index: Int): Float

@JsFun("function getVideoHeight(video) { return video.videoHeight || 0; }")
internal external fun getVideoHeight(video: kotlin.js.JsAny): Int

@JsFun("""
function getBufferedAfter(video) {
    if (!video.buffered || video.buffered.length === 0) return 0.0;
    var currentTime = video.currentTime;
    for (var i = 0; i < video.buffered.length; i++) {
        var start = video.buffered.start(i);
        var end = video.buffered.end(i);
        if (currentTime >= start && currentTime <= end) {
            return end - currentTime;
        }
    }
    return 0.0;
}
""")
internal external fun getBufferedAfter(video: kotlin.js.JsAny): Double

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

    var renditions by remember { mutableStateOf<List<com.livingpresence.mediakit.ProbedRendition>?>(null) }
    var showStats by remember { mutableStateOf(false) }
    val captionController = rememberCaptionController()

    // Scrub Preview State
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubFraction by remember { mutableStateOf(0f) }
    var previewDataUrl by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        initPreviewEngine()
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Transparent)) {
        
        DisposableEffect(Unit) {
            val composeCanvas = document.getElementsByTagName("canvas").item(0) as? org.w3c.dom.HTMLElement
            if (composeCanvas != null) {
                composeCanvas.style.apply { setProperty("z-index", "1") }
            }
            
            document.body?.style?.apply {
                setProperty("background", "black")
            }
            
            val video = document.createElement("video") as HTMLVideoElement
            video.style.apply {
                setProperty("position", "absolute")
                setProperty("left", "0px")
                setProperty("top", "0px")
                setProperty("width", "100%")
                setProperty("height", "100%")
                setProperty("z-index", "0")
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
            
            attachAudioTap(video) { pcmData, numFrames, channels, sampleRate ->
                val floatArray = FloatArray(numFrames) { i -> getFloat32ArrayElement(pcmData, i) }
                CaptionAudioRouter.get().onPcm(floatArray, numFrames, channels, sampleRate)
            }
            
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
                    renditions = ladder?.renditions
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
                TextButton(onClick = onClose) {
                    Text("←", color = Color.White)
                }
            }
            
            Column(modifier = Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.5f)).padding(8.dp)) {
                CaptionOverlay(
                    captions = captionController.captions,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

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
                        TextButton(onClick = {
                            videoElement?.let { if (isPlaying) pauseVideo(it) else playVideo(it) }
                        }) {
                            Text(if (isPlaying) "Pause" else "Play", color = Color.White)
                        }
                        Text(
                            text = "${formatDurationWeb(currentTime)} / ${formatDurationWeb(duration)}",
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        QualityMenu(
                            renditions = renditions,
                            onSetAuto = { hlsInstance?.let { setHlsLevel(it, -1) } },
                            onPinToRendition = { rendition ->
                                val index = renditions?.filter { !it.isAudioOnly }?.indexOf(rendition) ?: -1
                                if (index >= 0) hlsInstance?.let { setHlsLevel(it, index) }
                            },
                            onDisableVideo = {
                                val audioIndex = renditions?.indexOfFirst { it.isAudioOnly } ?: -1
                                if (audioIndex >= 0) hlsInstance?.let { setHlsLevel(it, audioIndex) }
                            }
                        )
                        TextButton(onClick = { showStats = !showStats }) {
                            Text("Stats", color = Color.White)
                        }
                        if (captionController.enabled) {
                            CaptionProviderButton(controller = captionController)
                        }
                        CaptionToggleButton(controller = captionController)

                        TextButton(onClick = {
                            videoElement?.let { toggleFullscreenWeb(it) }
                        }) {
                            Text("Fullscreen", color = Color.White)
                        }
                    }
                }
            }
        }

        if (showStats) {
            // Recompute stats continuously when shown
            var tick by remember { mutableStateOf(0) }
            LaunchedEffect(isPlaying, showStats) {
                while(true) {
                    kotlinx.coroutines.delay(250)
                    tick++
                }
            }
            
            val currentHeight = videoElement?.let { getVideoHeight(it) }?.takeIf { it > 0 }
            val bufferedAfterMs = videoElement?.let { (getBufferedAfter(it) * 1000).toLong() } ?: 0L
            
            StatsOverlay(
                currentHeight = tick.let { currentHeight },
                bufferedAfterMs = tick.let { bufferedAfterMs },
                renditions = renditions,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 56.dp, start = 8.dp),
            )
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
