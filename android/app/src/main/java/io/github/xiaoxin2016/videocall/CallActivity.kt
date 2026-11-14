package io.github.xiaoxin2016.videocall

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import io.github.xiaoxin2016.videocall.databinding.ActivityCallBinding
import io.github.xiaoxin2016.videocall.databinding.SheetTrustBinding
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
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        host = intent.getStringExtra(EXTRA_HOST).orEmpty()
        port = intent.getIntExtra(EXTRA_PORT, 0)
        password = intent.getStringExtra(EXTRA_PASSWORD).orEmpty()
        name = intent.getStringExtra(EXTRA_NAME).orEmpty()
        pins = PinStore(this)
        b.callingHost.text = hostPort

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
        b.btnCancel.setOnClickListener { hangup() }
        b.btnSwitch.setOnClickListener { rtc.switchCamera() }
        b.btnFlipCalling.setOnClickListener { rtc.switchCamera() }
        b.btnMute.setOnClickListener { toggleMic() }
        b.btnMuteCalling.setOnClickListener { toggleMic() }
        b.btnCamera.setOnClickListener { toggleCamera() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = hangup()
        })

        connectSignaling()
    }

    // ---------------- signaling ----------------

    private fun connectSignaling() {
        signaling?.close()
        setPhase(Phase.CONNECTING, getString(R.string.status_connecting))
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
        val sheet = BottomSheetDialog(this)
        val sb = SheetTrustBinding.inflate(layoutInflater)
        bindTrustSheet(this, sb, hostPort, fingerprint, previouslyPinned)
        var decided = false
        sb.trustAccept.setOnClickListener {
            decided = true
            pins.put(hostPort, fingerprint)
            sheet.dismiss()
            connectSignaling()
        }
        sb.trustCancel.setOnClickListener { sheet.dismiss() }
        sheet.setOnDismissListener { if (!decided) end(getString(R.string.status_untrusted)) }
        sheet.setContentView(sb.root)
        sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        sheet.behavior.skipCollapsed = true
        sheet.show()
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
                    callStartedAt = SystemClock.elapsedRealtime()
                    setPhase(Phase.IN_CALL, null)
                    tickTimer()
                } else {
                    showStatus(null)
                }
            }
            PeerConnection.PeerConnectionState.DISCONNECTED ->
                if (phase == Phase.IN_CALL) showStatus(getString(R.string.status_reconnecting))
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

    override fun onRelayProtocol(protocol: String) = runOnUiThread {
        if (released) return@runOnUiThread
        b.encPill.text = getString(R.string.e2e_encrypted_via, protocol.uppercase())
        send("netinfo") { put("relay", protocol) }
    }

    override fun onError(message: String) = runOnUiThread { showStatus(message) }

    // ---------------- UI ----------------

    private fun setPhase(p: Phase, status: String?) {
        phase = p
        when (p) {
            Phase.IN_CALL -> {
                showStatus(null)
                showInCallLayout(true)
            }
            Phase.ENDED -> {
                b.callingControls.visibility = View.INVISIBLE
                b.inCallControls.visibility = View.INVISIBLE
                b.callingGroup.visibility = View.GONE
                showStatus(status)
            }
            else -> {
                showInCallLayout(false)
                b.callingStatus.text = status.orEmpty()
            }
        }
    }

    private fun showInCallLayout(inCall: Boolean) {
        val calling = if (inCall) View.GONE else View.VISIBLE
        val connected = if (inCall) View.VISIBLE else View.GONE
        b.scrim.visibility = calling
        b.callingGroup.visibility = calling
        b.callingControls.visibility = calling
        b.inCallTop.visibility = connected
        b.inCallControls.visibility = connected
        b.localFrame.visibility = connected

        val d = resources.displayMetrics.density
        val lp = b.localView.layoutParams as FrameLayout.LayoutParams
        if (inCall) {
            lp.width = (108 * d).toInt()
            lp.height = (152 * d).toInt()
            lp.gravity = Gravity.TOP or Gravity.END
            lp.topMargin = (48 * d).toInt()
            lp.marginEnd = (16 * d).toInt()
        } else {
            lp.width = FrameLayout.LayoutParams.MATCH_PARENT
            lp.height = FrameLayout.LayoutParams.MATCH_PARENT
            lp.topMargin = 0
            lp.marginEnd = 0
        }
        b.localView.layoutParams = lp
    }

    private fun showStatus(text: String?) {
        b.status.text = text.orEmpty()
        b.status.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    private fun toggleMic() {
        micOn = !micOn
        rtc.setMicEnabled(micOn)
        val icon = if (micOn) R.drawable.ic_mic else R.drawable.ic_mic_off
        val desc = getString(if (micOn) R.string.mute else R.string.unmute)
        b.btnMute.setImageResource(icon)
        b.btnMuteCalling.setImageResource(icon)
        b.btnMute.contentDescription = desc
        b.btnMuteCalling.contentDescription = desc
        styleToggle(b.btnMute, !micOn, R.drawable.bg_round_dark)
        styleToggle(b.btnMuteCalling, !micOn, R.drawable.bg_round_glass)
        val label = getString(if (micOn) R.string.mute else R.string.muted)
        b.lblMute.text = label
        b.lblMuteCalling.text = label
    }

    private fun toggleCamera() {
        camOn = !camOn
        rtc.setCameraEnabled(camOn)
        b.btnCamera.setImageResource(if (camOn) R.drawable.ic_video else R.drawable.ic_video_off)
        b.btnCamera.contentDescription = getString(if (camOn) R.string.camera_off else R.string.camera_on)
        styleToggle(b.btnCamera, !camOn, R.drawable.bg_round_dark)
        b.lblCamera.text = getString(if (camOn) R.string.camera else R.string.camera_is_off)
    }

    /** "Active" toggles (muted / camera off) are drawn light with a dark icon, as in the design. */
    private fun styleToggle(v: ImageButton, active: Boolean, normalBg: Int) {
        v.setBackgroundResource(if (active) R.drawable.bg_round_light else normalBg)
        v.imageTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, if (active) R.color.bg else R.color.fg)
        )
    }

    private fun tickTimer() {
        if (phase != Phase.IN_CALL) return
        val s = (SystemClock.elapsedRealtime() - callStartedAt) / 1000
        b.callTimer.text = if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60)
        else "%02d:%02d".format(s / 60, s % 60)
        ui.postDelayed(::tickTimer, 1000)
    }

    private fun hangup() {
        if (phase in setOf(Phase.CALLING, Phase.CONNECTING_MEDIA, Phase.IN_CALL)) send("hangup")
        end(getString(R.string.status_ended))
    }

    private fun end(message: String) {
        if (phase == Phase.ENDED) return
        setPhase(Phase.ENDED, message)
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

        fun formatFingerprint(fp: String): String =
            fp.split(':').chunked(8).joinToString("\n") { it.joinToString(":") }

        /** Fills the certificate sheet; also used by the screenshot tests. */
        fun bindTrustSheet(
            ctx: Context,
            sb: SheetTrustBinding,
            hostPort: String,
            fingerprint: String,
            previouslyPinned: String?,
        ) {
            val changed = previouslyPinned != null
            sb.trustTitle.setText(if (changed) R.string.trust_changed_title else R.string.trust_first_title)
            sb.trustHost.text = if (changed) hostPort else ctx.getString(R.string.trust_first_host, hostPort)
            sb.trustBody.setText(if (changed) R.string.trust_changed_body else R.string.trust_first_body)
            sb.trustFpLabel.setText(if (changed) R.string.trust_new_label else R.string.trust_fp_label)
            sb.trustAccept.setText(if (changed) R.string.trust_accept_changed else R.string.trust_accept)
            sb.trustAccept.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(ctx, if (changed) R.color.danger_strong else R.color.accent)
            )
            if (changed) {
                sb.trustIcon.setImageResource(R.drawable.ic_warning)
                sb.trustIcon.setBackgroundResource(R.drawable.bg_tile_red)
                sb.trustIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.danger_fg))
                sb.trustOld.visibility = View.VISIBLE
                sb.trustOld.text = ctx.getString(R.string.trust_old, formatFingerprint(previouslyPinned!!))
            }
            // 32 bytes -> 4 x 4 grid of "AA:BB" pairs
            sb.trustGrid.removeAllViews()
            fingerprint.split(':').chunked(2).forEach { pair ->
                sb.trustGrid.addView(TextView(ctx).apply {
                    text = pair.joinToString(":")
                    typeface = Typeface.MONOSPACE
                    textSize = 14f
                    gravity = Gravity.CENTER
                    setTextColor(ContextCompat.getColor(ctx, R.color.fg))
                    val pad = (4 * ctx.resources.displayMetrics.density).toInt()
                    setPadding(0, pad, 0, pad)
                    layoutParams = GridLayout.LayoutParams(
                        GridLayout.spec(GridLayout.UNDEFINED),
                        GridLayout.spec(GridLayout.UNDEFINED, 1f),
                    ).apply { width = 0 }
                })
            }
        }
    }
}
