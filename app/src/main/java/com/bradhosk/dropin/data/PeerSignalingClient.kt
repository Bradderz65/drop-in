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
    private val lock = Any()
    private var socket: WebSocket? = null
    private val pendingMessages = ArrayDeque<String>()
    private var isConnected = false
    private var isConnecting = false
    private var connectionId = 0L

    val events = _events.asSharedFlow()
    val status = _status.asSharedFlow()

    fun connect(host: String, port: Int) {
        val newConnectionId = synchronized(lock) {
            if (socket != null || isConnecting) return
            isConnected = false
            isConnecting = true
            ++connectionId
        }
        val request = runCatching {
            require(host.isNotBlank()) { "Peer host is blank" }
            require(port in 1..65535) { "Peer port is invalid: $port" }
            Request.Builder()
                .url("ws://$host:$port")
                .build()
        }.getOrElse { error ->
            synchronized(lock) {
                if (connectionId == newConnectionId) {
                    isConnected = false
                    isConnecting = false
                    socket = null
                }
            }
            val message = error.message ?: "Invalid peer address"
            Log.w(logTag, "signaling rejected peer address host=$host port=$port", error)
            _status.tryEmit(PeerSignalingStatus.Failed(message))
            return
        }
        Log.d(logTag, "signaling connect ${request.url}")
        _status.tryEmit(PeerSignalingStatus.Connecting(host, port))

        val newSocket = httpClient.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d(logTag, "signaling open")
                    val messagesToSend = synchronized(lock) {
                        if (connectionId != newConnectionId || !isConnecting) {
                            return@synchronized emptyList<String>()
                        }
                        socket = webSocket
                        isConnecting = false
                        isConnected = true
                        buildList {
                            while (pendingMessages.isNotEmpty()) {
                                add(pendingMessages.removeFirst())
                            }
                        }
                    }
                    _status.tryEmit(PeerSignalingStatus.Connected)
                    messagesToSend.forEach(webSocket::send)
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
                    synchronized(lock) {
                        if (connectionId == newConnectionId && socket === webSocket) {
                            isConnected = false
                            isConnecting = false
                            socket = null
                        }
                    }
                    _status.tryEmit(PeerSignalingStatus.Closed(code, reason))
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e(logTag, "signaling failure", t)
                    synchronized(lock) {
                        if (connectionId == newConnectionId) {
                            isConnected = false
                            isConnecting = false
                            socket = null
                        }
                    }
                    _status.tryEmit(PeerSignalingStatus.Failed(t.message ?: "Unknown signaling error"))
                }
            },
        )
        synchronized(lock) {
            when {
                connectionId != newConnectionId -> newSocket.close(1000, "superseded")
                isConnecting -> socket = newSocket
                socket !== newSocket -> newSocket.close(1000, "superseded")
                else -> Unit
            }
        }
    }

    fun send(message: SignalEnvelope) {
        val encoded = json.encodeToString(SignalEnvelope.serializer(), message)
        val activeSocket = synchronized(lock) {
            if (isConnected) {
                socket
            } else {
                pendingMessages.addLast(encoded)
                null
            }
        }
        if (activeSocket != null) {
            activeSocket.send(encoded)
            Log.d(logTag, "signaling send type=${message.type}")
        } else {
            Log.d(logTag, "signaling queue type=${message.type}")
        }
    }

    fun disconnect() {
        val socketToClose = synchronized(lock) {
            pendingMessages.clear()
            isConnected = false
            isConnecting = false
            connectionId++
            socket.also { socket = null }
        }
        socketToClose?.close(1000, "bye")
    }
}

sealed interface PeerSignalingStatus {
    data class Connecting(val host: String, val port: Int) : PeerSignalingStatus
    data object Connected : PeerSignalingStatus
    data class Closed(val code: Int, val reason: String) : PeerSignalingStatus
    data class Failed(val message: String) : PeerSignalingStatus
}
