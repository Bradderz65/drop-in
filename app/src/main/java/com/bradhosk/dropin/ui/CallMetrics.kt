package com.bradhosk.dropin.ui

data class CallMetrics(
    val durationSeconds: Long = 0,
    val connectionQuality: ConnectionQuality = ConnectionQuality.UNKNOWN,
)
