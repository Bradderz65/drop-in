package com.bradhosk.dropin.ui

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bradhosk.dropin.data.IceCandidatePayload
import com.bradhosk.dropin.data.DropInRuntime
import com.bradhosk.dropin.data.PeerSignalingClient
import com.bradhosk.dropin.data.PeerSignalingStatus
import com.bradhosk.dropin.data.SignalEnvelope
import com.bradhosk.dropin.data.SignalType
import com.bradhosk.dropin.effectiveDeviceClass
import com.bradhosk.dropin.model.PeerDevice
import com.bradhosk.dropin.webrtc.DropInManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription

// ── Connection quality buckets ───────────────────────────────
enum class ConnectionQuality { EXCELLENT, GOOD, FAIR, POOR, UNKNOWN }

data class DropInUiState(
    val deviceName: String,
    val devices: List<PeerDevice> = emptyList(),
    val savedTailnetHost: String = "",
    val tailnetRegistryUrl: String = "",
    val localTailscaleAddress: String? = null,
    val selectedPeer: PeerDevice? = null,
    val isInCall: Boolean = false,
    val isMicOn: Boolean = true,
    val isCameraOn: Boolean = true,
    val isSpeakerOn: Boolean = true,
    val isUsingFrontCamera: Boolean = true,
    val isRemotePrimary: Boolean = true,
    val hasRemoteVideo: Boolean = false,
    val status: String = "Searching for local devices",
    val recentPeers: List<PeerDevice> = emptyList(),
    val isRefreshing: Boolean = false,
)

class DropInViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val logTag = "DropInApp"
    private val runtime = DropInRuntime.getInstance(application)
    private val deviceName = runtime.localPeerId
    private val signalingClient = PeerSignalingClient()
    val dropInManager = DropInManager(application)
    private var connectTimeoutJob: Job? = null
    private var callTimerJob: Job? = null
    private var qualityPollingJob: Job? = null

    private val recentPrefs = application.getSharedPreferences("dropin_recents", Context.MODE_PRIVATE)
    private val recentsStore = RecentPeersStore(recentPrefs)

    private val _uiState = MutableStateFlow(
        DropInUiState(
            deviceName = runtime.deviceName,
            recentPeers = loadRecentPeers(),
        ),
    )
    val uiState: StateFlow<DropInUiState> = _uiState.asStateFlow()

    private val _callMetrics = MutableStateFlow(CallMetrics())
    val callMetrics: StateFlow<CallMetrics> = _callMetrics.asStateFlow()

    val peers = runtime.peers.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )

    init {
        runtime.start()
        dropInManager.onRemoteVideoReady = {
            _uiState.value = _uiState.value.copy(hasRemoteVideo = true)
            dropInManager.rebindVideoSinks()
        }
        dropInManager.onIceCandidateDiscovered = { candidate ->
            _uiState.value.selectedPeer?.let { peer ->
                signalingClient.send(candidate.toSignalEnvelope(peer.serviceName))
                runtime.sendLocal(candidate.toSignalEnvelope(peer.serviceName))
            }
        }
        dropInManager.onConnectionLost = {
            viewModelScope.launch {
                if (_uiState.value.isInCall) {
                    Log.w(logTag, "connection lost; ending call")
                    hangUp(notifyPeer = false)
                    _uiState.value = _uiState.value.copy(status = "Connection lost")
                }
            }
        }

        viewModelScope.launch {
            runtime.peers.collect { devices ->
                _uiState.value = _uiState.value.copy(devices = devices)
            }
        }

        viewModelScope.launch {
            runtime.savedTailnetHost.collect { host ->
                _uiState.value = _uiState.value.copy(savedTailnetHost = host)
            }
        }

        viewModelScope.launch {
            runtime.tailnetRegistryUrl.collect { url ->
                _uiState.value = _uiState.value.copy(tailnetRegistryUrl = url)
            }
        }

        _uiState.value = _uiState.value.copy(localTailscaleAddress = runtime.localTailscaleAddress.value)

        viewModelScope.launch {
            runtime.localTailscaleAddress.collect { address ->
                _uiState.value = _uiState.value.copy(localTailscaleAddress = address)
            }
        }

        viewModelScope.launch {
            runtime.signals.collect(::handleSignal)
        }

        viewModelScope.launch {
            runtime.pendingOffer.collect { signal ->
                if (signal == null) return@collect
                runtime.consumePendingOffer()
                handleOffer(signal)
            }
        }

        viewModelScope.launch {
            signalingClient.events.collect(::handleSignal)
        }

        viewModelScope.launch {
            signalingClient.status.collect(::handleSignalingStatus)
        }
    }

    fun startLocalMedia() {
        val hasLocalCamera = dropInManager.startLocalMedia()
        _uiState.value = _uiState.value.copy(
            isCameraOn = hasLocalCamera,
            status = if (hasLocalCamera) "Ready for drop in" else "Ready for drop in; no local camera",
        )
    }

    fun connectToPeer(peer: PeerDevice) {
        Log.d(logTag, "connectToPeer peer=${peer.displayName} host=${peer.host}:${peer.port}")
        dropInManager.endCall()
        signalingClient.disconnect()
        dropInManager.prepareForCall(peer.effectiveDeviceClass())
        _uiState.value = _uiState.value.copy(
            selectedPeer = peer,
            hasRemoteVideo = false,
            status = "Connecting to ${peer.displayName}",
        )
        dropInManager.createPeerConnection {
            onCallConnected(peer)
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
                    deviceClass = dropInManager.localDeviceClass(),
                ),
            )
        }
        connectTimeoutJob?.cancel()
        connectTimeoutJob = viewModelScope.launch {
            delay(CONNECTION_TIMEOUT_MS)
            if (!_uiState.value.isInCall && _uiState.value.selectedPeer?.serviceName == peer.serviceName) {
                Log.w(logTag, "connect timeout peer=${peer.displayName}")
                signalingClient.disconnect()
                dropInManager.endCall()
                _uiState.value = _uiState.value.copy(
                    selectedPeer = null,
                    status = "Could not connect to ${peer.displayName}",
                )
            }
        }
    }

    fun hangUp(notifyPeer: Boolean = true) {
        Log.d(logTag, "hangUp tapped selectedPeer=${_uiState.value.selectedPeer?.displayName} isInCall=${_uiState.value.isInCall}")
        if (notifyPeer) {
            val hangup = SignalEnvelope(
                type = SignalType.HANGUP,
                from = deviceName,
                to = _uiState.value.selectedPeer?.serviceName,
            )
            signalingClient.send(hangup)
            runtime.sendLocal(hangup)
        }
        signalingClient.disconnect()
        dropInManager.endCall()
        connectTimeoutJob?.cancel()
        stopCallTimer()
        stopQualityPolling()
        _uiState.value = _uiState.value.copy(
            selectedPeer = null,
            isInCall = false,
            isSpeakerOn = true,
            hasRemoteVideo = false,
            status = "Ready for drop in",
        )
        _callMetrics.value = CallMetrics()
    }

    fun setMicEnabled(enabled: Boolean) {
        dropInManager.toggleMic(enabled)
        _uiState.value = _uiState.value.copy(isMicOn = enabled)
    }

    fun setCameraEnabled(enabled: Boolean) {
        dropInManager.toggleCamera(enabled)
        _uiState.value = _uiState.value.copy(isCameraOn = enabled)
    }

    fun setSpeakerEnabled(enabled: Boolean) {
        dropInManager.toggleSpeaker(enabled)
        _uiState.value = _uiState.value.copy(isSpeakerOn = enabled)
    }

    fun switchCamera() {
        dropInManager.switchCamera { isFrontCamera ->
            _uiState.value = _uiState.value.copy(isUsingFrontCamera = isFrontCamera)
        }
    }

    fun setSavedTailnetHost(host: String) {
        runtime.updateSavedTailnetHost(host)
        _uiState.value = _uiState.value.copy(
            savedTailnetHost = host.trim(),
            status = if (host.isBlank()) "Saved Tailscale peer cleared" else "Saved Tailscale peer updated",
        )
    }

    fun setTailnetRegistryUrl(url: String) {
        runtime.updateTailnetRegistryUrl(url)
        _uiState.value = _uiState.value.copy(
            tailnetRegistryUrl = url.trim().trimEnd('/'),
            status = if (url.isBlank()) "Tailnet registry cleared" else "Tailnet registry updated",
        )
        runtime.refreshPeers()
    }

    fun swapVideoViews() {
        val remotePrimary = !_uiState.value.isRemotePrimary
        dropInManager.setRemotePrimary(remotePrimary)
        _uiState.value = _uiState.value.copy(isRemotePrimary = remotePrimary)
    }

    /** Pull-to-refresh: restarts NSD/tailnet discovery. */
    fun refreshPeers() {
        _uiState.value = _uiState.value.copy(
            isRefreshing = true,
            localTailscaleAddress = runtime.localTailscaleAddress.value,
        )
        runtime.refreshPeers()
        viewModelScope.launch {
            delay(1_500) // brief visual feedback
            _uiState.value = _uiState.value.copy(isRefreshing = false)
        }
    }

    // ── Call timer ────────────────────────────────────────────
    private fun startCallTimer() {
        callTimerJob?.cancel()
        _callMetrics.value = CallMetrics()
        callTimerJob = viewModelScope.launch {
            var seconds = 0L
            while (isActive) {
                delay(1_000)
                seconds++
                _callMetrics.value = _callMetrics.value.copy(durationSeconds = seconds)
            }
        }
    }

    private fun stopCallTimer() {
        callTimerJob?.cancel()
        callTimerJob = null
    }

    // ── Connection quality polling ───────────────────────────
    private fun startQualityPolling() {
        qualityPollingJob?.cancel()
        qualityPollingJob = viewModelScope.launch {
            while (isActive) {
                delay(3_000)
                dropInManager.getRoundTripTimeMs { rttMs ->
                    val quality = when {
                        rttMs == null -> ConnectionQuality.UNKNOWN
                        rttMs < 50    -> ConnectionQuality.EXCELLENT
                        rttMs < 150   -> ConnectionQuality.GOOD
                        rttMs < 300   -> ConnectionQuality.FAIR
                        else          -> ConnectionQuality.POOR
                    }
                    _callMetrics.value = _callMetrics.value.copy(connectionQuality = quality)
                }
            }
        }
    }

    private fun stopQualityPolling() {
        qualityPollingJob?.cancel()
        qualityPollingJob = null
    }

    // ── Recent peers persistence ─────────────────────────────
    private fun addRecentPeer(peer: PeerDevice) {
        val peerKey = peer.recentIdentityKey()
        val current = _uiState.value.recentPeers
            .filterNot { it.recentIdentityKey() == peerKey }
            .toMutableList()
        current.add(0, peer.normalizedRecentPeer())
        val trimmed = current.dedupedRecentPeers().take(5)
        _uiState.value = _uiState.value.copy(recentPeers = trimmed)
        recentsStore.save(trimmed)
    }

    private fun loadRecentPeers(): List<PeerDevice> = recentsStore.load()

    private fun List<PeerDevice>.dedupedRecentPeers(): List<PeerDevice> =
        distinctBy { it.recentIdentityKey() }

    private fun PeerDevice.normalizedRecentPeer(): PeerDevice =
        copy(
            displayName = stableDisplayName(),
            serviceName = stableServiceName(),
        )

    private fun PeerDevice.recentIdentityKey(): String {
        val normalizedHost = host.trim().lowercase()
        if (normalizedHost.isNotBlank()) return "host:$normalizedHost:$port"

        val normalizedName = stableDisplayName().lowercase()
        if (normalizedName.isNotBlank()) return "name:$normalizedName"

        return "service:${stableServiceName().lowercase()}"
    }

    private fun PeerDevice.stableServiceName(): String {
        val base = serviceName.trim().removePrefix("dropin-")
        return "dropin-${base.stripTransientSuffix()}"
    }

    private fun PeerDevice.stableDisplayName(): String {
        val base = displayName.trim().ifBlank { serviceName.trim().removePrefix("dropin-") }
        return base.stripTransientSuffix()
    }

    private fun String.stripTransientSuffix(): String =
        replace(Regex("-[0-9a-fA-F]{4}$"), "")

    // ── Called when a call is established ─────────────────────
    private fun onCallConnected(peer: PeerDevice) {
        _uiState.value = _uiState.value.copy(isInCall = true, status = "Connected to ${peer.displayName}")
        addRecentPeer(peer)
        startCallTimer()
        startQualityPolling()
    }

    // ── Signal handling (unchanged logic) ────────────────────
    private fun handleSignal(signal: SignalEnvelope) {
        Log.d(logTag, "handleSignal type=${signal.type} from=${signal.from} to=${signal.to}")
        when (signal.type) {
            SignalType.OFFER -> handleOffer(signal)
            SignalType.ANSWER -> handleAnswer(signal)
            SignalType.ICE -> handleIce(signal)
            SignalType.HANGUP -> {
                signalingClient.disconnect()
                dropInManager.endCall()
                connectTimeoutJob?.cancel()
                stopCallTimer()
                stopQualityPolling()
                _uiState.value = _uiState.value.copy(
                    isInCall = false,
                    isSpeakerOn = true,
                    hasRemoteVideo = false,
                    selectedPeer = null,
                    status = "Call ended",
                )
                _callMetrics.value = CallMetrics()
            }
        }
    }

    private fun handleOffer(signal: SignalEnvelope) {
        val peer = peers.value.firstOrNull { it.serviceName == signal.from } ?: run {
            val tailnetHost = runtime.savedTailnetHost.value.trim()
            val isTailnetTarget = signal.to == "dropin-tailnet-saved"
            val fallbackHost = when {
                isTailnetTarget && tailnetHost.isNotBlank() -> tailnetHost
                !signal.remoteHost.isNullOrBlank() -> signal.remoteHost
                else -> _uiState.value.selectedPeer?.host
            }.orEmpty()

            PeerDevice(
                serviceName = signal.from,
                displayName = signal.from.removePrefix("dropin-"),
                host = fallbackHost,
                port = _uiState.value.selectedPeer?.port ?: 8989,
            ).also {
                Log.w(
                    logTag,
                    "handleOffer using fallback peer serviceName=${signal.from}; " +
                        "knownPeers=${peers.value.map { known -> known.serviceName }}; " +
                        "tailnetTarget=$isTailnetTarget tailnetHost='$tailnetHost' remoteHost='${signal.remoteHost}' chosenHost='$fallbackHost'",
                )
            }
        }
        Log.d(logTag, "handleOffer peer=${peer.displayName}")
        _uiState.value = _uiState.value.copy(
            selectedPeer = peer,
            hasRemoteVideo = false,
            status = "Incoming drop in from ${peer.displayName}",
        )

        dropInManager.endCall()
        signalingClient.disconnect()
        val peerClass = signal.deviceClass ?: peer.effectiveDeviceClass()
        dropInManager.prepareForCall(peerClass)
        ensureLocalMediaReady()
        if (peer.host.isNotBlank()) {
            signalingClient.connect(peer.host, peer.port)
        } else {
            Log.w(logTag, "handleOffer no peer host available for direct signaling response")
        }
        dropInManager.createPeerConnection {
            connectTimeoutJob?.cancel()
            onCallConnected(peer)
        }
        val remoteOffer = SessionDescription(SessionDescription.Type.OFFER, signal.sdp.orEmpty())
        dropInManager.setRemoteDescription(remoteOffer) {
            dropInManager.createAnswer { answer ->
                Log.d(logTag, "sending answer to=${peer.serviceName}")
                val response = SignalEnvelope(
                    type = SignalType.ANSWER,
                    from = deviceName,
                    to = peer.serviceName,
                    sdp = answer.description,
                    sdpType = answer.type.canonicalForm(),
                    deviceClass = dropInManager.localDeviceClass(),
                )
                signalingClient.send(response)
                runtime.sendLocal(response)
            }
        }
    }

    private fun handleAnswer(signal: SignalEnvelope) {
        Log.d(logTag, "handleAnswer from=${signal.from}")
        connectTimeoutJob?.cancel()
        signal.deviceClass?.let { dropInManager.prepareForCall(it) }
        val description = SessionDescription(SessionDescription.Type.ANSWER, signal.sdp.orEmpty())
        dropInManager.setRemoteDescription(description)
        val peer = _uiState.value.selectedPeer
        if (peer != null) {
            onCallConnected(peer)
        } else {
            _uiState.value = _uiState.value.copy(
                isInCall = true,
                hasRemoteVideo = false,
                status = "Call established",
            )
        }
    }

    private fun handleIce(signal: SignalEnvelope) {
        Log.d(logTag, "handleIce from=${signal.from}")
        val candidate = signal.candidate ?: return
        dropInManager.addIceCandidate(
            IceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdpCandidate),
        )
    }

    override fun onCleared() {
        connectTimeoutJob?.cancel()
        stopCallTimer()
        stopQualityPolling()
        signalingClient.disconnect()
        dropInManager.release()
        super.onCleared()
    }

    private fun handleSignalingStatus(status: PeerSignalingStatus) {
        when (status) {
            is PeerSignalingStatus.Connecting -> {
                Log.d(logTag, "signaling status connecting ${status.host}:${status.port}")
            }
            PeerSignalingStatus.Connected -> {
                Log.d(logTag, "signaling status connected")
            }
            is PeerSignalingStatus.Closed -> {
                Log.d(logTag, "signaling status closed code=${status.code} reason=${status.reason}")
            }
            is PeerSignalingStatus.Failed -> {
                Log.w(logTag, "signaling status failed message=${status.message}")
                if (!_uiState.value.isInCall) {
                    connectTimeoutJob?.cancel()
                    dropInManager.endCall()
                    _uiState.value = _uiState.value.copy(
                        selectedPeer = null,
                        hasRemoteVideo = false,
                        status = "Signaling failed: ${status.message}",
                    )
                }
            }
        }
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

    private fun ensureLocalMediaReady() {
        dropInManager.startLocalMedia()
    }

    private companion object {
        // Tailnet routes may need time to establish a DERP path and complete ICE negotiation.
        const val CONNECTION_TIMEOUT_MS = 25_000L
    }
}

