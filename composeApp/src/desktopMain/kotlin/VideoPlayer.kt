import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import uk.co.caprica.vlcj.player.component.EmbeddedMediaPlayerComponent

@Composable
actual fun VideoPlayer(modifier: Modifier, url: String) {
    val mediaPlayerComponent = remember { EmbeddedMediaPlayerComponent() }
    SwingPanel(
        factory = {
            mediaPlayerComponent
        },
        modifier = modifier
    )
    mediaPlayerComponent.mediaPlayer().media().play(url)
}
