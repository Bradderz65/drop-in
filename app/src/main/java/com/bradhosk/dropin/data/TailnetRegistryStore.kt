package com.bradhosk.dropin.data

import com.bradhosk.dropin.DeviceCapability
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class TailnetRegistryStore(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val records = linkedMapOf<String, TailnetPeerRecord>()
    private val lock = Any()

    fun register(
        serviceName: String,
        displayName: String,
        host: String,
        port: Int,
        deviceClass: String = DeviceCapability.CLASS_STANDARD,
        persistent: Boolean = false,
    ) {
        if (serviceName.isBlank() || host.isBlank() || port <= 0) return
        synchronized(lock) {
            records[serviceName] = TailnetPeerRecord(
                serviceName = serviceName,
                displayName = displayName.ifBlank { serviceName },
                host = host,
                port = port,
                deviceClass = deviceClass,
                persistent = persistent,
                lastSeenEpochSeconds = System.currentTimeMillis() / 1000,
            )
        }
    }

    fun peers(exclude: String? = null): List<TailnetPeerRecord> {
        val now = System.currentTimeMillis() / 1000
        synchronized(lock) {
            val staleKeys = records.filterValues { record ->
                !record.persistent && now - record.lastSeenEpochSeconds > REGISTRY_TTL_SECONDS
            }.keys
            staleKeys.forEach(records::remove)
            return records.values
                .filter { it.serviceName != exclude }
                .sortedBy { it.displayName.lowercase() }
                .map { it.copy(persistent = false, lastSeenEpochSeconds = 0) }
        }
    }

    fun peersJson(exclude: String? = null): String =
        json.encodeToString(
            TailnetPeersResponse.serializer(),
            TailnetPeersResponse(peers = peers(exclude)),
        )

    fun registerFromJson(body: String, remoteHost: String): Boolean {
        val payload = runCatching {
            json.decodeFromString(TailnetPeerRegistration.serializer(), body)
        }.getOrNull() ?: return false
        val host = payload.host?.trim().orEmpty().ifBlank { remoteHost.trim() }
        if (payload.serviceName.isBlank() || host.isBlank() || payload.port <= 0) return false
        register(
            serviceName = payload.serviceName,
            displayName = payload.displayName,
            host = host,
            port = payload.port,
            deviceClass = payload.deviceClass ?: DeviceCapability.CLASS_STANDARD,
        )
        return true
    }

    private companion object {
        const val REGISTRY_TTL_SECONDS = 45L
    }
}

@Serializable
data class TailnetPeerRegistration(
    @SerialName("service_name")
    val serviceName: String,
    @SerialName("display_name")
    val displayName: String,
    val port: Int,
    val host: String? = null,
    @SerialName("device_class")
    val deviceClass: String? = null,
)

@Serializable
data class TailnetPeerRecord(
    @SerialName("service_name")
    val serviceName: String,
    @SerialName("display_name")
    val displayName: String,
    val host: String,
    val port: Int,
    @SerialName("device_class")
    val deviceClass: String? = null,
    @kotlinx.serialization.Transient
    val persistent: Boolean = false,
    @kotlinx.serialization.Transient
    val lastSeenEpochSeconds: Long = 0,
)

@Serializable
data class TailnetPeersResponse(
    val peers: List<TailnetPeerRecord>,
)
