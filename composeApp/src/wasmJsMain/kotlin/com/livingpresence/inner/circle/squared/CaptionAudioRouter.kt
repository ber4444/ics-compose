package com.livingpresence.inner.circle.squared

import com.livingpresence.inner.circle.squared.transcription.LiveTranscriber
import com.livingpresence.inner.circle.squared.transcription.TranscriberStatus
import com.livingpresence.inner.circle.squared.transcription.TranscriptionProvider
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt

/**
 * Web audio bridge for live captions.
 * Resamples float buffers to 16 kHz mono s16le for the LiveTranscriber.
 */
internal actual class CaptionAudioRouter private actual constructor() {

    private val live = LiveTranscriber()

    actual val captions: StateFlow<List<CaptionCue>> = live.captions
    actual val status: StateFlow<TranscriberStatus> = live.status
    actual val error: StateFlow<String?> = live.error

    actual fun enable(provider: TranscriptionProvider) = live.enable(provider)
    actual fun switch(provider: TranscriptionProvider) = live.enable(provider)
    actual fun disable() = live.disable()

    fun onPcm(pcmData: FloatArray, numFrames: Int, channels: Int, sampleRate: Int) {
        if (numFrames <= 0 || channels <= 0) return
        val pcm16 = resampleTo16kMonoS16(pcmData, numFrames, sampleRate, channels)
        if (pcm16.isNotEmpty()) live.feedPcm(pcm16)
    }

    private fun resampleTo16kMonoS16(
        pcmData: FloatArray,
        numFrames: Int,
        inRate: Int,
        channels: Int
    ): ByteArray {
        val mono = ShortArray(numFrames) { i ->
            var sum = 0f
            for (c in 0 until channels) {
                sum += pcmData[i * channels + c]
            }
            ((sum / channels) * 32767f).roundToInt().coerceIn(-32768, 32767).toShort()
        }

        val outFrames = if (inRate == TARGET_SAMPLE_RATE_HZ) mono.size
            else (mono.size.toLong() * TARGET_SAMPLE_RATE_HZ / inRate).toInt()
        if (outFrames <= 0) return ByteArray(0)
        
        val out = ShortArray(outFrames)
        if (inRate == TARGET_SAMPLE_RATE_HZ) {
            mono.copyInto(out, endIndex = outFrames)
        } else {
            val step = mono.size.toDouble() / outFrames
            for (i in 0 until outFrames) {
                val start = (i * step).toInt()
                val end = ((i + 1) * step).toInt().coerceIn(start + 1, mono.size)
                var sum = 0
                for (j in start until end) sum += mono[j].toInt()
                out[i] = (sum / (end - start)).toShort()
            }
        }
        
        val result = ByteArray(out.size * 2)
        for (i in out.indices) {
            val s = out[i].toInt()
            result[i * 2] = (s and 0xFF).toByte()
            result[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return result
    }

    actual companion object {
        private const val TARGET_SAMPLE_RATE_HZ = 16000
        private val _instance by lazy { CaptionAudioRouter() }

        actual fun get(): CaptionAudioRouter = _instance
    }
}
