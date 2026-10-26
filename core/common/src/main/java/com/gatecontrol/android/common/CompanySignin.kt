package com.gatecontrol.android.common

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Result of "Mit Microsoft anmelden" in the browser, delivered back to the
 * app as `gatecontrol://signin?handoff=…&state=…` or
 * `gatecontrol://signin?error=…&state=…` (server contract: app sign-in,
 * GateControl ≥ 1.201.0). Exactly one of [handoff] / [error] is set.
 *
 * Pure parsing (java.net only) so it is unit-testable without Android.
 * [toString] never shows the handoff value.
 */
data class SigninRedirect(val handoff: String?, val error: String?, val state: String) {

    override fun toString(): String =
        "SigninRedirect(handoff=${if (handoff != null) "***" else "null"}, error=$error, state=***)"

    companion object {
        /** Handoff / state as the server creates them: URL-safe (or base64), bounded. */
        private val TOKEN_RE = Regex("^[A-Za-z0-9._~+/=-]{8,512}$")
        private val ERROR_RE = Regex("^[a-z_]{1,40}$")

        /** True for any `gatecontrol://signin` link, valid or not. */
        fun isSigninLink(raw: String?): Boolean {
            if (raw.isNullOrBlank()) return false
            val uri = try {
                URI(raw.trim())
            } catch (_: Exception) {
                return false
            }
            return uri.scheme.equals("gatecontrol", ignoreCase = true) && uri.host.equals("signin", ignoreCase = true)
        }

        /** Parses the redirect; null when it is not a well-formed sign-in result. */
        fun parse(raw: String?): SigninRedirect? {
            if (!isSigninLink(raw)) return null
            val uri = URI(raw!!.trim())
            if (uri.rawUserInfo != null || uri.port != -1) return null
            if (!uri.rawPath.isNullOrEmpty() && uri.rawPath != "/") return null
            val params = parseQuery(uri.rawQuery ?: return null) ?: return null
            val state = params["state"]?.takeIf { TOKEN_RE.matches(it) } ?: return null
            val handoff = params["handoff"]
            val error = params["error"]
            return when {
                handoff != null && error == null ->
                    if (TOKEN_RE.matches(handoff)) SigninRedirect(handoff, null, state) else null
                error != null && handoff == null ->
                    if (ERROR_RE.matches(error)) SigninRedirect(null, error, state) else null
                else -> null
            }
        }

        /** Query parameters; null when one appears twice (ambiguous, never sent by the server). */
        private fun parseQuery(rawQuery: String): Map<String, String>? {
            val out = HashMap<String, String>()
            for (part in rawQuery.split("&")) {
                if (part.isEmpty()) continue
                val idx = part.indexOf('=')
                if (idx <= 0) continue
                val key = try {
                    URLDecoder.decode(part.substring(0, idx), "UTF-8")
                } catch (_: Exception) {
                    return null
                }
                val value = try {
                    URLDecoder.decode(part.substring(idx + 1), "UTF-8")
                } catch (_: Exception) {
                    return null
                }
                if (out.put(key, value) != null) return null
            }
            return out
        }
    }
}

/**
 * PKCE (RFC 7636, S256) and the browser start URL of "Mit Microsoft anmelden".
 */
object CompanySignin {

    /** The only redirect the Android app registers (manifest: gatecontrol://signin). */
    const val REDIRECT_URI = "gatecontrol://signin"

    private val random = SecureRandom()
    private val VERIFIER_RE = Regex("^[A-Za-z0-9._~-]{43,128}$")

    /** 64 random bytes as base64url: 86 characters from [A-Za-z0-9-_]. */
    fun newVerifier(): String = randomToken(64)

    /** Random `state` value that ties the browser result to this app run. */
    fun newState(): String = randomToken(32)

    fun isValidVerifier(verifier: String): Boolean = VERIFIER_RE.matches(verifier)

    /** `base64url(sha256(verifier))` without padding. */
    fun challenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    /**
     * `{server}/auth/oidc/{id}/start?client=app&redirect_uri=…&state=…&challenge=…`;
     * [serverUrl] is the normalized https server (scheme://host[:port]).
     */
    fun startUrl(serverUrl: String, directoryId: Int, state: String, challenge: String): String {
        fun enc(v: String) = URLEncoder.encode(v, "UTF-8")
        return "${serverUrl.trimEnd('/')}/auth/oidc/$directoryId/start" +
            "?client=app&redirect_uri=${enc(REDIRECT_URI)}&state=${enc(state)}&challenge=${enc(challenge)}"
    }

    private fun randomToken(bytes: Int): String {
        val buf = ByteArray(bytes)
        random.nextBytes(buf)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf)
    }
}
