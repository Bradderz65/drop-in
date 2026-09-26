package com.bradhosk.dropin.data

import org.webrtc.IceCandidate

fun IceCandidate.toSignalEnvelope(from: String, target: String) = SignalEnvelope(
    type = SignalType.ICE,
    from = from,
    to = target,
    candidate = IceCandidatePayload(
        sdpMid = sdpMid,
        sdpMLineIndex = sdpMLineIndex,
        sdpCandidate = sdp,
    ),
)