class RecentPeersStore(
    private val preferences: android.content.SharedPreferences,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun save(peers: List<PeerDevice>) {
        val encoded = json.encodeToString(
            ListSerializer(RecentPeer.serializer()),
            peers.take(MAX_RECENTS).map(RecentPeer::fromPeerDevice),
        )
        preferences.edit().putString(KEY_RECENTS, encoded).apply()
    }

    fun load(): List<PeerDevice> {
        val raw = preferences.getString(KEY_RECENTS, "").orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(RecentPeer.serializer()), raw)
                .map { it.toPeerDevice() }
        }.getOrElse {
            loadLegacyDelimited(raw)
        }.deduped().take(MAX_RECENTS)
    }

    private fun loadLegacyDelimited(raw: String): List<PeerDevice> =
        raw.split("|").mapNotNull { entry ->
            val parts = entry.split(";;")
            if (parts.size == 4) {
                PeerDevice(
                    serviceName = parts[0],
                    displayName = parts[1],
                    host = parts[2],
                    port = parts[3].toIntOrNull() ?: DEFAULT_SIGNALING_PORT,
                )
            } else {
                null
            }
        }

    private fun List<PeerDevice>.deduped(): List<PeerDevice> =
        distinctBy { peer ->
            val normalizedHost = peer.host.trim().lowercase()
            if (normalizedHost.isNotBlank()) {
                "host:$normalizedHost:${peer.port}"
            } else {
                "service:${peer.serviceName.trim().lowercase()}"
            }
        }

    @Serializable
    private data class RecentPeer(
        val serviceName: String,
        val displayName: String,
        val host: String,
        val port: Int,
        val deviceClass: String = "standard",
    ) {
        fun toPeerDevice(): PeerDevice = PeerDevice(
            serviceName = serviceName,
            displayName = displayName,
            host = host,
            port = port,
            deviceClass = deviceClass,
        )

        companion object {
            fun fromPeerDevice(peer: PeerDevice): RecentPeer = RecentPeer(
                serviceName = peer.serviceName,
                displayName = peer.displayName,
                host = peer.host,
                port = peer.port,
                deviceClass = peer.deviceClass,
            )
        }
    }

    private companion object {
        const val KEY_RECENTS = "recents"
        const val MAX_RECENTS = 5
        const val DEFAULT_SIGNALING_PORT = 8989
    }
}
