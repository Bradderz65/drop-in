package com.bradhosk.dropin.webrtc

import android.content.Context
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RendererCommon
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

class DropInManager(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val eglBase = EglBase.create()
    private val peerFactory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null
    private var remoteRenderer: SurfaceViewRenderer? = null
    private var localRenderer: SurfaceViewRenderer? = null

    var onIceCandidate: (IceCandidate) -> Unit = {}
    var onRemoteVideoReady: () -> Unit = {}

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

    fun initializeRenderers(local: SurfaceViewRenderer, remote: SurfaceViewRenderer) {
        if (localRenderer === local && remoteRenderer === remote) return
        localRenderer = local
        remoteRenderer = remote

        listOf(local to RendererCommon.ScalingType.SCALE_ASPECT_FILL, remote to RendererCommon.ScalingType.SCALE_ASPECT_FIT)
            .forEach { (renderer, scaling) ->
                renderer.init(eglBase.eglBaseContext, null)
                renderer.setScalingType(scaling)
                renderer.setMirror(renderer === local)
                renderer.setEnableHardwareScaler(true)
            }
    }

    fun startLocalMedia() {
        if (localVideoTrack != null) return

        val surfaceTextureHelper = SurfaceTextureHelper.create("DropInCaptureThread", eglBase.eglBaseContext)
        videoCapturer = createCameraCapturer() ?: return
        videoSource = peerFactory.createVideoSource(false)
        audioSource = peerFactory.createAudioSource(MediaConstraints())
        localVideoTrack = peerFactory.createVideoTrack("localVideo", videoSource)
        localAudioTrack = peerFactory.createAudioTrack("localAudio", audioSource)

        videoCapturer?.initialize(surfaceTextureHelper, localRenderer?.context, videoSource?.capturerObserver)
        videoCapturer?.startCapture(1280, 720, 30)
        localVideoTrack?.addSink(localRenderer)
    }

    fun createPeerConnection(onConnected: () -> Unit) {
        if (peerConnection != null) return
        val rtcConfig = PeerConnection.RTCConfiguration(
            listOf(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            ),
        )
        peerConnection = peerFactory.createPeerConnection(
            rtcConfig,
            object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate) {
                    onIceCandidate(candidate)
                }

                override fun onTrack(transceiver: org.webrtc.RtpTransceiver?) {
                    val track = transceiver?.receiver?.track() as? VideoTrack ?: return
                    track.addSink(remoteRenderer)
                    onRemoteVideoReady()
                }

                override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                    if (newState == PeerConnection.PeerConnectionState.CONNECTED) {
                        onConnected()
                    }
                }

                override fun onSignalingChange(newState: PeerConnection.SignalingState?) = Unit
                override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) = Unit
                override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
                override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) = Unit
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate?>?) = Unit
                override fun onAddStream(stream: org.webrtc.MediaStream?) = Unit
                override fun onRemoveStream(stream: org.webrtc.MediaStream?) = Unit
                override fun onDataChannel(dataChannel: org.webrtc.DataChannel?) = Unit
                override fun onRenegotiationNeeded() = Unit
                override fun onAddTrack(receiver: org.webrtc.RtpReceiver?, mediaStreams: Array<out org.webrtc.MediaStream>?) = Unit
            },
        )

        localVideoTrack?.setEnabled(true)
        localAudioTrack?.setEnabled(true)
        peerConnection?.addTrack(localVideoTrack, listOf("stream"))
        peerConnection?.addTrack(localAudioTrack, listOf("stream"))
    }

    fun createOffer(onCreated: (SessionDescription) -> Unit) {
        peerConnection?.createOffer(object : SdpAdapter() {
            override fun onCreateSuccess(desc: SessionDescription?) {
                if (desc == null) return
                peerConnection?.setLocalDescription(SdpAdapter(), desc)
                onCreated(desc)
            }
        }, MediaConstraints())
    }

    fun createAnswer(onCreated: (SessionDescription) -> Unit) {
        peerConnection?.createAnswer(object : SdpAdapter() {
            override fun onCreateSuccess(desc: SessionDescription?) {
                if (desc == null) return
                peerConnection?.setLocalDescription(SdpAdapter(), desc)
                onCreated(desc)
            }
        }, MediaConstraints())
    }

    fun setRemoteDescription(description: SessionDescription, onSet: () -> Unit = {}) {
        peerConnection?.setRemoteDescription(object : SdpAdapter() {
            override fun onSetSuccess() {
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

    fun release() {
        endCall()
        stopLocalMedia()
        localRenderer?.release()
        remoteRenderer?.release()
        localRenderer = null
        remoteRenderer = null
        eglBase.release()
    }

    fun endCall() {
        peerConnection?.close()
        peerConnection?.dispose()
        peerConnection = null
    }

    private fun stopLocalMedia() {
        runCatching { videoCapturer?.stopCapture() }
        videoCapturer?.dispose()
        videoCapturer = null
        localVideoTrack?.dispose()
        localVideoTrack = null
        localAudioTrack?.dispose()
        localAudioTrack = null
        videoSource?.dispose()
        videoSource = null
        audioSource?.dispose()
        audioSource = null
    }

    private fun createCameraCapturer(): CameraVideoCapturer? {
        val enumerator = Camera2Enumerator(appContext)
        val deviceNames = enumerator.deviceNames

        val frontCamera = deviceNames.firstOrNull(enumerator::isFrontFacing)
        val backCamera = deviceNames.firstOrNull(enumerator::isBackFacing)

        return listOfNotNull(frontCamera, backCamera).firstNotNullOfOrNull { name ->
            enumerator.createCapturer(name, null)
        }
    }

    private open class SdpAdapter : org.webrtc.SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription?) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String?) = Unit
        override fun onSetFailure(error: String?) = Unit
    }
}
