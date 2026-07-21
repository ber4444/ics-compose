package com.livingpresence.inner.circle.squared

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit tests for the playback-time formatting used by [PlayerControlsOverlay].
 *
 * The visual layout / click wiring of the overlay is a Compose UI concern that needs a
 * device or a Robolectric-hosted activity; here we cover the pure formatting logic that
 * drives the position/duration labels, which runs on every platform without a runtime.
 */
class PlayerControlsOverlayTest {

    @Test
    fun formatsMinutesAndSeconds() {
        assertEquals("0:00", formatPlaybackTime(0L))
        assertEquals("0:05", formatPlaybackTime(5 * 1000L))
        assertEquals("0:25", formatPlaybackTime(25 * 1000L))
        assertEquals("19:25", formatPlaybackTime(19 * 60 * 1000L + 25 * 1000L))
        assertEquals("47:00", formatPlaybackTime(47 * 60 * 1000L))
    }

    @Test
    fun formatsHoursWhenPresent() {
        assertEquals("1:00:00", formatPlaybackTime(60 * 60 * 1000L))
        assertEquals("1:05:03", formatPlaybackTime((60 * 60 + 5 * 60 + 3) * 1000L))
        assertEquals("10:00:00", formatPlaybackTime(10 * 60 * 60 * 1000L))
    }

    @Test
    fun clampsNegativeToZero() {
        assertEquals("0:00", formatPlaybackTime(-5_000L))
    }

    @Test
    fun truncatesSubSecondMillis() {
        assertEquals("0:01", formatPlaybackTime(1_999L))
    }
}
