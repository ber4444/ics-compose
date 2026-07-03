package com.livingpresence.inner.circle.squared

import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier

/**
 * Decides how the video surface fills its container given the content aspect
 * ratio and the chosen [ResizeMode]. This is the horizontal & vertical video
 * layout matrix (an explicit job requirement): portrait content (AR < 1) fills
 * height with pillarboxing under FIT; landscape content (AR > 1) fills width.
 *
 * - [ResizeMode.FIT]  — letterbox/pillarbox so the whole frame is visible.
 * - [ResizeMode.FILL] — stretch to the container (may distort).
 * - [ResizeMode.ZOOM] — fill the container, cropping the overflow.
 */
internal fun videoSurfaceModifier(
    videoAspectRatio: Float,
    containerAspectRatio: Float,
    resizeMode: ResizeMode,
): Modifier = when (resizeMode) {
    ResizeMode.FILL -> Modifier.fillMaxSize()
    ResizeMode.ZOOM -> {
        // Fill whichever dimension overflows, cropping the rest.
        if (containerAspectRatio > videoAspectRatio) {
            Modifier.fillMaxWidth().aspectRatio(videoAspectRatio)
        } else {
            Modifier.fillMaxHeight().aspectRatio(videoAspectRatio)
        }
    }
    ResizeMode.FIT -> {
        // Contain: scale to the smaller-fitting dimension.
        if (containerAspectRatio > videoAspectRatio) {
            Modifier.fillMaxHeight().aspectRatio(videoAspectRatio)
        } else {
            Modifier.fillMaxWidth().aspectRatio(videoAspectRatio)
        }
    }
}

/** Whether the content is portrait (taller than wide). */
internal fun isPortraitVideo(videoAspectRatio: Float): Boolean =
    videoAspectRatio in 0f..1f
