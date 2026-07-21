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
 * AssemblyAI streaming ASR over websocket (`wss://streaming.assemblyai.com/v3/ws`).
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
            val msg = "Missing AssemblyAI API key"
            _error.value = msg
            _status.value = TranscriberStatus.ERROR
            accumulator.setPartial(msg)
            return
        }
        _error.value = null
        _status.value = TranscriberStatus.CONNECTING
        val channel = Channel<ByteArray>(capacity = 128)
        pcm = channel
        
        val url = "wss://streaming.assemblyai.com/v3/ws?sample_rate=$sampleRate&token=$key"
        
        job = scope.launch {
            try {
                client.webSocket(urlString = url) {
                    _status.value = TranscriberStatus.LISTENING
                    
                    val sender = launch {
                        try {
                            for (chunk in channel) {
                                if (chunk.isNotEmpty()) {
                                    send(Frame.Binary(fin = true, data = chunk))
                                }
                            }
                        } catch (_: Throwable) { /* connection closing */ }
                    }
                    
                    try {
                        for (frame in incoming) {
                            if (frame is Frame.Text) handleMessage(frame.readText())
                            else println("AssemblyAiClient non-text frame: $frame")
                        }
                    } finally {
                        val reason = runCatching { closeReason.await() }.getOrNull()
                        if (reason != null && reason.knownReason != io.ktor.websocket.CloseReason.Codes.NORMAL) {
                            val msg = "AssemblyAI Closed: $reason"
                            _error.value = msg
                            _status.value = TranscriberStatus.ERROR
                            accumulator.setPartial(msg)
                        }
                        sender.cancel()
                        runCatching { send(Frame.Text("{\"terminate_session\": true}")) }
                    }
                }
                if (_status.value != TranscriberStatus.ERROR) _status.value = TranscriberStatus.IDLE
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    println("AssemblyAiClient Exception: ${e.message}")
                    e.printStackTrace()
                    val msg = e.message ?: "AssemblyAI connection failed"
                    _error.value = msg
                    _status.value = TranscriberStatus.ERROR
                    accumulator.setPartial("AssemblyAI EXCEPTION: $msg")
                }
            }
        }
    }

    private var audioBuffer = ByteArray(0)

    override fun feedPcm(pcm16: ByteArray) {
        audioBuffer += pcm16
        // Send in chunks of 3200 bytes (100ms of 16kHz 16-bit mono PCM)
        while (audioBuffer.size >= 3200) {
            val chunk = audioBuffer.copyOfRange(0, 3200)
            audioBuffer = audioBuffer.copyOfRange(3200, audioBuffer.size)
            pcm?.trySend(chunk)
        }
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
        val msg = runCatching { json.decodeFromString<AssemblyAiMessage>(text) }.getOrNull()
        if (msg == null) {
            val err = "AssemblyAI decode fail: $text"
            _error.value = err
            _status.value = TranscriberStatus.ERROR
            accumulator.setPartial(err)
            return
        }
        
        when (msg.type) {
            "Turn" -> {
                if (!msg.transcript.isNullOrBlank()) {
                    if (msg.endOfTurn) accumulator.appendFinal(msg.transcript) else accumulator.setPartial(msg.transcript)
                }
            }
            "Begin" -> {
                // Session started successfully
            }
            else -> {
                // Ignore other messages, but check for error
                if (msg.error != null) {
                    _error.value = msg.error
                    _status.value = TranscriberStatus.ERROR
                    accumulator.setPartial("AssemblyAI ERROR MSG: ${msg.error}")
                }
            }
        }
    }

    @Serializable
    private data class AssemblyAiMessage(
        val type: String? = null,
        val transcript: String? = null,
        @SerialName("end_of_turn") val endOfTurn: Boolean = false,
        val error: String? = null
    )
}
