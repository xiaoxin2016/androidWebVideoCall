package io.github.xiaoxin2016.videocall

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.xiaoxin2016.videocall.databinding.ActivityCallBinding
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.PeerConnection
import org.webrtc.RendererCommon
import org.webrtc.VideoTrack

class CallActivity : AppCompatActivity(), SignalingClient.Listener, RtcSession.Events {

    private enum class Phase { CONNECTING, WAITING_PEER, CALLING, CONNECTING_MEDIA, IN_CALL, ENDED }

    private lateinit var b: ActivityCallBinding
    private lateinit var host: String
    private var port = 0
    private lateinit var password: String
    private lateinit var name: String
    private lateinit var pins: PinStore

    private var signaling: SignalingClient? = null
    private lateinit var rtc: RtcSession
    private lateinit var audio: AudioRouter
    private var iceServers: JSONArray? = null
    private var remoteTrack: VideoTrack? = null

    private var phase = Phase.CONNECTING
    private var micOn = true
    private var camOn = true
    private var callStartedAt = 0L
    private var released = false
    private val ui = Handler(Looper.getMainLooper())

    private val hostPort get() = "${SignalingClient.formatHost(host)}:$port"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityCallBinding.inflate(layoutInflater)
        setContentView(b.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        host = intent.getStringExtra(EXTRA_HOST).orEmpty()
        port = intent.getIntExtra(EXTRA_PORT, 0)
        password = intent.getStringExtra(EXTRA_PASSWORD).orEmpty()
        name = intent.getStringExtra(EXTRA_NAME).orEmpty()
        pins = PinStore(this)

        rtc = RtcSession(this, this)
        b.remoteView.init(rtc.eglBase.eglBaseContext, null)
        b.remoteView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        b.remoteView.setEnableHardwareScaler(true)
        b.localView.init(rtc.eglBase.eglBaseContext, null)
        b.localView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
        b.localView.setZOrderMediaOverlay(true)
        b.localView.setEnableHardwareScaler(true)
        b.localView.setMirror(true)

        audio = AudioRouter(this)
        audio.start()
        rtc.init(b.localView)

        b.btnHangup.setOnClickListener { hangup() }
        b.btnSwitch.setOnClickListener { rtc.switchCamera() }
        b.btnMute.setOnClickListener {
            micOn = !micOn
            rtc.setMicEnabled(micOn)
            b.btnMute.text = getString(if (micOn) R.string.mute else R.string.unmute)
        }
        b.btnCamera.setOnClickListener {
            camOn = !camOn
            rtc.setCameraEnabled(camOn)
            b.btnCamera.text = getString(if (camOn) R.string.camera_off else R.string.camera_on)
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = hangup()
        })

