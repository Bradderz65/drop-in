package com.bradhosk.dropin.ui

import com.bradhosk.dropin.model.PeerDevice

const val SAVED_TAILNET_PEER_ID = "dropin-tailnet-saved"

/**
 * One callable device, however many ways there are to reach it. The same device shows up as a
 * LAN discovery, a tailnet discovery, the saved tailnet address and a remembered recent; the
 * user should see one name and the app should work out which address answers.
 */
data class Contact(
    val key: String,
    val displayName: String,
    /** Addresses to try, best first. Never empty. */
    val routes: List<PeerDevice>,
    val isOnline: Boolean,
) {
    /** The entry shown in the list; dialling it resolves back to [routes] by [key]. */
    val primary: PeerDevice get() = routes.first().copy(displayName = displayName)
}

private val transientSuffix = Regex("-[0-9a-fA-F]{4}$")

fun String.stripTransientSuffix(): String = replace(transientSuffix, "")

/** Identity that survives the random id suffix and letter case. */
fun String.stableDeviceId(): String =
    trim().removePrefix("dropin-").stripTransientSuffix().lowercase()

fun isTailnetHost(host: String): Boolean {
    if (host.trim().endsWith(".ts.net", ignoreCase = true)) return true
    val parts = host.trim().split('.')
    if (parts.size != 4) return false
    val second = parts[1].toIntOrNull() ?: return false
    return parts[0] == "100" && second in 64..127
}

fun buildContacts(
    discovered: List<PeerDevice>,
    recents: List<PeerDevice>,
): List<Contact> {
    val named = (discovered + recents).filterNot { it.serviceName == SAVED_TAILNET_PEER_ID }
    val keyByHost = named
        .filter { it.host.isNotBlank() }
        .associate { it.host.trim().lowercase() to it.serviceName.stableDeviceId() }

    fun keyOf(peer: PeerDevice): String =
        if (peer.serviceName == SAVED_TAILNET_PEER_ID) {
            // The saved address has no name of its own; fold it into the device known at that host.
            keyByHost[peer.host.trim().lowercase()] ?: SAVED_TAILNET_PEER_ID
        } else {
            peer.serviceName.stableDeviceId()
        }

    // Live discoveries beat memory, and a tailnet address beats a remembered LAN one: the
    // tailnet address works at home and away, a stale LAN address only fails slowly.
    data class Ranked(val peer: PeerDevice, val rank: Int, val live: Boolean)

    val ranked = buildList {
        discovered.forEach { peer ->
            val rank = when {
                peer.serviceName == SAVED_TAILNET_PEER_ID -> 2
                isTailnetHost(peer.host) -> 1
                else -> 0
            }
            add(Ranked(peer, rank, live = peer.serviceName != SAVED_TAILNET_PEER_ID))
        }
        recents
            .filterNot { it.serviceName == SAVED_TAILNET_PEER_ID }
            .forEach { peer -> add(Ranked(peer, if (isTailnetHost(peer.host)) 3 else 4, live = false)) }
    }.filter { it.peer.host.isNotBlank() }

    return ranked
        .groupBy { keyOf(it.peer) }
        .map { (key, entries) ->
            val ordered = entries.sortedBy { it.rank }
            val name = ordered
                .firstOrNull { it.peer.serviceName != SAVED_TAILNET_PEER_ID }
                ?.peer
                ?.let { it.displayName.trim().ifBlank { it.serviceName.removePrefix("dropin-") } }
                ?.stripTransientSuffix()
                ?: ordered.first().peer.displayName
            Contact(
                key = key,
                displayName = name,
                routes = ordered.map { it.peer }.distinctBy { "${it.host.trim().lowercase()}:${it.port}" },
                isOnline = ordered.any { it.live },
            )
        }
        // Alphabetical only. Sorting reachable devices first made rows swap places whenever
        // someone joined or left the Wi-Fi, so the same spot on screen called a different person.
        .sortedBy { it.displayName.lowercase() }
}
