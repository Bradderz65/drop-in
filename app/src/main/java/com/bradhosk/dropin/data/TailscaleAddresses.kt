package com.bradhosk.dropin.data

import java.net.Inet4Address
import java.net.NetworkInterface

object TailscaleAddresses {
    fun all(): List<String> =
        NetworkInterface.getNetworkInterfaces()
            .toList()
            .flatMap { networkInterface ->
                networkInterface.inetAddresses
                    .toList()
                    .filterIsInstance<Inet4Address>()
                    .mapNotNull { address ->
                        val host = address.hostAddress?.substringBefore('%')?.trim().orEmpty()
                        host.takeIf { isTailscaleAddress(it) }
                    }
            }
            .distinct()

    fun primary(): String? = all().firstOrNull()

    fun isTailscaleAddress(host: String): Boolean {
        val parts = host.split('.')
        if (parts.size != 4) return false
        val first = parts[0].toIntOrNull() ?: return false
        val second = parts[1].toIntOrNull() ?: return false
        return first == 100 && second in 64..127
    }
}
