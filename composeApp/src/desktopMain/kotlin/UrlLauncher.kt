import androidx.compose.runtime.Composable
import java.awt.Desktop
import java.net.URI

@Composable
actual fun openUrl(url: String) {
    Desktop.getDesktop().browse(URI.create(url))
}
