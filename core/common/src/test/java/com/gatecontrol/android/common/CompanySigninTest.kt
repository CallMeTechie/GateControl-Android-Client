package com.gatecontrol.android.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CompanySigninTest {

    @Test
    fun `parses the handoff the server redirects to`() {
        assertEquals(
            SigninRedirect(handoff = "h0ff_AbC-123.xyz", error = null, state = "st4te-value_1"),
            SigninRedirect.parse("gatecontrol://signin?handoff=h0ff_AbC-123.xyz&state=st4te-value_1"),
        )
    }

    @Test
    fun `parses an error redirect and decodes values`() {
        assertEquals(
            SigninRedirect(handoff = null, error = "not_linked", state = "st4te/value=="),
            SigninRedirect.parse("gatecontrol://signin?error=not_linked&state=st4te%2Fvalue%3D%3D"),
        )
        assertEquals("access_denied", SigninRedirect.parse("GATECONTROL://SIGNIN/?state=abcdefgh1&error=access_denied")?.error)
    }

    @Test
    fun `rejects links that are not a well-formed sign-in result`() {
        // other scheme / host
        assertNull(SigninRedirect.parse("https://signin?handoff=abcdefgh1&state=abcdefgh1"))
        assertNull(SigninRedirect.parse("gatecontrol://enroll?handoff=abcdefgh1&state=abcdefgh1"))
        // missing or invalid state
        assertNull(SigninRedirect.parse("gatecontrol://signin?handoff=abcdefgh1"))
        assertNull(SigninRedirect.parse("gatecontrol://signin?handoff=abcdefgh1&state=short"))
        // neither or both of handoff / error
        assertNull(SigninRedirect.parse("gatecontrol://signin?state=abcdefgh1"))
        assertNull(SigninRedirect.parse("gatecontrol://signin?handoff=abcdefgh1&error=failed&state=abcdefgh1"))
        // duplicated parameter, odd characters, odd error codes
        assertNull(SigninRedirect.parse("gatecontrol://signin?handoff=abcdefgh1&state=abcdefgh1&state=zzzzzzzz2"))
        assertNull(SigninRedirect.parse("gatecontrol://signin?handoff=abc%20defgh1&state=abcdefgh1"))
        assertNull(SigninRedirect.parse("gatecontrol://signin?error=%3Cscript%3E&state=abcdefgh1"))
        // user info, port or path
        assertNull(SigninRedirect.parse("gatecontrol://x@signin?handoff=abcdefgh1&state=abcdefgh1"))
        assertNull(SigninRedirect.parse("gatecontrol://signin:80?handoff=abcdefgh1&state=abcdefgh1"))
        assertNull(SigninRedirect.parse("gatecontrol://signin/other?handoff=abcdefgh1&state=abcdefgh1"))
        // garbage
        assertNull(SigninRedirect.parse("gatecontrol://signin?%zz=1&state=abcdefgh1"))
        assertNull(SigninRedirect.parse(""))
        assertNull(SigninRedirect.parse(null))
        assertNull(SigninRedirect.parse("not a uri at all"))
    }

    @Test
    fun `recognises sign-in links for routing`() {
        assertTrue(SigninRedirect.isSigninLink("gatecontrol://signin?error=failed&state=abcdefgh1"))
        assertTrue(SigninRedirect.isSigninLink("gatecontrol://signin"))
        assertFalse(SigninRedirect.isSigninLink("gatecontrol://enroll?url=https%3A%2F%2Fa.b&code=AB12CD34EF567890"))
        assertFalse(SigninRedirect.isSigninLink(null))
    }

    @Test
    fun `toString hides handoff and state`() {
        val text = SigninRedirect.parse("gatecontrol://signin?handoff=secret-handoff-1&state=secret-state-1").toString()
        assertFalse(text.contains("secret-handoff-1"))
        assertFalse(text.contains("secret-state-1"))
    }

    @Test
    fun `challenge is base64url sha256 of the verifier (RFC 7636 example)`() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            CompanySignin.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test
    fun `verifier and state are random and well-formed`() {
        val a = CompanySignin.newVerifier()
        val b = CompanySignin.newVerifier()
        assertNotEquals(a, b)
        assertTrue(CompanySignin.isValidVerifier(a))
        assertEquals(86, a.length)
        assertFalse(CompanySignin.isValidVerifier("too-short"))
        assertNotEquals(CompanySignin.newState(), CompanySignin.newState())
        // a state from here survives the redirect parser
        val state = CompanySignin.newState()
        assertEquals(state, SigninRedirect.parse("gatecontrol://signin?error=failed&state=$state")?.state)
    }

    @Test
    fun `start url carries client, redirect, state and challenge`() {
        assertEquals(
            "https://gate.example.com:8443/auth/oidc/3/start?client=app&redirect_uri=gatecontrol%3A%2F%2Fsignin" +
                "&state=st4te-1&challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            CompanySignin.startUrl("https://gate.example.com:8443/", 3, "st4te-1", "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"),
        )
    }
}
