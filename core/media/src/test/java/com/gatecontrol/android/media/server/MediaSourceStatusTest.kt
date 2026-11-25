package com.gatecontrol.android.media.server

import com.gatecontrol.android.media.MediaRoute
import com.gatecontrol.android.media.MediaSource
import com.gatecontrol.android.media.MediaSourceKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Medien › Quellen as a display: each source once (a pair as its SMB half) with what is wrong. */
class MediaSourceStatusTest {
    private val jf = MediaSource("jf-1", MediaSourceKind.JELLYFIN, "DS918+", "http://192.168.1.20:8096", managed = true, remoteId = "jf-1", linkedUserId = "a71e")
    private val smb = MediaSource("sh-1", MediaSourceKind.SMB, "Filme", "smb://192.168.1.20/filme", managed = true, remoteId = "sh-1", pairWith = "sh-1~webdav")
    private val dav = MediaSource("sh-1~webdav", MediaSourceKind.WEBDAV, "Filme", "https://192.168.1.20:5006/filme", managed = true, remoteId = "sh-1", pairWith = "sh-1")

    @Test
    fun `status of each source`() {
        val state = MediaServerState(pairProblems = mapOf("jf-1" to "quick_connect_off"))
        val rows = MediaSourcesLogic.rows(listOf(jf, smb, dav), state) { MediaRoute.VPN }
        assertEquals(listOf("jf-1", "sh-1"), rows.map { it.source.id })
        assertEquals("quick_connect_off", rows[0].problem)
        assertEquals(dav, rows[1].partner)
        assertEquals(MediaSourcesText.NO_PASSWORD, rows[1].problem)
        assertFalse(rows[1].ready)

        val ok = MediaSourcesLogic.rows(listOf(jf.copy(token = "t"), smb.copy(password = "pw"), dav.copy(password = "pw")), MediaServerState(pairing = setOf("x"))) { MediaRoute.HOME }
        assertTrue(ok.all { it.ready })
        assertNull(ok[0].problem)
        val pairing = MediaSourcesLogic.rows(listOf(jf), MediaServerState(pairing = setOf("jf-1"))) { MediaRoute.HOME }.single()
        assertTrue(pairing.pairing)
        assertNull(pairing.problem)
        assertEquals("no_mapping", MediaSourcesLogic.rows(listOf(jf.copy(problem = "no_mapping")), MediaServerState()) { MediaRoute.HOME }.single().problem)
    }

    @Test
    fun `minutes, hours, days ago`() {
        val now = 10_000_000_000L
        assertNull(MediaSourcesText.ago(now, 0))
        assertEquals('n' to 0L, MediaSourcesText.ago(now, now - 10_000))
        assertEquals('m' to 3L, MediaSourcesText.ago(now, now - 3 * 60_000))
        assertEquals('h' to 2L, MediaSourcesText.ago(now, now - 2 * 3_600_000))
        assertEquals('d' to 31L, MediaSourcesText.ago(now, now - 31 * MediaServerState.DAY_MS))
    }

    @Test
    fun `the state survives a restart`() {
        val s = MediaServerState(
            known = true, tab = true, rev = "r7", lastContact = 123, maxOfflineDays = 60, offlineHintDays = 20,
            network = MediaNetworkRule(probes = listOf(MediaHomeProbe.Jellyfin("http://192.168.1.20:8096/System/Info/Public", "4f1ca9"), MediaHomeProbe.Tls("https://192.168.1.20:5006/filme", "ab".repeat(32)))),
            migration = listOf(MediaMigration("uuid-1", MediaSourceKind.JELLYFIN, "Jellyfin · DS918+", "DS918+")),
            secretRevs = mapOf("sh-1" to 3),
            unavailable = true, unavailableSince = 99,
        )
        assertEquals(s, MediaServerState.decode(MediaServerState.encode(s)))
        assertEquals(MediaServerState(), MediaServerState.decode("nonsense"))
    }
}
