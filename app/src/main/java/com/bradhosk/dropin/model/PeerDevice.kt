package com.bradhosk.dropin.model

data class PeerDevice(
    val serviceName: String,
    val displayName: String,
    val host: String,
    val port: Int,
    val isReachable: Boolean = true,
)
