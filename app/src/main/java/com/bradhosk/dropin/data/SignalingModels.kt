package com.bradhosk.dropin.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SignalEnvelope(
    val type: SignalType,
    val from: String,
    val to: String? = null,
    val sdp: String? = null,
    val sdpType: String? = null,
    val candidate: IceCandidatePayload? = null,
)

@Serializable
enum class SignalType {
    @SerialName("offer")
    OFFER,

    @SerialName("answer")
    ANSWER,

    @SerialName("ice")
    ICE,

    @SerialName("hangup")
    HANGUP,
}

@Serializable
data class IceCandidatePayload(
    val sdpMid: String?,
    val sdpMLineIndex: Int,
    val sdpCandidate: String,
)
