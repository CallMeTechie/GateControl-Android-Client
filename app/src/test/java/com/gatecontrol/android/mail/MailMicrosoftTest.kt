package com.gatecontrol.android.mail

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.URLDecoder

/** Contract D: PKCE in the browser, the redirect `msauth://com.gatecontrol.client/<hash>`. */
class MailMicrosoftTest {

    @Test
    fun `PKCE - the challenge of RFC 7636, fresh verifiers`() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", MailMicrosoft.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        val v = MailMicrosoft.newVerifier()
        assertTrue(MailMicrosoft.isVerifier(v), v)
        assertEquals(86, v.length)
        assertNotEquals(v, MailMicrosoft.newVerifier())
        assertNotEquals(MailMicrosoft.newState(), MailMicrosoft.newState())
        assertFalse(MailMicrosoft.isVerifier("short"))
    }

    @Test
    fun `the redirect - Base64 SHA-1 of the signature, URL-encoded`() {
        // SHA-1("abc") = a9993e36…, its Base64 has + and =
        val hash = MailMicrosoft.certHash("abc".toByteArray())
        assertEquals("qZk+NkcGgWq6PiVxeFDCbJzQ2J0=", hash)
        assertEquals("msauth://com.gatecontrol.client/qZk%2BNkcGgWq6PiVxeFDCbJzQ2J0%3D", MailMicrosoft.redirectUri(hash))
    }

    @Test
    fun `the sign-in page`() {
        val url = MailMicrosoft.authorizeUrl(
            authority = "https://login.microsoftonline.com/common/",
            clientId = "client-1",
            scopes = listOf("openid", "offline_access", "Mail.Send"),
            redirectUri = "msauth://com.gatecontrol.client/qZk%2BNkcGgWq6PiVxeFDCbJzQ2J0%3D",
            state = "st",
            challenge = "ch",
        )!!
        val uri = URI(url)
        assertEquals("login.microsoftonline.com", uri.host)
        assertEquals("/common/oauth2/v2.0/authorize", uri.path)
        val q = uri.rawQuery.split('&').associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        assertEquals("client-1", q["client_id"])
        assertEquals("code", q["response_type"])
        assertEquals("openid offline_access Mail.Send", q["scope"])
        assertEquals("msauth://com.gatecontrol.client/qZk%2BNkcGgWq6PiVxeFDCbJzQ2J0%3D", q["redirect_uri"], "the redirect exactly as registered")
        assertEquals("S256", q["code_challenge_method"])
        assertEquals("select_account", q["prompt"])
        assertTrue(MailMicrosoft.authorizeUrl("https://login.microsoftonline.com/common", "c", listOf("openid"), "r", "s", "c", loginHint = "a@b.de")!!.contains("login_hint=a%40b.de"))
        assertNull(MailMicrosoft.authorizeUrl("http://login.microsoftonline.com/common", "c", listOf("openid"), "r", "s", "c"), "never without TLS")
        assertNull(MailMicrosoft.authorizeUrl("https://x/common?y=1", "c", listOf("openid"), "r", "s", "c"))
    }

    @Test
    fun `the browser's answer`() {
        val ok = MailMicrosoft.parse("msauth://com.gatecontrol.client/qZk%2BNkcGgWq6PiVxeFDCbJzQ2J0%3D?code=M.C1_abc&state=st&session_state=x")!!
        assertEquals("M.C1_abc", ok.code)
        assertEquals("st", ok.state)
        assertNull(ok.error)
        val denied = MailMicrosoft.parse("msauth://com.gatecontrol.client/h?error=access_denied&error_description=The+user+cancelled&state=st")!!
        assertNull(denied.code)
        assertEquals("access_denied", denied.error)
        assertEquals("The user cancelled", denied.description)
        assertNull(MailMicrosoft.parse("msauth://com.other.app/h?code=x&state=st"), "another app's redirect")
        assertNull(MailMicrosoft.parse("https://com.gatecontrol.client/h?code=x"))
        assertNull(MailMicrosoft.parse("msauth://com.gatecontrol.client/h"), "neither code nor error")
        assertTrue(MailMicrosoft.isRedirect("MSAUTH://com.gatecontrol.client/x"))
        assertFalse(MailMicrosoft.isRedirect(null))
    }
}
