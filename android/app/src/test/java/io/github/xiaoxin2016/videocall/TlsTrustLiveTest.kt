package io.github.xiaoxin2016.videocall

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Exercises the app's TLS trust policy and the auth handshake against a live
 * server. Skipped unless VIDEOCALL_TEST_HOST / VIDEOCALL_TEST_PORT are set.
 */
class TlsTrustLiveTest {
    private val host = System.getenv("VIDEOCALL_TEST_HOST")
    private val port = System.getenv("VIDEOCALL_TEST_PORT")?.toIntOrNull()
    private val password = System.getenv("VIDEOCALL_TEST_PASSWORD").orEmpty()

    private fun open(pin: String?, pw: String): Pair<Throwable?, String?> {
        val tm = TofuTrustManager(pin)
        val client = OkHttpClient.Builder()
            .sslSocketFactory(tm.socketFactory(), tm)
            .hostnameVerifier(tm.hostnameVerifier())
            .build()
        val result = CompletableFuture<Pair<Throwable?, String?>>()
        client.newWebSocket(
            Request.Builder().url("wss://$host:$port/ws").build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send("""{"type":"auth","role":"android","name":"jvm-test","password":"$pw"}""")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    result.complete(null to text)
                    webSocket.close(1000, null)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    result.complete(t to null)
                }
            })
        return result.get(15, TimeUnit.SECONDS).also { client.dispatcher.executorService.shutdown() }
    }

    @Test
    fun tofuPinAndAuth() {
        assumeTrue(host != null && port != null)

        // 1. first contact: self-signed cert is rejected with its fingerprint
        val (err1, _) = open(null, password)
        val unknown = err1?.findCause { it is UnknownCertificateException } as UnknownCertificateException?
        assertNotNull("expected UnknownCertificateException, got $err1", unknown)
        val fp = unknown!!.fingerprint
        assertTrue(fp.matches(Regex("([0-9A-F]{2}:){31}[0-9A-F]{2}")))

        // 2. pinned: handshake succeeds (hostname check bypassed by the pin) and auth works
        val (err2, msg2) = open(fp, password)
        assertEquals(null, err2)
        assertTrue("got $msg2", msg2!!.contains("\"auth_ok\""))

        // 3. wrong pin: mismatch is detected
        val wrong = fp.replaceRange(0, 2, if (fp.startsWith("00")) "11" else "00")
        val (err3, _) = open(wrong, password)
        assertNotNull(err3?.findCause { it is PinMismatchException })

        // 4. wrong password is refused
        if (password.isNotEmpty()) {
            val (_, msg4) = open(fp, password + "x")
            assertTrue("got $msg4", msg4!!.contains("bad_password"))
        }
    }
}
