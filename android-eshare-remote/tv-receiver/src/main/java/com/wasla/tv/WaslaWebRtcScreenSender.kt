package com.wasla.tv

import android.content.Context
import android.content.Intent
import org.json.JSONObject
import org.webrtc.*

/**
 * Native WebRTC screen sender. PeerJS is used only for authenticated signaling;
 * the video itself travels over a negotiated WebRTC media track.
 */
class WaslaWebRtcScreenSender(
    context: Context,
    permissionData: Intent,
    private val sendSignal: (JSONObject) -> Unit,
    private val onState: (String) -> Unit
) {
    private val appContext = context.applicationContext
    private val egl = EglBase.create()
    private var factory: PeerConnectionFactory? = null
    private var capturer: ScreenCapturerAndroid? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var peerConnection: PeerConnection? = null
    private val pendingCandidates = mutableListOf<IceCandidate>()
    @Volatile private var started = false
    @Volatile private var peerReady = false
    private val projectionIntent = Intent(permissionData)

    fun start(): Boolean {
        if (started) return true
        return try {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(appContext).createInitializationOptions()
            )
            factory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
                .createPeerConnectionFactory()
            val source = factory!!.createVideoSource(true)
            videoSource = source
            val screenCapturer = ScreenCapturerAndroid(projectionIntent, object : android.media.projection.MediaProjection.Callback() {
                override fun onStop() {
                    onState("screen-permission-ended")
                    stop()
                }
            })
            capturer = screenCapturer
            val helper = SurfaceTextureHelper.create("WaslaScreenCapture", egl.eglBaseContext)
            surfaceHelper = helper
            screenCapturer.initialize(helper, appContext, source.capturerObserver)
            val metrics = appContext.resources.displayMetrics
            val sourceWidth = metrics.widthPixels.coerceAtLeast(1)
            val width = minOf(sourceWidth, 1280).coerceAtLeast(480)
            val height = (metrics.heightPixels.toFloat() * width / sourceWidth).toInt().coerceIn(270, 720)
            screenCapturer.startCapture(width, height, 10)
            videoTrack = factory!!.createVideoTrack("wasla-screen-track", source).apply { setEnabled(true) }
            started = true
            onState("screen-capturing")
            if (peerReady) createOffer()
            true
        } catch (error: Exception) {
            onState("screen-start-failed")
            stop()
            false
        }
    }

    fun onPeerReady() {
        peerReady = true
        if (started && peerConnection == null) createOffer()
    }

    @Synchronized fun onPeerDisconnected() {
        peerReady = false
        try { peerConnection?.close() } catch (_: Exception) {}
        try { peerConnection?.dispose() } catch (_: Exception) {}
        peerConnection = null
        pendingCandidates.clear()
    }

    @Synchronized fun onSignal(signal: JSONObject) {
        when (signal.optString("type")) {
            "request" -> {
                if (started && peerReady) {
                    try { peerConnection?.close() } catch (_: Exception) {}
                    try { peerConnection?.dispose() } catch (_: Exception) {}
                    peerConnection = null
                    pendingCandidates.clear()
                    createOffer()
                }
            }
            "answer" -> {
                val sdp = signal.optString("sdp")
                if (sdp.isBlank()) return
                peerConnection?.setRemoteDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        synchronized(pendingCandidates) {
                            pendingCandidates.forEach { peerConnection?.addIceCandidate(it) }
                            pendingCandidates.clear()
                        }
                        onState("screen-connected")
                    }
                    override fun onSetFailure(error: String) { onState("screen-answer-failed") }
                    override fun onCreateSuccess(desc: SessionDescription?) {}
                    override fun onCreateFailure(error: String?) {}
                }, SessionDescription(SessionDescription.Type.ANSWER, sdp))
            }
            "ice" -> {
                val candidate = IceCandidate(
                    signal.optString("sdpMid"),
                    signal.optInt("sdpMLineIndex"),
                    signal.optString("candidate")
                )
                synchronized(pendingCandidates) {
                    if (peerConnection?.remoteDescription == null) pendingCandidates.add(candidate)
                    else peerConnection?.addIceCandidate(candidate)
                }
            }
        }
    }

    @Synchronized private fun createOffer() {
        if (!started || !peerReady || peerConnection != null) return
        val rtcFactory = factory ?: return
        val config = PeerConnection.RTCConfiguration(listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer()
        )).apply { sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN }
        val observer = object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                onState(when (state) {
                    PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> "screen-connected"
                    PeerConnection.IceConnectionState.DISCONNECTED -> "screen-disconnected"
                    PeerConnection.IceConnectionState.FAILED -> "screen-connection-failed"
                    else -> "screen-connecting"
                })
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
            override fun onIceCandidate(candidate: IceCandidate) {
                sendSignal(JSONObject().put("type", "ice").put("sdpMid", candidate.sdpMid)
                    .put("sdpMLineIndex", candidate.sdpMLineIndex).put("candidate", candidate.sdp))
            }
            override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) {}
            override fun onAddStream(stream: MediaStream) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onDataChannel(channel: DataChannel) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<MediaStream>) {}
        }
        val pc = rtcFactory.createPeerConnection(config, observer) ?: run {
            onState("screen-peer-create-failed")
            return
        }
        peerConnection = pc
        val track = videoTrack ?: return
        pc.addTrack(track, listOf("wasla-screen-stream"))
        onState("screen-negotiating")
        pc.createOffer(object : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription) {
                pc.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        sendSignal(JSONObject().put("type", "offer").put("sdp", description.description))
                    }
                    override fun onSetFailure(error: String) { onState("screen-local-sdp-failed") }
                    override fun onCreateSuccess(desc: SessionDescription?) {}
                    override fun onCreateFailure(error: String?) {}
                }, description)
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String) { onState("screen-offer-failed") }
            override fun onSetFailure(error: String) {}
        }, MediaConstraints())
    }

    @Synchronized fun stop() {
        started = false
        peerReady = false
        try { capturer?.stopCapture() } catch (_: Exception) {}
        try { capturer?.dispose() } catch (_: Exception) {}
        capturer = null
        try { surfaceHelper?.dispose() } catch (_: Exception) {}
        surfaceHelper = null
        try { videoTrack?.dispose() } catch (_: Exception) {}
        videoTrack = null
        try { videoSource?.dispose() } catch (_: Exception) {}
        videoSource = null
        try { peerConnection?.close() } catch (_: Exception) {}
        try { peerConnection?.dispose() } catch (_: Exception) {}
        peerConnection = null
        pendingCandidates.clear()
        try { factory?.dispose() } catch (_: Exception) {}
        factory = null
        try { egl.release() } catch (_: Exception) {}
    }
}
