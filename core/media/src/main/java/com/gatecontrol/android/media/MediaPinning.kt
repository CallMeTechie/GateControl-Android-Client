package com.gatecontrol.android.media

import okhttp3.OkHttpClient
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.X509TrustManager

/**
 * The certificate of the NAS pinned by its SHA-256 (the plugin's `certSha256`, DSM's self-signed certificate at
 * `https://192.168.1.20:5006`): only exactly that certificate is accepted, whatever its name or issuer. Also the home
 * check's `tls` probe. Pure apart from the JDK's TLS classes; unit-tested.
 */
object MediaPinning {
    fun sha256(cert: java.security.cert.Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it) }

    /** Accepts exactly the certificate [pin] (lower-case hex), refuses every other one. */
    class PinTrustManager(private val pin: String) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
            throw CertificateException("client certificates are not accepted")

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val leaf = chain?.firstOrNull() ?: throw CertificateException("no certificate")
            if (sha256(leaf) != pin) throw CertificateException("certificate does not match the pinned one")
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    /** The name does not count for a pinned certificate (an IP address in the home network); the certificate does. */
    class PinHostnameVerifier(private val pin: String) : HostnameVerifier {
        override fun verify(hostname: String?, session: SSLSession?): Boolean {
            val leaf = runCatching { session?.peerCertificates?.firstOrNull() }.getOrNull() ?: return false
            return sha256(leaf) == pin
        }
    }

    /** [base] with only the certificate [pin] trusted. */
    fun client(base: OkHttpClient, pin: String): OkHttpClient {
        val tm = PinTrustManager(pin)
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }
        return base.newBuilder().sslSocketFactory(ctx.socketFactory, tm).hostnameVerifier(PinHostnameVerifier(pin)).build()
    }
}
