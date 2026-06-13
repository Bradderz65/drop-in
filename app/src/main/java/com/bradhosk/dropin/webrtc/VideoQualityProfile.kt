package com.bradhosk.dropin.webrtc

import com.bradhosk.dropin.DeviceCapability

data class VideoQualityProfile(
    val captureWidth: Int,
    val captureHeight: Int,
    val captureFps: Int,
    val maxBitrateBps: Int,
    val minBitrateBps: Int,
) {
    companion object {
        val STANDARD = VideoQualityProfile(
            captureWidth = 1280,
            captureHeight = 720,
            captureFps = 30,
            maxBitrateBps = 2_500_000,
            minBitrateBps = 300_000,
        )

        /** This device is a leanback/tablet receiver and sender. */
        val LIMITED = VideoQualityProfile(
            captureWidth = 640,
            captureHeight = 480,
            captureFps = 15,
            maxBitrateBps = 600_000,
            minBitrateBps = 150_000,
        )

        /** Full-power phone calling a limited peer — cap before GCC overreacts. */
        val TO_LIMITED_PEER = VideoQualityProfile(
            captureWidth = 640,
            captureHeight = 360,
            captureFps = 20,
            maxBitrateBps = 900_000,
            minBitrateBps = 250_000,
        )

        fun forCall(localClass: String, peerClass: String): VideoQualityProfile = when {
            localClass == DeviceCapability.CLASS_LIMITED -> LIMITED
            peerClass == DeviceCapability.CLASS_LIMITED -> TO_LIMITED_PEER
            else -> STANDARD
        }
    }
}
