package com.livingpresence.inner.circle.squared

import android.content.pm.ActivityInfo
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * Rotate-to-fullscreen for landscape content (plan.md FU-4 / Phase 3).
 *
 * When the user taps "Rotate" the activity is locked to sensor-landscape so
 * landscape video gets the full screen; closing the player (or toggling off)
 * restores the user's orientation preference.
 *
 * The mechanism is the activity's
 * [android.app.Activity.setRequestedOrientation]: the [LocalContext] is
 * unwrapped to the host [android.app.Activity] via [findActivity] (also used
 * for immersive mode). We use
 * [ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE] rather than a hard
 * `LANDSCAPE` lock so the sensor still picks the comfortable of the two
 * landscape orientations — the manual button *requests* landscape and the
 * sensor handles the rest, which is the behavior the plan describes ("sensor
 * plus manual button").
 *
 * Portrait content is left alone: a 9:16 clip in a portrait activity already
 * fills the screen, so the rotate affordance is only offered for landscape
 * video (see [ExoPlayerScreen]).
 *
 * On exit the orientation is restored via [DisposableEffect], so navigating
 * away never strands the user in a forced landscape.
 */
@Composable
internal fun rememberFullscreenOrientation(isFullscreen: Boolean) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() } ?: return

    DisposableEffect(activity) {
        onDispose {
            // Leaving the player entirely → hand orientation back to the user.
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    LaunchedEffect(activity, isFullscreen) {
        activity.requestedOrientation = if (isFullscreen) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
}

/**
 * Tracks the rotate-to-fullscreen toggle. The affordance is only relevant for
 * landscape content; the caller decides whether to render it.
 *
 * Restores to non-fullscreen automatically when the player is closed — handled
 * by [rememberFullscreenOrientation]'s onDispose.
 */
@Composable
internal fun rememberFullscreenToggle(): FullscreenToggle {
    val toggle = remember { FullscreenToggle() }
    rememberFullscreenOrientation(toggle.isFullscreen)
    return toggle
}

/**
 * Holds the fullscreen-rotate state. Owns its own [mutableStateOf] so there's
 * no lambda-capture cycle between the holder and its mutators; the composable
 * above observes [isFullscreen] for the orientation side-effect.
 */
internal class FullscreenToggle {
    var isFullscreen by mutableStateOf(false)
        private set

    /** Flip between portrait (user default) and landscape fullscreen. */
    fun toggle() {
        isFullscreen = !isFullscreen
    }

    /** Force back to non-fullscreen (used when the player is closing). */
    fun exit() {
        isFullscreen = false
    }
}

/**
 * Manual "Rotate" / "Portrait" button. Offers landscape fullscreen for
 * landscape content; when already fullscreen it offers to return to portrait.
 */
@Composable
internal fun RotateButton(toggle: FullscreenToggle) {
    TextButton(onClick = { toggle.toggle() }) {
        Text(
            text = if (toggle.isFullscreen) "Portrait" else "Rotate",
            color = Color.White,
        )
    }
}
