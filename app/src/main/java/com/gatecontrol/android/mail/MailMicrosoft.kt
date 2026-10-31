package com.gatecontrol.android.mail

import android.content.Context
import android.content.pm.PackageManager
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * "Mit Microsoft anmelden" for a mail account (contract D): authorization code with PKCE in the system browser
 * (Custom Tab), answered to `msauth://com.gatecontrol.client/<hash>` — the hash is the URL-encoded Base64 SHA-1 of the
 * app's signing certificate, as Microsoft registers Android apps. The server redeems the code (`POST
 * accounts/microsoft`), the refresh token never reaches the phone. Pure except [signatureHash] of the running app.
 */
object MailMicrosoft {

    const val SCHEME = "msauth"
    /** The redirect's host: the app's package as published (contract D). */
    const val HOST = "com.gatecontrol.client"
    const val DEFAULT_AUTHORITY = "https://login.microsoftonline.com/common"
    val DEFAULT_SCOPES = listOf("openid", "profile", "email", "offline_access", "User.Read", "Mail.ReadWrite", "Mail.Send")
    /** Since Mail 1.2.0: the rights of calendars and contacts (`Account.missingScopes`). */
    const val CALENDAR_SCOPE = "Calendars.ReadWrite"
    const val CONTACTS_SCOPE = "Contacts.ReadWrite"

    private val random = SecureRandom()
    private val VERIFIER = Regex("^[A-Za-z0-9._~-]{43,128}$")

    private fun token(bytes: Int): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes).also { random.nextBytes(it) })

    /** PKCE `code_verifier`: 43…128 characters of the unreserved set (64 random bytes → 86). */
    fun newVerifier(): String = token(64)

    fun newState(): String = token(24)

    fun isVerifier(v: String): Boolean = VERIFIER.matches(v)

    /** `code_challenge` (S256): base64url(sha256(verifier)) without padding. */
    fun challenge(verifier: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    /** Base64 (standard, with padding) of the SHA-1 of a signing certificate. */
    fun certHash(cert: ByteArray): String = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest(cert))

    /** `msauth://com.gatecontrol.client/<URL-encoded hash>`. */
    fun redirectUri(hash: String): String = "$SCHEME://$HOST/" + URLEncoder.encode(hash, "UTF-8")

    /**
     * The sign-in page: `<authority>/oauth2/v2.0/authorize` with the client id, the scopes (space separated), the
     * redirect, state and challenge. [loginHint] fills in the address (re-login of an account). Null when the
     * authority is not an https address.
     */
    fun authorizeUrl(
        authority: String,
        clientId: String,
        scopes: List<String>,
        redirectUri: String,
        state: String,
        challenge: String,
        loginHint: String? = null,
    ): String? {
        val base = authority.trimEnd('/')
        val uri = runCatching { URI(base) }.getOrNull() ?: return null
        if (uri.scheme != "https" || uri.host.isNullOrEmpty() || uri.rawQuery != null) return null
        fun enc(v: String) = URLEncoder.encode(v, "UTF-8").replace("+", "%20")
        return buildString {
            append(base).append("/oauth2/v2.0/authorize")
            append("?client_id=").append(enc(clientId))
            append("&response_type=code")
            append("&redirect_uri=").append(enc(redirectUri))
            append("&response_mode=query")
            append("&scope=").append(enc(scopes.joinToString(" ")))
            append("&state=").append(enc(state))
            append("&code_challenge=").append(enc(challenge))
            append("&code_challenge_method=S256")
            if (loginHint != null) append("&login_hint=").append(enc(loginHint)) else append("&prompt=select_account")
        }
    }

    /** The browser's answer: a code, or an error with its description. */
    data class Answer(val state: String?, val code: String?, val error: String?, val description: String?)

    fun isRedirect(raw: String?): Boolean {
        val uri = raw?.let { runCatching { URI(it) }.getOrNull() } ?: return false
        return uri.scheme.equals(SCHEME, ignoreCase = true) && uri.host.equals(HOST, ignoreCase = true)
    }

    /** `msauth://com.gatecontrol.client/<hash>?code=…&state=…` (or `error=…`); null for anything else. */
    fun parse(raw: String?): Answer? {
        if (!isRedirect(raw)) return null
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        val q = uri.rawQuery ?: uri.rawFragment ?: return null
        val map = HashMap<String, String>()
        for (part in q.split('&')) {
            if (part.isEmpty()) continue
            val k = part.substringBefore('=')
            val v = runCatching { URLDecoder.decode(part.substringAfter('=', ""), "UTF-8") }.getOrNull() ?: return null
            if (k !in map) map[k] = v
        }
        val code = map["code"]?.takeIf { it.isNotEmpty() && it.length <= 4096 }
        val error = map["error"]?.takeIf { it.isNotEmpty() }
        if (code == null && error == null) return null
        return Answer(map["state"], code, error, map["error_description"]?.take(300))
    }

    /**
     * The running app's signature hash, null when Android does not tell: the current signing certificate (the last of
     * a rotated key's history; the first signer of an app signed by several).
     */
    fun signatureHash(context: Context): String? = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val signing = info.signingInfo ?: return null
        val cert = if (signing.hasMultipleSigners()) signing.apkContentsSigners?.firstOrNull() else signing.signingCertificateHistory?.lastOrNull()
        cert?.toByteArray()?.let(::certHash)
    }.getOrNull()
}
