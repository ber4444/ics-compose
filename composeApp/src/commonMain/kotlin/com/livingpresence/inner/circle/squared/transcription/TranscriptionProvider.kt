package com.livingpresence.inner.circle.squared.transcription

/**
 * The streaming ASR backends the user can switch between at runtime (see
 * [TranscriptionSettings]). Both are cloud websocket services; the choice is
 * surfaced in the player UI.
 *
 * - [DEEPGRAM] — default. Mature, widely recognized (Nova-3), generous free tier.
 * - [SONIOX] — cheapest, bundles real-time translation + diarization.
 */
enum class TranscriptionProvider(val label: String) {
    DEEPGRAM("Deepgram"),
    SONIOX("Soniox"),
    ASSEMBLY_AI("AssemblyAI"),
}
