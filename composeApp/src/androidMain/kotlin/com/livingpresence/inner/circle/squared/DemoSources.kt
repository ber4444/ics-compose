package com.livingpresence.inner.circle.squared

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * Alternative demo streams used by the debug-only "Demo" menu (plan.md FU-4).
 * These prove the player is not hardwired to one server's conventions: they
 * cover a server-advertised ABR ladder with I-frame playlists (Apple bipbop),
 * a portrait/vertical clip (this server only has landscape content), and the
 * production events (the default path).
 *
 * Demo sources bypass ladder synthesis and are loaded as plain media items so
 * the production resolution path ([LadderMediaSourceBuilder]) is untouched —
 * they are an *additional* entry point, never a replacement.
 *
 * @param label User-facing label in the menu.
 * @param url The stream URL (HLS master playlist or a plain media file).
 * @param mimeAwareMimeType When non-null, the mime type forced on the
 *  [androidx.media3.common.MediaItem]. Null lets ExoPlayer infer the type —
 *  correct for `.mp4` clips and `.m3u8` masters, where forcing
 *  `APPLICATION_M3U8` on an MP4 would break playback.
 */
internal enum class DemoSource(val label: String, val url: String, val mimeAwareMimeType: String?) {
    /**
     * Apple's bipbop "advanced" fMP4 multivariant stream. The master advertises
     * a real ladder *and* `EXT-X-I-FRAME-STREAM-INF` playlists — genuine ABR
     * and trick-play that this project's production server lacks.
     */
    APPLE_BIPBOP(
        label = "Apple bipbop (ABR)",
        url = "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_fmp4/master.m3u8",
        mimeAwareMimeType = null,
    ),

    /**
     * A portrait (vertical) clip. The production streams are all 16:9, so this
     * exercises the portrait-video layout path (pillarboxing / resize modes)
     * and portrait PiP clamping. ExoPlayer infers the type from the extension
     * (no mime forcing).
     *
     * The URL is sourced from the `icsVerticalDemoUrl` gradle property (set it
     * to a portrait clip you host — any height>width MP4/M3U8 works); it
     * defaults to empty, in which case the menu entry is hidden until a URL is
     * configured. The portrait *code path* (resize matrix, PiP clamping,
     * orientation handling) is also exercised by [PlayerState] with a portrait
     * `videoSize` in tests; this entry provides a live demo when a source is
     * available.
     */
    VERTICAL_MP4(
        label = "Vertical (portrait)",
        url = VERTICAL_URL,
        mimeAwareMimeType = null,
    );

    companion object {
        /** Returns the [DemoSource] whose [url] matches, or null otherwise. */
        fun fromUrl(url: String): DemoSource? = entries.firstOrNull { it.url == url }

        /** Whether the vertical demo source has been configured (URL set). */
        val verticalConfigured: Boolean get() = VERTICAL_URL.isNotBlank()
    }
}

/**
 * The vertical/portrait demo clip URL — from the `icsVerticalDemoUrl` gradle
 * property (default empty → the menu entry is hidden). Point it at a portrait
 * clip you control: `-PicsVerticalDemoUrl=https://host/portrait.mp4`.
 */
private val VERTICAL_URL: String =
    com.livingpresence.inner.circle.squared.BuildConfig.VERTICAL_DEMO_URL

/**
 * Debug-only menu offering the [DemoSource]s plus a "Production" entry that
 * returns to the resolved event stream. Selecting a demo source loads it as a
 * plain media item, bypassing ladder synthesis. Gated on [BuildConfig.DEBUG] at
 * the call site so release builds are unaffected.
 *
 * @param activeUrl The URL currently playing, so the active entry can be
 *  marked. May be a demo URL or the production (ladder-resolved) URI.
 */
@Composable
internal fun DemoSourcesMenu(
    activeUrl: String,
    onDemoSourceSelected: (DemoSource) -> Unit,
    onProductionSelected: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val activeSource = DemoSource.fromUrl(activeUrl)

    Box(modifier = modifier) {
        TextButton(onClick = { expanded = true }) {
            Text("Demo", color = Color.White)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DemoSource.entries.forEach { source ->
                // Skip the vertical entry when its URL isn't configured (no
                // durable public portrait source exists — see VERTICAL_URL).
                if (source == DemoSource.VERTICAL_MP4 && !DemoSource.verticalConfigured) return@forEach
                DropdownMenuItem(
                    text = { Text(markLabel(source.label, source == activeSource)) },
                    onClick = {
                        onDemoSourceSelected(source)
                        expanded = false
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(markLabel("Production events", activeSource == null)) },
                onClick = {
                    onProductionSelected()
                    expanded = false
                },
            )
        }
    }
}

/** Prefixes the active entry with "✓" so the current source is obvious. */
private fun markLabel(label: String, active: Boolean): String =
    if (active) "✓ $label" else label
