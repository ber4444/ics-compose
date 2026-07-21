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
 * AssemblyAI streaming ASR over websocket (`wss://api.assemblyai.com/v2/realtime/ws`).
 * Raw 16 kHz mono s16le PCM is sent as binary frames.
 */
class AssemblyAiClient(
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
            _error.value = "Missing AssemblyAI API key"
            _status.value = TranscriberStatus.ERROR
            return
        }
        _error.value = null
        _status.value = TranscriberStatus.CONNECTING
        val channel = Channel<ByteArray>(capacity = 128)
        pcm = channel
        
        val url = "wss://api.assemblyai.com/v2/realtime/ws?sample_rate=$sampleRate"
        
        job = scope.launch {
            try {
                client.webSocket(urlString = url, request = { header("Authorization", key) }) {
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
                        runCatching { send(Frame.Text("{\"terminate_session\": true}")) }
                    }
                }
                if (_status.value != TranscriberStatus.ERROR) _status.value = TranscriberStatus.IDLE
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _error.value = e.message ?: "AssemblyAI connection failed"
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
        val msg = runCatching { json.decodeFromString<AssemblyAiMessage>(text) }.getOrNull() ?: return
        
        when (msg.messageType) {
            "PartialTranscript" -> {
                if (!msg.text.isNullOrBlank()) {
                    accumulator.setPartial(msg.text)
                }
            }
            "FinalTranscript" -> {
                if (!msg.text.isNullOrBlank()) {
                    accumulator.appendFinal(msg.text)
                }
            }
            "SessionInformation", "SessionBegins" -> {
                // Session started successfully
            }
            else -> {
                // Ignore other messages, but check for error
                if (msg.error != null) {
                    _error.value = msg.error
                    _status.value = TranscriberStatus.ERROR
                }
            }
        }
    }

    @Serializable
    private data class AssemblyAiMessage(
        @SerialName("message_type") val messageType: String? = null,
        val text: String? = null,
        val error: String? = null
    )
}
