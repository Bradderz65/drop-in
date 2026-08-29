package com.bradhosk.dropin.data

import android.util.Log
import com.bradhosk.dropin.DeviceCapability
import com.bradhosk.dropin.model.PeerDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class TailnetRegistryDiscovery(
    private val scope: CoroutineScope,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val logTag = "DropInApp"
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()
    private val _peers = MutableStateFlow<List<PeerDevice>>(emptyList())
    private var syncJob: Job? = null

    val peers: StateFlow<List<PeerDevice>> = _peers.asStateFlow()

    fun start(
        localServiceName: String,
        displayName: String,
        portProvider: () -> Int,
        hostProvider: () -> String,
        deviceClassProvider: () -> String,
        registryUrl: StateFlow<String>,
    ) {
        syncJob?.cancel()
        syncJob = scope.launch {
            registryUrl.collectLatest { rawUrl ->
                val baseUrl = rawUrl.trim().trimEnd('/')
                if (baseUrl.isBlank()) {
                    _peers.value = emptyList()
                    return@collectLatest
                }

                while (currentCoroutineContext().isActive) {
                    val port = portProvider()
                    if (port > 0) {
                        registerPeer(
                            baseUrl = baseUrl,
                            localServiceName = localServiceName,
                            displayName = displayName,
                            port = port,
                            host = hostProvider(),
                            deviceClass = deviceClassProvider(),
                        )
                        fetchPeers(baseUrl, localServiceName)?.let { discoveredPeers ->
                            _peers.value = discoveredPeers
                        }
                    }
                    delay(SYNC_INTERVAL_MS)
                }
            }
        }
    }

    fun stop() {
        syncJob?.cancel()
        syncJob = null
        _peers.value = emptyList()
    }

    private suspend fun registerPeer(
        baseUrl: String,
        localServiceName: String,
        displayName: String,
        port: Int,
        host: String,
        deviceClass: String,
    ) {
        val payload = json.encodeToString(
            TailnetPeerRegistration.serializer(),
            TailnetPeerRegistration(
                serviceName = localServiceName,
                displayName = displayName,
                port = port,
                host = host,
                deviceClass = deviceClass,
            ),
        )
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url("$baseUrl/api/registry/register")
                    .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IllegalStateException("registry register failed ${response.code}")
                    }
                }
            }.onFailure { error ->
                Log.w(logTag, "tailnet registry register failed url=$baseUrl host=$host", error)
            }
        }
    }

    private suspend fun fetchPeers(baseUrl: String, localServiceName: String): List<PeerDevice>? =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url("$baseUrl/api/registry/peers?exclude=$localServiceName")
                    .get()
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IllegalStateException("registry peers failed ${response.code}")
                    }
                    val body = response.body?.string().orEmpty()
                    json.decodeFromString(TailnetPeersResponse.serializer(), body).peers.map { peer ->
                        PeerDevice(
                            serviceName = peer.serviceName,
                            displayName = peer.displayName,
                            host = peer.host,
                            port = peer.port,
                            deviceClass = peer.deviceClass ?: DeviceCapability.CLASS_STANDARD,
                        )
                    }
                }
            }.onFailure { error ->
                Log.w(logTag, "tailnet registry fetch failed url=$baseUrl", error)
            }.getOrNull()
        }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private const val SYNC_INTERVAL_MS = 15_000L
    }
}
