@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.livingpresence.inner.circle.squared

import com.livingpresence.inner.circle.squared.transcription.LiveTranscriber
import com.livingpresence.inner.circle.squared.transcription.TranscriberStatus
import com.livingpresence.inner.circle.squared.transcription.TranscriptionProvider
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.get
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt

/**
 * iOS audio bridge for live captions: the player's MTAudioProcessingTap feeds this,
 * it resamples each buffer to the providers' required 16 kHz mono s16le, and hands
 * it to the shared [LiveTranscriber]. Process singleton so it outlives the player screen.
 */
internal class CaptionAudioRouter private constructor() {

    private val live = LiveTranscriber()

    val captions: StateFlow<List<CaptionCue>> = live.captions
    val status: StateFlow<TranscriberStatus> = live.status
    val error: StateFlow<String?> = live.error

    fun enable(provider: TranscriptionProvider) = live.enable(provider)
    fun switch(provider: TranscriptionProvider) = live.enable(provider)
    fun disable() = live.disable()

    fun onPcm(pcmData: CPointer<FloatVar>?, numFrames: Int, channels: Int, sampleRate: Int) {
        if (pcmData == null || numFrames <= 0 || channels <= 0) return
        val pcm16 = resampleTo16kMonoS16(pcmData, numFrames, sampleRate, channels)
        if (pcm16.isNotEmpty()) live.feedPcm(pcm16)
    }

    private fun resampleTo16kMonoS16(
        pcmData: CPointer<FloatVar>,
        numFrames: Int,
        inRate: Int,
        channels: Int
    ): ByteArray {
        // 1. Downmix to mono (if needed) and convert to 16-bit short
        val mono = ShortArray(numFrames) { i ->
            var sum = 0f
            for (c in 0 until channels) {
                sum += pcmData[i * channels + c]
            }
            ((sum / channels) * 32767f).roundToInt().coerceIn(-32768, 32767).toShort()
        }

        // 2. Resample to TARGET_SAMPLE_RATE_HZ (16000)
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
        
        // 3. Convert to little-endian bytes
        val result = ByteArray(out.size * 2)
        for (i in out.indices) {
            val s = out[i].toInt()
            result[i * 2] = (s and 0xFF).toByte()
            result[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return result
    }

    companion object {
        private const val TARGET_SAMPLE_RATE_HZ = 16000

        private val _instance by lazy { CaptionAudioRouter() }

        fun get(): CaptionAudioRouter = _instance
    }
}
