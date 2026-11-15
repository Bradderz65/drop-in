package com.bradhosk.dropin.ui

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bradhosk.dropin.data.DEFAULT_SIGNALING_PORT
import com.bradhosk.dropin.data.DropInRuntime
import com.bradhosk.dropin.data.PeerSignalingClient
import com.bradhosk.dropin.data.PeerSignalingStatus
import com.bradhosk.dropin.data.SignalEnvelope
import com.bradhosk.dropin.data.SignalType
import com.bradhosk.dropin.data.isValid
import com.bradhosk.dropin.data.toSignalEnvelope
import com.bradhosk.dropin.effectiveDeviceClass
import com.bradhosk.dropin.model.PeerDevice
import com.bradhosk.dropin.webrtc.DropInManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import java.net.NetworkInterface

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
    /** One entry per callable device, merged from [devices] and [recentPeers]. */
    val contacts: List<PeerDevice> = emptyList(),
    /** Someone is calling and waiting for Answer or Decline. */
    val incomingCall: PeerDevice? = null,
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
    private var connectedPeerServiceName: String? = null
    // Signals reach us over two paths (our outgoing socket and our local server), so the
    // same ANSWER normally arrives twice; only the first may be applied.
    private var remoteAnswerApplied = false
    private var contacts: List<Contact> = emptyList()
    // Other addresses for the device being dialled, tried in order if the current one fails.
    private var fallbackRoutes: List<PeerDevice> = emptyList()
    private var isOutgoingCall = false
    // An incoming call that is ringing and has not been answered or declined yet.
    private var pendingIncomingOffer: SignalEnvelope? = null
    private var pendingIncomingPeer: PeerDevice? = null
    private val pendingIceCandidates = mutableListOf<IceCandidate>()
    private var ringTimeoutJob: Job? = null
    private val ringer = IncomingRinger(application)

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

    init {
        refreshContacts()
        runtime.start()
        dropInManager.onRemoteVideoReady = {
            _uiState.value = _uiState.value.copy(hasRemoteVideo = true)
            dropInManager.rebindVideoSinks()
        }
        dropInManager.onIceCandidateDiscovered = { candidate ->
            _uiState.value.selectedPeer?.let { peer ->
                val signal = candidate.toSignalEnvelope(deviceName, peer.serviceName)
                signalingClient.send(signal)
                runtime.sendLocal(signal)
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
                refreshContacts()
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
        val media = dropInManager.startLocalMedia()
        val availability = when {
            media.hasAudio && media.hasVideo -> "Ready for drop in"
            media.hasAudio -> "Ready for drop in; no local camera"
            media.hasVideo -> "Ready for drop in; no local microphone"
            else -> "Ready to receive; camera and microphone unavailable"
        }
        // The activity calls this on every resume, including mid-call; the idle status must not
        // replace "Connecting to…" / "Connected to…" while a call is in progress.
        val callInProgress = _uiState.value.selectedPeer != null
        _uiState.value = _uiState.value.copy(
            isMicOn = media.isAudioEnabled,
            isCameraOn = media.isVideoEnabled,
            status = if (callInProgress) _uiState.value.status else availability,
        )
    }

    /** Calls a device from the list, trying each known address for it until one answers. */
    fun connectToPeer(peer: PeerDevice) {
        val routes = routesFor(peer)
        fallbackRoutes = routes.drop(1)
        dial(routes.first().copy(displayName = peer.displayName))
    }

    private fun routesFor(peer: PeerDevice): List<PeerDevice> {
        val contact = contacts.firstOrNull { contact ->
            contact.routes.any { it.host == peer.host && it.port == peer.port && it.serviceName == peer.serviceName }
        } ?: return listOf(peer)
        // Start from the address the user tapped, then the rest in preference order.
        val tapped = contact.routes.first { it.host == peer.host && it.port == peer.port && it.serviceName == peer.serviceName }
        return listOf(tapped) + contact.routes.filterNot { it === tapped }
    }

    /** Moves to the next address for the device being dialled. False when none are left. */
    private fun tryNextRoute(reason: String): Boolean {
        val current = _uiState.value.selectedPeer ?: return false
        if (_uiState.value.isInCall) return false
        val next = fallbackRoutes.firstOrNull() ?: return false
        fallbackRoutes = fallbackRoutes.drop(1)
        Log.w(logTag, "route ${current.host}:${current.port} failed ($reason); trying ${next.host}:${next.port}")
        dial(next.copy(displayName = current.displayName))
        return true
    }

    private fun dial(peer: PeerDevice) {
        Log.d(logTag, "connectToPeer peer=${peer.displayName} host=${peer.host}:${peer.port}")
        if (peer.host.isBlank() || peer.port !in 1..65535) {
            _uiState.value = _uiState.value.copy(
                selectedPeer = null,
                status = "Invalid address for ${peer.displayName}",
            )
            return
        }
        connectedPeerServiceName = null
        remoteAnswerApplied = false
        isOutgoingCall = true
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
        dropInManager.createOffer(
            onFailure = { error -> failPendingCall("Could not create call: $error") },
        ) { offer ->
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
        startConnectTimeout(peer)
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
        connectedPeerServiceName = null
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
        _uiState.value = _uiState.value.copy(isMicOn = dropInManager.setMicEnabled(enabled))
    }

    fun setCameraEnabled(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(isCameraOn = dropInManager.setCameraEnabled(enabled))
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

    /** Pull-to-refresh: refreshes tailnet sources while continuous NSD remains active. */
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
        refreshContacts()
    }

    private fun refreshContacts() {
        val state = _uiState.value
        contacts = buildContacts(discovered = state.devices, recents = state.recentPeers)
        _uiState.value = state.copy(contacts = contacts.map { it.primary })
    }

    // A device can end up in its own recents (e.g. after answering a call routed back through a
    // registry); offering to call yourself is never useful.
    private fun loadRecentPeers(): List<PeerDevice> {
        val ownAddresses = localAddresses()
        return recentsStore.load().filterNot { it.host.trim() in ownAddresses }
    }

    private fun localAddresses(): Set<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .flatMap { it.inetAddresses.asSequence() }
            .mapNotNull { it.hostAddress?.substringBefore('%') }
            .toSet()
    }.getOrDefault(emptySet())

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

    // ── Called when a call is established ─────────────────────
    private fun onCallConnected(peer: PeerDevice) {
        if (connectedPeerServiceName == peer.serviceName && _uiState.value.isInCall) return
        connectedPeerServiceName = peer.serviceName
        connectTimeoutJob?.cancel()
        _uiState.value = _uiState.value.copy(isInCall = true, status = "Connected to ${peer.displayName}")
        addRecentPeer(peer)
        startCallTimer()
        startQualityPolling()
    }

    // ── Signal handling ──────────────────────────────────────
    private fun handleSignal(signal: SignalEnvelope) {
        Log.d(logTag, "handleSignal type=${signal.type} from=${signal.from} to=${signal.to}")
        if (!signal.isValid()) {
            Log.w(logTag, "ignoring invalid signal type=${signal.type} from=${signal.from}")
            return
        }
        if (signal.to != null && signal.to != SAVED_TAILNET_PEER_ID && !isSameDevice(signal.to, deviceName)) {
            Log.d(logTag, "ignoring signal addressed to=${signal.to}")
            return
        }
        val ringing = pendingIncomingOffer
        if (signal.type == SignalType.HANGUP && ringing != null) {
            if (isSameDevice(ringing.from, signal.from)) {
                // The caller gave up before anyone answered.
                val name = pendingIncomingPeer?.displayName
                clearIncomingCall(status = name?.let { "Missed call from $it" } ?: "Ready for drop in")
            }
            return
        }
        val selectedPeer = _uiState.value.selectedPeer
        val activePeer = selectedPeer?.serviceName
        // A saved or remembered peer is dialled by address, so its real id is only learned
        // from its reply; match on the stable name or the host we dialled instead.
        val fromActivePeer = activePeer == null ||
            activePeer == SAVED_TAILNET_PEER_ID ||
            isSameDevice(signal.from, activePeer) ||
            (!signal.remoteHost.isNullOrBlank() && signal.remoteHost == selectedPeer.host)
        if (signal.type != SignalType.OFFER && !fromActivePeer) {
            Log.d(logTag, "ignoring signal from unrelated peer=${signal.from} activePeer=$activePeer")
            return
        }
        when (signal.type) {
            SignalType.OFFER -> handleOffer(signal)
            SignalType.ANSWER -> handleAnswer(signal)
            SignalType.ICE -> handleIce(signal)
            SignalType.HANGUP -> {
                // A hang-up before the call connected means the other side declined or did
                // not pick up; say so instead of "Call ended", and do not redial elsewhere.
                val unanswered = !_uiState.value.isInCall && isOutgoingCall && selectedPeer != null
                fallbackRoutes = emptyList()
                signalingClient.disconnect()
                dropInManager.endCall()
                connectedPeerServiceName = null
                connectTimeoutJob?.cancel()
                stopCallTimer()
                stopQualityPolling()
                _uiState.value = _uiState.value.copy(
                    isInCall = false,
                    isSpeakerOn = true,
                    hasRemoteVideo = false,
                    selectedPeer = null,
                    status = if (unanswered) "${selectedPeer?.displayName} didn't answer" else "Call ended",
                )
                _callMetrics.value = CallMetrics()
            }
        }
    }

    /**
     * An incoming call only rings. Nothing is sent back and the camera and microphone are not
     * offered to the caller until [answerIncomingCall]; the person at this device decides.
     */
    private fun handleOffer(signal: SignalEnvelope) {
        val ringing = pendingIncomingOffer
        if (ringing != null && isSameDevice(ringing.from, signal.from)) {
            // The same caller re-sent its offer (e.g. after trying another address).
            pendingIncomingOffer = signal
            return
        }
        if (ringing != null || _uiState.value.selectedPeer != null) {
            Log.d(logTag, "busy; declining offer from=${signal.from}")
            runtime.sendLocal(SignalEnvelope(type = SignalType.HANGUP, from = deviceName, to = signal.from))
            return
        }
        val peer = resolveIncomingPeer(signal)
        Log.d(logTag, "incoming call ringing from=${peer.displayName}")
        pendingIncomingOffer = signal
        pendingIncomingPeer = peer
        pendingIceCandidates.clear()
        _uiState.value = _uiState.value.copy(
            incomingCall = peer,
            status = "${peer.displayName} is calling",
        )
        ringer.start()
        ringTimeoutJob?.cancel()
        ringTimeoutJob = viewModelScope.launch {
            delay(RING_TIMEOUT_MS)
            if (pendingIncomingOffer != null) {
                Log.d(logTag, "incoming call from=${peer.displayName} not answered")
                runtime.sendLocal(SignalEnvelope(type = SignalType.HANGUP, from = deviceName, to = signal.from))
                clearIncomingCall(status = "Missed call from ${peer.displayName}")
            }
        }
    }

    fun answerIncomingCall() {
        val signal = pendingIncomingOffer ?: return
        val peer = pendingIncomingPeer ?: return
        val bufferedCandidates = pendingIceCandidates.toList()
        clearIncomingCall(status = null)
        acceptOffer(signal, peer, bufferedCandidates)
    }

    fun declineIncomingCall() {
        val signal = pendingIncomingOffer ?: return
        Log.d(logTag, "incoming call declined from=${signal.from}")
        runtime.sendLocal(SignalEnvelope(type = SignalType.HANGUP, from = deviceName, to = signal.from))
        clearIncomingCall(status = "Ready for drop in")
    }

    private fun clearIncomingCall(status: String?) {
        ringer.stop()
        ringTimeoutJob?.cancel()
        ringTimeoutJob = null
        pendingIncomingOffer = null
        pendingIncomingPeer = null
        pendingIceCandidates.clear()
        _uiState.value = _uiState.value.copy(
            incomingCall = null,
            status = status ?: _uiState.value.status,
        )
    }

    private fun resolveIncomingPeer(signal: SignalEnvelope): PeerDevice {
        val knownPeer = _uiState.value.devices.firstOrNull { it.serviceName == signal.from } ?: run {
            val tailnetHost = runtime.savedTailnetHost.value.trim()
            val isTailnetTarget = signal.to == "dropin-tailnet-saved"
            // The address the offer actually came from is the one that is known to work; our
            // own saved tailnet host may point at a different device entirely.
            val fallbackHost = when {
                !signal.remoteHost.isNullOrBlank() -> signal.remoteHost
                isTailnetTarget && tailnetHost.isNotBlank() -> tailnetHost
                else -> _uiState.value.selectedPeer?.host
            }.orEmpty()

            PeerDevice(
                serviceName = signal.from,
                displayName = signal.from.removePrefix("dropin-"),
                host = fallbackHost,
                port = _uiState.value.selectedPeer?.port ?: DEFAULT_SIGNALING_PORT,
            ).also {
                Log.w(
                    logTag,
                    "handleOffer using fallback peer serviceName=${signal.from}; " +
                        "knownPeers=${_uiState.value.devices.map { known -> known.serviceName }}; " +
                        "tailnetTarget=$isTailnetTarget tailnetHost='$tailnetHost' remoteHost='${signal.remoteHost}' chosenHost='$fallbackHost'",
                )
            }
        }
        // Show "Brad's phone", not "Brad's phone-52da".
        return knownPeer.copy(displayName = knownPeer.displayName.stripTransientSuffix())
    }

    private fun acceptOffer(signal: SignalEnvelope, peer: PeerDevice, bufferedCandidates: List<IceCandidate>) {
        Log.d(logTag, "handleOffer peer=${peer.displayName}")
        fallbackRoutes = emptyList()
        isOutgoingCall = false
        connectedPeerServiceName = null
        _uiState.value = _uiState.value.copy(
            selectedPeer = peer,
            hasRemoteVideo = false,
            status = "Connecting to ${peer.displayName}",
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
        startConnectTimeout(peer)
        val remoteOffer = SessionDescription(SessionDescription.Type.OFFER, signal.sdp.orEmpty())
        dropInManager.setRemoteDescription(
            description = remoteOffer,
            onFailure = { error -> failPendingCall("Could not accept call: $error") },
        ) {
            // Candidates that arrived while the call was still ringing.
            bufferedCandidates.forEach(dropInManager::addIceCandidate)
            dropInManager.createAnswer(
                onFailure = { error -> failPendingCall("Could not answer call: $error") },
            ) { answer ->
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
        if (remoteAnswerApplied) {
            Log.d(logTag, "ignoring duplicate answer from=${signal.from}")
            return
        }
        remoteAnswerApplied = true
        Log.d(logTag, "handleAnswer from=${signal.from}")
        signal.deviceClass?.let { dropInManager.prepareForCall(it) }
        val description = SessionDescription(SessionDescription.Type.ANSWER, signal.sdp.orEmpty())
        dropInManager.setRemoteDescription(
            description = description,
            onFailure = { error -> failPendingCall("Could not negotiate call: $error") },
        ) {
            val peer = _uiState.value.selectedPeer
            _uiState.value = _uiState.value.copy(
                status = peer?.let { "Connecting to ${it.displayName}" } ?: "Connecting call",
            )
        }
    }

    private fun handleIce(signal: SignalEnvelope) {
        Log.d(logTag, "handleIce from=${signal.from}")
        val payload = signal.candidate ?: return
        val candidate = IceCandidate(payload.sdpMid, payload.sdpMLineIndex, payload.sdpCandidate)
        if (pendingIncomingOffer != null) {
            // Still ringing: there is no connection to add this to until the call is answered.
            pendingIceCandidates.add(candidate)
            return
        }
        dropInManager.addIceCandidate(candidate)
    }

    override fun onCleared() {
        ringer.stop()
        ringTimeoutJob?.cancel()
        connectTimeoutJob?.cancel()
        stopCallTimer()
        stopQualityPolling()
        signalingClient.close()
        dropInManager.release()
    }

    private fun handleSignalingStatus(status: PeerSignalingStatus) {
        when (status) {
            is PeerSignalingStatus.Connecting -> {
                Log.d(logTag, "signaling status connecting ${status.host}:${status.port}")
            }
            PeerSignalingStatus.Connected -> {
                Log.d(logTag, "signaling status connected")
                val peer = _uiState.value.selectedPeer
                if (isOutgoingCall && peer != null && !_uiState.value.isInCall) {
                    // We reached the device, so it is now ringing there. Wait as long as it
                    // rings rather than giving up on the short "is this address alive" timer.
                    fallbackRoutes = emptyList()
                    _uiState.value = _uiState.value.copy(status = "Ringing ${peer.displayName}…")
                    startConnectTimeout(peer, CALLER_RING_TIMEOUT_MS)
                }
            }
            is PeerSignalingStatus.Closed -> {
                Log.d(logTag, "signaling status closed code=${status.code} reason=${status.reason}")
            }
            is PeerSignalingStatus.Failed -> {
                Log.w(logTag, "signaling status failed message=${status.message}")
                if (tryNextRoute("signaling failed")) return
                if (!_uiState.value.isInCall) {
                    connectTimeoutJob?.cancel()
                    dropInManager.endCall()
                    // The technical cause is in the log; on screen it only needs to say what
                    // happened and what to do.
                    val name = _uiState.value.selectedPeer?.displayName
                    _uiState.value = _uiState.value.copy(
                        selectedPeer = null,
                        hasRemoteVideo = false,
                        status = if (name != null) "Couldn't reach $name. Try again in a moment." else "Ready for drop in",
                    )
                }
            }
        }
    }

    private fun ensureLocalMediaReady() {
        dropInManager.startLocalMedia()
    }

    private fun failPendingCall(message: String) {
        Log.w(logTag, message)
        signalingClient.disconnect()
        dropInManager.endCall()
        connectedPeerServiceName = null
        connectTimeoutJob?.cancel()
        stopCallTimer()
        stopQualityPolling()
        _callMetrics.value = CallMetrics()
        _uiState.value = _uiState.value.copy(
            selectedPeer = null,
            isInCall = false,
            hasRemoteVideo = false,
            status = message,
        )
    }

    private fun startConnectTimeout(peer: PeerDevice, timeoutMs: Long? = null) {
        connectTimeoutJob?.cancel()
        connectTimeoutJob = viewModelScope.launch {
            // With another address still to try, give up on this one sooner.
            delay(timeoutMs ?: if (fallbackRoutes.isEmpty()) CONNECTION_TIMEOUT_MS else ROUTE_ATTEMPT_TIMEOUT_MS)
            if (!_uiState.value.isInCall && _uiState.value.selectedPeer?.serviceName == peer.serviceName) {
                if (tryNextRoute("no answer")) return@launch
                if (timeoutMs != null) {
                    // It rang and nobody picked up; tell the other device to stop ringing.
                    signalingClient.send(SignalEnvelope(type = SignalType.HANGUP, from = deviceName, to = peer.serviceName))
                    failPendingCall("${peer.displayName} didn't answer")
                } else {
                    failPendingCall("Could not reach ${peer.displayName}")
                }
            }
        }
    }

    private fun isSameDevice(first: String, second: String): Boolean =
        first == second || first.stableDeviceId() == second.stableDeviceId()

    private companion object {
        const val ROUTE_ATTEMPT_TIMEOUT_MS = 10_000L
        // How long an unanswered call rings. The caller waits slightly longer than the
        // ringing device so the "missed call" is decided in one place.
        const val RING_TIMEOUT_MS = 45_000L
        const val CALLER_RING_TIMEOUT_MS = 50_000L
        // Tailnet routes may need time to establish a DERP path and complete ICE negotiation.
        const val CONNECTION_TIMEOUT_MS = 25_000L
    }
}
