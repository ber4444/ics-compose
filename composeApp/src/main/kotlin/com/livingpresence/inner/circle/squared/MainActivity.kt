package com.livingpresence.inner.circle.squared

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.core.view.WindowCompat
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.FilteringMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import androidx.media3.ui.compose.material3.buttons.PlayPauseButton
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import dev.zacsweers.metro.createGraph
import dev.zacsweers.metrox.viewmodel.LocalMetroViewModelFactory
import dev.zacsweers.metrox.viewmodel.metroViewModel
import kotlinx.coroutines.delay
import kotlin.math.roundToLong

class MainActivity : ComponentActivity() {
    companion object {
        const val TAG = "InnerCircleSquared"
        val appGraph: AppGraph by lazy(LazyThreadSafetyMode.NONE) {
            createGraph<AppGraph>()
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable edge-to-edge display to remove title bar
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            CompositionLocalProvider(
                LocalMetroViewModelFactory provides appGraph.metroViewModelFactory
            ) {
                InnerCircleSquaredTheme {
                    InnerCircleSquaredApp()
                }
            }
        }
    }
}

@Composable
fun InnerCircleSquaredTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFFEF5350), // Red[400]
            onPrimary = Color.White,
            background = Color.White,
            onBackground = Color(0xFF00695C) // Teal[900]
        ),
        content = content
    )
}

@Composable
private fun InnerCircleSquaredApp(mainViewModel: MainViewModel = metroViewModel()) {
    val uiState by mainViewModel.uiState.collectAsStateWithLifecycle()
    val backStack = remember { mutableStateListOf<AppRoute>(LoginRoute) }
    val popBackStack = {
        if (backStack.size > 1) {
            backStack.removeLast()
        }
    }

    NavDisplay(
        backStack = backStack,
        modifier = Modifier.fillMaxSize(),
        onBack = popBackStack,
        entryProvider = { route ->
            when (route) {
                LoginRoute -> NavEntry(route) {
                    LoginScreen(
                        uiState = uiState,
                        onPasswordChange = mainViewModel::onPasswordChanged,
                        onAudioOnlyChange = mainViewModel::onAudioOnlyChanged,
                        onShowVideosDialog = mainViewModel::showVideosDialog,
                        onDismissVideosDialog = mainViewModel::dismissVideosDialog,
                        onPlayVideo = { eventNumber ->
                            val playbackRequest = mainViewModel.createPlayback(eventNumber)
                            backStack.add(
                                PlayerRoute(
                                    url = playbackRequest.url,
                                    audioOnly = playbackRequest.audioOnly
                                )
                            )
                        },
                        onRetryLoadingVideos = mainViewModel::retryLoadingVideos
                    )
                }

                is PlayerRoute -> NavEntry(route) {
                    VideoPlayerScreen(
                        url = route.url,
                        audioOnly = route.audioOnly,
                        onClose = popBackStack
                    )
                }
            }
        }
    )
}

private sealed interface AppRoute

private data object LoginRoute : AppRoute

private data class PlayerRoute(
    val url: String,
    val audioOnly: Boolean,
) : AppRoute

@Composable
fun LoginScreen(
    uiState: MainUiState,
    onPasswordChange: (String) -> Unit,
    onAudioOnlyChange: (Boolean) -> Unit,
    onShowVideosDialog: () -> Unit,
    onDismissVideosDialog: () -> Unit,
    onPlayVideo: (Int) -> Unit,
    onRetryLoadingVideos: () -> Unit
) {
    val context = LocalContext.current

    Box(modifier = Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(id = R.drawable.background_image),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Spacer(modifier = Modifier.height(20.dp))

            Row(
                modifier = Modifier
                    .width(200.dp)
                    .background(Color(0x99FFFFFF))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Audio only",
                    modifier = Modifier.weight(1f),
                    color = Color(0xFF00695C)
                )
                Switch(
                    checked = uiState.audioOnly,
                    onCheckedChange = onAudioOnlyChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color(0xFFEF5350),
                        checkedTrackColor = Color(0xFFE57373)
                    )
                )
            }

            Spacer(modifier = Modifier.height(5.dp))

            Button(
                onClick = onShowVideosDialog,
                enabled = uiState.isLiveEventsEnabled,
                modifier = Modifier.width(150.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFEF5350),
                    disabledContainerColor = Color.Gray
                )
            ) {
                Text("Live Events")
            }

            Spacer(modifier = Modifier.height(5.dp))

            TextField(
                value = uiState.password,
                onValueChange = onPasswordChange,
                modifier = Modifier
                    .width(100.dp)
                    .background(Color(0x99FFFFFF)),
                colors = TextFieldDefaults.colors(
                    focusedTextColor = Color.Red,
                    unfocusedTextColor = Color.Red,
                    cursorColor = Color.Red,
                    focusedContainerColor = Color(0x99FFFFFF),
                    unfocusedContainerColor = Color(0x99FFFFFF),
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(5.dp))

            Button(
                onClick = {
                    handleURLButtonPress(
                        context,
                        "https://www.propylaia.org:443/wordpress/"
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF5350))
            ) {
                Text("Teaching Payments")
            }

            Spacer(modifier = Modifier.height(5.dp))

            Button(
                onClick = {
                    handleURLButtonPress(
                        context,
                        "https://s4898.americommerce.com"
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF5350))
            ) {
                Text("Event Payments")
            }
        }
    }

    if (uiState.isVideosDialogVisible) {
        VideosDialog(
            videoList = uiState.availableVideos,
            isLoading = uiState.isLoadingVideos,
            error = uiState.videoLoadError,
            onDismiss = onDismissVideosDialog,
            onPlayVideo = onPlayVideo,
            onRetry = onRetryLoadingVideos
        )
    }
}

