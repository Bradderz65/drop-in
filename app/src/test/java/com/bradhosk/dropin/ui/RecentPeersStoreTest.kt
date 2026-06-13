package com.bradhosk.dropin.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bradhosk.dropin.model.PeerDevice
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RecentPeersStoreTest {
    private lateinit var store: RecentPeersStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("recent-peers-test", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        store = RecentPeersStore(preferences)
    }

    @Test
    fun roundTripsPeerFieldsContainingLegacyDelimiters() {
        val peer = PeerDevice(
            serviceName = "dropin-phone;;alpha",
            displayName = "Phone|Kitchen;;A",
            host = "100.64.1.2",
            port = 8989,
            deviceClass = "limited",
        )

        store.save(listOf(peer))

        assertEquals(listOf(peer), store.load())
    }

    @Test
    fun fallsBackToLegacyDelimitedRecents() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("legacy-recent-peers-test", Context.MODE_PRIVATE)
        preferences.edit()
            .clear()
            .putString("recents", "dropin-a;;Kitchen;;192.168.1.10;;8989|dropin-b;;Living Room;;192.168.1.11;;8990")
            .commit()

        val loaded = RecentPeersStore(preferences).load()

        assertEquals(2, loaded.size)
        assertEquals("dropin-a", loaded[0].serviceName)
        assertEquals("Kitchen", loaded[0].displayName)
        assertEquals("192.168.1.11", loaded[1].host)
    }

    @Test
    fun capsRecentsAtFive() {
        val peers = (1..7).map { index ->
            PeerDevice(
                serviceName = "dropin-$index",
                displayName = "Peer $index",
                host = "100.64.1.$index",
                port = 8989,
            )
        }

        store.save(peers)

        assertEquals(5, store.load().size)
    }
}
