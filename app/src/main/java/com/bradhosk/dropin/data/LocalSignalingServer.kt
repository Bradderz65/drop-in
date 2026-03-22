package com.bradhosk.dropin.data

import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoWSD.WebSocketFrame
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.Json
import java.io.IOException

class LocalSignalingServer(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val _events = MutableSharedFlow<SignalEnvelope>(extraBufferCapacity = 32)
    private var webSocket: SignalingSocket? = null
    private var server: SignalingWsd? = null

    val events = _events.asSharedFlow()
    val port: Int
        get() = server?.listeningPort ?: 0

    fun start(preferredPort: Int = 8989) {
        if (server != null) return
        server = SignalingWsd(preferredPort).also { it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
    }

    fun stop() {
        runCatching { webSocket?.close(WebSocketFrame.CloseCode.NormalClosure, "bye", false) }
        webSocket = null
        server?.stop()
        server = null
    }

    fun send(message: SignalEnvelope) {
        runCatching {
            webSocket?.send(json.encodeToString(SignalEnvelope.serializer(), message))
        }
    }

    private inner class SignalingWsd(port: Int) : NanoWSD(port) {
        @Throws(IOException::class)
        override fun openWebSocket(handshake: IHTTPSession): WebSocket {
            return SignalingSocket(handshake).also { socket ->
                webSocket = socket
            }
        }

        override fun serveHttp(session: IHTTPSession): Response {
            return newFixedLengthResponse(Response.Status.OK, NanoHTTPD.MIME_PLAINTEXT, "DropIn signaling server")
        }
    }

    private inner class SignalingSocket(handshakeRequest: IHTTPSession) : NanoWSD.WebSocket(handshakeRequest) {
        override fun onOpen() = Unit

        override fun onClose(code: WebSocketFrame.CloseCode?, reason: String?, initiatedByRemote: Boolean) {
            webSocket = null
        }

        override fun onMessage(message: WebSocketFrame) {
            runCatching {
                json.decodeFromString(SignalEnvelope.serializer(), message.textPayload)
            }.onSuccess { decoded ->
                _events.tryEmit(decoded)
            }
        }

        override fun onPong(pong: WebSocketFrame) = Unit
        override fun onException(exception: IOException) = Unit
    }
}