@Composable
fun VideoPlayerScreen(url: String, audioOnly: Boolean, onClose: () -> Unit) {
    val liveEdgeThresholdMs = 3_000L
    val context = LocalContext.current
    val videoTapInteractionSource = remember { MutableInteractionSource() }
    val trackSelector = remember(url, audioOnly) {
        DefaultTrackSelector(context).apply {
            parameters = buildUponParameters()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                .build()
        }
    }
    val player = remember(url, audioOnly) {
        ExoPlayer.Builder(context)
            .setTrackSelector(trackSelector)
            .build()
            .apply {
            val audioAttributes = androidx.media3.common.AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(
                    if (audioOnly) C.AUDIO_CONTENT_TYPE_MUSIC else C.AUDIO_CONTENT_TYPE_MOVIE
                )
                .build()

            setAudioAttributes(audioAttributes, true)
            setHandleAudioBecomingNoisy(true)
            volume = 1f
            setMediaSource(buildPlaybackMediaSource(context, url, audioOnly))
            prepare()
            playWhenReady = true
        }
    }
    var currentPosition by remember(player) { mutableLongStateOf(0L) }
    var duration by remember(player) { mutableLongStateOf(0L) }
    var isSeekable by remember(player) { mutableStateOf(false) }
    var isLive by remember(player) { mutableStateOf(false) }
    var isPlaying by remember(player) { mutableStateOf(player.isPlaying) }
    var videoAspectRatio by remember(player) { mutableFloatStateOf(16f / 9f) }
    var isScrubbing by remember(player) { mutableStateOf(false) }
    var sliderFraction by remember(player) { mutableFloatStateOf(0f) }
    var showVideoControls by remember(player) { mutableStateOf(false) }
    val canJumpToLive by remember(isLive, isSeekable, duration, currentPosition, isScrubbing, sliderFraction) {
        derivedStateOf {
            if (!isLive || !isSeekable || duration <= 0L) {
                false
            } else {
                val effectivePosition = if (isScrubbing) {
                    (duration * sliderFraction).roundToLong()
                } else {
                    currentPosition
                }
                effectivePosition < (duration - liveEdgeThresholdMs).coerceAtLeast(0L)
            }
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                isSeekable = player.isCurrentMediaItemSeekable
                isLive = player.isCurrentMediaItemLive
                isPlaying = player.isPlaying
                duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L

                val videoSize = player.videoSize
                val width = videoSize.width
                val height = videoSize.height
                if (width > 0 && height > 0) {
                    videoAspectRatio = (width * videoSize.pixelWidthHeightRatio) / height.toFloat()
                }

                val audioTrackGroups = player.currentTracks.groups.filter { group ->
                    group.type == C.TRACK_TYPE_AUDIO
                }
                val hasSelectedAudioTrack = audioTrackGroups.any { group -> group.isSelected }
                if (!hasSelectedAudioTrack) {
                    Log.w(
                        MainActivity.TAG,
                        "No audio track selected for stream: $url (audio groups=${audioTrackGroups.size})"
                    )
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(MainActivity.TAG, "Player error for $url", error)
            }
        }

        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
        }
    }

    LaunchedEffect(player, isScrubbing) {
        while (true) {
            isSeekable = player.isCurrentMediaItemSeekable
            isLive = player.isCurrentMediaItemLive
            duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L
            isPlaying = player.isPlaying

            if (!isScrubbing) {
                currentPosition = player.currentPosition.coerceAtLeast(0L)
                sliderFraction = if (duration > 0L) {
                    currentPosition.toFloat() / duration.toFloat()
                } else {
                    0f
                }
            }

            delay(500)
        }
    }

    DisposableEffect(player) {
        onDispose {
            player.pause()
            player.release()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        val displayedPosition = if (isScrubbing && duration > 0L) {
            (duration * sliderFraction).roundToLong()
        } else {
            currentPosition
        }

        if (!audioOnly) {
            BoxWithConstraints(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                val safeVideoAspectRatio = videoAspectRatio.takeIf { it > 0f } ?: (16f / 9f)
                val containerAspectRatio = maxWidth.value / maxHeight.value
                val baseVideoModifier = if (containerAspectRatio > safeVideoAspectRatio) {
                    Modifier
                        .fillMaxHeight()
                        .aspectRatio(safeVideoAspectRatio)
                } else {
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(safeVideoAspectRatio)
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clipToBounds(),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = baseVideoModifier
                            .clickable(
                                interactionSource = videoTapInteractionSource,
                                indication = null
                            ) {
                                showVideoControls = !showVideoControls
                            }
                    ) {
                        PlayerSurface(
                            player = player,
                            surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
                            modifier = Modifier.matchParentSize()
                        )

                        androidx.compose.animation.AnimatedVisibility(
                            visible = showVideoControls,
                            modifier = Modifier.align(Alignment.BottomCenter),
                            enter = fadeIn(),
                            exit = fadeOut()
                        ) {
                            PlayerControlPanel(
                                player = player,
                                currentPosition = displayedPosition,
                                duration = duration,
                                isSeekable = isSeekable && duration > 0L,
                                isLive = isLive,
                                isPlaying = isPlaying,
                                sliderFraction = sliderFraction,
                                canJumpToLive = canJumpToLive,
                                onSliderValueChange = {
                                    isScrubbing = true
                                    sliderFraction = it
                                },
                                onSliderValueChangeFinished = {
                                    if (duration > 0L) {
                                        val newPosition = (duration * sliderFraction)
                                            .roundToLong()
                                            .coerceIn(0L, duration)
                                        player.seekTo(newPosition)
                                        currentPosition = newPosition
                                    }
                                    isScrubbing = false
                                },
                                onJumpToLive = {
                                    player.seekToDefaultPosition()
                                    player.play()
                                    currentPosition = duration
                                    sliderFraction = 1f
                                    isScrubbing = false
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color.Black.copy(alpha = 0.55f))
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                isOverlay = true
                            )
                        }
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Spacer(modifier = Modifier.height(16.dp))

                PlayerControlPanel(
                    player = player,
                    currentPosition = displayedPosition,
                    duration = duration,
                    isSeekable = isSeekable && duration > 0L,
                    isLive = isLive,
                    isPlaying = isPlaying,
                    sliderFraction = sliderFraction,
                    canJumpToLive = canJumpToLive,
                    onSliderValueChange = {
                        isScrubbing = true
                        sliderFraction = it
                    },
                    onSliderValueChangeFinished = {
                        if (duration > 0L) {
                            val newPosition = (duration * sliderFraction)
                                .roundToLong()
                                .coerceIn(0L, duration)
                            player.seekTo(newPosition)
                            currentPosition = newPosition
                        }
                        isScrubbing = false
                    },
                    onJumpToLive = {
                        player.seekToDefaultPosition()
                        player.play()
                        currentPosition = duration
                        sliderFraction = 1f
                        isScrubbing = false
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    isOverlay = false
                )
            }
        }
    }
}

@Composable
private fun PlayerControlPanel(
    player: Player,
    currentPosition: Long,
    duration: Long,
    isSeekable: Boolean,
    isLive: Boolean,
    isPlaying: Boolean,
    sliderFraction: Float,
    canJumpToLive: Boolean,
    onSliderValueChange: (Float) -> Unit,
    onSliderValueChangeFinished: () -> Unit,
    onJumpToLive: () -> Unit,
    modifier: Modifier = Modifier,
    isOverlay: Boolean
) {
    Column(modifier = modifier) {
        PlayerTimelineControls(
            currentPosition = currentPosition,
            duration = duration,
            isSeekable = isSeekable,
            isLive = isLive,
            sliderFraction = sliderFraction,
            onSliderValueChange = onSliderValueChange,
            onSliderValueChangeFinished = onSliderValueChangeFinished,
            textColor = Color.White
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PlayPauseButton(
                player = player,
                modifier = Modifier.size(64.dp)
            )

            Button(
                onClick = {
                    if (isPlaying) {
                        player.pause()
                    } else {
                        player.play()
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isOverlay) Color.Black.copy(alpha = 0.45f) else Color(0xFFEF5350)
                )
            ) {
                Text(if (isPlaying) "Pause" else "Unpause")
            }


            if (isLive && isSeekable && duration > 0L) {
                FilledTonalButton(
                    onClick = onJumpToLive,
                    enabled = canJumpToLive,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = if (isOverlay) Color.White.copy(alpha = 0.22f) else MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Text("Jump to live")
                }
            }
        }
    }
}

@Composable
private fun PlayerTimelineControls(
    currentPosition: Long,
    duration: Long,
    isSeekable: Boolean,
    isLive: Boolean,
    sliderFraction: Float,
    onSliderValueChange: (Float) -> Unit,
    onSliderValueChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
    textColor: Color = Color.White
) {
    Column(modifier = modifier) {
        if (isSeekable) {
            Slider(
                value = sliderFraction.coerceIn(0f, 1f),
                onValueChange = onSliderValueChange,
                onValueChangeFinished = onSliderValueChangeFinished,
                valueRange = 0f..1f
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = formatPlaybackTime(currentPosition),
                color = textColor,
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = when {
                    isSeekable -> formatPlaybackTime(duration)
                    isLive -> "Live"
                    else -> "Position unavailable"
                },
                color = textColor,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

private fun formatPlaybackTime(timeMs: Long): String {
    val totalSeconds = (timeMs.coerceAtLeast(0L) / 1000L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L

    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

private fun buildPlaybackMediaSource(
    context: android.content.Context,
    url: String,
    audioOnly: Boolean
): MediaSource {
    val mediaSourceFactory = DefaultMediaSourceFactory(context)
    val mediaItem = MediaItem.Builder()
        .setUri(url)
        .setMimeType(MimeTypes.APPLICATION_M3U8)
        .build()

    if (audioOnly) {
        return mediaSourceFactory.createMediaSource(mediaItem)
    }

    val audioUrl = deriveAudioOnlyUrl(url)
    if (audioUrl == null || audioUrl == url) {
        return mediaSourceFactory.createMediaSource(mediaItem)
    }

    val videoSource = FilteringMediaSource(
        mediaSourceFactory.createMediaSource(mediaItem),
        C.TRACK_TYPE_VIDEO
    )
    val audioSource = FilteringMediaSource(
        mediaSourceFactory.createMediaSource(
            MediaItem.Builder()
                .setUri(audioUrl)
                .setMimeType(MimeTypes.APPLICATION_M3U8)
                .build()
        ),
        C.TRACK_TYPE_AUDIO
    )

    return MergingMediaSource(
        true,
        videoSource,
        audioSource
    )
}

private fun deriveAudioOnlyUrl(url: String): String? {
    val eventUrlRegex = Regex("""(event\d+)(?!_aac)(/playlist\.m3u8(?:\?.*)?)""")
    return if (eventUrlRegex.containsMatchIn(url)) {
        url.replace(eventUrlRegex, "$1_aac$2")
    } else {
        null
    }
}

@Composable
fun VideosDialog(
    videoList: List<Int>,
    isLoading: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onPlayVideo: (Int) -> Unit,
    onRetry: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Videos List") },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
            ) {
                when {
                    isLoading -> {
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                    error != null -> {
                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(text = "Error: $error")
                            Button(onClick = onRetry) {
                                Text("Retry")
                            }
                        }
                    }
                    videoList.isNotEmpty() -> {
                        LazyColumn {
                            items(videoList) { eventNumber ->
                                TextButton(
                                    onClick = { onPlayVideo(eventNumber) },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("event $eventNumber")
                                }
                            }
                        }
                    }
                    else -> {
                        Text(
                            text = "No videos available",
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

fun getUrl(eventNumber: Int, audioOnly: Boolean): String {
    val audioSuffix = if (audioOnly) "_aac" else ""
    return "https://65e54f30ec73c.streamlock.net:443/live/event$eventNumber$audioSuffix/playlist.m3u8?DVR"
}

fun handleURLButtonPress(context: android.content.Context, url: String) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        context.startActivity(intent)
    } catch (e: Exception) {
        e.printStackTrace()
    }
}
