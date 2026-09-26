package com.bradhosk.dropin.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val DEFAULT_SIGNALING_PORT = 8989

@Serializable
data class SignalEnvelope(
    val type: SignalType,
    val from: String,
    val to: String? = null,
    val sdp: String? = null,
    val sdpType: String? = null,
    val candidate: IceCandidatePayload? = null,
    val remoteHost: String? = null,
    val deviceClass: String? = null,
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

fun SignalEnvelope.isValid(): Boolean {
    if (from.isBlank()) return false
    return when (type) {
        SignalType.OFFER,
        SignalType.ANSWER,
        -> !sdp.isNullOrBlank()
        SignalType.ICE -> candidate?.let {
            it.sdpMLineIndex >= 0 && it.sdpCandidate.isNotBlank()
        } == true
        SignalType.HANGUP -> true
    }
}
