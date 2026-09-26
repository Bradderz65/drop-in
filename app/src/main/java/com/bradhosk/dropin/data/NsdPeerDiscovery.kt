package com.bradhosk.dropin.data

import android.annotation.SuppressLint
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.bradhosk.dropin.DeviceCapability
import com.bradhosk.dropin.model.PeerDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

class NsdPeerDiscovery(
    private val context: Context,
    private val localServiceName: String,
) {
    private val nsdManager = context.getSystemService(NsdManager::class.java)
    private val _peers = MutableStateFlow<List<PeerDevice>>(emptyList())
    private val discoveredPeers = ConcurrentHashMap<String, PeerDevice>()
    private val serviceInfoCallbacks = ConcurrentHashMap<String, NsdManager.ServiceInfoCallback>()

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    val peers: StateFlow<List<PeerDevice>> = _peers.asStateFlow()

    fun start(port: Int) {
        if (port !in 1..65535) return
        runCatching { registerService(port) }
            .onFailure { error -> Log.w(LOG_TAG, "NSD registration could not start", error) }
        runCatching { discoverServices() }
            .onFailure { error -> Log.w(LOG_TAG, "NSD discovery could not start", error) }
    }

    fun stop() {
        registrationListener?.let { listener ->
            runCatching { nsdManager.unregisterService(listener) }
                .onFailure { error -> Log.d(LOG_TAG, "NSD service was already unregistered", error) }
        }
        discoveryListener?.let { listener ->
            runCatching { nsdManager.stopServiceDiscovery(listener) }
                .onFailure { error -> Log.d(LOG_TAG, "NSD discovery was already stopped", error) }
        }
        unregisterAllServiceInfoCallbacks()
        serviceInfoCallbacks.clear()
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
            setAttribute(ATTR_DEVICE_CLASS, DeviceCapability.localDeviceClass(context))
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                Log.d(LOG_TAG, "NSD registered ${serviceInfo.serviceName}:${serviceInfo.port}")
            }
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(LOG_TAG, "NSD registration failed code=$errorCode")
            }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(LOG_TAG, "NSD unregistration failed code=$errorCode")
            }
        }

        registrationListener = listener
        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun discoverServices() {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(LOG_TAG, "NSD discovery start failed code=$errorCode")
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(LOG_TAG, "NSD discovery stop failed code=$errorCode")
            }
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType != SERVICE_TYPE || serviceInfo.serviceName == localServiceName) {
                    return
                }
                resolveService(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                unregisterServiceInfoCallback(serviceInfo.serviceName)
                discoveredPeers.remove(serviceInfo.serviceName)
                emitPeers()
            }
        }

        discoveryListener = listener
        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun resolveService(serviceInfo: NsdServiceInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            resolveServiceWithCallback(serviceInfo)
        } else {
            resolveServiceLegacy(serviceInfo)
        }
    }

    @SuppressLint("NewApi") // API 34 includes T extension 7; lint does not infer that relationship.
    private fun resolveServiceWithCallback(serviceInfo: NsdServiceInfo) {
        if (serviceInfoCallbacks.containsKey(serviceInfo.serviceName)) return
        val callback = object : NsdManager.ServiceInfoCallback {
            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                serviceInfoCallbacks.remove(serviceInfo.serviceName, this)
                Log.d(LOG_TAG, "NSD resolve registration failed service=${serviceInfo.serviceName} code=$errorCode")
            }

            override fun onServiceInfoCallbackUnregistered() {
                serviceInfoCallbacks.remove(serviceInfo.serviceName, this)
            }

            override fun onServiceLost() {
                discoveredPeers.remove(serviceInfo.serviceName)
                emitPeers()
            }

            override fun onServiceUpdated(updatedServiceInfo: NsdServiceInfo) {
                val hostAddress = updatedServiceInfo.hostAddresses.firstOrNull()?.normalizeHost() ?: return
                updatePeer(updatedServiceInfo, hostAddress)
            }
        }
        serviceInfoCallbacks[serviceInfo.serviceName] = callback
        runCatching {
            nsdManager.registerServiceInfoCallback(
                serviceInfo,
                ContextCompat.getMainExecutor(context),
                callback,
            )
        }.onFailure { error ->
            serviceInfoCallbacks.remove(serviceInfo.serviceName, callback)
            Log.d(LOG_TAG, "NSD resolve could not start service=${serviceInfo.serviceName}", error)
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveServiceLegacy(serviceInfo: NsdServiceInfo) {
        nsdManager.resolveService(
            serviceInfo,
            object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.d(LOG_TAG, "NSD resolve failed service=${serviceInfo.serviceName} code=$errorCode")
                }

                override fun onServiceResolved(resolvedServiceInfo: NsdServiceInfo) {
                    val hostAddress = resolvedServiceInfo.host?.normalizeHost() ?: return
                    updatePeer(resolvedServiceInfo, hostAddress)
                }
            },
        )
    }

    @SuppressLint("NewApi") // API 34 includes T extension 7; lint does not infer that relationship.
    private fun unregisterServiceInfoCallback(serviceName: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val callback = serviceInfoCallbacks.remove(serviceName) ?: return
        runCatching { nsdManager.unregisterServiceInfoCallback(callback) }
    }

    @SuppressLint("NewApi") // API 34 includes T extension 7; lint does not infer that relationship.
    private fun unregisterAllServiceInfoCallbacks() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        serviceInfoCallbacks.values.forEach { callback ->
            runCatching { nsdManager.unregisterServiceInfoCallback(callback) }
        }
    }

    private fun updatePeer(serviceInfo: NsdServiceInfo, hostAddress: String) {
        if (serviceInfo.port !in VALID_PORT_RANGE) return
        val peer = PeerDevice(
            serviceName = serviceInfo.serviceName,
            displayName = serviceInfo.serviceName.removePrefix(NAME_PREFIX),
            host = hostAddress,
            port = serviceInfo.port,
            deviceClass = serviceInfo.readDeviceClass(),
        )
        discoveredPeers[peer.serviceName] = peer
        emitPeers()
    }

    private fun emitPeers() {
        _peers.value = discoveredPeers.values.sortedBy { it.displayName.lowercase() }
    }

    private fun InetAddress.normalizeHost(): String = hostAddress?.substringBefore('%').orEmpty()

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
        private const val LOG_TAG = "DropInApp"
        private val VALID_PORT_RANGE = 1..65535
    }
}
