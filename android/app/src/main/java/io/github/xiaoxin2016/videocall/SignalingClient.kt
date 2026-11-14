package io.github.xiaoxin2016.videocall

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * WSS signaling client. All callbacks are delivered on the main thread.
 */
class SignalingClient(
    host: String,
    port: Int,
    pinnedFingerprint: String?,
    private val listener: Listener,
) {
    interface Listener {
        fun onOpen()
        fun onMessage(msg: JSONObject)
        fun onFailure(error: Throwable)
        fun onClosed()
    }

    private val main = Handler(Looper.getMainLooper())
    private val trust = TofuTrustManager(pinnedFingerprint)
    private val client = OkHttpClient.Builder()
        .sslSocketFactory(trust.socketFactory(), trust)
        .hostnameVerifier(trust.hostnameVerifier())
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private val url = "wss://${formatHost(host)}:$port/ws"
    private var ws: WebSocket? = null

    @Volatile
    private var closedByUser = false

    fun connect() {
        val req = Request.Builder().url(url).build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                main.post { if (!closedByUser) listener.onOpen() }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = try {
                    JSONObject(text)
                } catch (e: Exception) {
                    return
                }
                main.post { if (!closedByUser) listener.onMessage(msg) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                main.post { if (!closedByUser) listener.onClosed() }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                main.post { if (!closedByUser) listener.onFailure(t) }
            }
        })
    }

    fun send(msg: JSONObject) {
        ws?.send(msg.toString())
    }

    fun close() {
        closedByUser = true
        ws?.close(1000, null)
        ws = null
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    companion object {
        fun formatHost(host: String): String =
            if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
    }
}
