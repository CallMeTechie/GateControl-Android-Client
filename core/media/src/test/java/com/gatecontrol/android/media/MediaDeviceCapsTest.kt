package com.gatecontrol.android.media

import com.gatecontrol.android.media.jellyfin.JellyfinDeviceCaps
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A phone's device profile from its decoders: what it plays itself, nothing passed on to a receiver. */
class MediaDeviceCapsTest {

    private fun stream(codec: String) = MediaStream(1, MediaStream.Type.AUDIO, codec = codec, channels = 6)

    @Test
    fun `the decoders found decide`() {
        val caps = MediaDeviceCaps.fromDecoders(
            listOf("video/avc", "VIDEO/HEVC", "video/x-vnd.on2.vp9", "audio/mp4a-latm", "audio/eac3"),
            hdrScreen = true,
            name = "GateControl",
        )
        assertTrue(caps.hevc)
        assertTrue(caps.vp9)
        assertFalse(caps.av1)
        assertTrue(caps.hdr)
        assertFalse(caps.ac3)
        assertTrue(caps.eac3)
        assertTrue(caps.passthrough.isEmpty())
        assertEquals("GateControl", caps.name)
        assertEquals(6, caps.maxAudioChannels)
        assertEquals(2, MediaDeviceCaps.fromDecoders(emptyList(), false, "x", maxAudioChannels = 1).maxAudioChannels)
    }

    @Test
    fun `DTS and TrueHD are converted on a phone, Dolby only without a decoder`() {
        val phone = MediaDeviceCaps.fromDecoders(listOf("video/avc", "audio/mp4a-latm"), hdrScreen = false, name = "GateControl")
        assertEquals(MediaAudioFate.CONVERTED, MediaTracks.fate(stream("dts"), phone))
        assertEquals(MediaAudioFate.CONVERTED, MediaTracks.fate(stream("truehd"), phone))
        assertEquals(MediaAudioFate.CONVERTED, MediaTracks.fate(stream("ac3"), phone))
        assertEquals(MediaAudioFate.DECODED, MediaTracks.fate(stream("aac"), phone))
        val withDolby = MediaDeviceCaps.fromDecoders(listOf("audio/ac3", "audio/eac3"), hdrScreen = false, name = "GateControl")
        assertEquals(MediaAudioFate.DECODED, MediaTracks.fate(stream("ac3"), withDolby))
        // the TV as before: Dolby decoded on the stick, DTS to the receiver
        val tv = JellyfinDeviceCaps(passthrough = setOf("dts"))
        assertEquals(MediaAudioFate.DECODED, MediaTracks.fate(stream("eac3"), tv))
        assertEquals(MediaAudioFate.PASSTHROUGH, MediaTracks.fate(stream("dts"), tv))
    }
}
