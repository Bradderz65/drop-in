package com.bradhosk.dropin.data

import java.net.Inet4Address
import java.net.NetworkInterface

object TailscaleAddresses {
    fun all(): List<String> =
        runCatching {
            NetworkInterface.getNetworkInterfaces()
                ?.toList()
                .orEmpty()
                .flatMap { networkInterface ->
                    networkInterface.inetAddresses
                        ?.toList()
                        .orEmpty()
                        .filterIsInstance<Inet4Address>()
                        .mapNotNull { address ->
                            val host = address.hostAddress?.substringBefore('%')?.trim().orEmpty()
                            host.takeIf(::isTailscaleAddress)
                        }
                    }
                .distinct()
        }.getOrDefault(emptyList())

    fun primary(): String? = all().firstOrNull()

    fun isTailscaleAddress(host: String): Boolean {
        val octets = host.split('.').map { it.toIntOrNull() ?: return false }
        return octets.size == IPV4_OCTET_COUNT &&
            octets.all { it in IPV4_OCTET_RANGE } &&
            octets[0] == 100 &&
            octets[1] in TAILSCALE_SECOND_OCTET_RANGE
    }

    private const val IPV4_OCTET_COUNT = 4
    private val IPV4_OCTET_RANGE = 0..255
    private val TAILSCALE_SECOND_OCTET_RANGE = 64..127
}
