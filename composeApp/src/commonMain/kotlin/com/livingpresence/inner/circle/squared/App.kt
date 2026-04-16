package com.livingpresence.inner.circle.squared

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import com.livingpresence.inner.circle.squared.generated.resources.Res
import com.livingpresence.inner.circle.squared.generated.resources.background_image
import org.jetbrains.compose.resources.painterResource

private sealed interface AppRoute
private data object LoginRoute : AppRoute
private data class PlayerRoute(
    val url: String,
    val audioOnly: Boolean,
) : AppRoute

@Composable
fun App() {
    InnerCircleSquaredTheme {
        val mainViewModel = rememberMainViewModel()
        val uiState by mainViewModel.uiState.collectAsState()
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
                                        audioOnly = playbackRequest.audioOnly,
                                    )
                                )
                            },
                            onRetryLoadingVideos = mainViewModel::retryLoadingVideos,
                        )
                    }

                    is PlayerRoute -> NavEntry(route) {
                        PlatformPlayerScreen(
                            url = route.url,
                            audioOnly = route.audioOnly,
                            onClose = popBackStack,
                        )
                    }
                }
            },
        )
    }
}

@Composable
private fun rememberMainViewModel(): MainViewModel {
    val owner = checkNotNull(LocalViewModelStoreOwner.current)
    val videoRepository = remember { VideoRepository(createHttpClient()) }
    return viewModel(
        viewModelStoreOwner = owner,
        factory = viewModelFactory {
            initializer {
                MainViewModel(videoRepository)
            }
        },
    )
}

@Composable
fun InnerCircleSquaredTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFFEF5350),
            onPrimary = Color.White,
            background = Color.White,
            onBackground = Color(0xFF00695C),
        ),
        content = content,
    )
}

@Composable
fun LoginScreen(
    uiState: MainUiState,
    onPasswordChange: (String) -> Unit,
    onAudioOnlyChange: (Boolean) -> Unit,
    onShowVideosDialog: () -> Unit,
    onDismissVideosDialog: () -> Unit,
    onPlayVideo: (Int) -> Unit,
    onRetryLoadingVideos: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current

    Box(modifier = Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(Res.drawable.background_image),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Spacer(modifier = Modifier.height(20.dp))

            Row(
                modifier = Modifier
                    .width(200.dp)
                    .background(Color(0x99FFFFFF))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Audio only",
                    modifier = Modifier.weight(1f),
                    color = Color(0xFF00695C),
                )
                Switch(
                    checked = uiState.audioOnly,
                    onCheckedChange = onAudioOnlyChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color(0xFFEF5350),
                        checkedTrackColor = Color(0xFFE57373),
                    ),
                )
            }

            Spacer(modifier = Modifier.height(5.dp))

            Button(
                onClick = onShowVideosDialog,
                enabled = uiState.isLiveEventsEnabled,
                modifier = Modifier.width(150.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFEF5350),
                    disabledContainerColor = Color.Gray,
                ),
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
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                singleLine = true,
            )

            Spacer(modifier = Modifier.height(5.dp))

            Button(
                onClick = { uriHandler.openUri("https://www.propylaia.org:443/wordpress/") },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF5350)),
            ) {
                Text("Teaching Payments")
            }

            Spacer(modifier = Modifier.height(5.dp))

            Button(
                onClick = { uriHandler.openUri("https://s4898.americommerce.com") },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF5350)),
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
            onRetry = onRetryLoadingVideos,
        )
    }
}

@Composable
fun VideosDialog(
    videoList: List<Int>,
    isLoading: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onPlayVideo: (Int) -> Unit,
    onRetry: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Videos List") },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp),
            ) {
                when {
                    isLoading -> {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    }

                    error != null -> {
                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
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
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text("event $eventNumber")
                                }
                            }
                        }
                    }

                    else -> {
                        Text(
                            text = "No videos available",
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
    )
}
