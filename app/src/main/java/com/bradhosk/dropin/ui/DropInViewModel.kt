package com.bradhosk.dropin.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bradhosk.dropin.data.IceCandidatePayload
import com.bradhosk.dropin.data.LocalSignalingServer
import com.bradhosk.dropin.data.NsdPeerDiscovery
import com.bradhosk.dropin.data.PeerSignalingClient
import com.bradhosk.dropin.data.SignalEnvelope
import com.bradhosk.dropin.data.SignalType
import com.bradhosk.dropin.model.PeerDevice
import com.bradhosk.dropin.webrtc.DropInManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import java.util.UUID

data class DropInUiState(
    val deviceName: String,
    val devices: List<PeerDevice> = emptyList(),
    val selectedPeer: PeerDevice? = null,
    val isInCall: Boolean = false,
    val isMicOn: Boolean = true,
    val isCameraOn: Boolean = true,
    val status: String = "Searching for local devices",
)

class DropInViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val deviceName = "dropin-${android.os.Build.MODEL}-${UUID.randomUUID().toString().take(4)}"
    private val signalingServer = LocalSignalingServer()
    private val signalingClient = PeerSignalingClient()
    private val peerDiscovery = NsdPeerDiscovery(application, deviceName)
    val dropInManager = DropInManager(application)

    private val _uiState = MutableStateFlow(
        DropInUiState(deviceName = deviceName.removePrefix("dropin-")),
    )
    val uiState: StateFlow<DropInUiState> = _uiState.asStateFlow()

    val peers = peerDiscovery.peers.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )

    init {
        dropInManager.onIceCandidate = { candidate ->
            _uiState.value.selectedPeer?.let { peer ->
                signalingClient.send(candidate.toSignalEnvelope(peer.serviceName))
                signalingServer.send(candidate.toSignalEnvelope(peer.serviceName))
            }
        }

        signalingServer.start()
        peerDiscovery.start(signalingServer.port)

        viewModelScope.launch {
            peerDiscovery.peers.collect { devices ->
                _uiState.value = _uiState.value.copy(devices = devices)
            }
        }

        viewModelScope.launch {
            signalingServer.events.collect(::handleSignal)
        }

        viewModelScope.launch {
            signalingClient.events.collect(::handleSignal)
        }
    }

    fun startLocalMedia() {
        dropInManager.startLocalMedia()
        _uiState.value = _uiState.value.copy(status = "Ready for drop in")
    }

    fun connectToPeer(peer: PeerDevice) {
        dropInManager.endCall()
        signalingClient.disconnect()
        _uiState.value = _uiState.value.copy(
            selectedPeer = peer,
            status = "Connecting to ${peer.displayName}",
        )
        dropInManager.createPeerConnection {
            _uiState.value = _uiState.value.copy(isInCall = true, status = "Connected to ${peer.displayName}")
        }
        signalingClient.connect(peer.host, peer.port)
        dropInManager.createOffer { offer ->
            signalingClient.send(
                SignalEnvelope(
                    type = SignalType.OFFER,
                    from = deviceName,
                    to = peer.serviceName,
                    sdp = offer.description,
                    sdpType = offer.type.canonicalForm(),
                ),
            )
        }
    }

    fun hangUp() {
        signalingClient.send(SignalEnvelope(type = SignalType.HANGUP, from = deviceName))
        signalingServer.send(SignalEnvelope(type = SignalType.HANGUP, from = deviceName))
        signalingClient.disconnect()
        dropInManager.endCall()
        _uiState.value = _uiState.value.copy(
            selectedPeer = null,
            isInCall = false,
            status = "Ready for drop in",
        )
    }

    fun setMicEnabled(enabled: Boolean) {
        dropInManager.toggleMic(enabled)
        _uiState.value = _uiState.value.copy(isMicOn = enabled)
    }

    fun setCameraEnabled(enabled: Boolean) {
        dropInManager.toggleCamera(enabled)
        _uiState.value = _uiState.value.copy(isCameraOn = enabled)
    }

    private fun handleSignal(signal: SignalEnvelope) {
        when (signal.type) {
            SignalType.OFFER -> handleOffer(signal)
            SignalType.ANSWER -> handleAnswer(signal)
            SignalType.ICE -> handleIce(signal)
            SignalType.HANGUP -> {
                signalingClient.disconnect()
                dropInManager.endCall()
                _uiState.value = _uiState.value.copy(isInCall = false, selectedPeer = null, status = "Call ended")
            }
        }
    }

    private fun handleOffer(signal: SignalEnvelope) {
        val peer = peers.value.firstOrNull { it.serviceName == signal.from } ?: return
        _uiState.value = _uiState.value.copy(selectedPeer = peer, status = "Incoming drop in from ${peer.displayName}")

        dropInManager.endCall()
        dropInManager.createPeerConnection {
            _uiState.value = _uiState.value.copy(isInCall = true, status = "Connected to ${peer.displayName}")
        }
        val remoteOffer = SessionDescription(SessionDescription.Type.OFFER, signal.sdp.orEmpty())
        dropInManager.setRemoteDescription(remoteOffer) {
            dropInManager.createAnswer { answer ->
                signalingServer.send(
                    SignalEnvelope(
                        type = SignalType.ANSWER,
                        from = deviceName,
                        to = peer.serviceName,
                        sdp = answer.description,
                        sdpType = answer.type.canonicalForm(),
                    ),
                )
            }
        }
    }

    private fun handleAnswer(signal: SignalEnvelope) {
        val description = SessionDescription(SessionDescription.Type.ANSWER, signal.sdp.orEmpty())
        dropInManager.setRemoteDescription(description)
        _uiState.value = _uiState.value.copy(isInCall = true, status = "Call established")
    }

    private fun handleIce(signal: SignalEnvelope) {
        val candidate = signal.candidate ?: return
        dropInManager.addIceCandidate(
            IceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdpCandidate),
        )
    }

    override fun onCleared() {
        peerDiscovery.stop()
        signalingClient.disconnect()
        signalingServer.stop()
        dropInManager.release()
        super.onCleared()
    }

    private fun IceCandidate.toSignalEnvelope(target: String) = SignalEnvelope(
        type = SignalType.ICE,
        from = deviceName,
        to = target,
        candidate = IceCandidatePayload(
            sdpMid = sdpMid,
            sdpMLineIndex = sdpMLineIndex,
            sdpCandidate = sdp,
        ),
    )

    private fun SessionDescription.Type.canonicalForm(): String = when (this) {
        SessionDescription.Type.OFFER -> "offer"
        SessionDescription.Type.ANSWER -> "answer"
        SessionDescription.Type.PRANSWER -> "pranswer"
        SessionDescription.Type.ROLLBACK -> "rollback"
    }
}
