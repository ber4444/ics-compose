package com.livingpresence.inner.circle.squared

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import androidx.media3.ui.compose.material3.PlayPauseButton
import com.livingpresence.inner.circle.squared.MainActivity.Companion.httpClient
import io.ktor.client.*
import io.ktor.client.engine.android.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    companion object {
        const val TAG = "InnerCircleSquared"
        // Reusable HTTP client for efficiency
        val httpClient = HttpClient(Android)
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable edge-to-edge display to remove title bar
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            InnerCircleSquaredTheme {
                LoginScreen()
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // Clean up HTTP client when activity is destroyed
        httpClient.close()
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
fun LoginScreen() {
    var password by remember { mutableStateOf("") }
    var audioOnly by remember { mutableStateOf(false) }
    var showDialog by remember { mutableStateOf(false) }
    var playerUrl by remember { mutableStateOf<String?>(null) }
    var playerAudioOnly by remember { mutableStateOf(false) }
    val context = LocalContext.current
    
    // NOTE: Password stored in plain text for feature parity with original Flutter app.
    // In production, consider implementing proper server-side authentication.
    val isEnabled = password == "be2BE"

    if (playerUrl != null) {
        VideoPlayerScreen(
            url = playerUrl!!,
            audioOnly = playerAudioOnly,
            onClose = { playerUrl = null }
        )
    } else {
        Box(modifier = Modifier.fillMaxSize()) {
            // Background image
            Image(
                painter = painterResource(id = R.drawable.background_image),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )

            // Centered content
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Spacer(modifier = Modifier.height(20.dp))

                // Audio only switch
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
                        checked = audioOnly,
                        onCheckedChange = { audioOnly = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFFEF5350),
                            checkedTrackColor = Color(0xFFE57373)
                        )
                    )
                }

                Spacer(modifier = Modifier.height(5.dp))

                // Live Events button
                Button(
                    onClick = { showDialog = true },
                    enabled = isEnabled,
                    modifier = Modifier.width(150.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFEF5350),
                        disabledContainerColor = Color.Gray
                    )
                ) {
                    Text("Live Events")
                }

                Spacer(modifier = Modifier.height(5.dp))

                // Password field
                TextField(
                    value = password,
                    onValueChange = { password = it },
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

                // Teaching Payments button
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

                // Event Payments button
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

        // Videos dialog
        if (showDialog) {
            VideosDialog(
                audioOnly = audioOnly,
                onDismiss = { showDialog = false },
                onPlayVideo = { url, audio ->
                    playerUrl = url
                    playerAudioOnly = audio
                    showDialog = false
                }
            )
        }
    }
}

@Composable
fun VideoPlayerScreen(url: String, audioOnly: Boolean, onClose: () -> Unit) {
    val context = LocalContext.current
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            val mediaItem = MediaItem.fromUri(url)
            setMediaItem(mediaItem)
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(Unit) {
        onDispose { player.release() }
    }

    Scaffold(
        topBar = {
            @OptIn(ExperimentalMaterial3Api::class)
            TopAppBar(
                title = { Text(if (audioOnly) "Audio Player" else "Video Player") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Text("✕")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        },
        containerColor = Color.Black
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = if (audioOnly) Arrangement.Center else Arrangement.Top
        ) {
            if (!audioOnly) {
                PlayerSurface(
                    player = player,
                    surfaceType = SURFACE_TYPE_SURFACE_VIEW,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Material3 play/pause control
            PlayPauseButton(
                player = player,
                modifier = Modifier.size(64.dp)
            )
        }
    }
}

@Composable
fun VideosDialog(audioOnly: Boolean, onDismiss: () -> Unit, onPlayVideo: (String, Boolean) -> Unit) {
    var videoList by remember { mutableStateOf<List<Int>?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        scope.launch {
            try {
                videoList = getVideoList()
                isLoading = false
            } catch (e: Exception) {
                error = e.message
                isLoading = false
            }
        }
    }

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
                        Text(
                            text = "Error: $error",
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                    videoList != null -> {
                        LazyColumn {
                            items(videoList!!) { eventNumber ->
                                TextButton(
                                    onClick = {
                                        val url = getUrl(eventNumber, audioOnly)
                                        onPlayVideo(url, audioOnly)
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("event $eventNumber")
                                }
                            }
                        }
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

suspend fun getVideoList(): List<Int> {
    val good = mutableListOf<Int>()
    
    try {
        for (i in 20 downTo 1) {
            try {
                val response: HttpResponse = httpClient.get(getUrl(i, false))
                if (response.status.value != 404) {
                    good.add(i)
                }
            } catch (e: Exception) {
                // Log errors for debugging, but continue checking other events
                Log.w(MainActivity.TAG, "Error checking event $i: ${e.message}")
            }
        }
    } catch (e: Exception) {
        Log.e(MainActivity.TAG, "Error fetching video list", e)
        throw e
    }
    
    return good
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
