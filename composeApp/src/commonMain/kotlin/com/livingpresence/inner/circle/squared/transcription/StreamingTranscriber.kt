package com.livingpresence.inner.circle.squared.transcription

import com.livingpresence.inner.circle.squared.CaptionCue
import kotlinx.coroutines.flow.StateFlow

/** Connection/lifecycle state of a [StreamingTranscriber], surfaced to the UI. */
enum class TranscriberStatus { IDLE, CONNECTING, LISTENING, ERROR }

/**
 * A live, streaming speech-to-text backend (Deepgram, Soniox, …). Platform code
 * captures decoded audio, resamples it to the required format, and pushes it via
 * [feedPcm]; recognized text is published on [captions] for the caption overlay.
 *
 * **PCM contract:** [feedPcm] expects **16 kHz, mono, signed 16-bit little-endian**
 * frames. Resampling from the source (usually 48 kHz stereo) happens on the caller's
 * side (Android reuses the anti-aliased resampler in `TranscriptionEngine`).
 *
 * Implementations are cloud websocket clients and live in `commonMain`, so the same
 * code runs on Android, iOS and web — only the audio tap and key source differ.
 */
interface StreamingTranscriber {
    val captions: StateFlow<List<CaptionCue>>
    val status: StateFlow<TranscriberStatus>
    val error: StateFlow<String?>

    /** Opens the websocket and begins streaming. Idempotent. */
    fun start()

    /** Feeds one chunk of 16 kHz mono s16le PCM. Non-blocking; dropped if not connected. */
    fun feedPcm(pcm16: ByteArray)

    /** Closes the connection and clears transient state. The instance may be [start]ed again. */
    fun stop()
}
