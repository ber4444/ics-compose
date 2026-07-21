package com.livingpresence.inner.circle.squared

import com.livingpresence.inner.circle.squared.transcription.TranscriberStatus
import com.livingpresence.inner.circle.squared.transcription.TranscriptionProvider
import kotlinx.coroutines.flow.StateFlow

internal expect class CaptionAudioRouter private constructor() {
    val captions: StateFlow<List<CaptionCue>>
    val status: StateFlow<TranscriberStatus>
    val error: StateFlow<String?>

    fun enable(provider: TranscriptionProvider)
    fun switch(provider: TranscriptionProvider)
    fun disable()

    companion object {
        fun get(): CaptionAudioRouter
    }
}
