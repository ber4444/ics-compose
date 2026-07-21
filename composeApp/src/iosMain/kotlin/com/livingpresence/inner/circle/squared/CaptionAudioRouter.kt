@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.livingpresence.inner.circle.squared

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.get
import kotlin.math.roundToInt

/**
 * iOS PCM ingestion for [CaptionAudioRouter]: the player's MTAudioProcessingTap
 * delivers interleaved float samples; downmix to mono 16-bit and hand off to the
 * shared [CaptionAudioRouter.feedMono] (which resamples + streams to the provider).
 */
internal fun CaptionAudioRouter.onPcm(pcmData: CPointer<FloatVar>?, numFrames: Int, channels: Int, sampleRate: Int) {
    if (pcmData == null || numFrames <= 0 || channels <= 0) return
    val mono = downmixFloatToMono16(numFrames, channels) { i -> pcmData[i] }
    feedMono(mono, sampleRate)
}
