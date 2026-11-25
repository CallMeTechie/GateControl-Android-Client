package com.gatecontrol.android.media

import com.gatecontrol.android.media.jellyfin.JellyfinSignIn
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.ProtocolException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.UnknownServiceException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/** Why a Jellyfin address did not work: not reachable, no Jellyfin there, or a certificate. */
class MediaFailureTest {

    @Test
    fun `nothing answered - not reachable`() {
        assertEquals(MediaAddProblem.OFFLINE, MediaFailure.problemOf(SocketTimeoutException("connect timed out")))
        assertEquals(MediaAddProblem.OFFLINE, MediaFailure.problemOf(ConnectException("Failed to connect to /192.168.1.20:8096")))
        assertEquals(MediaAddProblem.OFFLINE, MediaFailure.problemOf(NoRouteToHostException("No route to host")))
        assertEquals(MediaAddProblem.OFFLINE, MediaFailure.problemOf(UnknownHostException("nas")))
        assertEquals(MediaAddProblem.OFFLINE, MediaFailure.problemOf(null))
    }

    @Test
    fun `a certificate the device does not trust`() {
        val untrusted = SSLHandshakeException("Trust anchor for certification path not found.").apply {
            initCause(CertificateException(CertPathValidatorException("Trust anchor for certification path not found.")))
        }
        assertEquals(MediaAddProblem.CERTIFICATE, MediaFailure.problemOf(untrusted))
        assertEquals(MediaAddProblem.CERTIFICATE, MediaFailure.problemOf(SSLPeerUnverifiedException("Hostname 192.168.1.20 not verified")))
    }

    @Test
    fun `something answered, but not as Jellyfin`() {
        // https:// to Jellyfin's http port: no TLS there
        assertEquals(MediaAddProblem.NOT_FOUND, MediaFailure.problemOf(SSLHandshakeException("Unable to parse TLS packet header")))
        assertEquals(MediaAddProblem.NOT_FOUND, MediaFailure.problemOf(ProtocolException("Unexpected status line: ")))
        // http:// to an https port
        assertEquals(MediaAddProblem.NOT_FOUND, MediaFailure.problemOf(IOException("unexpected end of stream on http://192.168.1.20:8920/...")))
    }

    @Test
    fun `http refused by the network security policy is not called unreachable`() {
        val cleartext = UnknownServiceException("CLEARTEXT communication to 192.168.2.151 not permitted by network security policy")
        assertEquals(MediaAddProblem.FAILED, MediaFailure.problemOf(cleartext))
    }

    @Test
    fun `the check of an address tells the reasons apart`() = runTest {
        val signIn = mockk<JellyfinSignIn>()
        val hub = mockk<MediaHub>(relaxed = true) {
            every { signIn(any()) } returns signIn
            every { link } returns MutableStateFlow(MediaLink(atHome = true))
        }
        val setup = MediaSetup(hub)
        coEvery { signIn.server() } returns MediaResult.Offline
        every { signIn.lastFailure } returns ConnectException("Connection refused")
        assertEquals(MediaServerCheck.Problem(MediaAddProblem.OFFLINE), setup.check("192.168.2.151:8096"))
        every { signIn.lastFailure } returns SSLPeerUnverifiedException("Hostname not verified")
        assertEquals(MediaServerCheck.Problem(MediaAddProblem.CERTIFICATE), setup.check("https://192.168.2.151:8920"))
        // an answer, but no Jellyfin (a router's page, another service on the port)
        coEvery { signIn.server() } returns MediaResult.NotFound
        assertEquals(MediaServerCheck.Problem(MediaAddProblem.NOT_FOUND), setup.check("192.168.2.151:8096"))
        coEvery { signIn.server() } returns MediaResult.Failed(200)
        assertEquals(MediaServerCheck.Problem(MediaAddProblem.NOT_FOUND), setup.check("192.168.2.151:8096"))
    }

    @Test
    fun `away a typed home address goes through the tunnel - not reachable there asks about the gateway`() = runTest {
        val signIn = mockk<JellyfinSignIn>()
        val link = MutableStateFlow(MediaLink(atHome = false))
        val hub = mockk<MediaHub>(relaxed = true) {
            every { signIn(any()) } returns signIn
            every { this@mockk.link } returns link
        }
        val setup = MediaSetup(hub)
        coEvery { signIn.server() } returns MediaResult.Offline
        every { signIn.lastFailure } returns SocketTimeoutException("connect timed out")
        assertEquals(MediaServerCheck.Problem(MediaAddProblem.OFFLINE_AWAY), setup.check("http://192.168.2.151:8096"))
        // a certificate stays a certificate, also away
        every { signIn.lastFailure } returns SSLPeerUnverifiedException("Hostname not verified")
        assertEquals(MediaServerCheck.Problem(MediaAddProblem.CERTIFICATE), setup.check("https://192.168.2.151:8920"))
        // the private address is accepted away too (http only for home-network hosts)
        coEvery { signIn.server() } returns MediaResult.Ok(com.gatecontrol.android.media.jellyfin.JellyfinServerInfo("DS918+", "10.10.3", "srv1"))
        assertEquals("http://192.168.2.151:8096", (setup.check("http://192.168.2.151:8096") as MediaServerCheck.Found).address)
        assertEquals("192.168.2.0/24", MediaAddress.subnet24("192.168.2.151"))
        assertNull(MediaAddress.subnet24("nas.local"))
    }

    @Test
    fun `the search away says why it found nothing`() = runTest {
        val link = MutableStateFlow(MediaLink(atHome = false))
        val hub = mockk<MediaHub>(relaxed = true) {
            coEvery { discover() } returns emptyList()
            every { this@mockk.link } returns link
            every { hasLan() } returns true
        }
        val setup = MediaSetup(hub)
        assertEquals(MediaSearch(emptyList(), away = true), setup.searchHome())
        link.value = MediaLink(atHome = true)
        assertEquals(MediaSearch(emptyList(), away = false), setup.searchHome())
        // mobile data only: no Wi-Fi to search in
        every { hub.hasLan() } returns false
        assertEquals(MediaSearch(emptyList(), away = true), setup.searchHome())
    }
}
