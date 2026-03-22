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
    private val preferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val localServiceName = "dropin-${android.os.Build.MODEL}-${UUID.randomUUID().toString().take(4)}"
    private val signalingServer = LocalSignalingServer()
    private val peerDiscovery = NsdPeerDiscovery(appContext, localServiceName)
    private val tailnetRegistry = TailnetRegistryDiscovery(scope)
    private val _savedTailnetHost = MutableStateFlow(
        preferences.getString(KEY_SAVED_TAILNET_HOST, "").orEmpty(),
    )
    private val _tailnetRegistryUrl = MutableStateFlow(
        preferences.getString(KEY_TAILNET_REGISTRY_URL, "").orEmpty(),
    )
    private val _nonOfferSignals = MutableSharedFlow<SignalEnvelope>(extraBufferCapacity = 32)
    private val _pendingOffer = MutableStateFlow<SignalEnvelope?>(null)
    private var started = false

    val deviceName: String = localServiceName.removePrefix("dropin-")
    val localPeerId: String = localServiceName
    val savedTailnetHost: StateFlow<String> = _savedTailnetHost.asStateFlow()
    val tailnetRegistryUrl: StateFlow<String> = _tailnetRegistryUrl.asStateFlow()
    val peers: StateFlow<List<PeerDevice>> = combine(savedTailnetHost, peerDiscovery.peers, tailnetRegistry.peers) { savedHost, discovered, tailnet ->
        val manual = buildList {
            add(
                PeerDevice(
                    serviceName = "dropin-PC-Test",
                    displayName = "PC Test",
                    host = "192.168.0.18",
                    port = 8989,
                ),
            )
            if (savedHost.isNotBlank()) {
                add(
                    PeerDevice(
                        serviceName = "dropin-tailnet-saved",
                        displayName = "Saved Tailscale Peer",
                        host = savedHost,
                        port = 8989,
                    ),
                )
            }
        }
        (manual + discovered + tailnet)
            .distinctBy { it.serviceName }
            .sortedBy { it.displayName.lowercase() }
    }.stateIn(
        scope,
        SharingStarted.Eagerly,
        listOf(
            PeerDevice(
                serviceName = "dropin-PC-Test",
                displayName = "PC Test",
                host = "192.168.0.18",
                port = 8989,
            ),
        ),
    )
    val signals = _nonOfferSignals.asSharedFlow()
    val pendingOffer = _pendingOffer.asStateFlow()

    fun start() {
        if (started) return
        started = true
        signalingServer.start()
        peerDiscovery.start(signalingServer.port)
        tailnetRegistry.start(
            localServiceName = localPeerId,
            displayName = deviceName,
            portProvider = { signalingServer.port },
            registryUrl = tailnetRegistryUrl,
        )
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
        tailnetRegistry.stop()
        _pendingOffer.value = null
    }

    /** Re-trigger NSD and Tailnet discovery. */
    fun refreshPeers() {
        if (!started) return
        peerDiscovery.stop()
        peerDiscovery.start(signalingServer.port)
        tailnetRegistry.stop()
        tailnetRegistry.start(
            localServiceName = localPeerId,
            displayName = deviceName,
            portProvider = { signalingServer.port },
            registryUrl = tailnetRegistryUrl,
        )
    }

    fun sendLocal(message: SignalEnvelope) {
        signalingServer.send(message)
    }

    fun consumePendingOffer() {
        _pendingOffer.value = null
    }

    fun updateTailnetRegistryUrl(url: String) {
        val normalized = url.trim().trimEnd('/')
        if (_tailnetRegistryUrl.value == normalized) return
        _tailnetRegistryUrl.value = normalized
        preferences.edit().putString(KEY_TAILNET_REGISTRY_URL, normalized).apply()
    }

    fun updateSavedTailnetHost(host: String) {
        val normalized = host.trim()
        if (_savedTailnetHost.value == normalized) return
        _savedTailnetHost.value = normalized
        preferences.edit().putString(KEY_SAVED_TAILNET_HOST, normalized).apply()
    }

    companion object {
        private const val PREFS_NAME = "dropin_runtime"
        private const val KEY_SAVED_TAILNET_HOST = "saved_tailnet_host"
        private const val KEY_TAILNET_REGISTRY_URL = "tailnet_registry_url"
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
