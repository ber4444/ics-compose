package com.livingpresence.inner.circle.squared

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class PlayerControlsOverlayTest {

    @Test
    fun testControlsAreVisibleAndCorrectlyLaidOut() = runComposeUiTest {
        var playToggleCalled = false
        var closeCalled = false

        setContent {
            PlayerControlsOverlay(
                modifier = Modifier.size(800.dp, 600.dp),
                isPlaying = false,
                durationMs = 47 * 60 * 1000L, // 47:00
                positionMs = 19 * 60 * 1000L + 25 * 1000L, // 19:25
                isLive = false,
                isSeekable = true,
                isScrubbing = false,
                sliderFraction = 0.5f,
                onSliderValueChange = {},
                onSliderValueChangeFinished = {},
                onPlayPauseToggle = { playToggleCalled = true },
                onJumpToLive = {},
                onClose = { closeCalled = true },
                topRightControls = {
                    Text("Quality")
                    Text("Stats")
                    Text("CC")
                }
            )
        }

        // Check top right controls
        onNodeWithText("Quality").assertExists()
        onNodeWithText("Stats").assertExists()
        onNodeWithText("CC").assertExists()

        // Check Top left control
        onNodeWithText("Close").assertExists()
        onNodeWithText("Close").performClick()
        assertTrue(closeCalled, "Close callback should be called")

        // Check formatted time
        onNodeWithText("19:25").assertExists()
        onNodeWithText("47:00").assertExists()

        // Check play/pause button
        onNodeWithText("Play").assertExists()
        onNodeWithText("Play").performClick()
        assertTrue(playToggleCalled, "Play toggle callback should be called")
    }
}
