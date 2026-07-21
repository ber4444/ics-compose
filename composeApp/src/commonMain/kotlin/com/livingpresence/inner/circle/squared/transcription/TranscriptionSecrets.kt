package com.livingpresence.inner.circle.squared.transcription

/**
 * Runtime holder for the streaming-ASR API keys. Keys are **not** compiled into
 * shared code — each platform reads them from its own gitignored source and pushes
 * them in here at startup:
 *  - Android: `BuildConfig.DEEPGRAM_API_KEY` / `SONIOX_API_KEY` (from `secrets.properties`)
 *  - iOS: xcconfig / Info.plist (Phase 3)
 *  - Web: build-time env (Phase 4)
 *
 * Empty when not configured; clients surface a clear error rather than connecting.
 *
 * SECURITY: shipping a key in the client (BuildConfig/plist) is a dev/portfolio
 * convenience only — the key is extractable. For production, stream through a
 * backend proxy that holds the key and never expose it to the app.
 */
object TranscriptionSecrets {
    var deepgramApiKey: String = ""
    var sonioxApiKey: String = ""
    var assemblyAiApiKey: String = ""

    fun keyFor(provider: TranscriptionProvider): String = when (provider) {
        TranscriptionProvider.DEEPGRAM -> deepgramApiKey
        TranscriptionProvider.SONIOX -> sonioxApiKey
        TranscriptionProvider.ASSEMBLY_AI -> assemblyAiApiKey
    }
}