        connectSignaling()
    }

    // ---------------- signaling ----------------

    private fun connectSignaling() {
        signaling?.close()
        setPhase(Phase.CONNECTING, getString(R.string.status_connecting, hostPort))
        signaling = SignalingClient(host, port, pins.get(hostPort), this).also { it.connect() }
    }

    private fun send(type: String, block: JSONObject.() -> Unit = {}) {
        signaling?.send(JSONObject().put("type", type).apply(block))
    }

    override fun onOpen() {
        send("auth") {
            put("role", "android")
            put("password", password)
            put("name", name)
        }
    }

    override fun onMessage(msg: JSONObject) {
        if (phase == Phase.ENDED) return
        when (msg.optString("type")) {
            "auth_ok" -> {
                iceServers = msg.optJSONArray("iceServers")
                if (msg.optBoolean("peerOnline")) placeCall()
                else setPhase(Phase.WAITING_PEER, getString(R.string.status_waiting_peer))
            }
            "auth_fail" -> end(
                when (msg.optString("reason")) {
                    "bad_password" -> getString(R.string.err_bad_password)
                    "rate_limited" -> getString(R.string.err_rate_limited, msg.optString("retry_after"))
                    "busy" -> getString(R.string.err_busy)
                    else -> getString(R.string.err_auth, msg.optString("reason"))
                }
            )
            "presence" -> if (msg.optBoolean("online") && phase == Phase.WAITING_PEER) placeCall()
            "call_failed" -> when (msg.optString("reason")) {
                "offline" -> setPhase(Phase.WAITING_PEER, getString(R.string.status_waiting_peer))
                else -> end(getString(R.string.err_busy))
            }
            "accepted" -> {
                setPhase(Phase.CONNECTING_MEDIA, getString(R.string.status_connecting_media))
                rtc.startCall(iceServers ?: JSONArray())
            }
            "answer" -> rtc.onRemoteAnswer(msg.optString("sdp"))
            "candidate" -> msg.optJSONObject("candidate")?.let { rtc.onRemoteCandidate(it) }
            "rejected" -> end(getString(R.string.status_rejected))
            "hangup" -> end(
                getString(
                    if (msg.optString("reason") == "disconnected") R.string.status_peer_lost
                    else R.string.status_peer_hangup
                )
            )
            "kicked" -> end(getString(R.string.status_kicked))
        }
    }

    private fun placeCall() {
        setPhase(Phase.CALLING, getString(R.string.status_calling))
        send("call")
    }

    override fun onFailure(error: Throwable) {
        if (phase == Phase.ENDED) return
        val unknown = error.findCause { it is UnknownCertificateException } as UnknownCertificateException?
        val mismatch = error.findCause { it is PinMismatchException } as PinMismatchException?
        when {
            unknown != null -> askTrust(unknown.fingerprint, null)
            mismatch != null -> askTrust(mismatch.fingerprint, mismatch.pinned)
            else -> end(getString(R.string.err_connect, error.message ?: error.javaClass.simpleName))
        }
    }

    override fun onClosed() {
        if (phase != Phase.ENDED) end(getString(R.string.status_server_lost))
    }

    private fun askTrust(fingerprint: String, previouslyPinned: String?) {
        if (isFinishing) return
        val msg = StringBuilder()
        if (previouslyPinned == null) {
            msg.append(getString(R.string.trust_first_body, hostPort))
        } else {
            msg.append(getString(R.string.trust_changed_body, hostPort))
            msg.append("\n\n").append(getString(R.string.trust_old)).append('\n')
                .append(formatFingerprint(previouslyPinned))
            msg.append("\n\n").append(getString(R.string.trust_new))
        }
        msg.append('\n').append(formatFingerprint(fingerprint))

        val tv = TextView(this).apply {
            text = msg
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(if (previouslyPinned == null) R.string.trust_first_title else R.string.trust_changed_title)
            .setView(tv)
            .setCancelable(false)
            .setPositiveButton(
                if (previouslyPinned == null) R.string.trust_accept else R.string.trust_accept_changed
            ) { _, _ ->
                pins.put(hostPort, fingerprint)
                connectSignaling()
            }
            .setNegativeButton(R.string.cancel) { _, _ -> end(getString(R.string.status_untrusted)) }
            .show()
    }

    // ---------------- RtcSession events (WebRTC threads) ----------------

    override fun onLocalOffer(sdp: String) = runOnUiThread { send("offer") { put("sdp", sdp) } }

    override fun onLocalCandidate(candidate: JSONObject) =
        runOnUiThread { send("candidate") { put("candidate", candidate) } }

    override fun onConnectionState(state: PeerConnection.PeerConnectionState) = runOnUiThread {
        if (phase == Phase.ENDED) return@runOnUiThread
        when (state) {
            PeerConnection.PeerConnectionState.CONNECTED -> {
                if (phase != Phase.IN_CALL) {
                    setPhase(Phase.IN_CALL, null)
                    callStartedAt = SystemClock.elapsedRealtime()
                    tickTimer()
                } else {
                    showStatus(null)
                }
            }
            PeerConnection.PeerConnectionState.DISCONNECTED ->
                showStatus(getString(R.string.status_reconnecting))
            PeerConnection.PeerConnectionState.FAILED -> {
                send("hangup")
                end(getString(R.string.err_media_failed))
            }
            else -> {}
        }
    }

    override fun onRemoteVideo(track: VideoTrack) = runOnUiThread {
        if (released) return@runOnUiThread
        remoteTrack = track
        track.addSink(b.remoteView)
    }

    override fun onCameraSwitched(isFront: Boolean) = runOnUiThread {
        if (!released) b.localView.setMirror(isFront)
    }

    override fun onError(message: String) = runOnUiThread { showStatus(message) }

    // ---------------- UI / lifecycle ----------------

    private fun setPhase(p: Phase, status: String?) {
        phase = p
        showStatus(status)
        val inCall = p == Phase.IN_CALL
        b.callTimer.visibility = if (inCall) View.VISIBLE else View.GONE
    }

    private fun showStatus(text: String?) {
        b.status.text = text.orEmpty()
        b.status.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    private fun tickTimer() {
        if (phase != Phase.IN_CALL) return
        val s = (SystemClock.elapsedRealtime() - callStartedAt) / 1000
        b.callTimer.text = "%02d:%02d".format(s / 60, s % 60)
        ui.postDelayed(::tickTimer, 1000)
    }

    private fun hangup() {
        if (phase in setOf(Phase.CALLING, Phase.CONNECTING_MEDIA, Phase.IN_CALL)) send("hangup")
        end(getString(R.string.status_ended))
    }

    private fun end(message: String) {
        if (phase == Phase.ENDED) return
        setPhase(Phase.ENDED, message)
        b.controls.visibility = View.INVISIBLE
        release()
        ui.postDelayed({ if (!isFinishing) finish() }, 1800)
    }

    private fun release() {
        if (released) return
        released = true
        ui.removeCallbacksAndMessages(null)
        signaling?.close()
        signaling = null
        remoteTrack?.let { runCatching { it.removeSink(b.remoteView) } }
        remoteTrack = null
        b.remoteView.release()
        b.localView.release()
        rtc.dispose()
        audio.stop()
    }

    override fun onStart() {
        super.onStart()
        if (!released) rtc.resumeCapture()
    }

    override fun onStop() {
        super.onStop()
        if (!released && !isChangingConfigurations) rtc.pauseCapture()
    }

    override fun onDestroy() {
        if (phase in setOf(Phase.CALLING, Phase.CONNECTING_MEDIA, Phase.IN_CALL)) send("hangup")
        release()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_HOST = "host"
        private const val EXTRA_PORT = "port"
        private const val EXTRA_PASSWORD = "password"
        private const val EXTRA_NAME = "name"

        fun intent(ctx: Context, host: String, port: Int, password: String, name: String) =
            Intent(ctx, CallActivity::class.java)
                .putExtra(EXTRA_HOST, host)
                .putExtra(EXTRA_PORT, port)
                .putExtra(EXTRA_PASSWORD, password)
                .putExtra(EXTRA_NAME, name)

        fun formatFingerprint(fp: String): String {
            val parts = fp.split(':')
            return parts.chunked(8).joinToString("\n") { it.joinToString(":") }
        }
    }
}
