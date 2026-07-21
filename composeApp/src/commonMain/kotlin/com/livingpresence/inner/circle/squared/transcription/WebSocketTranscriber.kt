package com.livingpresence.inner.circle.squared.transcription

import com.livingpresence.inner.circle.squared.CaptionCue
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
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
 * The receive loop reads inbound text frames and forwards them to [handleMessage];
 * outbound audio is drained from a bounded [Channel] populated by [feedPcm].
 */
abstract class WebSocketTranscriber(
    private val apiKey: () -> String,
    protected val json: Json = Json { ignoreUnknownKeys = true },
) : StreamingTranscriber {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val client = HttpClient { install(WebSockets) }
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

    /** Adds auth headers (and any query the endpoint needs) to the connect request. */
    protected abstract fun HttpRequestBuilder.configureRequest(apiKey: String)

    /** Parses one inbound text frame and feeds the [accumulator]. */
    protected abstract fun handleMessage(text: String)

    /** Optional first frame(s) sent right after connect, before audio (e.g. a config handshake). */
    protected open suspend fun DefaultClientWebSocketSession.onOpen(apiKey: String) {}

    /** Optional periodic frame loop for the session lifetime (e.g. keep-alives). Cancelled on close. */
    protected open suspend fun DefaultClientWebSocketSession.keepAlive() {}

    /** Optional end-of-stream frame sent once the audio channel drains. */
    protected open suspend fun DefaultClientWebSocketSession.onAudioDrained() {}

    /** Optional handling when the receive loop ends (e.g. inspect the close reason). */
    protected open suspend fun DefaultClientWebSocketSession.onReceiveLoopEnd() {}

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
                client.webSocket(urlString = url, request = { configureRequest(key) }) {
                    val session = this
                    session.onOpen(key)
                    _status.value = TranscriberStatus.LISTENING
                    val sender = launch {
                        try {
                            for (chunk in channel) {
                                if (chunk.isNotEmpty()) session.send(Frame.Binary(fin = true, data = chunk))
                            }
                            session.onAudioDrained()
                        } catch (_: Throwable) { /* connection closing */ }
                    }
                    val keepAlive = launch { session.keepAlive() }
                    try {
                        for (frame in incoming) {
                            if (frame is Frame.Text) handleMessage(frame.readText())
                        }
                    } finally {
                        keepAlive.cancel()
                        sender.cancel()
                        session.onReceiveLoopEnd()
                    }
                }
                if (_status.value != TranscriberStatus.ERROR) _status.value = TranscriberStatus.IDLE
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                println("$providerName connection error: ${e.message}")
                e.printStackTrace()
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
