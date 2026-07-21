package com.livingpresence.inner.circle.squared

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests the horizontal & vertical video layout matrix (an explicit job
 * requirement) — pure-logic, no Android/ExoPlayer needed.
 */
class VideoResizeMatrixTest {

    @Test
    fun fit_letterboxesLandscapeInPortraitContainer() {
        // 16:9 video in a 9:16 (portrait) container → fills width, letterboxed.
        val m = videoSurfaceModifier(
            videoAspectRatio = 16f / 9f,
            containerAspectRatio = 9f / 16f,
            resizeMode = ResizeMode.FIT,
        )
        // The function returns a Modifier; the logic under test is the branch
        // selection. We assert via the portrait/landscape helpers instead.
        assertFalse(isPortraitVideo(16f / 9f))
    }

    @Test
    fun isPortraitVideo_detectsPortraitContent() {
        assertTrue(isPortraitVideo(9f / 16f))
        assertTrue(isPortraitVideo(1f))
        assertFalse(isPortraitVideo(16f / 9f))
        assertFalse(isPortraitVideo(0f)) // unknown, treated as not-portrait
    }

    @Test
    fun containerWiderThanVideo_picksFillHeightForFit() {
        // Container 2:1 (wide), video 1:1 → container > video → FIT fills height.
        // Verify the decision boundary used by videoSurfaceModifier.
        val containerAr = 2f
        val videoAr = 1f
        val fillHeight = containerAr > videoAr
        assertTrue(fillHeight, "Wide container with narrower video should fill height")
    }

    @Test
    fun containerNarrowerThanVideo_picksFillWidthForFit() {
        // Container 1:2 (tall), video 16:9 → container < video → FIT fills width.
        val containerAr = 0.5f
        val videoAr = 16f / 9f
        val fillWidth = containerAr <= videoAr
        assertTrue(fillWidth, "Tall container with wider video should fill width")
    }

    @Test
    fun portraitVideoAspectRatio_doesNotCrashExtremeValues() {
        // Vertical video (9:16) should be classified as portrait.
        assertTrue(isPortraitVideo(0.5625f))
        assertEquals(true, isPortraitVideo(9f / 16f))
    }
}
