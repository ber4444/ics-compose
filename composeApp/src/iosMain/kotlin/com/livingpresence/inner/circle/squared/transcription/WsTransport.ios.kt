package com.livingpresence.inner.circle.squared.transcription

/** iOS uses the Ktor (Darwin) transport, which can set the `Authorization` header. */
internal actual fun createWsTransport(): WsTransport = KtorWsTransport()
