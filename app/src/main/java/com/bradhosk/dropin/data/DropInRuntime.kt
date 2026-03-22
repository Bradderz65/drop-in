package com.bradhosk.dropin.data

import android.content.Context
import android.util.Log
import com.bradhosk.dropin.model.PeerDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

class DropInRuntime private constructor(
    context: Context,
) {
    private val logTag = "DropInApp"
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val localServiceName = "dropin-${android.os.Build.MODEL}-${UUID.randomUUID().toString().take(4)}"
    private val signalingServer = LocalSignalingServer()
    private val peerDiscovery = NsdPeerDiscovery(appContext, localServiceName)
    private val manualPeers = MutableStateFlow(
        listOf(
            PeerDevice(
                serviceName = "dropin-PC-Test",
                displayName = "PC Test",
                host = "192.168.0.18",
                port = 8989,
            ),
        ),
    )
    private val _nonOfferSignals = MutableSharedFlow<SignalEnvelope>(extraBufferCapacity = 32)
    private val _pendingOffer = MutableStateFlow<SignalEnvelope?>(null)
    private var started = false

    val deviceName: String = localServiceName.removePrefix("dropin-")
    val localPeerId: String = localServiceName
    val peers: StateFlow<List<PeerDevice>> = combine(manualPeers, peerDiscovery.peers) { manual, discovered ->
        (manual + discovered)
            .distinctBy { it.serviceName }
            .sortedBy { it.displayName.lowercase() }
    }.stateIn(scope, SharingStarted.Eagerly, manualPeers.value)
    val signals = _nonOfferSignals.asSharedFlow()
    val pendingOffer = _pendingOffer.asStateFlow()

    fun start() {
        if (started) return
        started = true
        signalingServer.start()
        peerDiscovery.start(signalingServer.port)
        scope.launch {
            signalingServer.events.collect { signal ->
                Log.d(logTag, "runtime signal type=${signal.type} from=${signal.from} to=${signal.to}")
                if (signal.type == SignalType.OFFER) {
                    _pendingOffer.value = signal
                } else {
                    _nonOfferSignals.emit(signal)
                }
            }
        }
    }

    fun stop() {
        if (!started) return
        started = false
        signalingServer.stop()
        peerDiscovery.stop()
        _pendingOffer.value = null
    }

    fun sendLocal(message: SignalEnvelope) {
        signalingServer.send(message)
    }

    fun consumePendingOffer() {
        _pendingOffer.value = null
    }

    companion object {
        @Volatile
        private var instance: DropInRuntime? = null

        fun getInstance(context: Context): DropInRuntime =
            instance ?: synchronized(this) {
                instance ?: DropInRuntime(context).also { created ->
                    instance = created
                }
            }
    }
}
