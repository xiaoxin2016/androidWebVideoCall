package io.github.xiaoxin2016.videocall

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera1Enumerator
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraEnumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.concurrent.Executors

/**
 * Owns the WebRTC objects for one call. Public methods may be called from any
 * thread; WebRTC work is serialized on a private executor. [Events] callbacks
 * arrive on WebRTC/internal threads — the caller must hop to the UI thread.
 */
class RtcSession(
    private val context: Context,
    private val events: Events,
) {
    interface Events {
        fun onLocalOffer(sdp: String)
        fun onLocalCandidate(candidate: JSONObject)
        fun onConnectionState(state: PeerConnection.PeerConnectionState)
        fun onRemoteVideo(track: VideoTrack)
        fun onCameraSwitched(isFront: Boolean)
        fun onRelayProtocol(protocol: String)
        fun onError(message: String)
    }

    val eglBase: EglBase = EglBase.create()
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var factory: PeerConnectionFactory
    private var pc: PeerConnection? = null
    private var capturer: CameraVideoCapturer? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null
    private var localVideo: VideoTrack? = null
    private var localAudio: AudioTrack? = null
    private val pendingRemoteCandidates = mutableListOf<IceCandidate>()
    private var remoteDescriptionSet = false
    private var disposed = false
    private var capturePaused = false

    var isFrontCamera = true
        private set

    fun init(localRenderer: SurfaceViewRenderer) {
        executor.execute {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                    .createInitializationOptions()
            )
            val adm = JavaAudioDeviceModule.builder(context.applicationContext)
                .setUseHardwareAcousticEchoCanceler(true)
                .setUseHardwareNoiseSuppressor(true)
                .createAudioDeviceModule()
            factory = PeerConnectionFactory.builder()
                .setAudioDeviceModule(adm)
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
                .createPeerConnectionFactory()
            adm.release() // factory holds its own reference

            startCamera(localRenderer)

            audioSource = factory.createAudioSource(MediaConstraints())
            localAudio = factory.createAudioTrack("audio0", audioSource)
        }
    }

    private fun startCamera(localRenderer: SurfaceViewRenderer) {
        val enumerator: CameraEnumerator =
            if (Camera2Enumerator.isSupported(context)) Camera2Enumerator(context) else Camera1Enumerator(true)
        val names = enumerator.deviceNames
        val name = names.firstOrNull { enumerator.isFrontFacing(it) } ?: names.firstOrNull()
        if (name == null) {
            events.onError("未找到摄像头")
            return
        }
        isFrontCamera = enumerator.isFrontFacing(name)
        val cap = enumerator.createCapturer(name, null) ?: run {
            events.onError("无法打开摄像头")
            return
        }
        capturer = cap
        surfaceHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
        videoSource = factory.createVideoSource(cap.isScreencast)
        cap.initialize(surfaceHelper, context.applicationContext, videoSource!!.capturerObserver)
        cap.startCapture(1280, 720, 30)
        localVideo = factory.createVideoTrack("video0", videoSource).apply { addSink(localRenderer) }
        events.onCameraSwitched(isFrontCamera)
    }

    /** Called once the web side accepted: create the PeerConnection and send an offer. */
    fun startCall(iceServers: JSONArray) {
        executor.execute {
            if (disposed) return@execute
            val servers = mutableListOf<PeerConnection.IceServer>()
            for (i in 0 until iceServers.length()) {
                val s = iceServers.getJSONObject(i)
                val urls = s.getJSONArray("urls").let { a -> List(a.length()) { a.getString(it) } }
                servers += PeerConnection.IceServer.builder(urls)
                    .setUsername(s.optString("username"))
                    .setPassword(s.optString("credential"))
                    .createIceServer()
            }
            val cfg = PeerConnection.RTCConfiguration(servers).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                // All media goes through the server's embedded TURN relay.
                iceTransportsType = PeerConnection.IceTransportsType.RELAY
                bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
                rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            }
            val conn = factory.createPeerConnection(cfg, pcObserver)
            if (conn == null) {
                events.onError("创建 PeerConnection 失败")
                return@execute
            }
            pc = conn
            localAudio?.let { conn.addTrack(it, listOf("local")) }
            localVideo?.let { conn.addTrack(it, listOf("local")) }

            conn.createOffer(object : SdpAdapter("createOffer") {
                override fun onCreateSuccess(desc: SessionDescription) {
                    executor.execute {
                        conn.setLocalDescription(object : SdpAdapter("setLocal") {
                            override fun onSetSuccess() = events.onLocalOffer(desc.description)
                        }, desc)
                    }
                }
            }, MediaConstraints())
        }
    }

    fun onRemoteAnswer(sdp: String) {
        executor.execute {
            val conn = pc ?: return@execute
            conn.setRemoteDescription(object : SdpAdapter("setRemote") {
                override fun onSetSuccess() {
                    executor.execute {
                        remoteDescriptionSet = true
                        pendingRemoteCandidates.forEach { conn.addIceCandidate(it) }
                        pendingRemoteCandidates.clear()
                    }
                }
            }, SessionDescription(SessionDescription.Type.ANSWER, sdp))
        }
    }

    fun onRemoteCandidate(c: JSONObject) {
        executor.execute {
            val cand = c.optString("candidate")
            if (cand.isEmpty()) return@execute
            val ice = IceCandidate(c.optString("sdpMid", "0"), c.optInt("sdpMLineIndex", 0), cand)
            val conn = pc
            if (conn == null || !remoteDescriptionSet) pendingRemoteCandidates += ice
            else conn.addIceCandidate(ice)
        }
    }

    fun switchCamera() {
        capturer?.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFront: Boolean) {
                isFrontCamera = isFront
                events.onCameraSwitched(isFront)
            }

            override fun onCameraSwitchError(error: String) = events.onError("切换摄像头失败: $error")
        })
    }

    fun setMicEnabled(enabled: Boolean) = executor.execute { localAudio?.setEnabled(enabled) }
    fun setCameraEnabled(enabled: Boolean) = executor.execute { localVideo?.setEnabled(enabled) }

    /** Pause capture while the activity is in background (camera is blocked there anyway). */
    fun pauseCapture() = executor.execute {
        val cap = capturer ?: return@execute
        if (!capturePaused && !disposed) {
            runCatching { cap.stopCapture() }
            capturePaused = true
        }
    }

    fun resumeCapture() = executor.execute {
        if (capturePaused && !disposed) {
            capturer?.startCapture(1280, 720, 30)
            capturePaused = false
        }
    }

    fun dispose() {
        executor.execute {
            if (disposed) return@execute
            disposed = true
            runCatching { pc?.close() }
            pc?.dispose()
            pc = null
            runCatching { capturer?.stopCapture() }
            capturer?.dispose()
            surfaceHelper?.dispose()
            localVideo?.dispose()
            localAudio?.dispose()
            videoSource?.dispose()
            audioSource?.dispose()
            if (::factory.isInitialized) factory.dispose()
            eglBase.release()
        }
        executor.shutdown()
    }

    /** Which transport ("udp"/"tcp") our TURN allocation uses for the selected pair. */
    private fun reportRelayProtocol() {
        executor.execute {
            val conn = pc ?: return@execute
            conn.getStats { report ->
                val stats = report.statsMap
                val pairId = stats.values.firstOrNull { it.type == "transport" }
                    ?.members?.get("selectedCandidatePairId") as? String ?: return@getStats
                val localId = stats[pairId]?.members?.get("localCandidateId") as? String ?: return@getStats
                val proto = stats[localId]?.members?.get("relayProtocol") as? String ?: return@getStats
                events.onRelayProtocol(proto.lowercase())
            }
        }
    }

    private val pcObserver = object : PeerConnection.Observer {
        override fun onIceCandidate(c: IceCandidate) {
            events.onLocalCandidate(JSONObject().apply {
                put("candidate", c.sdp)
                put("sdpMid", c.sdpMid)
                put("sdpMLineIndex", c.sdpMLineIndex)
            })
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            events.onConnectionState(newState)
            if (newState == PeerConnection.PeerConnectionState.CONNECTED) reportRelayProtocol()
        }

        override fun onTrack(transceiver: RtpTransceiver) {
            val track = transceiver.receiver.track()
            if (track is VideoTrack) events.onRemoteVideo(track)
        }

        override fun onSignalingChange(s: PeerConnection.SignalingState) {}
        override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) {}
        override fun onIceConnectionReceivingChange(b: Boolean) {}
        override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {}
        override fun onIceCandidatesRemoved(c: Array<out IceCandidate>) {}
        override fun onAddStream(s: MediaStream) {}
        override fun onRemoveStream(s: MediaStream) {}
        override fun onDataChannel(dc: DataChannel) {}
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(r: RtpReceiver, s: Array<out MediaStream>) {}
    }

    private open inner class SdpAdapter(private val op: String) : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String) {
            Log.e(TAG, "$op failed: $error")
            events.onError("$op 失败: $error")
        }

        override fun onSetFailure(error: String) = onCreateFailure(error)
    }

    companion object {
        private const val TAG = "RtcSession"
    }
}
