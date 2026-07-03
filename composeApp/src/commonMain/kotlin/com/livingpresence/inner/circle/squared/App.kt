package com.livingpresence.inner.circle.squared

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.savedstate.read
import com.livingpresence.inner.circle.squared.generated.resources.Res
import com.livingpresence.inner.circle.squared.generated.resources.background_image
import org.jetbrains.compose.resources.painterResource

private object AppRoute {
    const val Login = "login"
    const val Gallery = "gallery"
    const val PlayerEventNumberArg = "eventNumber"
    const val Player = "player/{$PlayerEventNumberArg}"

    fun player(eventNumber: Int): String = "player/$eventNumber"
}

@Composable
fun App() {
    InnerCircleSquaredTheme {
        val viewModelStoreOwner = remember {
            object : ViewModelStoreOwner {
                override val viewModelStore = ViewModelStore()
            }
        }
        DisposableEffect(viewModelStoreOwner) {
            onDispose {
                viewModelStoreOwner.viewModelStore.clear()
            }
        }

        CompositionLocalProvider(LocalViewModelStoreOwner provides viewModelStoreOwner) {
            val mainViewModel = rememberMainViewModel()
            val uiState by mainViewModel.uiState.collectAsState()
            val navController = rememberNavController()

            NavHost(
                navController = navController,
                startDestination = AppRoute.Login,
                modifier = Modifier.fillMaxSize(),
            ) {
                composable(route = AppRoute.Login) {
                    LoginScreen(
                        uiState = uiState,
                        onPasswordChange = mainViewModel::onPasswordChanged,
                        onShowGallery = {
                            mainViewModel.showGallery()
                            navController.navigate(AppRoute.Gallery)
                        },
                    )
                }

                composable(route = AppRoute.Gallery) {
                    GalleryScreen(
                        uiState = uiState,
                        onRetry = mainViewModel::retryLoadingVideos,
                        onPlayEvent = { eventNumber ->
                            mainViewModel.playVideo(eventNumber)
                            navController.navigate(AppRoute.player(eventNumber))
                        },
                        onClose = navController::popBackStack,
                    )
                }

                composable(
                    route = AppRoute.Player,
                    arguments = listOf(
                        navArgument(AppRoute.PlayerEventNumberArg) {
                            type = NavType.IntType
                        },
                    ),
                ) { backStackEntry ->
                    val arguments = checkNotNull(backStackEntry.arguments)
                    val eventNumber = arguments.read { getInt(AppRoute.PlayerEventNumberArg) }

                    PlatformPlayerScreen(
                        url = getUrl(eventNumber),
                        onClose = navController::popBackStack,
                    )
                }
            }
        }
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
                MainViewModel(videoRepository, eventsPassword())
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
    onShowGallery: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val backgroundPainter = painterResource(Res.drawable.background_image)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds(),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = backgroundPainter,
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

            Button(
                onClick = onShowGallery,
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
                label = { Text("Login to events") },
                modifier = Modifier
                    .width(160.dp)
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
}

/**
 * Full-screen gallery of available events. Hosts the [LiveEventsGallery] feed
 * with a top bar offering a back/close affordance.
 */
@Composable
fun GalleryScreen(
    uiState: MainUiState,
    onRetry: () -> Unit,
    onPlayEvent: (Int) -> Unit,
    onClose: () -> Unit,
) {
    val downloadController = rememberDownloadController()
    val downloadStates by downloadController.states.collectAsState()

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        LiveEventsGallery(
            events = uiState.availableEvents,
            isLoading = uiState.isLoadingVideos,
            error = uiState.videoLoadError,
            onPlayEvent = onPlayEvent,
            onRetry = onRetry,
            modifier = Modifier.fillMaxSize(),
            downloadStates = downloadStates,
            onDownload = downloadController::enqueue,
            onRemoveDownload = downloadController::remove,
        )

        TextButton(
            onClick = onClose,
            modifier = Modifier.padding(8.dp),
        ) {
            Text("Close")
        }
    }
}
