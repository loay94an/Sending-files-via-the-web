package com.wasla.eshareremote

import android.content.Context
import org.json.JSONObject
import org.webrtc.*

/** WebRTC receiver for the paired TV screen stream. */
class WaslaWebRtcScreenReceiver(
    context: Context,
    private val renderer: SurfaceViewRenderer,
    private val sendSignal: (JSONObject) -> Unit,
    private val onState: (String) -> Unit
) {
    private val appContext = context.applicationContext
    private val egl = EglBase.create()
    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var remoteTrack: VideoTrack? = null
    private val pendingCandidates = mutableListOf<IceCandidate>()
    @Volatile private var stopped = false

    init {
        renderer.init(egl.eglBaseContext, null)
        renderer.setEnableHardwareScaler(true)
        renderer.setMirror(false)
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(appContext).createInitializationOptions()
        )
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
    }

    @Synchronized fun onSignal(signal: JSONObject) {
        if (stopped) return
        when (signal.optString("type")) {
            "offer" -> acceptOffer(signal.optString("sdp"))
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

    @Synchronized private fun acceptOffer(sdp: String) {
        if (sdp.isBlank()) return
        try {
            peerConnection?.close()
            peerConnection?.dispose()
            peerConnection = null
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
                override fun onAddStream(stream: MediaStream) {
                    stream.videoTracks.firstOrNull()?.let(::attachTrack)
                }
                override fun onRemoveStream(stream: MediaStream) {
                    stream.videoTracks.forEach { it.removeSink(renderer) }
                }
                override fun onDataChannel(channel: DataChannel) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<MediaStream>) {
                    (receiver.track() as? VideoTrack)?.let(::attachTrack)
                }
            }
            val pc = factory?.createPeerConnection(config, observer) ?: run {
                onState("screen-peer-create-failed")
                return
            }
            peerConnection = pc
            onState("screen-negotiating")
            pc.setRemoteDescription(object : SdpObserver {
                override fun onSetSuccess() {
                    synchronized(pendingCandidates) {
                        pendingCandidates.forEach { pc.addIceCandidate(it) }
                        pendingCandidates.clear()
                    }
                    pc.createAnswer(object : SdpObserver {
                        override fun onCreateSuccess(description: SessionDescription) {
                            pc.setLocalDescription(object : SdpObserver {
                                override fun onSetSuccess() {
                                    sendSignal(JSONObject().put("type", "answer").put("sdp", description.description))
                                }
                                override fun onSetFailure(error: String) { onState("screen-local-sdp-failed") }
                                override fun onCreateSuccess(desc: SessionDescription?) {}
                                override fun onCreateFailure(error: String?) {}
                            }, description)
                        }
                        override fun onSetSuccess() {}
                        override fun onCreateFailure(error: String) { onState("screen-answer-failed") }
                        override fun onSetFailure(error: String) {}
                    }, MediaConstraints())
                }
                override fun onSetFailure(error: String) { onState("screen-remote-sdp-failed") }
                override fun onCreateSuccess(desc: SessionDescription?) {}
                override fun onCreateFailure(error: String?) {}
            }, SessionDescription(SessionDescription.Type.OFFER, sdp))
        } catch (_: Exception) {
            onState("screen-negotiation-failed")
        }
    }

    private fun attachTrack(track: VideoTrack) {
        remoteTrack?.removeSink(renderer)
        remoteTrack = track
        track.addSink(renderer)
        onState("screen-video-track")
    }

    @Synchronized fun stop() {
        if (stopped) return
        stopped = true
        remoteTrack?.removeSink(renderer)
        remoteTrack = null
        try { peerConnection?.close() } catch (_: Exception) {}
        try { peerConnection?.dispose() } catch (_: Exception) {}
        peerConnection = null
        pendingCandidates.clear()
        try { factory?.dispose() } catch (_: Exception) {}
        factory = null
        try { renderer.release() } catch (_: Exception) {}
        try { egl.release() } catch (_: Exception) {}
    }
}
