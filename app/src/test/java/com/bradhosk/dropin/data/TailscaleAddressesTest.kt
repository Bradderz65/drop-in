package com.bradhosk.dropin.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TailscaleAddressesTest {
    @Test
    fun acceptsOnlyValidAddressesInCarrierGradeNatRange() {
        assertTrue(TailscaleAddresses.isTailscaleAddress("100.64.0.1"))
        assertTrue(TailscaleAddresses.isTailscaleAddress("100.127.255.254"))

        assertFalse(TailscaleAddresses.isTailscaleAddress("100.63.255.255"))
        assertFalse(TailscaleAddresses.isTailscaleAddress("100.128.0.1"))
        assertFalse(TailscaleAddresses.isTailscaleAddress("100.64.999.1"))
        assertFalse(TailscaleAddresses.isTailscaleAddress("100.64.1"))
        assertFalse(TailscaleAddresses.isTailscaleAddress("not-an-address"))
    }
}
