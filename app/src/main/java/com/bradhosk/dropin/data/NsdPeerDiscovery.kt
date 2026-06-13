package com.bradhosk.dropin.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import com.bradhosk.dropin.DeviceCapability
import com.bradhosk.dropin.model.PeerDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

class NsdPeerDiscovery(
    private val context: Context,
    private val localServiceName: String,
) {
    private val nsdManager = context.getSystemService(NsdManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _peers = MutableStateFlow<List<PeerDevice>>(emptyList())
    private val discoveredPeers = ConcurrentHashMap<String, PeerDevice>()

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    val peers: StateFlow<List<PeerDevice>> = _peers.asStateFlow()

    fun start(port: Int) {
        registerService(port)
        discoverServices()
    }

    fun stop() {
        registrationListener?.let { listener -> runCatching { nsdManager.unregisterService(listener) } }
        discoveryListener?.let { listener -> runCatching { nsdManager.stopServiceDiscovery(listener) } }
        registrationListener = null
        discoveryListener = null
        discoveredPeers.clear()
        _peers.value = emptyList()
    }

    private fun registerService(port: Int) {
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = localServiceName
            serviceType = SERVICE_TYPE
            setPort(port)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                setAttribute(ATTR_DEVICE_CLASS, DeviceCapability.localDeviceClass(context))
            }
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }

        registrationListener = listener
        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun discoverServices() {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType != SERVICE_TYPE || serviceInfo.serviceName == localServiceName) {
                    return
                }
                nsdManager.resolveService(
                    serviceInfo,
                    object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit

                        override fun onServiceResolved(resolvedServiceInfo: NsdServiceInfo) {
                            val hostAddress = resolvedServiceInfo.host?.normalizeHost() ?: return
                            val peer = PeerDevice(
                                serviceName = resolvedServiceInfo.serviceName,
                                displayName = resolvedServiceInfo.serviceName.removePrefix(NAME_PREFIX),
                                host = hostAddress,
                                port = resolvedServiceInfo.port,
                                deviceClass = resolvedServiceInfo.readDeviceClass(),
                            )
                            discoveredPeers[peer.serviceName] = peer
                            emitPeers()
                        }
                    },
                )
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                discoveredPeers.remove(serviceInfo.serviceName)
                emitPeers()
            }
        }

        discoveryListener = listener
        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun emitPeers() {
        scope.launch {
            _peers.value = discoveredPeers.values.sortedBy { it.displayName.lowercase() }
        }
    }

    private fun InetAddress.normalizeHost(): String = hostAddress.substringBefore('%')

    private fun NsdServiceInfo.readDeviceClass(): String {
        val rawClass = readDeviceClassAttribute().orEmpty()
        return rawClass.ifBlank { DeviceCapability.CLASS_STANDARD }
    }

    private fun NsdServiceInfo.readDeviceClassAttribute(): String? {
        val raw = attributes?.get(ATTR_DEVICE_CLASS) ?: return null
        return String(raw, StandardCharsets.UTF_8)
    }

    companion object {
        const val SERVICE_TYPE = "_dropin._tcp."
        const val NAME_PREFIX = "dropin-"
        private const val ATTR_DEVICE_CLASS = "deviceClass"
    }
}
