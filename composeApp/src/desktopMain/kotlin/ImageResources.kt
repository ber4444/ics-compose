import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource

@Composable
actual fun painterResource(name: String): Painter {
    return painterResource("drawable/$name")
}
