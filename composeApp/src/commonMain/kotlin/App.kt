import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun App() {
    var currentScreen by remember { mutableStateOf<Screen>(Screen.Login) }

    MaterialTheme {
        when (val screen = currentScreen) {
            is Screen.Login -> LoginPage(onNavigateToVideoPlayer = { url ->
                currentScreen = Screen.VideoPlayer(url)
            })
            is Screen.VideoPlayer -> VideoPlayerScreen(
                url = screen.url,
                onNavigateBack = { currentScreen = Screen.Login }
            )
        }
    }
}

@Composable
fun LoginPage(onNavigateToVideoPlayer: (String) -> Unit) {
    var password by remember { mutableStateOf("") }
    var audioOnly by remember { mutableStateOf(false) }
    var isPasswordCorrect by remember { mutableStateOf(false) }
    var showVideoList by remember { mutableStateOf(false) }

    if (showVideoList) {
        VideoListDialog(
            onDismiss = { showVideoList = false },
            onVideoSelected = { videoId ->
                val url = "https://65e54f30ec73c.streamlock.net:443/live/$videoId" +
                        (if (audioOnly) "_aac" else "") + "/playlist.m3u8?DVR"
                onNavigateToVideoPlayer(url)
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Image(
            painter = painterResource("image3"),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            SwitchListTile(
                value = audioOnly,
                onValueChange = { audioOnly = it },
                title = { Text("Audio only") }
            )

            Button(
                onClick = { showVideoList = true },
                enabled = isPasswordCorrect
            ) {
                Text("Live Events")
            }

            Spacer(modifier = Modifier.height(16.dp))

            TextField(
                value = password,
                onValueChange = {
                    password = it
                    isPasswordCorrect = (it == "be2BE")
                },
                label = { Text("Password") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.width(200.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(onClick = { openUrl("https://www.propylaia.org:443/wordpress/") }) {
                Text("Teaching Payments")
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(onClick = { openUrl("https://s4898.americommerce.com") }) {
                Text("Event Payments")
            }
        }
    }
}

@Composable
fun VideoPlayerScreen(url: String, onNavigateBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Video Player") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) {
        VideoPlayer(
            modifier = Modifier.fillMaxSize(),
            url = url
        )
    }
}

@Composable
fun SwitchListTile(
    value: Boolean,
    onValueChange: (Boolean) -> Unit,
    title: @Composable () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 16.dp)
    ) {
        title()
        Spacer(modifier = Modifier.weight(1f))
        Switch(
            checked = value,
            onCheckedChange = onValueChange
        )
    }
}
