package com.livingpresence.inner.circle.squared

import kotlin.math.roundToInt

/**
 * Web PCM ingestion for [CaptionAudioRouter]: the WebAudio tap delivers interleaved
 * float samples; downmix to mono 16-bit and hand off to the shared
 * [CaptionAudioRouter.feedMono] (which resamples + streams to the provider).
 */
internal fun CaptionAudioRouter.onPcm(pcmData: FloatArray, numFrames: Int, channels: Int, sampleRate: Int) {
    if (numFrames <= 0 || channels <= 0) return
    val mono = downmixFloatToMono16(numFrames, channels) { i -> pcmData[i] }
    feedMono(mono, sampleRate)
}
