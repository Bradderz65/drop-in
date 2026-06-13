package com.bradhosk.dropin.data

import android.util.Log
import com.bradhosk.dropin.model.PeerDevice
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
    private val registry: TailnetRegistryStore = TailnetRegistryStore(json),
) {
    private val logTag = "DropInApp"
    private val _events = MutableSharedFlow<SignalEnvelope>(extraBufferCapacity = 32)
    private var webSocket: SignalingSocket? = null
    private var server: SignalingWsd? = null

    val events = _events.asSharedFlow()
    val port: Int
        get() = server?.listeningPort ?: 0

    fun start(preferredPort: Int = 8989) {
        if (server != null) return
        Log.d(logTag, "local signaling server start port=$preferredPort")
        server = SignalingWsd(preferredPort).also { it.start(WEBSOCKET_READ_TIMEOUT_MS, false) }
    }

    fun stop() {
        runCatching { webSocket?.close(WebSocketFrame.CloseCode.NormalClosure, "bye", false) }
        webSocket = null
        server?.stop()
        server = null
    }

    fun send(message: SignalEnvelope) {
        Log.d(logTag, "local signaling send type=${message.type} to=${message.to}")
        runCatching {
            webSocket?.send(json.encodeToString(SignalEnvelope.serializer(), message))
        }
    }

    private inner class SignalingWsd(port: Int) : NanoWSD(port) {
        @Throws(IOException::class)
        override fun openWebSocket(handshake: IHTTPSession): WebSocket {
            Log.d(logTag, "local signaling socket opened from=${handshake.remoteIpAddress}")
            return SignalingSocket(handshake).also { socket ->
                webSocket = socket
            }
        }

        override fun serveHttp(session: IHTTPSession): Response {
            val uri = session.uri.orEmpty()
            return when {
                uri == "/api/registry/register" && session.method == Method.POST ->
                    handleRegistryRegister(session)
                uri.startsWith("/api/registry/peers") && session.method == Method.GET ->
                    handleRegistryPeers(session)
                else ->
                    newFixedLengthResponse(Response.Status.OK, NanoHTTPD.MIME_PLAINTEXT, "DropIn signaling server")
            }
        }

        private fun handleRegistryRegister(session: IHTTPSession): Response {
            val body = readBody(session)
            val remoteHost = session.remoteIpAddress.orEmpty()
            val ok = registry.registerFromJson(body, remoteHost)
            return if (ok) {
                newFixedLengthResponse(Response.Status.OK, MIME_JSON, """{"ok":true}""")
            } else {
                newFixedLengthResponse(
                    Response.Status.BAD_REQUEST,
                    MIME_JSON,
                    """{"error":"service_name, host, and port are required"}""",
                )
            }
        }

        private fun handleRegistryPeers(session: IHTTPSession): Response {
            val exclude = session.parms["exclude"]
            return newFixedLengthResponse(
                Response.Status.OK,
                MIME_JSON,
                registry.peersJson(exclude),
            )
        }

        private fun readBody(session: IHTTPSession): String {
            val map = hashMapOf<String, String>()
            session.parseBody(map)
            return map["postData"].orEmpty()
        }
    }

    private inner class SignalingSocket(handshakeRequest: IHTTPSession) : NanoWSD.WebSocket(handshakeRequest) {
        override fun onOpen() {
            Log.d(logTag, "local signaling websocket onOpen")
        }

        override fun onClose(code: WebSocketFrame.CloseCode?, reason: String?, initiatedByRemote: Boolean) {
            Log.d(logTag, "local signaling websocket onClose code=$code reason=$reason initiatedByRemote=$initiatedByRemote")
            webSocket = null
        }

        override fun onMessage(message: WebSocketFrame) {
            runCatching {
                json.decodeFromString(SignalEnvelope.serializer(), message.textPayload)
            }.onSuccess { decoded ->
                val signal = decoded.copy(remoteHost = handshakeRequest.remoteIpAddress)
                Log.d(logTag, "local signaling receive type=${signal.type} from=${signal.from} to=${signal.to} remote=${signal.remoteHost}")
                _events.tryEmit(signal)
            }
        }

        override fun onPong(pong: WebSocketFrame) = Unit
        override fun onException(exception: IOException) {
            Log.e(logTag, "local signaling websocket exception", exception)
        }
    }

    private companion object {
        const val WEBSOCKET_READ_TIMEOUT_MS = 0
        const val MIME_JSON = "application/json"
    }
}
