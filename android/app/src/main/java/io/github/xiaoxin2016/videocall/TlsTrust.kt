package io.github.xiaoxin2016.videocall

import android.content.Context
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** Server certificate not trusted by the system and not pinned yet (first contact). */
class UnknownCertificateException(val fingerprint: String) :
    CertificateException("Untrusted server certificate $fingerprint")

/** Server certificate differs from the one pinned for this host:port — possible MITM. */
class PinMismatchException(val fingerprint: String, val pinned: String) :
    CertificateException("Server certificate changed: pinned $pinned, got $fingerprint")

/** Persists one pinned SHA-256 leaf-certificate fingerprint per host:port (TOFU). */
class PinStore(context: Context) {
    private val prefs = context.getSharedPreferences("cert_pins", Context.MODE_PRIVATE)

    fun get(hostPort: String): String? = prefs.getString(hostPort.lowercase(), null)
    fun put(hostPort: String, fingerprint: String) =
        prefs.edit().putString(hostPort.lowercase(), fingerprint).apply()

    fun clearAll() = prefs.edit().clear().apply()
    fun count(): Int = prefs.all.size
}

fun sha256Fingerprint(der: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(der).joinToString(":") { "%02X".format(it) }

/**
 * Trust policy:
 *  - a pinned fingerprint exists for this host:port  -> leaf must match it exactly
 *  - otherwise                                       -> must chain to a system CA
 *    (e.g. Let's Encrypt), else [UnknownCertificateException] so the UI can
 *    show the fingerprint and let the user pin it.
 */
class TofuTrustManager(private val pinned: String?) : X509TrustManager {
    private val system: X509TrustManager = TrustManagerFactory
        .getInstance(TrustManagerFactory.getDefaultAlgorithm())
        .apply { init(null as KeyStore?) }
        .trustManagers.filterIsInstance<X509TrustManager>().first()

    @Volatile
    var pinMatched = false
        private set

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        if (chain.isEmpty()) throw CertificateException("empty chain")
        val fp = sha256Fingerprint(chain[0].encoded)
        if (pinned != null) {
            if (MessageDigest.isEqual(fp.toByteArray(), pinned.toByteArray())) {
                pinMatched = true
                return
            }
            throw PinMismatchException(fp, pinned)
        }
        try {
            system.checkServerTrusted(chain, authType)
        } catch (e: CertificateException) {
            throw UnknownCertificateException(fp)
        }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) =
        throw CertificateException("client auth not supported")

    override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers

    fun socketFactory(): SSLSocketFactory =
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(this@TofuTrustManager), null) }
            .socketFactory

    /** Pinned certs are bound to host:port by the pin itself; CA certs need hostname checks. */
    fun hostnameVerifier(): HostnameVerifier {
        val default = HttpsURLConnection.getDefaultHostnameVerifier()
        return HostnameVerifier { host, session -> pinMatched || default.verify(host, session) }
    }
}

fun Throwable.findCause(predicate: (Throwable) -> Boolean): Throwable? {
    var t: Throwable? = this
    var depth = 0
    while (t != null && depth++ < 10) {
        if (predicate(t)) return t
        t = t.cause
    }
    return null
}
