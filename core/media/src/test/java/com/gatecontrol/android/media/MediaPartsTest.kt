package com.gatecontrol.android.media

import com.gatecontrol.android.media.jellyfin.JellyfinDeviceCaps
import com.gatecontrol.android.media.jellyfin.JellyfinProfile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Addresses, the device profile, the tracks and the stored sources. */
class MediaPartsTest {

    @Test
    fun `addresses of the home network`() {
        assertEquals("http://192.168.1.20:8096", MediaAddress.normalize("192.168.1.20"))
        assertEquals("http://192.168.1.20:8096", MediaAddress.normalize(" 192.168.1.20:8096/ "))
        assertEquals("http://nas.local:8096/jellyfin", MediaAddress.normalize("http://nas.local:8096/jellyfin"))
        assertEquals("http://nas:8096", MediaAddress.normalize("nas"))
        assertEquals("https://media.example.test", MediaAddress.normalize("https://media.example.test"))
        // plain HTTP only in the home network: the token never goes unencrypted over the internet
        assertNull(MediaAddress.normalize("http://media.example.test:8096"))
        assertNull(MediaAddress.normalize("8.8.8.8:8096"))
        assertNull(MediaAddress.normalize("http://user:pw@192.168.1.20:8096"))
        assertNull(MediaAddress.normalize("192.168.1.20:8096?x=1"))
        assertNull(MediaAddress.normalize(""))
        assertNull(MediaAddress.normalize("nas filme"))
        assertTrue(MediaAddress.isHomeHost("172.20.1.1"))
        assertFalse(MediaAddress.isHomeHost("172.32.1.1"))
    }

    @Test
    fun `the profile - DTS only with an AV receiver`() {
        val plain = JellyfinProfile.directAudio(JellyfinDeviceCaps())
        assertFalse("dts" in plain)
        assertTrue("eac3" in plain)
        val receiver = JellyfinProfile.directAudio(JellyfinDeviceCaps(passthrough = setOf("dts", "truehd", "bogus")))
        assertTrue("dts" in receiver && "dca" in receiver && "truehd" in receiver)
        assertFalse("bogus" in receiver)
        val p = JellyfinProfile.build(JellyfinDeviceCaps(hevc = false), 8_000_000)
        assertEquals(8_000_000L, p.get("MaxStreamingBitrate").asLong)
        val video = p.getAsJsonArray("DirectPlayProfiles").first().asJsonObject.get("VideoCodec").asString
        assertFalse(video.contains("hevc"))
        val subs = p.getAsJsonArray("SubtitleProfiles").map { it.asJsonObject.get("Format").asString + ":" + it.asJsonObject.get("Method").asString }
        assertTrue("subrip:External" in subs)
        assertTrue("pgssub:Encode" in subs)
        assertEquals(0, JellyfinProfile.build(JellyfinDeviceCaps(), 1).getAsJsonArray("CodecProfiles").size())
        assertEquals(1, JellyfinProfile.build(JellyfinDeviceCaps(hdr = false), 1).getAsJsonArray("CodecProfiles").size())
    }

    private val version = MediaVersion(
        id = "v1",
        streams = listOf(
            MediaStream(0, MediaStream.Type.VIDEO, codec = "hevc"),
            MediaStream(1, MediaStream.Type.AUDIO, codec = "dts", language = "ger", channels = 6, isDefault = true),
            MediaStream(2, MediaStream.Type.AUDIO, codec = "truehd", language = "eng", channels = 8),
            MediaStream(3, MediaStream.Type.AUDIO, codec = "aac", language = "ger", channels = 2),
            MediaStream(4, MediaStream.Type.SUBTITLE, codec = "subrip", language = "ger", textual = true, forced = true),
            MediaStream(5, MediaStream.Type.SUBTITLE, codec = "subrip", language = "ger", textual = true),
            MediaStream(6, MediaStream.Type.SUBTITLE, codec = "subrip", language = "eng", textual = true, external = true),
        ),
        defaultAudio = 1,
    )

