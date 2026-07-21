@file:JvmName("CaptionAudioRouterAndroid")

package com.livingpresence.inner.circle.squared

import android.media.AudioFormat
import com.livingpresence.inner.circle.squared.transcription.resampleTo16kMonoS16
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Receives decoded PCM from the player's [TeeAudioProcessor] tap. */
internal interface PcmTapSink {
    fun onPcm(buffer: ByteBuffer, sampleRate: Int, channels: Int, encoding: Int)
}

/**
 * Adapts the shared [CaptionAudioRouter] to the ExoPlayer PCM tap: down-mixes each
 * decoded buffer to mono 16-bit (the float/short encoding split is Android-specific)
 * and hands it to [CaptionAudioRouter.feedMono], which does the shared anti-aliased
 * resample to 16 kHz s16le and streams it to the selected provider.
 */
internal fun CaptionAudioRouter.asPcmTapSink(): PcmTapSink = object : PcmTapSink {
    override fun onPcm(buffer: ByteBuffer, sampleRate: Int, channels: Int, encoding: Int) {
        val mono = downmixToMono16(buffer, channels, encoding) ?: return
        feedMono(mono, sampleRate)
    }
}

private fun downmixToMono16(buffer: ByteBuffer, channels: Int, encoding: Int): ShortArray? {
    if (channels < 1) return null
    val src = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
    val remaining = src.remaining()
    if (remaining < 2) return null
    return if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
        val frames = remaining / (4 * channels)
        if (frames == 0) return null
        // Android ByteBuffer.getFloat(index) reads a float at byte offset `index`.
        // The array index `i` must be multiplied by 4 to get the byte offset.
        downmixFloatToMono16(frames, channels) { i -> src.getFloat(i * 4) }
    } else {
        val frames = remaining / (2 * channels)
        if (frames == 0) return null
        ShortArray(frames) {
            var sum = 0
            for (c in 0 until channels) sum += src.short.toInt()
            (sum / channels).toShort()
        }
    }
}
