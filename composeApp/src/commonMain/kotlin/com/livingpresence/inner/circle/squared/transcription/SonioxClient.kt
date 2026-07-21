package com.livingpresence.inner.circle.squared.transcription

import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Soniox streaming ASR over websocket. The protocol differs from Deepgram: the first
 * frame is a JSON **config** (carrying the API key and audio format), then raw
 * 16 kHz mono s16le PCM is streamed as binary frames. Results arrive as a stream of
 * tokens, each flagged `is_final`; final tokens are concatenated into a line that is
 * committed as a cue at sentence boundaries, while non-final tokens form the live tail.
 *
 * The connect/send/receive lifecycle lives in [WebSocketTranscriber]; this subclass
 * supplies the endpoint, `Bearer` auth, the config handshake, the `finalize` on
 * end-of-stream, close-reason reporting, and Soniox's token protocol.
 *
 * NOTE: endpoint host and field names should be re-verified against current Soniox
 * docs (see docs/live-captions-plan.md) — they change and this hasn't been run against
 * the live service.
 */
class SonioxClient(
    apiKey: () -> String,
    private val sampleRate: Int = 16_000,
    private val languageHints: List<String> = listOf("en"),
) : WebSocketTranscriber(apiKey, json = Json { ignoreUnknownKeys = true; encodeDefaults = true }) {

    override val providerName = "Soniox"

    override val url = "wss://stt-rt.soniox.com/transcribe-websocket"

    /** Final tokens accumulated for the current (not-yet-committed) caption line. */
    private val lineBuffer = StringBuilder()

    override fun HttpRequestBuilder.configureRequest(apiKey: String) {
        header("Authorization", "Bearer $apiKey")
    }

    override suspend fun DefaultClientWebSocketSession.onOpen(apiKey: String) {
        lineBuffer.clear()
        val config = SonioxConfig(
            apiKey = apiKey,
            sampleRate = sampleRate,
            languageHints = languageHints,
        )
        send(Frame.Text(json.encodeToString(config)))
    }

    override suspend fun DefaultClientWebSocketSession.onAudioDrained() {
        // Empty audio frame / finalize signals end-of-stream to Soniox.
        runCatching { send(Frame.Text("{\"type\":\"finalize\"}")) }
    }

    override suspend fun DefaultClientWebSocketSession.onReceiveLoopEnd() {
        val reason = runCatching { closeReason.await() }.getOrNull()
        if (reason != null && reason.knownReason != CloseReason.Codes.NORMAL) {
            val msg = "Soniox Closed: $reason"
            setError(msg)
            accumulator.setPartial(msg)
        }
    }

    override fun onMissingKey() {
        val msg = "Missing Soniox API key"
        setError(msg)
        accumulator.setPartial(msg)
    }

    override fun onConnectException(e: Throwable) {
        accumulator.setPartial("Soniox EXCEPTION: ${e.message ?: "Soniox connection failed"}")
    }

    override fun onStop() {
        lineBuffer.clear()
    }

    override fun handleMessage(text: String) {
        val resp = runCatching { json.decodeFromString<SonioxResponse>(text) }.getOrNull()
        if (resp == null) {
            val err = "Soniox decode fail: $text"
            setError(err)
            accumulator.setPartial(err)
            return
        }
        if (resp.errorMessage != null) {
            setError(resp.errorMessage)
            accumulator.setPartial("Soniox ERROR MSG: ${resp.errorMessage}")
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
