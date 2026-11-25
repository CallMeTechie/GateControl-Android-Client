package com.gatecontrol.android.media.server

import com.gatecontrol.android.media.MediaSource
import com.gatecontrol.android.media.MediaSourceKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The server's list merged with the device's sources: kept access, takeover (question 33 a), revocation. */
class MediaServerMergeTest {

    private val jf = MediaServerSource(
        id = "jf-1", kind = MediaServerSource.Kind.JELLYFIN, name = "DS918+", serverId = "4F1CA9", homeAddress = "http://192.168.1.20:8096",
        userId = "a71e", userName = "mila", deviceId = "gc-7Hq2mXk4Rb9tLp0sVw3N", deviceName = "GateControl · Pixel von Mila",
    )
    private val sh = MediaServerSource(
        id = "sh-1", kind = MediaServerSource.Kind.SHARE, name = "Filme", smb = "smb://192.168.1.20/filme",
        webdav = "https://192.168.1.20:5006/filme", shareUser = "medien-lesen", secretRev = 3,
    )

    private fun list(vararg s: MediaServerSource, media: Boolean = true) = MediaServerList(rev = "r1", at = null, media = media, sources = s.toList())

    @Test
    fun `new sources of the server - Jellyfin without access yet, a share as an SMB and WebDAV pair`() {
        val r = MediaServerMerge.merge(emptyList(), list(jf, sh))
        val j = r.list.first { it.kind == MediaSourceKind.JELLYFIN }
        assertEquals("jf-1", j.id)
        assertTrue(j.managed)
        assertNull(j.token)
        assertEquals("gc-7Hq2mXk4Rb9tLp0sVw3N", j.deviceId)
        assertEquals("a71e", j.linkedUserId)
        val smb = r.list.first { it.kind == MediaSourceKind.SMB }
        val dav = r.list.first { it.kind == MediaSourceKind.WEBDAV }
        assertEquals("sh-1", smb.id)
        assertEquals("sh-1~webdav", dav.id)
        assertEquals(dav.id, smb.pairWith)
        assertEquals(smb.id, dav.pairWith)
        assertEquals("sh-1", dav.remoteId)
        assertNull(smb.password, "fetched with secret")
        assertTrue(r.gone.isEmpty())
        assertTrue(r.migration.isEmpty())
    }

    @Test
    fun `the access and the password stay while nothing changed, go when the user or the password changed`() {
        val first = MediaServerMerge.merge(emptyList(), list(jf, sh)).list.map {
            when (it.kind) {
                MediaSourceKind.JELLYFIN -> it.copy(token = "tok", userId = "a71e", userName = "mila", alwaysOriginal = true)
                else -> it.copy(password = "pw", secretRev = 3)
            }
        }
        val same = MediaServerMerge.merge(first, list(jf, sh)).list
        assertEquals("tok", same.first { it.kind == MediaSourceKind.JELLYFIN }.token)
        assertTrue(same.first { it.kind == MediaSourceKind.JELLYFIN }.alwaysOriginal, "the device's own setting stays")
        assertEquals("pw", same.first { it.kind == MediaSourceKind.SMB }.password)

        // the admin linked another Jellyfin user: signed in again; a new password: fetched again
        val changed = MediaServerMerge.merge(first, list(jf.copy(userId = "b22f", userName = "jonas"), sh.copy(secretRev = 4))).list
        assertNull(changed.first { it.kind == MediaSourceKind.JELLYFIN }.token)
        assertEquals("b22f", changed.first { it.kind == MediaSourceKind.JELLYFIN }.linkedUserId)
        assertNull(changed.first { it.kind == MediaSourceKind.SMB }.password)
        // no mapping any more: no access either
        assertNull(MediaServerMerge.merge(first, list(jf.copy(userId = null, problem = "no_mapping"), sh)).list.first { it.kind == MediaSourceKind.JELLYFIN }.token)
    }

