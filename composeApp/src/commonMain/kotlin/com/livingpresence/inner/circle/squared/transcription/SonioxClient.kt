package com.livingpresence.inner.circle.squared.transcription

import com.livingpresence.inner.circle.squared.CaptionCue
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException

/**
 * Soniox streaming ASR over websocket. The protocol differs from Deepgram: the first
 * frame is a JSON **config** (carrying the API key and audio format), then raw
 * 16 kHz mono s16le PCM is streamed as binary frames. Results arrive as a stream of
 * tokens, each flagged `is_final`; final tokens are concatenated into a line that is
 * committed as a cue at sentence boundaries, while non-final tokens form the live tail.
 *
 * NOTE: endpoint host and field names should be re-verified against current Soniox
 * docs (see docs/live-captions-plan.md) — they change and this hasn't been run against
 * the live service.
 */
class SonioxClient(
    private val apiKey: () -> String,
    private val sampleRate: Int = 16_000,
    private val languageHints: List<String> = listOf("en"),
) : StreamingTranscriber {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val client = HttpClient { install(WebSockets) }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val accumulator = CaptionAccumulator()

    private val _status = MutableStateFlow(TranscriberStatus.IDLE)
    private val _error = MutableStateFlow<String?>(null)
    override val captions: StateFlow<List<CaptionCue>> = accumulator.captions
    override val status: StateFlow<TranscriberStatus> = _status.asStateFlow()
    override val error: StateFlow<String?> = _error.asStateFlow()

    private var job: Job? = null
    private var pcm: Channel<ByteArray>? = null

    /** Final tokens accumulated for the current (not-yet-committed) caption line. */
    private val lineBuffer = StringBuilder()

    override fun start() {
        if (job != null) return
        val key = apiKey()
        if (key.isBlank()) {
            _error.value = "Missing Soniox API key"
            _status.value = TranscriberStatus.ERROR
            return
        }
        _error.value = null
        _status.value = TranscriberStatus.CONNECTING
        lineBuffer.clear()
        val channel = Channel<ByteArray>(capacity = 128)
        pcm = channel
        job = scope.launch {
            try {
                client.webSocket(urlString = "wss://stt-rt.soniox.com/transcribe-websocket") {
                    // 1) config handshake, 2) then stream audio.
                    val config = SonioxConfig(
                        apiKey = key,
                        sampleRate = sampleRate,
                        languageHints = languageHints,
                    )
                    send(Frame.Text(json.encodeToString(config)))
                    _status.value = TranscriberStatus.LISTENING
                    val sender = launch {
                        try {
                            for (chunk in channel) send(Frame.Binary(fin = true, data = chunk))
                            // Empty audio frame signals end-of-stream to Soniox.
                            runCatching { send(Frame.Text("{\"type\":\"finalize\"}")) }
                        } catch (_: Throwable) { /* connection closing */ }
                    }
                    try {
                        for (frame in incoming) {
                            if (frame is Frame.Text) handleMessage(frame.readText())
                        }
                    } finally {
                        sender.cancel()
                    }
                }
                if (_status.value != TranscriberStatus.ERROR) _status.value = TranscriberStatus.IDLE
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _error.value = e.message ?: "Soniox connection failed"
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
        lineBuffer.clear()
        accumulator.clear()
        if (_status.value != TranscriberStatus.ERROR) _status.value = TranscriberStatus.IDLE
    }

    private fun handleMessage(text: String) {
        val resp = runCatching { json.decodeFromString<SonioxResponse>(text) }.getOrNull() ?: return
        if (resp.errorMessage != null) {
            _error.value = resp.errorMessage
            _status.value = TranscriberStatus.ERROR
            return
        }
        val partial = StringBuilder()
        for (token in resp.tokens) {
            if (token.isFinal) lineBuffer.append(token.text) else partial.append(token.text)
        }
        val line = lineBuffer.toString()
        val committed = line.isNotBlank() &&
            (line.trimEnd().let { it.endsWith(".") || it.endsWith("?") || it.endsWith("!") } || line.length > 80)
        if (committed) {
            accumulator.appendFinal(line)
            lineBuffer.clear()
        } else {
            accumulator.setPartial(line + partial.toString())
        }
    }

    @Serializable
    private data class SonioxConfig(
        @SerialName("api_key") val apiKey: String,
        val model: String = "stt-rt-v5",
        @SerialName("audio_format") val audioFormat: String = "pcm_s16le",
        @SerialName("sample_rate") val sampleRate: Int,
        @SerialName("num_channels") val numChannels: Int = 1,
        @SerialName("language_hints") val languageHints: List<String> = listOf("en"),
    )

    @Serializable
    private data class SonioxResponse(
        val tokens: List<SonioxToken> = emptyList(),
        val finished: Boolean = false,
        @SerialName("error_code") val errorCode: Int? = null,
        @SerialName("error_message") val errorMessage: String? = null,
    )

    @Serializable
    private data class SonioxToken(
        val text: String = "",
        @SerialName("is_final") val isFinal: Boolean = false,
    )
}
