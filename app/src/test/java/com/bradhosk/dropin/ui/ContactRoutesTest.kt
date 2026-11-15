package com.bradhosk.dropin.ui

import com.bradhosk.dropin.model.PeerDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactRoutesTest {
    private fun peer(service: String, name: String, host: String) =
        PeerDevice(serviceName = service, displayName = name, host = host, port = 8989)

    @Test
    fun `one device seen four ways becomes one contact`() {
        val contacts = buildContacts(
            discovered = listOf(
                peer("dropin-Echo Show 8-97f4", "Echo Show 8-97f4", "192.168.0.49"),
                peer(SAVED_TAILNET_PEER_ID, "Saved Tailscale Peer", "100.83.97.16"),
            ),
            recents = listOf(
                peer("dropin-Echo Show 8", "Echo Show 8", "192.168.0.49"),
                peer("dropin-Echo Show 8", "Echo Show 8", "100.83.97.16"),
            ),
        )

        assertEquals(1, contacts.size)
        assertEquals("Echo Show 8", contacts.single().displayName)
        assertTrue(contacts.single().isOnline)
        // Live LAN address first, then the tailnet address; no duplicates.
        assertEquals(listOf("192.168.0.49", "100.83.97.16"), contacts.single().routes.map { it.host })
    }

    @Test
    fun `remembered device prefers its tailnet address when nothing is discovered`() {
        val contacts = buildContacts(
            discovered = emptyList(),
            recents = listOf(
                peer("dropin-Echo Show 8", "Echo Show 8", "192.168.0.49"),
                peer("dropin-Echo Show 8", "Echo Show 8", "100.83.97.16"),
            ),
        )

        assertFalse(contacts.single().isOnline)
        assertEquals(listOf("100.83.97.16", "192.168.0.49"), contacts.single().routes.map { it.host })
    }

    @Test
    fun `saved address with no known name stays its own contact`() {
        val contacts = buildContacts(
            discovered = listOf(peer(SAVED_TAILNET_PEER_ID, "Saved Tailscale Peer", "100.83.97.16")),
            recents = emptyList(),
        )

        assertEquals("Saved Tailscale Peer", contacts.single().displayName)
    }

    @Test
    fun `stale saved-peer recents are dropped`() {
        val contacts = buildContacts(
            discovered = emptyList(),
            recents = listOf(peer(SAVED_TAILNET_PEER_ID, "Saved Tailscale Peer", "192.168.0.5")),
        )

        assertTrue(contacts.isEmpty())
    }

    @Test
    fun `tailnet hosts are recognised`() {
        assertTrue(isTailnetHost("100.83.97.16"))
        assertTrue(isTailnetHost("echo-show-8.tail1234.ts.net"))
        assertFalse(isTailnetHost("192.168.0.49"))
        assertFalse(isTailnetHost("100.20.1.1"))
    }
}
