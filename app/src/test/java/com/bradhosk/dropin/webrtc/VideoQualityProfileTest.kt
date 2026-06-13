package com.bradhosk.dropin.webrtc

import com.bradhosk.dropin.DeviceCapability
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoQualityProfileTest {
    @Test
    fun limitedLocalDeviceUsesLimitedProfile() {
        assertEquals(
            VideoQualityProfile.LIMITED,
            VideoQualityProfile.forCall(DeviceCapability.CLASS_LIMITED, DeviceCapability.CLASS_STANDARD),
        )
    }

    @Test
    fun standardLocalCallingLimitedPeerUsesCappedProfile() {
        assertEquals(
            VideoQualityProfile.TO_LIMITED_PEER,
            VideoQualityProfile.forCall(DeviceCapability.CLASS_STANDARD, DeviceCapability.CLASS_LIMITED),
        )
    }

    @Test
    fun standardDevicesUseStandardProfile() {
        assertEquals(
            VideoQualityProfile.STANDARD,
            VideoQualityProfile.forCall(DeviceCapability.CLASS_STANDARD, DeviceCapability.CLASS_STANDARD),
        )
    }
}
