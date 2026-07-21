package com.livingpresence.inner.circle.squared.transcription

import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.websocket.Frame
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString

/**
 * Deepgram streaming ASR over websocket (`wss://api.deepgram.com/v1/listen`, model
 * `nova-3`). Raw 16 kHz mono s16le PCM is sent as binary frames; JSON results carry
 * `channel.alternatives[0].transcript` with a top-level `is_final` flag that maps to
 * partial vs finalized cues.
 *
 * The connect/send/receive lifecycle lives in [WebSocketTranscriber]; this subclass
 * supplies the endpoint, `Token` auth, a periodic KeepAlive, the CloseStream on exit,
 * and Deepgram's JSON shape.
 *
 * @param apiKey supplies the key lazily at [start] time (from [TranscriptionSecrets]).
 * @param sampleRate PCM rate declared to Deepgram; must match what [feedPcm] sends.
 */
class DeepgramClient(
    apiKey: () -> String,
    private val sampleRate: Int = 16_000,
) : WebSocketTranscriber(apiKey) {

    override val providerName = "Deepgram"

    override val url = "wss://api.deepgram.com/v1/listen" +
        "?model=nova-3&encoding=linear16&sample_rate=$sampleRate&channels=1" +
        "&interim_results=true&punctuate=true&smart_format=true"

    override fun HttpRequestBuilder.configureRequest(apiKey: String) {
        header("Authorization", "Token $apiKey")
    }

    override suspend fun DefaultClientWebSocketSession.keepAlive() {
        while (true) {
            delay(3000)
            runCatching { send(Frame.Text("{\"type\":\"KeepAlive\"}")) }
        }
    }

    override suspend fun DefaultClientWebSocketSession.onReceiveLoopEnd() {
        runCatching { send(Frame.Text("{\"type\":\"CloseStream\"}")) }
    }

    override fun handleMessage(text: String) {
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
