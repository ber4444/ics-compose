package com.livingpresence.inner.circle.squared

import android.media.AudioFormat
import com.livingpresence.inner.circle.squared.transcription.LiveTranscriber
import com.livingpresence.inner.circle.squared.transcription.TranscriberStatus
import com.livingpresence.inner.circle.squared.transcription.TranscriptionProvider
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.flow.StateFlow

/** Receives decoded PCM from the player's [TeeAudioProcessor] tap. */
internal interface PcmTapSink {
    fun onPcm(buffer: ByteBuffer, sampleRate: Int, channels: Int, encoding: Int)
}

/**
 * Android audio bridge for live captions: the service player's PCM tap feeds this,
 * it resamples each buffer to the providers' required 16 kHz mono s16le, and hands
 * it to the shared [LiveTranscriber] (which owns the selected Deepgram/Soniox client
 * and publishes captions). Process singleton so it outlives the player screen.
 *
 * All provider selection / caption plumbing lives in the shared [LiveTranscriber];
 * this class is only the Android-specific capture + resample.
 */
internal class CaptionAudioRouter private constructor() : PcmTapSink {

    companion object {
        private const val TARGET_SAMPLE_RATE_HZ = 16000
    }

    private val live = LiveTranscriber()

    val captions: StateFlow<List<CaptionCue>> = live.captions
    val status: StateFlow<TranscriberStatus> = live.status
    val error: StateFlow<String?> = live.error

    fun enable(provider: TranscriptionProvider) = live.enable(provider)
    fun switch(provider: TranscriptionProvider) = live.enable(provider)
    fun disable() = live.disable()

    override fun onPcm(buffer: ByteBuffer, sampleRate: Int, channels: Int, encoding: Int) {
        val copy = ByteArray(buffer.remaining())
        buffer.duplicate().get(copy)
        val pcm16 = resampleTo16kMonoS16(copy, sampleRate, channels, encoding)
        if (pcm16.isNotEmpty()) live.feedPcm(pcm16)
    }

    /**
     * Down-mixes to mono and anti-alias-decimates to 16 kHz, emitting signed 16-bit
     * little-endian PCM (what Deepgram/Soniox expect). Averaging (not nearest-sample)
     * decimation avoids the aliasing that garbled recognition in the old path.
     */
    private fun resampleTo16kMonoS16(bytes: ByteArray, inRate: Int, channels: Int, encoding: Int): ByteArray {
        if (bytes.size < 2 || channels < 1) return ByteArray(0)
        val src = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val mono: ShortArray = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
            val frames = bytes.size / (4 * channels)
            if (frames == 0) return ByteArray(0)
            ShortArray(frames) {
                var sum = 0f
                for (c in 0 until channels) sum += src.float
                ((sum / channels) * 32767f).toInt().coerceIn(-32768, 32767).toShort()
            }
        } else {
            val frames = bytes.size / (2 * channels)
            if (frames == 0) return ByteArray(0)
            ShortArray(frames) {
                var sum = 0
                for (c in 0 until channels) sum += src.short.toInt()
                (sum / channels).toShort()
            }
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
                for (j in start until end) sum += mono[j]
                out[i] = (sum / (end - start)).toShort()
            }
        }
        val result = ByteArray(out.size * 2)
        ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(out)
        return result
    }

    companion object {
        @Volatile
        private var instance: CaptionAudioRouter? = null

        fun get(): CaptionAudioRouter =
            instance ?: synchronized(this) {
                instance ?: CaptionAudioRouter().also { instance = it }
            }
    }
}
