package com.livingpresence.inner.circle.squared.transcription

import com.livingpresence.inner.circle.squared.CaptionCue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException

/**
 * Shared websocket plumbing for streaming ASR clients (Deepgram, Soniox, …): key
 * validation, the connect/send/receive lifecycle, PCM buffering, and status/error/
 * caption flow wiring. Subclasses supply only the parts that genuinely differ —
 * the endpoint, auth, optional handshake/keep-alive frames, and message parsing.
 *
 * The transport ([WsTransport]) is platform-specific — Ktor on native, a raw browser
 * `WebSocket` on web — so subclasses declare auth as [headers] (used where the platform
 * can set them) plus [subprotocols] (used by the browser transport for `token` auth),
 * and never touch the socket directly.
 */
abstract class WebSocketTranscriber(
    private val apiKey: () -> String,
    protected val json: Json = Json { ignoreUnknownKeys = true },
) : StreamingTranscriber {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val transport = createWsTransport()
    protected val accumulator = CaptionAccumulator()

    private val _status = MutableStateFlow(TranscriberStatus.IDLE)
    private val _error = MutableStateFlow<String?>(null)
    override val captions: StateFlow<List<CaptionCue>> = accumulator.captions
    override val status: StateFlow<TranscriberStatus> = _status.asStateFlow()
    override val error: StateFlow<String?> = _error.asStateFlow()

    private var job: Job? = null
    private var pcm: Channel<ByteArray>? = null

    /** The `wss://` endpoint to connect to. */
    protected abstract val url: String

    /** Human-readable provider name, used in default error/log messages. */
    protected abstract val providerName: String

    /** Handshake headers (e.g. `Authorization`). Ignored by the browser transport. */
    protected open fun headers(apiKey: String): Map<String, String> = emptyMap()

    /** WebSocket subprotocols (e.g. Deepgram's `["token", key]`). Used by the browser transport. */
    protected open fun subprotocols(apiKey: String): List<String> = emptyList()

    /** Parses one inbound text frame and feeds the [accumulator]. */
    protected abstract fun handleMessage(text: String)

    /** Optional first frame(s) sent right after connect, before audio (e.g. a config handshake). */
    protected open suspend fun onOpen(ws: WsSession, apiKey: String) {}

    /** Optional periodic frame loop for the session lifetime (e.g. keep-alives). Cancelled on close. */
    protected open suspend fun keepAlive(ws: WsSession) {}

    /** Optional end-of-stream frame sent once the audio channel drains. */
    protected open suspend fun onAudioDrained(ws: WsSession) {}

    /** Optional handling of the close reason (null when the socket closed cleanly). */
    protected open fun onClosed(reason: String?) {}

    /** Called when the API key is blank. Default reports a standard "missing key" error. */
    protected open fun onMissingKey() = setError("Missing $providerName API key")

    /** Called on a connection-level exception (after [setError]); e.g. surface it as a partial cue. */
    protected open fun onConnectException(e: Throwable) {}

    /** Subclass cleanup on [stop] (e.g. clearing a line buffer). */
    protected open fun onStop() {}

    /** Moves to the ERROR state with [message]. */
    protected fun setError(message: String) {
        _error.value = message
        _status.value = TranscriberStatus.ERROR
    }

    final override fun start() {
        if (job != null) return
        val key = apiKey()
        if (key.isBlank()) {
            onMissingKey()
            return
        }
        _error.value = null
        _status.value = TranscriberStatus.CONNECTING
        val channel = Channel<ByteArray>(capacity = 128)
        pcm = channel
        job = scope.launch {
            try {
                val reason = transport.run(
                    url = url,
                    subprotocols = subprotocols(key),
                    headers = headers(key),
                    onText = { handleMessage(it) },
                ) { ws ->
                    onOpen(ws, key)
                    _status.value = TranscriberStatus.LISTENING
                    launch {
                        try {
                            for (chunk in channel) {
                                if (chunk.isNotEmpty()) ws.sendBinary(chunk)
                            }
                            onAudioDrained(ws)
                        } catch (_: Throwable) { /* connection closing */ }
                    }
                    launch { keepAlive(ws) }
                }
                onClosed(reason)
                if (_status.value != TranscriberStatus.ERROR) _status.value = TranscriberStatus.IDLE
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                println("$providerName connection error: ${e.message}")
                setError(e.message ?: "$providerName connection failed")
                onConnectException(e)
            }
        }
    }

    final override fun feedPcm(pcm16: ByteArray) {
        pcm?.trySend(pcm16)
    }

    final override fun stop() {
        job?.cancel()
        job = null
        pcm?.close()
        pcm = null
        onStop()
        accumulator.clear()
        if (_status.value != TranscriberStatus.ERROR) _status.value = TranscriberStatus.IDLE
    }
}
