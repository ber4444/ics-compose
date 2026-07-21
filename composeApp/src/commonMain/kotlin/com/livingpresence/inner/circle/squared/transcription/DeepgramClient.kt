package com.livingpresence.inner.circle.squared.transcription

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException

/**
 * Deepgram streaming ASR over websocket (`wss://api.deepgram.com/v1/listen`, model
 * `nova-3`). Raw 16 kHz mono s16le PCM is sent as binary frames; JSON results carry
 * `channel.alternatives[0].transcript` with a top-level `is_final` flag that maps to
 * partial vs finalized cues.
 *
 * @param apiKey supplies the key lazily at [start] time (from [TranscriptionSecrets]).
 * @param sampleRate PCM rate declared to Deepgram; must match what [feedPcm] sends.
 */
class DeepgramClient(
    private val apiKey: () -> String,
    private val sampleRate: Int = 16_000,
) : StreamingTranscriber {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val client = HttpClient { install(WebSockets) }
    private val json = Json { ignoreUnknownKeys = true }
    private val accumulator = CaptionAccumulator()

    private val _status = MutableStateFlow(TranscriberStatus.IDLE)
    private val _error = MutableStateFlow<String?>(null)
    override val captions: StateFlow<List<com.livingpresence.inner.circle.squared.CaptionCue>> = accumulator.captions
    override val status: StateFlow<TranscriberStatus> = _status.asStateFlow()
    override val error: StateFlow<String?> = _error.asStateFlow()

    private var job: Job? = null
    private var pcm: Channel<ByteArray>? = null

    override fun start() {
        if (job != null) return
        val key = apiKey()
        if (key.isBlank()) {
            _error.value = "Missing Deepgram API key"
            _status.value = TranscriberStatus.ERROR
            return
        }
        _error.value = null
        _status.value = TranscriberStatus.CONNECTING
        val channel = Channel<ByteArray>(capacity = 128)
        pcm = channel
        val url = "wss://api.deepgram.com/v1/listen" +
            "?model=nova-3&encoding=linear16&sample_rate=$sampleRate&channels=1" +
            "&interim_results=true&punctuate=true&smart_format=true"
        job = scope.launch {
            try {
                client.webSocket(urlString = url, request = { header("Authorization", "Token $key") }) {
                    _status.value = TranscriberStatus.LISTENING
                    val sender = launch {
                        try {
                            for (chunk in channel) send(Frame.Binary(fin = true, data = chunk))
                        } catch (_: Throwable) { /* connection closing */ }
                    }
                    try {
                        for (frame in incoming) {
                            if (frame is Frame.Text) handleMessage(frame.readText())
                        }
                    } finally {
                        sender.cancel()
                        runCatching { send(Frame.Text("{\"type\":\"CloseStream\"}")) }
                    }
                }
                if (_status.value != TranscriberStatus.ERROR) _status.value = TranscriberStatus.IDLE
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _error.value = e.message ?: "Deepgram connection failed"
                _status.value = TranscriberStatus.ERROR
            }
        }
    }

    override fun feedPcm(pcm16: ByteArray) {
        pcm?.trySend(pcm16)
    }

    override fun stop() {
        job?.cancel()
        job = null
        pcm?.close()
        pcm = null
        accumulator.clear()
        if (_status.value != TranscriberStatus.ERROR) _status.value = TranscriberStatus.IDLE
    }

    private fun handleMessage(text: String) {
        val msg = runCatching { json.decodeFromString<DeepgramMessage>(text) }.getOrNull() ?: return
        val transcript = msg.channel?.alternatives?.firstOrNull()?.transcript ?: return
        if (transcript.isBlank()) return
        if (msg.isFinal) accumulator.appendFinal(transcript) else accumulator.setPartial(transcript)
    }

    @Serializable
    private data class DeepgramMessage(
        val type: String? = null,
        @SerialName("is_final") val isFinal: Boolean = false,
        val channel: DeepgramChannel? = null,
    )

    @Serializable
    private data class DeepgramChannel(val alternatives: List<DeepgramAlt> = emptyList())

    @Serializable
    private data class DeepgramAlt(val transcript: String = "")
}
