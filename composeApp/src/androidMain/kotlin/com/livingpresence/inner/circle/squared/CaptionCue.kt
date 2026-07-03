package com.livingpresence.inner.circle.squared

/**
 * A single recognized-caption span shown by the caption overlay.
 *
 * [startMs]/[endMs] are player-clock-aligned (content position) timestamps in
 * milliseconds; `endMs == -1` marks an open/active partial that is still being
 * refined by the recognizer.
 */
data class CaptionCue(
    val text: String,
    val startMs: Long,
    val endMs: Long,
)
