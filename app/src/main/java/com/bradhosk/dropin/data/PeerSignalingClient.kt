package com.bradhosk.dropin.data

import android.util.Log
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
    private val logTag = "DropInApp"
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    private val _events = MutableSharedFlow<SignalEnvelope>(extraBufferCapacity = 32)
    private val _status = MutableSharedFlow<PeerSignalingStatus>(extraBufferCapacity = 16)
    private var socket: WebSocket? = null
    private val pendingMessages = ArrayDeque<String>()
    private var isConnected = false

    val events = _events.asSharedFlow()
    val status = _status.asSharedFlow()

    fun connect(host: String, port: Int) {
        if (socket != null) return
        isConnected = false
        val request = Request.Builder()
            .url("ws://$host:$port")
            .build()
        Log.d(logTag, "signaling connect ws://$host:$port")
        _status.tryEmit(PeerSignalingStatus.Connecting(host, port))

        socket = httpClient.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d(logTag, "signaling open")
                    isConnected = true
                    _status.tryEmit(PeerSignalingStatus.Connected)
                    while (pendingMessages.isNotEmpty()) {
                        webSocket.send(pendingMessages.removeFirst())
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    Log.d(logTag, "signaling message length=${text.length}")
                    runCatching {
                        json.decodeFromString(SignalEnvelope.serializer(), text)
                    }.onSuccess { message ->
                        Log.d(logTag, "signaling receive type=${message.type} from=${message.from} to=${message.to}")
                        _events.tryEmit(message)
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(logTag, "signaling closed code=$code reason=$reason")
                    isConnected = false
                    socket = null
                    _status.tryEmit(PeerSignalingStatus.Closed(code, reason))
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e(logTag, "signaling failure", t)
                    isConnected = false
                    socket = null
                    _status.tryEmit(PeerSignalingStatus.Failed(t.message ?: "Unknown signaling error"))
                }
            },
        )
    }

    fun send(message: SignalEnvelope) {
        val encoded = json.encodeToString(SignalEnvelope.serializer(), message)
        if (isConnected) {
            socket?.send(encoded)
            Log.d(logTag, "signaling send type=${message.type}")
        } else {
            pendingMessages.addLast(encoded)
            Log.d(logTag, "signaling queue type=${message.type}")
        }
    }

    fun disconnect() {
        pendingMessages.clear()
        isConnected = false
        socket?.close(1000, "bye")
        socket = null
    }
}

sealed interface PeerSignalingStatus {
    data class Connecting(val host: String, val port: Int) : PeerSignalingStatus
    data object Connected : PeerSignalingStatus
    data class Closed(val code: Int, val reason: String) : PeerSignalingStatus
    data class Failed(val message: String) : PeerSignalingStatus
}
