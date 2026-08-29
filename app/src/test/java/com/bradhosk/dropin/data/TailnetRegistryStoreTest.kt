package com.bradhosk.dropin.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TailnetRegistryStoreTest {
    @Test
    fun rejectsPortsOutsideTcpRange() {
        val store = TailnetRegistryStore()

        assertFalse(store.registerFromJson(registration(port = 0), "100.64.1.2"))
        assertFalse(store.registerFromJson(registration(port = 65_536), "100.64.1.2"))
        assertTrue(store.peers().isEmpty())
    }

    @Test
    fun acceptsValidRegistrationAndUsesRemoteHostFallback() {
        val store = TailnetRegistryStore()

        assertTrue(store.registerFromJson(registration(port = 8_989), "100.64.1.2"))

        val peer = store.peers().single()
        assertTrue(peer.host == "100.64.1.2")
        assertTrue(peer.port == 8_989)
    }

    private fun registration(port: Int): String =
        """{"service_name":"dropin-phone","display_name":"Phone","port":$port}"""
}
