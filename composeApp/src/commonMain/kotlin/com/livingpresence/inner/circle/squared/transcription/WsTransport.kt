package com.livingpresence.inner.circle.squared.transcription

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlin.coroutines.coroutineContext

/** A live websocket the caller sends handshake / audio / keep-alive frames through. */
interface WsSession {
    suspend fun sendBinary(bytes: ByteArray)
    suspend fun sendText(text: String)
}

/**
 * Platform seam for the websocket transport. Native targets use Ktor (which can set
 * the `Authorization` header); the browser can't set custom handshake headers, so the
 * web target uses a raw `WebSocket` that can carry the auth `token` subprotocol instead.
 */
internal interface WsTransport {
    /**
     * Connects to [url] — offering [subprotocols] and [headers] where the platform
     * allows — then runs [session] (which sends the handshake and launches the audio
     * sender / keep-alive into the provided [CoroutineScope]) concurrently with an
     * inbound-text pump to [onText]. Suspends until the socket closes, cancels the
     * session scope, and returns the abnormal close reason (null if clean/normal).
     */
    suspend fun run(
        url: String,
        subprotocols: List<String>,
        headers: Map<String, String>,
        onText: (String) -> Unit,
        session: suspend CoroutineScope.(WsSession) -> Unit,
    ): String?
}

/** Returns the transport for the current platform (Ktor on native, raw `WebSocket` on web). */
internal expect fun createWsTransport(): WsTransport

/**
 * Ktor-backed transport used by the native (Android/iOS) targets. Applies [headers]
 * (e.g. `Authorization`) to the handshake; also offers [subprotocols] via the
 * `Sec-WebSocket-Protocol` header for servers that accept subprotocol auth.
 */
internal class KtorWsTransport : WsTransport {

    private val client = HttpClient { install(WebSockets) }

    override suspend fun run(
        url: String,
        subprotocols: List<String>,
        headers: Map<String, String>,
        onText: (String) -> Unit,
        session: suspend CoroutineScope.(WsSession) -> Unit,
    ): String? {
        var reason: String? = null
        client.webSocket(urlString = url, request = {
            headers.forEach { (k, v) -> header(k, v) }
            if (subprotocols.isNotEmpty()) header("Sec-WebSocket-Protocol", subprotocols.joinToString(", "))
        }) {
            val ws = object : WsSession {
                override suspend fun sendBinary(bytes: ByteArray) = send(Frame.Binary(fin = true, data = bytes))
                override suspend fun sendText(text: String) = send(Frame.Text(text))
            }
            val sessionScope = CoroutineScope(coroutineContext + Job())
            sessionScope.session(ws)
            try {
                for (frame in incoming) {
                    if (frame is Frame.Text) onText(frame.readText())
                }
            } finally {
                sessionScope.cancel()
            }
            reason = runCatching { closeReason.await() }.getOrNull()
                ?.takeIf { it.knownReason != CloseReason.Codes.NORMAL }
                ?.toString()
        }
        return reason
    }
}
