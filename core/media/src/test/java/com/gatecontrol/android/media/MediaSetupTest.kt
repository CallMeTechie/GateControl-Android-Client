package com.gatecontrol.android.media

import com.gatecontrol.android.media.jellyfin.JellyfinAuth
import com.gatecontrol.android.media.jellyfin.JellyfinQuickConnect
import com.gatecontrol.android.media.jellyfin.JellyfinServerInfo
import com.gatecontrol.android.media.jellyfin.JellyfinSignIn
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The steps of adding a source without a page: the address, Quick Connect with a code that runs out, a share. */
@OptIn(ExperimentalCoroutinesApi::class)
class MediaSetupTest {

    private val store = mockk<MediaSourceStore>(relaxed = true)
    private val signIn = mockk<JellyfinSignIn>()
    private val hub = mockk<MediaHub>(relaxed = true) {
        every { store } returns this@MediaSetupTest.store
        every { signIn(any()) } returns signIn
    }
    private val setup = MediaSetup(hub)

    @Test
    fun `the address - a server found, an old one, nonsense`() = runTest {
        coEvery { signIn.server() } returns MediaResult.Ok(JellyfinServerInfo("DS918+", "10.10.3", "srv1"))
        val found = setup.check("192.168.1.20") as MediaServerCheck.Found
        assertEquals("http://192.168.1.20:8096", found.address)
        assertEquals("DS918+", found.server.name)
        coEvery { signIn.server() } returns MediaResult.Ok(JellyfinServerInfo("Alt", "10.8.13", "srv2"))
        assertEquals(MediaServerCheck.Problem(MediaAddProblem.OLD_SERVER), setup.check("192.168.1.21"))
        assertEquals(MediaServerCheck.Problem(MediaAddProblem.BAD_ADDRESS), setup.check("nonsense address"))
    }

    @Test
    fun `Quick Connect - a code that runs out is replaced, then confirmed and kept`() = runTest {
        coEvery { signIn.quickConnectEnabled() } returns true
        val first = JellyfinQuickConnect("482913", "sec1")
        val second = JellyfinQuickConnect("731205", "sec2")
        coEvery { signIn.quickConnect() } returnsMany listOf(MediaResult.Ok(first), MediaResult.Ok(second))
        coEvery { signIn.confirmed(first) } returns MediaResult.NotFound
        coEvery { signIn.confirmed(second) } returnsMany listOf(MediaResult.Ok(false), MediaResult.Ok(true))
        coEvery { signIn.signIn(second) } returns MediaResult.Ok(JellyfinAuth("tok", "u1", "Anna", null))
        val codes = mutableListOf<String?>()
        var signingIn = false
        val end = setup.quickConnect("http://192.168.1.20:8096", onCode = { codes += it }, onSigningIn = { signingIn = true })
        assertEquals(listOf("482913", null, "731205"), codes)
        assertTrue(signingIn)
        val auth = ((end as MediaQuickConnectEnd.SignedIn).auth as MediaResult.Ok).value
        val saved = slot<MediaSource>()
        every { store.save(capture(saved)) } answers { saved.captured }
        val source = setup.saveJellyfin(auth, JellyfinServerInfo("DS918+", "10.10.3", "srv1"), "http://192.168.1.20:8096")
        assertEquals("tok", source.token)
        assertEquals("srv1", source.serverId)
        assertEquals("DS918+", source.name)
        verify { hub.select(source.id) }
    }

    @Test
    fun `Quick Connect switched off on the server`() = runTest {
        coEvery { signIn.quickConnectEnabled() } returns false
        assertEquals(MediaQuickConnectEnd.Problem(MediaAddProblem.NO_QUICK_CONNECT), setup.quickConnect("http://192.168.1.20:8096", onCode = {}))
    }

    @Test
    fun `a share - the login tried, kept with its partner`() = runTest {
        coEvery { hub.tryShare(MediaSourceKind.SMB, "smb://nas/filme", "kino", "falsch") } returns MediaResult.Unauthorized
        coEvery { hub.tryShare(MediaSourceKind.SMB, "smb://nas/filme", "kino", "geheim") } returns MediaResult.Ok(Unit)
        val partner = MediaSource("dav1", MediaSourceKind.WEBDAV, "dav", "https://192.168.1.20:5006/filme", pairWith = "smb9")
        val saved = slot<MediaSource>()
        every { store.save(capture(saved)) } answers { saved.captured.copy(pairWith = "dav1") }
        every { store.source("dav1") } returns partner
        assertEquals(MediaShareAdded.Problem(MediaAddProblem.WRONG_LOGIN), setup.connectShare(MediaSourceKind.SMB, "\\\\nas\\filme", " kino ", "falsch"))
        val added = setup.connectShare(MediaSourceKind.SMB, "\\\\nas\\filme", "kino", "geheim") as MediaShareAdded.Saved
        assertEquals(partner, added.partner)
        assertEquals("\\\\nas\\filme", saved.captured.name)
        assertEquals(MediaShareAdded.Problem(MediaAddProblem.BAD_ADDRESS), setup.connectShare(MediaSourceKind.SMB, "smb://media.example.test/filme", "kino", "geheim"))
        assertNull(setup.connectShare(MediaSourceKind.SMB, "smb://nas/filme", "", "").let { (it as? MediaShareAdded.Saved)?.source?.password })
    }
}
