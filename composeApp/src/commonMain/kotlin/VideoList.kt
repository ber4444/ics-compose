import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.AlertDialog
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*

@Composable
fun VideoListDialog(onDismiss: () -> Unit, onVideoSelected: (String) -> Unit) {
    var videoList by remember { mutableStateOf<List<Int>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try {
            videoList = fetchVideoList()
        } catch (e: Exception) {
            error = e.message
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Live Events") },
        text = {
            when {
                videoList != null -> {
                    LazyColumn {
                        items(videoList!!) { videoId ->
                            Text(
                                text = "event $videoId",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onVideoSelected("event$videoId") }
                            )
                        }
                    }
                }
                error != null -> {
                    Text("Error: $error")
                }
                else -> {
                    CircularProgressIndicator()
                }
            }
        },
        confirmButton = {}
    )
}

private suspend fun fetchVideoList(): List<Int> {
    val client = HttpClient(CIO)
    val good = mutableListOf<Int>()
    for (i in 20 downTo 1) {
        try {
            val response: HttpResponse = client.get("https://65e54f30ec73c.streamlock.net:443/live/event$i/playlist.m3u8?DVR")
            if (response.status.value != 404) {
                good.add(i)
            }
        } catch (e: Exception) {
            // Ignore errors for individual requests
        }
    }
    client.close()
    return good
}
