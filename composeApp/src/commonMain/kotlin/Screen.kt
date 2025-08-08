sealed class Screen {
    object Login : Screen()
    data class VideoPlayer(val url: String) : Screen()
}
