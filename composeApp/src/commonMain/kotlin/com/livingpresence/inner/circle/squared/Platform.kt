package com.livingpresence.inner.circle.squared

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.livingpresence.mediakit.MediaKitConfig
import io.ktor.client.HttpClient

expect fun createHttpClient(): HttpClient

/**
 * The login-gate password, sourced per-platform (Android: BuildConfig from a
 * gradle property; wasmJs: a default) so it isn't in common source.
 */
expect fun eventsPassword(): String

@Composable
expect fun PlatformPlayerScreen(
    url: String,
    onClose: () -> Unit,
)

/**
 * Platform-specific thumbnail for an event tile. Android renders a frame
 * extracted from the `_160p` rendition via a shared [PreviewFrameEngine];
 * wasmJs shows a poster placeholder with a hover-to-play overlay.
 *
 * @param eventNumber The event whose frame to show.
 * @param contentDescription Accessibility description for the thumbnail.
 * @param modifier Layout modifier from the tile.
 */
@Composable
expect fun LiveEventThumbnail(
    eventNumber: Int,
    contentDescription: String?,
    modifier: Modifier,
)

/**
 * The base (720p) playlist URL for [eventNumber]. Delegates to [MediaKitConfig]
 * so the production host lives in one place (the `:mediakit` SDK) rather than
 * being sprinkled through app code.
 */
fun getUrl(eventNumber: Int): String =
    MediaKitConfig.Default.eventUrl(eventNumber)

/**
 * Background for the login screen. Android renders the `background_image`
 * resource (works there); wasmJs uses a gradient because decoding the raster
 * JPEG via `Image(painterResource(...))` throws an unrecoverable exception under
 * Kotlin/Wasm (a Skiko image-decode crash on this asset that kills composition).
 */
@Composable
expect fun loginBackgroundModifier(): Modifier
