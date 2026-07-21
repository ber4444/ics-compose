package com.livingpresence.inner.circle.squared.transcription

import com.livingpresence.inner.circle.squared.CaptionCue
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Provider-agnostic live-caption coordinator, shared across platforms. Owns the
 * currently-selected [StreamingTranscriber] (Deepgram/Soniox), mirrors its
 * captions/status/error, and switches providers on demand. Platform code only has
 * to: capture audio, resample it to 16 kHz mono s16le, and call [feedPcm]; then
 * observe [captions]/[status]/[error] for the overlay/UI.
 *
 * This is the piece the Android `CaptionAudioRouter` and the iOS/web taps all reuse —
 * only the audio capture differs per platform.
 */
class LiveTranscriber {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var active: StreamingTranscriber? = null
    @Volatile private var activeProvider: TranscriptionProvider? = null
    private var mirrorJob: Job? = null

    private val _captions = MutableStateFlow<List<CaptionCue>>(emptyList())
    val captions: StateFlow<List<CaptionCue>> = _captions.asStateFlow()

    private val _status = MutableStateFlow(TranscriberStatus.IDLE)
    val status: StateFlow<TranscriberStatus> = _status.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Starts (or switches to) [provider]. Idempotent for the already-active provider. */
    fun enable(provider: TranscriptionProvider) {
        if (activeProvider == provider && active != null) return
        stopActive()
        val client = createClient(provider)
        active = client
        activeProvider = provider
        mirrorJob = scope.launch {
            launch { client.captions.collect { _captions.value = it } }
            launch { client.status.collect { _status.value = it } }
            launch { client.error.collect { _error.value = it } }
        }
        client.start()
    }

    /** Feeds 16 kHz mono s16le PCM to the active client (no-op if disabled). */
    fun feedPcm(pcm16: ByteArray) {
        active?.feedPcm(pcm16)
    }

    fun disable() {
        stopActive()
        _captions.value = emptyList()
        _status.value = TranscriberStatus.IDLE
        _error.value = null
    }

    private fun stopActive() {
        mirrorJob?.cancel()
        mirrorJob = null
        active?.stop()
        active = null
        activeProvider = null
    }

    private fun createClient(provider: TranscriptionProvider): StreamingTranscriber = when (provider) {
        TranscriptionProvider.DEEPGRAM -> DeepgramClient(apiKey = { TranscriptionSecrets.deepgramApiKey })
        TranscriptionProvider.SONIOX -> SonioxClient(apiKey = { TranscriptionSecrets.sonioxApiKey })
        TranscriptionProvider.ASSEMBLY_AI -> AssemblyAiClient(apiKey = { TranscriptionSecrets.assemblyAiApiKey })
    }
}
