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
import androidx.compose.ui.platform.LocalDensity
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
function attachHlsWithAbr(videoElement, hlsUrl, nativeUrl) {
    if (window.Hls && window.Hls.isSupported()) {
        var hls = new window.Hls({
            capLevelToPlayerSize: true
        });
        hls.loadSource(hlsUrl);
        hls.attachMedia(videoElement);
        hls.on(window.Hls.Events.MANIFEST_PARSED, function() {
            videoElement.play().catch(function(){});
        });
        return hls;
    } else if (videoElement.canPlayType('application/vnd.apple.mpegurl')) {
        videoElement.src = nativeUrl;
        videoElement.play().catch(function(){});
    }
    return null;
}
""")
internal external fun attachHlsWithAbr(videoElement: kotlin.js.JsAny, hlsUrl: String, nativeUrl: String): kotlin.js.JsAny?

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

    // The Compose surface on web is a single opaque skiko canvas, so (unlike the
    // native players) controls can't be drawn translucently over the HTML <video>.
    // Instead we paint the canvas black and inset the video into the band between
    // the top bar and the bottom controls, measured live so the bars stay tappable.
    val density = LocalDensity.current.density
    var topBarBottomPx by remember { mutableStateOf(0f) }
    var bottomBarTopPx by remember { mutableStateOf(0f) }
    // Black strip (CSS px) reserved above the controls for captions when CC is on.
    val captionStripCss = 72.0

    // Scrub Preview State
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubFraction by remember { mutableStateOf(0f) }
    var previewDataUrl by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        initPreviewEngine()
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {

        DisposableEffect(Unit) {
            document.body?.style?.apply {
                setProperty("background", "black")
            }

            val video = document.createElement("video") as HTMLVideoElement
            video.style.apply {
                // The <video> paints above the Compose canvas (DOM order), so it
                // must be inset to the middle band; top/height are set live from
                // the measured control-bar positions (see LaunchedEffect below).
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

        // Inset the <video> to the band between the top bar and bottom controls so
        // the Compose controls (which render above the black canvas, not over the
        // video) stay visible and tappable. Captions can't overlay the opaque canvas
        // either, so when CC is on we shrink the band by [captionStripCss] to leave a
        // black strip above the controls for them. Measurements are in Compose px;
        // divide by density for CSS px.
        LaunchedEffect(videoElement, topBarBottomPx, bottomBarTopPx, captionController.enabled) {
            val video = videoElement ?: return@LaunchedEffect
            if (topBarBottomPx > 0f && bottomBarTopPx > topBarBottomPx) {
                val topCss = topBarBottomPx / density
                val strip = if (captionController.enabled) captionStripCss else 0.0
                val heightCss = ((bottomBarTopPx - topBarBottomPx) / density - strip).coerceAtLeast(0.0)
                video.style.setProperty("top", "${topCss}px")
                video.style.setProperty("height", "${heightCss}px")
            }
        }

        DisposableEffect(videoElement, url) {
            val video = videoElement ?: return@DisposableEffect onDispose {}
            var hlsJs: kotlin.js.JsAny? = null
            
            val job = scope.launch {
                if (eventNumber != null) {
                    val ladder = try {
                        ladderResolver.resolve(eventNumber)
                    } catch (e: Exception) {
                        null
                    }
                    renditions = ladder?.renditions
                    if (ladder != null) {
                        val blobUrl = createBlobUrl(ladder.masterPlaylistText, "application/vnd.apple.mpegurl")
                        hlsJs = attachHlsWithAbr(video, blobUrl, nativeUrl = url)
                    } else {
                        hlsJs = attachHlsWithAbr(video, url, nativeUrl = url)
                    }
                } else {
                    hlsJs = attachHlsWithAbr(video, url, nativeUrl = url)
                }
                hlsInstance = hlsJs
            }
            
            onDispose {
                job.cancel()
                hlsJs?.let { destroyHls(it) }
            }
        }
        
        DisposableEffect(hlsInstance, renditions) {
            var visibilityListener: kotlin.js.JsAny? = null
            if (hlsInstance != null && renditions != null) {
                visibilityListener = setupVisibilityListener(
                    onHidden = {
                        val audioIdx = renditions?.indexOfFirst { it.isAudioOnly } ?: -1
                        if (audioIdx >= 0) {
                            setHlsLevel(hlsInstance!!, audioIdx)
                        }
                    },
                    onVisible = {
                        setHlsLevel(hlsInstance!!, -1)
                    }
                )
            }
            onDispose {
                visibilityListener?.let { removeVisibilityListener(it) }
            }
        }

        // Custom Controls Overlay
        var thumbCenterRootX by remember { mutableFloatStateOf(0f) }
        var controlsBoxTop by remember { mutableFloatStateOf(0f) }

        Box(modifier = Modifier.fillMaxSize()) {
            PlayerControlsOverlay(
                modifier = Modifier.fillMaxSize().onGloballyPositioned {
                    controlsBoxTop = it.boundsInWindow().bottom - 120f // Approximate bottom controls height
                },
                isPlaying = isPlaying,
                durationMs = (duration * 1000).toLong(),
                positionMs = (currentTime * 1000).toLong(),
                isLive = duration <= 0.0,
                isSeekable = duration > 0.0,
                isScrubbing = isScrubbing,
                sliderFraction = scrubFraction,
                onSliderValueChange = { frac ->
                    isScrubbing = true
                    scrubFraction = frac
                    val target = (frac * duration).toDouble()
                    if (eventNumber != null) {
                        val url160p = MediaKitConfig.Default.renditionUrl(eventNumber, com.livingpresence.mediakit.RenditionTier.P160)
                        requestFrame(eventNumber, url160p, target) { dataUrl ->
                            if (isScrubbing && scrubFraction == frac) {
                                previewDataUrl = dataUrl
                            }
                        }
                    }
                },
                onSliderValueChangeFinished = {
                    isScrubbing = false
                    val target = (scrubFraction * duration).toDouble()
                    videoElement?.let { seekVideo(it, target) }
                    previewDataUrl = null
                },
                onPlayPauseToggle = {
                    videoElement?.let { if (isPlaying) pauseVideo(it) else playVideo(it) }
                },
                onJumpToLive = {
                    videoElement?.let { seekVideo(it, duration) }
                },
                onClose = onClose,
                onThumbCenterXChanged = { thumbCenterRootX = it },
                onTopBarBottomChanged = { topBarBottomPx = it },
                onBottomBarTopChanged = { bottomBarTopPx = it },
                topRightControls = {
                    PlayerTopRightControls(
                        captionController = captionController,
                        onToggleStats = { showStats = !showStats },
                        qualityMenu = {
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
                        },
                        trailingControls = {
                            TextButton(onClick = {
                                videoElement?.let { togglePip(it) }
                            }) {
                                Text("PiP", color = Color.White)
                            }
                            TextButton(onClick = {
                                videoElement?.let { toggleFullscreenWeb(it) }
                            }) {
                                Text("Fullscreen", color = Color.White)
                            }
                        },
                    )
                }
            )

            if (isScrubbing && previewDataUrl != null) {
                DisposableEffect(thumbCenterRootX, controlsBoxTop, previewDataUrl) {
                    val img = document.createElement("img") as org.w3c.dom.HTMLImageElement
                    img.style.apply {
                        setProperty("position", "absolute")
                        setProperty("left", "${thumbCenterRootX - 70}px")
                        setProperty("top", "${controlsBoxTop - 90}px")
                        setProperty("width", "140px")
                        setProperty("height", "80px")
                        setProperty("z-index", "100")
                        setProperty("border", "2px solid white")
                        setProperty("border-radius", "4px")
                        setProperty("object-fit", "cover")
                        setProperty("background", "black")
                    }
                    img.src = previewDataUrl!!
                    document.body?.appendChild(img)
                    onDispose { img.remove() }
                }
            }

            // Sit the captions just above the bottom control bar (in the reserved
            // black strip), using the measured bar height so they never overlap the
            // controls or hide behind the video.
            val bottomBarHeightDp = (window.innerHeight.toFloat() - bottomBarTopPx / density).coerceAtLeast(0f)
            CaptionOverlay(
                captions = captionController.captions,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = bottomBarHeightDp.dp)
            )
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

@JsFun("function togglePip(video) { if (document.pictureInPictureElement) { document.exitPictureInPicture().catch(function(e){}); } else if (video.requestPictureInPicture) { video.requestPictureInPicture().catch(function(e){}); } }")
internal external fun togglePip(video: kotlin.js.JsAny)

@JsFun("""
function setupVisibilityListener(onHidden, onVisible) {
    var listener = function() {
        if (document.hidden) {
            onHidden();
        } else {
            onVisible();
        }
    };
    document.addEventListener("visibilitychange", listener);
    return listener;
}
""")
internal external fun setupVisibilityListener(onHidden: () -> Unit, onVisible: () -> Unit): kotlin.js.JsAny

@JsFun("""
function removeVisibilityListener(listener) {
    document.removeEventListener("visibilitychange", listener);
}
""")
internal external fun removeVisibilityListener(listener: kotlin.js.JsAny)
