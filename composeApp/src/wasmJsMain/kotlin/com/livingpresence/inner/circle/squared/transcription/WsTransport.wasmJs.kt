@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.livingpresence.inner.circle.squared.transcription

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.khronos.webgl.Int8Array
import org.khronos.webgl.set
import kotlin.coroutines.coroutineContext
import kotlin.js.JsAny

/**
 * Web transport built on the browser `WebSocket`. Unlike Ktor's JS engine it can pass
 * the auth `token` subprotocol (`new WebSocket(url, ['token', key])`), which is how
 * Deepgram authenticates in a browser — the `Authorization` header can't be set on a
 * WebSocket handshake. [headers] are therefore ignored on web.
 */
internal actual fun createWsTransport(): WsTransport = RawWsTransport()

private class RawWsTransport : WsTransport {
    override suspend fun run(
        url: String,
        subprotocols: List<String>,
        headers: Map<String, String>,
        onText: (String) -> Unit,
        session: suspend CoroutineScope.(WsSession) -> Unit,
    ): String? {
        val socket = wsOpen(url, subprotocols.getOrElse(0) { "" }, subprotocols.getOrElse(1) { "" })
        val opened = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<String?>()
        wsSetHandlers(
            socket,
            onOpen = { if (!opened.isCompleted) opened.complete(Unit) },
            onMessage = { text -> onText(text) },
            onClose = { code, reason ->
                if (!opened.isCompleted) {
                    opened.completeExceptionally(RuntimeException("WebSocket closed before open (code=$code)"))
                }
                if (!closed.isCompleted) {
                    closed.complete(if (code == 1000) null else "code=$code reason=$reason")
                }
            },
            onError = {
                if (!opened.isCompleted) opened.completeExceptionally(RuntimeException("WebSocket error"))
            },
        )
        try {
            opened.await()
        } catch (e: Throwable) {
            runCatching { wsClose(socket) }
            throw e
        }

        val ws = object : WsSession {
            override suspend fun sendBinary(bytes: ByteArray) = wsSendBinary(socket, bytes.toArrayBuffer())
            override suspend fun sendText(text: String) = wsSendText(socket, text)
        }
        val sessionScope = CoroutineScope(coroutineContext + Job())
        sessionScope.session(ws)
        return try {
            closed.await()
        } finally {
            sessionScope.cancel()
            runCatching { wsClose(socket) }
        }
    }
}

/** Copies this PCM buffer into a JS `ArrayBuffer` for a binary WebSocket frame. */
private fun ByteArray.toArrayBuffer(): JsAny {
    val arr = Int8Array(size)
    for (i in indices) arr[i] = this[i]
    return arr.buffer
}

@JsFun(
    "(url, p0, p1) => { " +
        "const protocols = []; " +
        "if (p0) protocols.push(p0); " +
        "if (p1) protocols.push(p1); " +
        "const ws = protocols.length ? new WebSocket(url, protocols) : new WebSocket(url); " +
        "ws.binaryType = 'arraybuffer'; return ws; }"
)
private external fun wsOpen(url: String, p0: String, p1: String): JsAny

@JsFun(
    "(ws, onOpen, onMessage, onClose, onError) => { " +
        "ws.onopen = () => onOpen(); " +
        "ws.onmessage = (e) => { if (typeof e.data === 'string') onMessage(e.data); }; " +
        "ws.onclose = (e) => onClose(e.code, e.reason || ''); " +
        "ws.onerror = () => onError(); }"
)
private external fun wsSetHandlers(
    ws: JsAny,
    onOpen: () -> Unit,
    onMessage: (String) -> Unit,
    onClose: (Int, String) -> Unit,
    onError: () -> Unit,
)

@JsFun("(ws, text) => ws.send(text)")
private external fun wsSendText(ws: JsAny, text: String)

@JsFun("(ws, buffer) => ws.send(buffer)")
private external fun wsSendBinary(ws: JsAny, buffer: JsAny)

@JsFun("(ws) => { try { ws.close(); } catch (e) {} }")
private external fun wsClose(ws: JsAny)
