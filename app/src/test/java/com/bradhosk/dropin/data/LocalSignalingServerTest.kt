package com.bradhosk.dropin.data

import java.net.ServerSocket
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LocalSignalingServerTest {
    @Test
    fun fallsBackToAvailablePortWhenPreferredPortIsBusy() {
        ServerSocket(DEFAULT_PORT).use {
            val server = LocalSignalingServer()
            try {
                server.start(DEFAULT_PORT)

                assertTrue(server.port in 1..65535)
                assertNotEquals(DEFAULT_PORT, server.port)
            } finally {
                server.stop()
            }
        }
    }

    private companion object {
        const val DEFAULT_PORT = 8989
    }
}
