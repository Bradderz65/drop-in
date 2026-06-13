package com.bradhosk.dropin.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SignalingModelsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun decodesOfferWithUnknownFields() {
        val decoded = json.decodeFromString(
            SignalEnvelope.serializer(),
            """
            {
              "type": "offer",
              "from": "dropin-phone",
              "to": "dropin-tv",
              "sdp": "v=0",
              "sdpType": "offer",
              "deviceClass": "limited",
              "ignored": true
            }
            """.trimIndent(),
        )

        assertEquals(SignalType.OFFER, decoded.type)
        assertEquals("dropin-phone", decoded.from)
        assertEquals("dropin-tv", decoded.to)
        assertEquals("v=0", decoded.sdp)
        assertEquals("limited", decoded.deviceClass)
    }

    @Test
    fun decodesIceCandidatePayload() {
        val decoded = json.decodeFromString(
            SignalEnvelope.serializer(),
            """
            {
              "type": "ice",
              "from": "dropin-phone",
              "candidate": {
                "sdpMid": "0",
                "sdpMLineIndex": 0,
                "sdpCandidate": "candidate:1 udp 2122260223 192.168.1.2 54400 typ host"
              }
            }
            """.trimIndent(),
        )

        assertEquals(SignalType.ICE, decoded.type)
        assertEquals("0", decoded.candidate?.sdpMid)
        assertEquals(0, decoded.candidate?.sdpMLineIndex)
        assertEquals("candidate:1 udp 2122260223 192.168.1.2 54400 typ host", decoded.candidate?.sdpCandidate)
        assertNull(decoded.to)
    }
}
