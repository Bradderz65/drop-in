package com.bradhosk.dropin.webrtc

import android.content.Context.AUDIO_SERVICE
import android.os.Build
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.bradhosk.dropin.DeviceCapability
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStreamTrack
import org.webrtc.RtpParameters
import org.webrtc.RtpSender
import org.webrtc.CameraVideoCapturer.CameraSwitchHandler
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RendererCommon
import org.webrtc.RtpTransceiver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

class DropInManager(
    context: Context,
) {
    private val logTag = "DropInApp"
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AUDIO_SERVICE) as AudioManager
    private val eglBase = EglBase.create()
    private val peerFactory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var activeCaptureProfile: VideoQualityProfile? = null
    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null
    private var remoteRenderer: SurfaceViewRenderer? = null
    private var localRenderer: SurfaceViewRenderer? = null
    private var initializedRemoteRenderer: SurfaceViewRenderer? = null
    private var initializedLocalRenderer: SurfaceViewRenderer? = null
    private var remoteVideoTrack: VideoTrack? = null
    private var isUsingFrontCamera = true
    private var previousAudioMode: Int = audioManager.mode
    private var previousSpeakerphoneState: Boolean = audioManager.isSpeakerphoneOn
    private var audioRouteInitialized = false
    private var speakerEnabled = true
    private val mainHandler = Handler(Looper.getMainLooper())
    private var connectionLostRunnable: Runnable? = null
    private var connectionLostNotified = false
    private var outboundProfile: VideoQualityProfile = defaultOutboundProfile()

    var onIceCandidateDiscovered: (IceCandidate) -> Unit = {}
    var onRemoteVideoReady: () -> Unit = {}
    var onConnectionLost: () -> Unit = {}

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions(),
        )
        peerFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
    }

    fun rebindVideoSinks() {
        rebindLocalVideoSink()
        rebindRemoteVideoSink()
    }

    fun initializeRenderers(local: SurfaceViewRenderer, remote: SurfaceViewRenderer) {
        val sameSurfaces = localRenderer === local && remoteRenderer === remote &&
            initializedLocalRenderer === local && initializedRemoteRenderer === remote
        if (sameSurfaces) {
            rebindVideoSinks()
            return
        }

        if (localRenderer !== local) {
            localVideoTrack?.removeSink(localRenderer)
        }
        if (remoteRenderer !== remote) {
            remoteVideoTrack?.removeSink(remoteRenderer)
        }
        localRenderer = local
        remoteRenderer = remote

        initializeRendererIfNeeded(local, isLocal = true, scaling = RendererCommon.ScalingType.SCALE_ASPECT_FILL)
        initializeRendererIfNeeded(remote, isLocal = false, scaling = RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        rebindLocalVideoSink()
        rebindRemoteVideoSink()
        setRemotePrimary(true)
    }

    fun localDeviceClass(): String = DeviceCapability.localDeviceClass(appContext)

    fun prepareForCall(peerDeviceClass: String) {
        val peerClass = peerDeviceClass.ifBlank { DeviceCapability.CLASS_STANDARD }
        val profile = VideoQualityProfile.forCall(localDeviceClass(), peerClass)
        if (profile == outboundProfile) {
            applyOutboundEncoding()
            return
        }
        outboundProfile = profile
        Log.d(
            logTag,
            "prepareForCall peerClass=$peerClass capture=${profile.captureWidth}x${profile.captureHeight}@${profile.captureFps} " +
                "maxBitrate=${profile.maxBitrateBps}",
        )
        reconfigureCaptureIfNeeded()
        applyOutboundEncoding()
    }

    fun startLocalMedia(): Boolean {
        Log.d(logTag, "startLocalMedia")
        if (localAudioTrack != null || localVideoTrack != null) return localVideoTrack != null

        audioSource = peerFactory.createAudioSource(MediaConstraints())
        localAudioTrack = peerFactory.createAudioTrack("localAudio", audioSource)

        val capturer = createCameraCapturer()
        if (capturer == null) {
            Log.w(logTag, "startLocalMedia no camera capturer; continuing with audio-only local media")
            return false
        }

        val helper = SurfaceTextureHelper.create("DropInCaptureThread", eglBase.eglBaseContext)
        surfaceTextureHelper = helper
        videoCapturer = capturer
        videoSource = peerFactory.createVideoSource(false)
        localVideoTrack = peerFactory.createVideoTrack("localVideo", videoSource)

        val profile = outboundProfile
        runCatching {
            capturer.initialize(helper, appContext, videoSource?.capturerObserver)
            capturer.startCapture(profile.captureWidth, profile.captureHeight, profile.captureFps)
            activeCaptureProfile = profile
        }.onFailure { error ->
            Log.w(logTag, "startLocalMedia camera capture failed; continuing audio-only", error)
            releaseVideoCaptureResources()
        }
        rebindLocalVideoSink()
        return localVideoTrack != null
    }

    fun createPeerConnection(onConnected: () -> Unit) {
        Log.d(logTag, "createPeerConnection existing=${peerConnection != null}")
        if (peerConnection != null) return
        connectionLostNotified = false
        configureAudioRoute(useSpeaker = true)
        val rtcConfig = PeerConnection.RTCConfiguration(
            listOf(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            ),
        ).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        peerConnection = peerFactory.createPeerConnection(
            rtcConfig,
            object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate) {
                    Log.d(logTag, "onIceCandidate")
                    onIceCandidateDiscovered(candidate)
                }

                override fun onTrack(transceiver: org.webrtc.RtpTransceiver?) {
                    val track = transceiver?.receiver?.track() as? VideoTrack ?: return
                    Log.d(logTag, "onTrack remote video")
                    attachRemoteVideoTrack(track)
                }

                override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                    Log.d(logTag, "onConnectionChange state=$newState")
                    when (newState) {
                        PeerConnection.PeerConnectionState.CONNECTED -> {
                            cancelConnectionLostCheck()
                            applyOutboundEncoding()
                            onConnected()
                        }
                        PeerConnection.PeerConnectionState.FAILED -> notifyConnectionLost()
                        else -> Unit
                    }
                }

                override fun onSignalingChange(newState: PeerConnection.SignalingState?) = Unit
                override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
                    Log.d(logTag, "onIceConnectionChange state=$newState")
                    when (newState) {
                        PeerConnection.IceConnectionState.CONNECTED,
                        PeerConnection.IceConnectionState.COMPLETED,
                        -> cancelConnectionLostCheck()
                        PeerConnection.IceConnectionState.DISCONNECTED -> scheduleConnectionLostCheck()
                        PeerConnection.IceConnectionState.FAILED -> notifyConnectionLost()
                        else -> Unit
                    }
                }
                override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
                override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) = Unit
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate?>?) = Unit
                override fun onAddStream(stream: org.webrtc.MediaStream?) {
                    val track = stream?.videoTracks?.firstOrNull() ?: return
                    Log.d(logTag, "onAddStream remote video")
                    attachRemoteVideoTrack(track)
                }
                override fun onRemoveStream(stream: org.webrtc.MediaStream?) = Unit
                override fun onDataChannel(dataChannel: org.webrtc.DataChannel?) = Unit
                override fun onRenegotiationNeeded() = Unit
                override fun onAddTrack(receiver: org.webrtc.RtpReceiver?, mediaStreams: Array<out org.webrtc.MediaStream>?) = Unit
            },
        )

        localVideoTrack?.setEnabled(true)
        localAudioTrack?.setEnabled(true)
        localVideoTrack?.let { peerConnection?.addTrack(it, listOf("stream")) }
        localAudioTrack?.let { peerConnection?.addTrack(it, listOf("stream")) }
        applyOutboundEncoding()
        if (localVideoTrack == null) {
            peerConnection?.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                RtpTransceiver.RtpTransceiverInit(
                    RtpTransceiver.RtpTransceiverDirection.RECV_ONLY,
                    listOf("stream"),
                ),
            )
        }
    }

    fun createOffer(onCreated: (SessionDescription) -> Unit) {
        peerConnection?.createOffer(object : SdpAdapter() {
            override fun onCreateSuccess(desc: SessionDescription?) {
                if (desc == null) return
                peerConnection?.setLocalDescription(object : SdpAdapter() {
                    override fun onSetSuccess() {
                        applyOutboundEncoding()
                    }
                }, desc)
                onCreated(desc)
            }
        }, MediaConstraints())
    }

    fun createAnswer(onCreated: (SessionDescription) -> Unit) {
        peerConnection?.createAnswer(object : SdpAdapter() {
            override fun onCreateSuccess(desc: SessionDescription?) {
                if (desc == null) return
                peerConnection?.setLocalDescription(object : SdpAdapter() {
                    override fun onSetSuccess() {
                        applyOutboundEncoding()
                    }
                }, desc)
                onCreated(desc)
            }
        }, MediaConstraints())
    }

    fun setRemoteDescription(description: SessionDescription, onSet: () -> Unit = {}) {
        peerConnection?.setRemoteDescription(object : SdpAdapter() {
            override fun onSetSuccess() {
                applyOutboundEncoding()
                onSet()
            }
        }, description)
    }

    fun addIceCandidate(candidate: IceCandidate) {
        peerConnection?.addIceCandidate(candidate)
    }

    fun toggleMic(enabled: Boolean) {
        localAudioTrack?.setEnabled(enabled)
    }

    fun toggleCamera(enabled: Boolean) {
        localVideoTrack?.setEnabled(enabled)
    }

    fun toggleSpeaker(enabled: Boolean) {
        Log.d(logTag, "toggleSpeaker enabled=$enabled")
        speakerEnabled = enabled
        configureAudioRoute(useSpeaker = enabled)
    }

    fun setRemotePrimary(remotePrimary: Boolean) {
        remoteRenderer?.setZOrderMediaOverlay(!remotePrimary)
        localRenderer?.setZOrderMediaOverlay(remotePrimary)
    }

    fun switchCamera(onSwitched: (Boolean) -> Unit) {
        val capturer = videoCapturer ?: return
        capturer.switchCamera(object : CameraSwitchHandler {
            override fun onCameraSwitchDone(isFrontCamera: Boolean) {
                Log.d(logTag, "switchCamera done isFrontCamera=$isFrontCamera")
                isUsingFrontCamera = isFrontCamera
                localRenderer?.setMirror(isFrontCamera)
                onSwitched(isFrontCamera)
            }

            override fun onCameraSwitchError(errorDescription: String?) {
                Log.w(logTag, "switchCamera error=$errorDescription")
            }
        })
    }

    fun release() {
        endCall()
        stopLocalMedia()
        detachRenderers()
        eglBase.release()
    }

    fun detachRenderers() {
        localVideoTrack?.removeSink(localRenderer)
        remoteVideoTrack?.removeSink(remoteRenderer)
        initializedLocalRenderer = null
        initializedRemoteRenderer = null
        localRenderer = null
        remoteRenderer = null
    }

    fun endCall() {
        Log.d(logTag, "endCall")
        cancelConnectionLostCheck()
        connectionLostNotified = false
        remoteVideoTrack?.removeSink(remoteRenderer)
        remoteVideoTrack = null
        peerConnection?.close()
        peerConnection?.dispose()
        peerConnection = null
        outboundProfile = defaultOutboundProfile()
        restoreAudioRoute()
    }

    /** Returns the current round-trip time in milliseconds from the active ICE candidate pair, or null. */
    fun getRoundTripTimeMs(callback: (Double?) -> Unit) {
        val pc = peerConnection
        if (pc == null) {
            callback(null)
            return
        }
        pc.getStats { report ->
            val rtt = report?.statsMap?.values
                ?.firstOrNull { it.type == "candidate-pair" && it.members.containsKey("currentRoundTripTime") }
                ?.members?.get("currentRoundTripTime")
                ?.toString()?.toDoubleOrNull()
                ?.times(1000.0) // seconds → ms
            callback(rtt)
        }
    }

    private fun attachRemoteVideoTrack(track: VideoTrack) {
        Log.d(logTag, "attachRemoteVideoTrack rendererReady=${remoteRenderer != null}")
        remoteVideoTrack?.removeSink(remoteRenderer)
        remoteVideoTrack = track
        rebindRemoteVideoSink()
        onRemoteVideoReady()
    }

    private fun rebindLocalVideoSink() {
        val renderer = localRenderer ?: return
        val track = localVideoTrack ?: return
        track.removeSink(renderer)
        track.addSink(renderer)
    }

    private fun rebindRemoteVideoSink() {
        val renderer = remoteRenderer ?: return
        val track = remoteVideoTrack ?: return
        track.removeSink(renderer)
        track.addSink(renderer)
    }

    private fun initializeRendererIfNeeded(
        renderer: SurfaceViewRenderer,
        isLocal: Boolean,
        scaling: RendererCommon.ScalingType,
    ) {
        val alreadyInitialized = if (isLocal) {
            initializedLocalRenderer === renderer
        } else {
            initializedRemoteRenderer === renderer
        }
        if (!alreadyInitialized) {
            renderer.init(eglBase.eglBaseContext, null)
            if (isLocal) {
                initializedLocalRenderer = renderer
            } else {
                initializedRemoteRenderer = renderer
            }
        }
        renderer.setScalingType(scaling)
        renderer.setMirror(isLocal && isUsingFrontCamera)
        renderer.setEnableHardwareScaler(true)
    }

    private fun configureAudioRoute(useSpeaker: Boolean) {
        if (!audioRouteInitialized) {
            previousAudioMode = audioManager.mode
            previousSpeakerphoneState = audioManager.isSpeakerphoneOn
            audioRouteInitialized = true
        }
        speakerEnabled = useSpeaker
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = useSpeaker
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val targetType = if (useSpeaker) {
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            } else {
                AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
            }
            val targetDevice = audioManager.availableCommunicationDevices
                .firstOrNull { it.type == targetType }
                ?: if (!useSpeaker) {
                    audioManager.availableCommunicationDevices.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                    }
                } else {
                    null
                }
            if (targetDevice != null) {
                Log.d(logTag, "configureAudioRoute device=${targetDevice.productName} type=${targetDevice.type}")
                audioManager.setCommunicationDevice(targetDevice)
            } else {
                Log.w(logTag, "configureAudioRoute no target communication device for useSpeaker=$useSpeaker")
            }
        }
    }

    private fun restoreAudioRoute() {
        if (!audioRouteInitialized) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        }
        audioManager.isSpeakerphoneOn = previousSpeakerphoneState
        audioManager.mode = previousAudioMode
        audioRouteInitialized = false
    }

    private fun stopLocalMedia() {
        releaseVideoCaptureResources()
        localAudioTrack?.dispose()
        localAudioTrack = null
        audioSource?.dispose()
        audioSource = null
    }

    private fun releaseVideoCaptureResources() {
        localVideoTrack?.removeSink(localRenderer)
        runCatching { videoCapturer?.stopCapture() }
            .onFailure { error -> Log.w(logTag, "stopCapture failed", error) }
        videoCapturer?.dispose()
        videoCapturer = null
        localVideoTrack?.dispose()
        localVideoTrack = null
        videoSource?.dispose()
        videoSource = null
        surfaceTextureHelper?.dispose()
        surfaceTextureHelper = null
        activeCaptureProfile = null
    }

    private fun scheduleConnectionLostCheck() {
        cancelConnectionLostCheck()
        connectionLostRunnable = Runnable {
            val iceState = peerConnection?.iceConnectionState()
            if (iceState == PeerConnection.IceConnectionState.DISCONNECTED ||
                iceState == PeerConnection.IceConnectionState.FAILED
            ) {
                notifyConnectionLost()
            }
        }
        mainHandler.postDelayed(connectionLostRunnable!!, CONNECTION_LOST_GRACE_MS)
    }

    private fun cancelConnectionLostCheck() {
        connectionLostRunnable?.let { mainHandler.removeCallbacks(it) }
        connectionLostRunnable = null
    }

    private fun notifyConnectionLost() {
        if (connectionLostNotified || peerConnection == null) return
        connectionLostNotified = true
        cancelConnectionLostCheck()
        onConnectionLost()
    }

    private fun reconfigureCaptureIfNeeded() {
        val capturer = videoCapturer ?: return
        val profile = outboundProfile
        if (activeCaptureProfile == profile) return
        runCatching {
            capturer.changeCaptureFormat(profile.captureWidth, profile.captureHeight, profile.captureFps)
            activeCaptureProfile = profile
            Log.d(
                logTag,
                "reconfigureCapture ${profile.captureWidth}x${profile.captureHeight}@${profile.captureFps}",
            )
        }.onFailure { error ->
            Log.w(logTag, "reconfigureCapture failed; keeping previous capture profile=$activeCaptureProfile", error)
        }
    }

    private fun applyOutboundEncoding() {
        val sender = videoSender() ?: return
        val profile = outboundProfile
        val parameters = sender.parameters
        val encodings = parameters.encodings?.toMutableList() ?: mutableListOf()
        if (encodings.isEmpty()) {
            encodings.add(RtpParameters.Encoding(VIDEO_ENCODING_ID, true, 1.0))
        }
        encodings[0] = encodings[0].apply {
            active = true
            maxBitrateBps = profile.maxBitrateBps
            minBitrateBps = profile.minBitrateBps
            maxFramerate = profile.captureFps
            scaleResolutionDownBy = 1.0
        }
        parameters.encodings = encodings
        parameters.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE
        if (!sender.setParameters(parameters)) {
            Log.w(logTag, "applyOutboundEncoding setParameters failed")
        } else {
            Log.d(
                logTag,
                "applyOutboundEncoding maxBitrate=${profile.maxBitrateBps} fps=${profile.captureFps}",
            )
        }
    }

    private fun videoSender(): RtpSender? =
        peerConnection?.senders?.firstOrNull { it.track()?.kind() == MediaStreamTrack.VIDEO_TRACK_KIND }

    private fun defaultOutboundProfile(): VideoQualityProfile =
        VideoQualityProfile.forCall(localDeviceClass(), DeviceCapability.CLASS_STANDARD)

    private fun createCameraCapturer(): CameraVideoCapturer? {
        val enumerator = Camera2Enumerator(appContext)
        val deviceNames = enumerator.deviceNames

        val frontCamera = deviceNames.firstOrNull(enumerator::isFrontFacing)
        val backCamera = deviceNames.firstOrNull(enumerator::isBackFacing)

        return listOfNotNull(
            if (isUsingFrontCamera) frontCamera else backCamera,
            if (isUsingFrontCamera) backCamera else frontCamera,
        ).firstNotNullOfOrNull { name ->
            enumerator.createCapturer(name, null)
        }
    }

    companion object {
        private const val CONNECTION_LOST_GRACE_MS = 8_000L
        private const val VIDEO_ENCODING_ID = "dropin-video"
    }

    private open class SdpAdapter : org.webrtc.SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription?) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String?) = Unit
        override fun onSetFailure(error: String?) = Unit
    }
}
