package com.livingpresence.inner.circle.squared.transcription

/** Android uses the Ktor (OkHttp) transport, which can set the `Authorization` header. */
internal actual fun createWsTransport(): WsTransport = KtorWsTransport()
