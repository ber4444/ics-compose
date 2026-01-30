package com.livingpresence.inner.circle.squared

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.ktor.client.*
import io.ktor.client.engine.android.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            InnerCircleSquaredTheme {
                LoginScreen()
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
fun LoginScreen() {
    var password by remember { mutableStateOf("") }
    var audioOnly by remember { mutableStateOf(false) }
    var showDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    
    val isEnabled = password == "be2BE"

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

            // Password instruction
            Text(
                text = "Enter the password\nfrom the Video Test page\non Inner Circle Squared.",
                textAlign = TextAlign.Center,
                color = Color.Red,
                modifier = Modifier
                    .background(Color(0x99FFFFFF))
                    .padding(8.dp),
                style = MaterialTheme.typography.bodyMedium
            )

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
                modifier = Modifier.width(150.dp),
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
                modifier = Modifier.width(150.dp),
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
            onDismiss = { showDialog = false }
        )
    }
}

@Composable
fun VideosDialog(audioOnly: Boolean, onDismiss: () -> Unit) {
    var videoList by remember { mutableStateOf<List<Int>?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

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
                                        goToFullVideos(context, url, audioOnly)
                                        onDismiss()
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
    val client = HttpClient(Android)
    
    try {
        for (i in 20 downTo 1) {
            try {
                val response: HttpResponse = client.get(getUrl(i, false))
                if (response.status.value != 404) {
                    good.add(i)
                }
            } catch (e: Exception) {
                // Ignore errors for individual requests
            }
        }
    } finally {
        client.close()
    }
    
    return good
}

fun getUrl(eventNumber: Int, audioOnly: Boolean): String {
    val audioSuffix = if (audioOnly) "_aac" else ""
    return "https://65e54f30ec73c.streamlock.net:443/live/event$eventNumber$audioSuffix/playlist.m3u8?DVR"
}

fun goToFullVideos(context: android.content.Context, url: String, audioOnly: Boolean) {
    val uri = Uri.parse(url)
    val intent = Intent(Intent.ACTION_VIEW)
    intent.setPackage("com.mxtech.videoplayer.pro")
    
    intent.setDataAndType(uri, "video/*")
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    intent.putExtra("decode_mode", 2.toByte())
    intent.putExtra("orientation", 0)
    if (audioOnly) intent.putExtra("video", false)
    intent.putExtra("sticky", true)
    intent.putExtra("secure_uri", true)
    intent.putExtra("video_zoom", 2)
    intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
    
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        try {
            val packageName = "com.mxtech.videoplayer.ad"
            intent.setPackage(packageName)
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=MX+player"))
            )
        }
    }
}

fun handleURLButtonPress(context: android.content.Context, url: String) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        context.startActivity(intent)
    } catch (e: Exception) {
        e.printStackTrace()
    }
}