    @Test
    fun `a source no longer in the list goes with its downloads`() {
        val first = MediaServerMerge.merge(emptyList(), list(jf, sh)).list
        val r = MediaServerMerge.merge(first, list(jf))
        assertEquals(listOf("jf-1"), r.list.map { it.id })
        assertEquals(setOf("sh-1", "sh-1~webdav"), r.gone.toSet())
    }

    @Test
    fun `takeover - the same Jellyfin server and the same share keep their ids, the rest waits for the sheet`() {
        val oldJf = MediaSource("uuid-1", MediaSourceKind.JELLYFIN, "DS918+", "http://192.168.1.20:8096", userId = "u0", token = "old", serverId = "4f1ca9")
        val oldSmb = MediaSource("uuid-2", MediaSourceKind.SMB, "", "smb://192.168.1.20/filme", userName = "anna", password = "pw0")
        val other = MediaSource("uuid-3", MediaSourceKind.WEBDAV, "", "https://192.168.1.30/privat", userName = "x", password = "y")
        val r = MediaServerMerge.merge(listOf(oldJf, oldSmb, other), list(jf, sh))
        val j = r.list.first { it.kind == MediaSourceKind.JELLYFIN }
        assertEquals("uuid-1", j.id, "downloads stay (same id)")
        assertTrue(j.managed)
        assertNull(j.token, "the plugin's own access replaces the old one")
        assertEquals(listOf("uuid-1"), r.signOut.map { it.id })
        val smb = r.list.first { it.kind == MediaSourceKind.SMB }
        assertEquals("uuid-2", smb.id, "progress stays (same id)")
        assertEquals("pw0", smb.password, "until the server's password is fetched")
        assertEquals(0, smb.secretRev)
        assertEquals("medien-lesen", smb.userName)
        // not in the server: stays until the sheet was seen
        assertTrue(r.list.any { it.id == "uuid-3" && !it.managed })
        assertEquals(
            listOf(
                MediaMigration("uuid-1", MediaSourceKind.JELLYFIN, "Jellyfin · DS918+", "DS918+"),
                MediaMigration("uuid-2", MediaSourceKind.SMB, "\\\\192.168.1.20\\filme", "Filme"),
                MediaMigration("uuid-3", MediaSourceKind.WEBDAV, "192.168.1.30/privat", null),
            ),
            r.migration,
        )
        assertTrue(r.gone.isEmpty())
        val (managed, legacy) = MediaServerMerge.dropLegacy(r.list)
        assertEquals(listOf("uuid-3"), legacy.map { it.id })
        assertFalse(managed.any { !it.managed })
    }

    @Test
    fun `without the right Medien nothing stays, also not the device's own sources`() {
        val first = MediaServerMerge.merge(emptyList(), list(jf)).list
        val own = MediaSource("uuid-9", MediaSourceKind.JELLYFIN, "Alt", "http://192.168.1.40:8096", token = "t", serverId = "zz")
        val r = MediaServerMerge.merge(first + own, list(media = false))
        assertTrue(r.list.isEmpty())
        assertEquals(setOf("jf-1", "uuid-9"), r.gone.toSet())
        assertEquals(listOf("uuid-9"), r.signOut.map { it.id })
        assertTrue(r.migration.isEmpty())
    }

    @Test
    fun `away direct and the pinned certificate come along`() {
        val r = MediaServerMerge.merge(emptyList(), list(jf.copy(awayAddress = "https://jellyfin.example.test"), sh.copy(certSha256 = "ab".repeat(32), awayAddress = "https://nas.example.test/filme"))).list
        assertEquals("https://jellyfin.example.test", r.first { it.kind == MediaSourceKind.JELLYFIN }.awayAddress)
        val dav = r.first { it.kind == MediaSourceKind.WEBDAV }
        assertEquals("ab".repeat(32), dav.certSha256)
        assertEquals("https://nas.example.test/filme", dav.awayAddress)
        assertNull(r.first { it.kind == MediaSourceKind.SMB }.awayAddress, "SMB never goes over the internet")
    }
}
