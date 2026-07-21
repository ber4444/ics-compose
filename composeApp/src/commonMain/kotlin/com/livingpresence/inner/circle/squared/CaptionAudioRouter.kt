package com.livingpresence.inner.circle.squared

import com.livingpresence.inner.circle.squared.transcription.LiveTranscriber
import com.livingpresence.inner.circle.squared.transcription.TranscriberStatus
import com.livingpresence.inner.circle.squared.transcription.TranscriptionProvider
import com.livingpresence.inner.circle.squared.transcription.resampleTo16kMonoS16
import kotlinx.coroutines.flow.StateFlow

/**
 * Process-singleton bridge between a platform audio tap and the shared
 * [LiveTranscriber] (which owns the selected Deepgram/Soniox client and publishes
 * captions). Outlives the player screen so recognition survives navigation.
 *
 * Everything here is shared; only the PCM *ingestion* is platform-specific and lives
 * in each source set's `onPcm` extension: the tap downmixes its native buffer
 * (Android `ByteBuffer` / iOS Core Media float pointer / JS `FloatArray`) to mono
 * 16-bit and calls [feedMono]. Rate conversion, provider selection, and caption
 * plumbing are all common.
 */
internal class CaptionAudioRouter private constructor() {

    private val live = LiveTranscriber()

    val captions: StateFlow<List<CaptionCue>> = live.captions
    val status: StateFlow<TranscriberStatus> = live.status
    val error: StateFlow<String?> = live.error

    fun enable(provider: TranscriptionProvider) = live.enable(provider)
    fun switch(provider: TranscriptionProvider) = live.enable(provider)
    fun disable() = live.disable()

    /**
     * Feed already-downmixed mono 16-bit PCM sampled at [inRate]. The shared
     * [resampleTo16kMonoS16] handles the anti-aliased decimation to 16 kHz and the
     * s16le packing the streaming clients expect before the bytes reach the transcriber.
     */
    fun feedMono(mono: ShortArray, inRate: Int) {
        val pcm16 = mono.resampleTo16kMonoS16(inRate)
        if (pcm16.isNotEmpty()) live.feedPcm(pcm16)
    }

    companion object {
        private val instance by lazy { CaptionAudioRouter() }

        fun get(): CaptionAudioRouter = instance
    }
}

/**
 * Shared inline helper to downmix interleaved float PCM samples to mono 16-bit short PCM.
 * [getSample] provides the float sample at a specific absolute array/buffer index.
 */
internal inline fun downmixFloatToMono16(
    numFrames: Int,
    channels: Int,
    getSample: (index: Int) -> Float
): ShortArray {
    if (numFrames <= 0 || channels <= 0) return ShortArray(0)
    return ShortArray(numFrames) { i ->
        var sum = 0f
        val offset = i * channels
        for (c in 0 until channels) {
            sum += getSample(offset + c)
        }
        ((sum / channels) * 32767f).toInt().coerceIn(-32768, 32767).toShort()
    }
}