    @Test
    fun `sound and subtitles - labels, fate, remembered per series`() {
        assertEquals("Deutsch", MediaTracks.languageName("ger", java.util.Locale.GERMANY))
        assertEquals("Deutsch", MediaTracks.languageName("deu", java.util.Locale.GERMANY))
        assertEquals("English", MediaTracks.languageName("eng", java.util.Locale.ENGLISH))
        assertNull(MediaTracks.languageName("und"))
        assertNull(MediaTracks.languageName("qqq"))
        assertEquals("DTS 5.1", MediaTracks.audioLabel(version.audio[0]))
        assertEquals("TrueHD 7.1", MediaTracks.audioLabel(version.audio[1]))
        val caps = JellyfinDeviceCaps(passthrough = setOf("dts"))
        assertEquals(MediaAudioFate.PASSTHROUGH, MediaTracks.fate(version.audio[0], caps))
        assertEquals(MediaAudioFate.CONVERTED, MediaTracks.fate(version.audio[1], caps))
        assertEquals(MediaAudioFate.DECODED, MediaTracks.fate(version.audio[2], caps))

        assertEquals(1, MediaTracks.pickAudio(version, null))
        assertEquals(2, MediaTracks.pickAudio(version, MediaTrackChoice("eng", null, false)))
        assertNull(MediaTracks.pickSubtitle(version, null))
        assertEquals(MediaPlay.SUBTITLES_OFF, MediaTracks.pickSubtitle(version, MediaTrackChoice(null, null, true)))
        // not the forced one
        assertEquals(5, MediaTracks.pickSubtitle(version, MediaTrackChoice("ger", "ger", false)))
        assertEquals(MediaTrackChoice("eng", "eng", false), MediaTracks.choiceOf(version, 2, 6))

        // the place of a stream among the file's own tracks of its kind
        assertEquals(1, MediaTracks.ordinal(version, 2))
        assertEquals(0, MediaTracks.ordinal(version, 4))
        assertNull(MediaTracks.ordinal(version, 6))
        assertNull(MediaTracks.ordinal(version, 99))
    }

    private class MapSecrets : MediaSecrets {
        val map = HashMap<String, String>()
        override fun get(key: String) = map[key]
        override fun put(key: String, value: String) {
            map[key] = value
        }
        override fun remove(key: String) {
            map.remove(key)
        }
    }

    @Test
    fun `stored sources - kept, replaced when signed in again, the token dropped`() {
        val secrets = MapSecrets()
        val store = MediaSourceStore(secrets)
        assertTrue(store.sources.value.isEmpty())
        val a = MediaSource("a", MediaSourceKind.JELLYFIN, "DS918+", "http://192.168.1.20:8096", "u1", "Anna", "tok1", "srv1")
        store.save(a)
        store.setAlwaysOriginal("a", true)
        // the same server and person again: replaced, "immer Original" kept
        val again = store.save(a.copy(id = "b", token = "tok2"))
        assertEquals("a", again.id)
        assertTrue(again.alwaysOriginal)
        assertEquals(listOf("tok2"), store.sources.value.map { it.token })
        assertFalse(store.sources.value.toString().contains("tok2"))

        val reread = MediaSourceStore(secrets)
        assertEquals(store.sources.value, reread.sources.value)
        reread.dropToken("a")
        assertNull(reread.source("a")?.token)
        reread.remove("a")
        assertTrue(MediaSourceStore(secrets).sources.value.isEmpty())

        val id = store.deviceId
        assertEquals(id, MediaSourceStore(secrets).deviceId)

        store.setTrackChoice("s9", MediaTrackChoice("ger", null, true))
        assertEquals(MediaTrackChoice("ger", null, true), MediaSourceStore(secrets).trackChoice("s9"))
        assertNull(store.trackChoice("other"))
    }

