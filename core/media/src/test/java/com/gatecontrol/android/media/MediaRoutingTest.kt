package com.gatecontrol.android.media

import com.gatecontrol.android.media.server.MediaHomeProbe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The Netz-Regel: at home the LAN, away only the VPN (or the admin's HTTPS address); the home check by probes. */
class MediaRoutingTest {

    private val vpnOnly = MediaSource("jf-1", MediaSourceKind.JELLYFIN, "DS918+", "http://192.168.1.20:8096", managed = true)
    private val direct = vpnOnly.copy(awayAddress = "https://jellyfin.example.test")

    @Test
    fun `at home always the LAN, also while the VPN is up`() {
        assertEquals(MediaRoute.HOME, MediaRouting.route(vpnOnly, atHome = true, vpnUp = true))
        assertEquals(MediaRoute.HOME, MediaRouting.route(direct, atHome = true, vpnUp = false))
    }

    @Test
    fun `away only through the VPN - without it nothing`() {
        assertEquals(MediaRoute.VPN, MediaRouting.route(vpnOnly, atHome = false, vpnUp = true))
        assertEquals(MediaRoute.BLOCKED, MediaRouting.route(vpnOnly, atHome = false, vpnUp = false))
        // not known yet counts as away
        assertEquals(MediaRoute.BLOCKED, MediaRouting.route(vpnOnly, atHome = null, vpnUp = false))
        assertTrue(MediaRouting.needsVpn(listOf(direct, vpnOnly)))
        assertFalse(MediaRouting.needsVpn(listOf(direct)))
    }

    @Test
    fun `away direct where the admin allowed it - its HTTPS address`() {
        assertEquals(MediaRoute.DIRECT, MediaRouting.route(direct, atHome = false, vpnUp = false))
        assertEquals("https://jellyfin.example.test", MediaRouting.effective(direct, MediaRoute.DIRECT).address)
        assertEquals("http://192.168.1.20:8096", MediaRouting.effective(direct, MediaRoute.HOME).address)
        // the VPN up anyway: through it to the home address
        assertEquals(MediaRoute.VPN, MediaRouting.route(direct, atHome = false, vpnUp = true))
        val smb = MediaSource("sh-1", MediaSourceKind.SMB, "Filme", "smb://192.168.1.20/filme", awayAddress = "https://nas.example.test/filme")
        assertEquals("smb://192.168.1.20/filme", MediaRouting.effective(smb, MediaRoute.DIRECT).address, "SMB never over the internet")
    }

    @Test
    fun `the probes only confirm - the first local network where one answers`() = runTest {
        val probes = listOf(MediaHomeProbe.Jellyfin("http://192.168.1.20:8096/System/Info/Public", "4F1C-A9"), MediaHomeProbe.Tls("https://192.168.1.20:5006/filme", "ab".repeat(32)))
        val asked = ArrayList<String>()
        val found = MediaHomeCheck.first(listOf("hotel-wifi", "home-wifi"), probes) { net, p ->
            asked += "$net:${p::class.simpleName}"
            net == "home-wifi" && p is MediaHomeProbe.Tls
        }
        assertEquals("home-wifi", found)
        assertEquals(listOf("hotel-wifi:Jellyfin", "hotel-wifi:Tls", "home-wifi:Jellyfin", "home-wifi:Tls"), asked)
        assertNull(MediaHomeCheck.first(listOf("home-wifi"), emptyList()) { _, _ -> true })
        assertTrue(MediaHomeCheck.sameId("4f1ca9", "4F1C-A9"))
        assertFalse(MediaHomeCheck.sameId("4f1ca8", "4f1ca9"))
        assertFalse(MediaHomeCheck.sameId(null, "4f1ca9"))
    }

    @Test
    fun `the store keeps the server's fields`() {
        val s = direct.copy(remoteId = "jf-1", deviceId = "gc-1234", deviceName = "GateControl · TV", linkedUserId = "a71e", downloads = false, problem = "quick_connect_off")
        val dav = MediaSource("sh-1~webdav", MediaSourceKind.WEBDAV, "Filme", "https://192.168.1.20:5006/filme", managed = true, remoteId = "sh-1", certSha256 = "ab".repeat(32), secretRev = 3)
        val back = MediaSourceStore.decode(MediaSourceStore.encode(listOf(s, dav)))
        assertEquals(listOf(s, dav), back)
        // an away address without HTTPS is not taken
        assertNull(MediaSourceStore.decode(MediaSourceStore.encode(listOf(s.copy(awayAddress = "http://jellyfin.example.test")))).single().awayAddress)
    }
}
