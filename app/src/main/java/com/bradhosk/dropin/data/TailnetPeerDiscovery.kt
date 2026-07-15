package com.bradhosk.dropin.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.bradhosk.dropin.DeviceCapability
import com.bradhosk.dropin.model.PeerDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Inet4Address
import java.util.concurrent.TimeUnit

/** Discovers Drop In peers advertised by the active Android Tailscale VPN routes. */
class TailnetPeerDiscovery(
    context: Context,
    private val scope: CoroutineScope,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val logTag = "DropInApp"
    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()
    private val _peers = MutableStateFlow<List<PeerDevice>>(emptyList())
    private val _localAddress = MutableStateFlow<String?>(null)
    private var periodicRefreshJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    val peers: StateFlow<List<PeerDevice>> = _peers.asStateFlow()
    val localAddress: StateFlow<String?> = _localAddress.asStateFlow()

    fun start() {
        if (networkCallback != null) return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = refreshSoon()
            override fun onLost(network: Network) = refreshSoon()
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = refreshSoon()
        }
        networkCallback = callback
        connectivityManager.registerNetworkCallback(
            NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                .build(),
            callback,
        )
        periodicRefreshJob = scope.launch {
            while (isActive) {
                refresh()
                delay(REFRESH_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        networkCallback?.let { callback ->
            runCatching { connectivityManager.unregisterNetworkCallback(callback) }
        }
        networkCallback = null
        periodicRefreshJob?.cancel()
        periodicRefreshJob = null
        _peers.value = emptyList()
        _localAddress.value = null
    }

    fun refreshSoon() {
        scope.launch { refresh() }
    }

    private suspend fun refresh() {
        val localAddresses = TailscaleAddresses.all()
        _localAddress.value = localAddresses.firstOrNull()
        val candidateHosts = tailnetRouteHosts().filterNot(localAddresses::contains)
        if (candidateHosts.isEmpty()) {
            _peers.value = emptyList()
            return
        }
        val peers = buildList {
            candidateHosts.forEach { host ->
                fetchPeer(host)?.let(::add)
            }
        }
        _peers.value = peers.sortedBy { it.displayName.lowercase() }
    }

    private fun tailnetRouteHosts(): Set<String> =
        connectivityManager.allNetworks
            .asSequence()
            .filter { network ->
                connectivityManager.getNetworkCapabilities(network)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            }
            .flatMap { network ->
                connectivityManager.getLinkProperties(network)
                    ?.routes
                    ?.asSequence()
                    .orEmpty()
            }
            .mapNotNull { route ->
                val destination = route.destination ?: return@mapNotNull null
                val address = destination.address as? Inet4Address ?: return@mapNotNull null
                val host = address.hostAddress?.substringBefore('%').orEmpty()
                host.takeIf {
                    destination.prefixLength == IPV4_HOST_PREFIX_LENGTH &&
                        TailscaleAddresses.isTailscaleAddress(it)
                }
            }
            .toSet()

    private suspend fun fetchPeer(host: String): PeerDevice? = withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("http://$host:$DEFAULT_SIGNALING_PORT/api/identity")
                .get()
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val identity = json.decodeFromString(
                    TailnetPeerIdentity.serializer(),
                    response.body?.string().orEmpty(),
                )
                if (identity.serviceName.isBlank() || identity.port <= 0) return@use null
                PeerDevice(
                    serviceName = identity.serviceName,
                    displayName = identity.displayName.ifBlank { identity.serviceName.removePrefix("dropin-") },
                    host = host,
                    port = identity.port,
                    deviceClass = identity.deviceClass ?: DeviceCapability.CLASS_STANDARD,
                )
            }
        }.onFailure { error ->
            Log.d(logTag, "tailnet peer probe failed host=$host", error)
        }.getOrNull()
    }

    private companion object {
        const val DEFAULT_SIGNALING_PORT = 8989
        const val IPV4_HOST_PREFIX_LENGTH = 32
        const val CONNECT_TIMEOUT_MS = 1_500L
        const val READ_TIMEOUT_MS = 1_500L
        const val REFRESH_INTERVAL_MS = 15_000L
    }
}
