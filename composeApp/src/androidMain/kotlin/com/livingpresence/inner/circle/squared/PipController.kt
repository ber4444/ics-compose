package com.livingpresence.inner.circle.squared

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Lets the player screen report its video size + playing state to the host
 * [MainActivity] so PiP params can use the correct (clamped) aspect ratio and
 * auto-PiP on leave only triggers while actually playing.
 */
interface PipController {
    fun updateVideoSize(width: Int, height: Int)
    fun setPlaying(playing: Boolean)
}

val LocalPipController = staticCompositionLocalOf<PipController?> { null }
