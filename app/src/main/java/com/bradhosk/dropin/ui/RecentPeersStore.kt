package com.bradhosk.dropin.ui

import android.content.SharedPreferences
import androidx.core.content.edit
import com.bradhosk.dropin.data.DEFAULT_SIGNALING_PORT
import com.bradhosk.dropin.model.PeerDevice
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class RecentPeersStore(
    private val preferences: SharedPreferences,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun save(peers: List<PeerDevice>) {
        val encoded = json.encodeToString(
            ListSerializer(RecentPeer.serializer()),
            peers.take(MAX_RECENTS).map(RecentPeer::fromPeerDevice),
        )
        preferences.edit { putString(KEY_RECENTS, encoded) }
    }

    fun load(): List<PeerDevice> {
        val raw = preferences.getString(KEY_RECENTS, "").orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(RecentPeer.serializer()), raw)
                .map(RecentPeer::toPeerDevice)
        }.getOrElse {
            loadLegacyDelimited(raw)
        }.deduped().take(MAX_RECENTS)
    }

    private fun loadLegacyDelimited(raw: String): List<PeerDevice> =
        raw.split("|").mapNotNull { entry ->
            val parts = entry.split(";;")
            if (parts.size == LEGACY_FIELD_COUNT) {
                PeerDevice(
                    serviceName = parts[0],
                    displayName = parts[1],
                    host = parts[2],
                    port = parts[3].toIntOrNull() ?: DEFAULT_SIGNALING_PORT,
                )
            } else {
                null
            }
        }

    private fun List<PeerDevice>.deduped(): List<PeerDevice> =
        distinctBy { peer ->
            val normalizedHost = peer.host.trim().lowercase()
            if (normalizedHost.isNotBlank()) {
                "host:$normalizedHost:${peer.port}"
            } else {
                "service:${peer.serviceName.trim().lowercase()}"
            }
        }

    @Serializable
    private data class RecentPeer(
        val serviceName: String,
        val displayName: String,
        val host: String,
        val port: Int,
        val deviceClass: String = "standard",
    ) {
        fun toPeerDevice(): PeerDevice = PeerDevice(
            serviceName = serviceName,
            displayName = displayName,
            host = host,
            port = port,
            deviceClass = deviceClass,
        )

        companion object {
            fun fromPeerDevice(peer: PeerDevice): RecentPeer = RecentPeer(
                serviceName = peer.serviceName,
                displayName = peer.displayName,
                host = peer.host,
                port = peer.port,
                deviceClass = peer.deviceClass,
            )
        }
    }

    private companion object {
        const val KEY_RECENTS = "recents"
        const val MAX_RECENTS = 5
        const val LEGACY_FIELD_COUNT = 4
    }
}