    @Test
    fun `stored shares - password kept, SMB and WebDAV of one folder paired, unpaired when one goes`() {
        val secrets = MapSecrets()
        val store = MediaSourceStore(secrets)
        val smb = store.save(MediaSource("smb1", MediaSourceKind.SMB, "nas", "smb://nas/filme", userName = "kino", password = "geheim pw"))
        assertNull(smb.pairWith)
        assertTrue(smb.ready)
        assertFalse(smb.toString().contains("geheim"))
        // another folder: not paired
        store.save(MediaSource("dav0", MediaSourceKind.WEBDAV, "x", "https://192.168.1.20:5006/serien", userName = "kino", password = "pw"))
        val dav = store.save(MediaSource("dav1", MediaSourceKind.WEBDAV, "x", "https://192.168.1.20:5006/Filme", userName = "kino", password = "pw"))
        assertEquals("smb1", dav.pairWith)
        assertEquals("dav1", store.source("smb1")?.pairWith)
        assertNull(store.source("dav0")?.pairWith)
        // kept as it was, encrypted storage only
        val reread = MediaSourceStore(secrets)
        assertEquals("geheim pw", reread.source("smb1")?.password)
        assertEquals("smb://nas/filme", reread.source("smb1")?.address)
        assertEquals("dav1", reread.source("smb1")?.pairWith)
        // the same share again (new password): replaced, still paired
        val again = reread.save(MediaSource("smb2", MediaSourceKind.SMB, "n", "smb://nas/filme", userName = "kino", password = "neu"))
        assertEquals("smb1", again.id)
        assertEquals("dav1", again.pairWith)
        reread.remove("dav1")
        assertNull(reread.source("smb1")?.pairWith)
        assertEquals(listOf("smb1", "dav0"), MediaSourceStore(secrets).sources.value.map { it.id })
    }

    @Test
    fun `progress of share files - kept, the oldest go, gone with the pairing`() {
        val secrets = MapSecrets()
        val store = MediaSourceStore(secrets)
        store.setProgress("smb1|a.mkv", ShareProgress(60_000, 6_000_000, false, 10))
        store.setProgress("smb1|b.mkv", ShareProgress(0, 0, true, 11))
        store.setProgress("other|c.mkv", ShareProgress(1, 2, false, 12))
        val reread = MediaSourceStore(secrets)
        assertEquals(ShareProgress(60_000, 6_000_000, false, 10), reread.progress("smb1|a.mkv"))
        assertEquals(setOf("smb1|a.mkv", "smb1|b.mkv"), reread.all("smb1|").keys)
        repeat(MediaSourceStore.MAX_PROGRESS) { reread.setProgress("x|$it", ShareProgress(1, 2, false, 100L + it)) }
        // the oldest went first
        assertNull(reread.progress("smb1|a.mkv"))
        assertEquals(MediaSourceStore.MAX_PROGRESS, reread.all("").size)
        reread.forgetAll()
        assertTrue(MediaSourceStore(secrets).all("").isEmpty())
        assertTrue(secrets.map.isEmpty())
    }

    @Test
    fun `stored sources - broken or foreign entries are left out`() {
        assertTrue(MediaSourceStore.decode("not json").isEmpty())
        val list = MediaSourceStore.decode(
            """{"sources":[{"id":"a","kind":"jellyfin","name":"N","address":"http://192.168.1.20:8096"},
                {"id":"b","kind":"nfs","name":"N","address":"http://192.168.1.21"},
                {"id":"c","kind":"jellyfin","name":"N","address":"http://media.example.test"},
                {"id":"d","kind":"smb","name":"N","address":"smb://media.example.test/filme"},
                {"id":"e","kind":"smb","name":"N","address":"smb://nas/filme","pairWith":"gone"},
                {"id":"f","kind":"webdav","name":"N","address":"http://dav.example.test/filme"}]}""",
        )
        assertEquals(listOf("a", "e"), list.map { it.id })
        // a partner that is not there is none
        assertNull(list[1].pairWith)
    }

    @Test
    fun `titles - left, progress, a page's next`() {
        val t = MediaTitle("m", "s", MediaTitle.Kind.MOVIE, "Kobaltnacht", runtimeMs = 100_000, positionMs = 25_000)
        assertEquals(75_000L, t.leftMs)
        assertEquals(0.25f, t.progress)
        assertTrue(t.resumable && t.playable)
        assertFalse(t.copy(played = true).resumable)
        assertNull(MediaPage(listOf(t), total = 1, start = 0).next)
        assertEquals(1, MediaPage(listOf(t), total = 5, start = 0).next)
    }
}
