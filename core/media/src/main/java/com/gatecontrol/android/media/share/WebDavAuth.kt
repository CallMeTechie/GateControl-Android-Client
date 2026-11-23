package com.gatecontrol.android.media.share

import com.gatecontrol.android.media.MediaAddress
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/** A `WWW-Authenticate` challenge: its scheme ("digest", "basic") and parameters (names in lower case). */
data class AuthChallenge(val scheme: String, val params: Map<String, String>)

/**
 * The access to a WebDAV share: Basic or Digest (RFC 7616, MD5 or SHA-256, `qop=auth`), as the server asks. After the
 * first 401 the scheme is known and every further request carries it at once (the player's range requests do not
 * wait for a 401 each). Only to the share's own server ([base]'s scheme, host and port): the password never goes
 * elsewhere. Basic only over HTTPS or to a host of the home network (away through the tunnel).
 */
class WebDavAuth(
    private val base: HttpUrl,
    private val user: String?,
    private val password: String?,
    private val random: SecureRandom = SecureRandom(),
) : Interceptor {

    @Volatile
    private var challenge: AuthChallenge? = null
    private val count = AtomicInteger(0)

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (user.isNullOrEmpty() || !sameServer(request.url)) return chain.proceed(request)
        val known = challenge
        val first = chain.proceed(authorized(request, known))
        if (first.code != 401) return first
        val offered = parse(first.headers("WWW-Authenticate"))
        val pick = choose(offered) ?: return first
        val stale = pick.params["stale"].equals("true", ignoreCase = true)
        // the same challenge refused again: the name or the password is wrong
        if (known != null && !stale && known.scheme == pick.scheme && known.params["nonce"] == pick.params["nonce"]) return first
        challenge = pick
        count.set(0)
        first.close()
        return chain.proceed(authorized(request, pick))
    }

    private fun sameServer(url: HttpUrl) = url.scheme == base.scheme && url.host == base.host && url.port == base.port

    private val basicAllowed: Boolean get() = base.isHttps || MediaAddress.isHomeHost(base.host)

    /** Digest (SHA-256 before MD5) before Basic; null when none can be answered. */
    internal fun choose(list: List<AuthChallenge>): AuthChallenge? {
        val digests = list.filter { it.scheme == "digest" && it.params["nonce"] != null && algorithm(it) != null }
        return digests.firstOrNull { algorithm(it)?.startsWith("SHA-256") == true }
            ?: digests.firstOrNull()
            ?: list.firstOrNull { it.scheme == "basic" }?.takeIf { basicAllowed }
    }

    private fun authorized(request: Request, c: AuthChallenge?): Request {
        c ?: return request
        val header = when (c.scheme) {
            "basic" -> Credentials.basic(user.orEmpty(), password.orEmpty(), Charsets.UTF_8)
            "digest" -> digest(request.method, request.url.encodedPath + (request.url.encodedQuery?.let { "?$it" } ?: ""), c)
            else -> null
        } ?: return request
        return request.newBuilder().header("Authorization", header).build()
    }

    /** The `Authorization: Digest …` of [method] [uri] for challenge [c]. */
    internal fun digest(method: String, uri: String, c: AuthChallenge, cnonce: String = newCnonce()): String? {
        val algorithm = algorithm(c) ?: return null
        val hashName = if (algorithm.startsWith("SHA-256")) "SHA-256" else "MD5"
        fun h(s: String): String = MessageDigest.getInstance(hashName).digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        val realm = c.params["realm"].orEmpty()
        val nonce = c.params["nonce"] ?: return null
        val qops = c.params["qop"]?.split(',')?.map { it.trim().lowercase(Locale.ROOT) }.orEmpty()
        val qop = if ("auth" in qops) "auth" else null
        if (qops.isNotEmpty() && qop == null) return null
        val nc = String.format(Locale.ROOT, "%08x", count.incrementAndGet())
        var ha1 = h("${user.orEmpty()}:$realm:${password.orEmpty()}")
        if (algorithm.endsWith("-sess")) ha1 = h("$ha1:$nonce:$cnonce")
        val ha2 = h("$method:$uri")
        val response = if (qop != null) h("$ha1:$nonce:$nc:$cnonce:$qop:$ha2") else h("$ha1:$nonce:$ha2")
        fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        val parts = mutableListOf(
            "username=${q(user.orEmpty())}",
            "realm=${q(realm)}",
            "nonce=${q(nonce)}",
            "uri=${q(uri)}",
            "algorithm=$algorithm",
            "response=${q(response)}",
        )
        if (qop != null) parts += listOf("qop=$qop", "nc=$nc", "cnonce=${q(cnonce)}")
        c.params["opaque"]?.let { parts += "opaque=${q(it)}" }
        return "Digest " + parts.joinToString(", ")
    }

    private fun algorithm(c: AuthChallenge): String? = when (val a = (c.params["algorithm"] ?: "MD5").uppercase(Locale.ROOT)) {
        "MD5", "MD5-SESS", "SHA-256", "SHA-256-SESS" -> a.replace("-SESS", "-sess")
        else -> null
    }

    private fun newCnonce(): String = ByteArray(12).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    companion object {
        private val PARAM = Regex("""([A-Za-z0-9_-]+)\s*=\s*(?:"((?:[^"\\]|\\.)*)"|([^\s,]*))""")

        /** The challenges of the `WWW-Authenticate` headers (several in one header are split at their scheme). */
        fun parse(headers: List<String>): List<AuthChallenge> = headers.flatMap { header ->
            val out = mutableListOf<AuthChallenge>()
            // "Digest realm=…, nonce=…, Basic realm=…": a new challenge starts at a token followed by a space and a parameter
            val starts = Regex("""(?:^|,)\s*([A-Za-z]+)\s+(?=[A-Za-z0-9_-]+\s*=)""").findAll(header).toList()
            if (starts.isEmpty()) {
                header.trim().takeIf { it.isNotEmpty() && it.none { c -> c == ' ' || c == '=' } }?.let { out += AuthChallenge(it.lowercase(Locale.ROOT), emptyMap()) }
            }
            starts.forEachIndexed { i, m ->
                val end = if (i + 1 < starts.size) starts[i + 1].range.first else header.length
                val body = header.substring(m.range.last + 1, end)
                val params = PARAM.findAll(body).associate { p ->
                    p.groupValues[1].lowercase(Locale.ROOT) to (p.groups[2]?.value?.replace(Regex("""\\(.)"""), "$1") ?: p.groupValues[3])
                }
                out += AuthChallenge(m.groupValues[1].lowercase(Locale.ROOT), params)
            }
            out
        }
    }
}
