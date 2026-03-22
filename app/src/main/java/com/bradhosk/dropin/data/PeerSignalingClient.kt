package com.bradhosk.dropin.data

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

class PeerSignalingClient(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val _events = MutableSharedFlow<SignalEnvelope>(extraBufferCapacity = 32)
    private var socket: WebSocket? = null

    val events = _events.asSharedFlow()

    fun connect(host: String, port: Int) {
        if (socket != null) return
        val request = Request.Builder()
            .url("ws://$host:$port")
            .build()

        socket = httpClient.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    runCatching {
                        json.decodeFromString(SignalEnvelope.serializer(), text)
                    }.onSuccess { message ->
                        _events.tryEmit(message)
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    socket = null
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    socket = null
                }
            },
        )
    }

    fun send(message: SignalEnvelope) {
        socket?.send(json.encodeToString(SignalEnvelope.serializer(), message))
    }

    fun disconnect() {
        socket?.close(1000, "bye")
        socket = null
    }
}
