package com.livingpresence.inner.circle.squared.transcription

import com.livingpresence.inner.circle.squared.CaptionCue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Maintains the rolling transcript the caption overlay renders: a bounded list of
 * finalized cues plus at most one open "partial" cue (`endMs == -1`) that is still
 * being refined. Both streaming clients share this so their caption behaviour is
 * identical.
 *
 * Written by a single websocket receive loop per client; [MutableStateFlow.update]
 * keeps mutations atomic without a JVM-only lock (so it stays `commonMain`-safe).
 */
class CaptionAccumulator(private val maxCues: Int = MAX_CUES) {
    private val _captions = MutableStateFlow<List<CaptionCue>>(emptyList())
    val captions: StateFlow<List<CaptionCue>> = _captions.asStateFlow()

    /** Replaces the trailing open/partial cue with [text] (interim result). */
    fun setPartial(text: String) {
        if (text.isBlank()) return
        _captions.update { current ->
            (current.filter { it.endMs != -1L } + CaptionCue(text.trim(), startMs = 0, endMs = -1L))
                .takeLast(maxCues)
        }
    }

    /** Commits [text] as a finalized cue and drops any open partial. */
    fun appendFinal(text: String) {
        if (text.isBlank()) return
        _captions.update { current ->
            (current.filter { it.endMs != -1L } + CaptionCue(text.trim(), startMs = 0, endMs = 0))
                .takeLast(maxCues)
        }
    }

    fun clear() {
        _captions.value = emptyList()
    }

    private companion object {
        const val MAX_CUES = 30
    }
}
