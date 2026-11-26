package com.gatecontrol.android.media

import android.media.MediaCodecInfo.CodecProfileLevel
import com.gatecontrol.android.media.jellyfin.JellyfinDeviceCaps
import com.gatecontrol.android.media.jellyfin.JellyfinProfile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
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

    private fun conditions(caps: JellyfinDeviceCaps, codec: String): Map<String, String> =
        JellyfinProfile.build(caps, 1).getAsJsonArray("CodecProfiles").map { it.asJsonObject }
            .firstOrNull { it.get("Codec")?.asString == codec }
            ?.getAsJsonArray("Conditions")?.associate { c -> c.asJsonObject.get("Property").asString to c.asJsonObject.get("Value").asString }
            .orEmpty()

    @Test
    fun `profiles, levels and sizes of the decoders - above them nothing plays directly`() {
        val caps = MediaDeviceCaps.fromCodecs(
            listOf(
                MediaDecoder(
                    "video/avc",
                    listOf(CodecProfileLevel.AVCProfileHigh to CodecProfileLevel.AVCLevel51, CodecProfileLevel.AVCProfileMain to CodecProfileLevel.AVCLevel42),
                    maxWidth = 4096,
                    maxHeight = 2304,
                ),
                // only Main: a 10-bit HEVC (Main 10) is not played directly
                MediaDecoder("video/hevc", listOf(CodecProfileLevel.HEVCProfileMain to CodecProfileLevel.HEVCMainTierLevel51), maxWidth = 1920, maxHeight = 1088),
                MediaDecoder("video/av01", listOf(CodecProfileLevel.AV1ProfileMain10 to CodecProfileLevel.AV1Level51)),
                MediaDecoder("audio/mp4a-latm"),
            ),
            hdrScreen = false,
            name = "GateControl",
        )
        assertEquals(51, caps.h264.maxLevel)
        assertFalse(caps.h264.tenBit)
        assertEquals(4096, caps.h264.maxWidth)
        assertEquals(153, caps.hevcCaps.maxLevel)
        assertFalse(caps.hevcCaps.tenBit)
        assertTrue(caps.av1Caps.tenBit)
        assertFalse(caps.vp9)
        // no decoder for them on this phone: converted
        assertFalse(caps.mpeg4)
        assertFalse("mp2" in JellyfinProfile.directAudio(caps))
        assertFalse("mpeg4" in JellyfinProfile.videoCodecs(caps))

        val h264 = conditions(caps, "h264")
        assertEquals("high|main|baseline|constrained baseline", h264["VideoProfile"])
        assertEquals("8", h264["VideoBitDepth"])
        assertEquals("51", h264["VideoLevel"])
        assertEquals("4096", h264["Width"])
        val hevc = conditions(caps, "hevc")
        assertEquals("main", hevc["VideoProfile"])
        assertEquals("8", hevc["VideoBitDepth"])
        assertEquals("153", hevc["VideoLevel"])
        assertEquals("1920", hevc["Height"])
        assertEquals("10", conditions(caps, "av1")["VideoBitDepth"])
        // no condition the device does not need: VP9 is not offered at all
        assertTrue(conditions(caps, "vp9").isEmpty())
    }

    @Test
    fun `with a Main 10 decoder HEVC 10-bit plays directly, unknown limits pass`() {
        val caps = MediaDeviceCaps.fromCodecs(
            listOf(MediaDecoder("video/hevc", listOf(CodecProfileLevel.HEVCProfileMain10 to CodecProfileLevel.HEVCHighTierLevel52))),
            hdrScreen = true,
            name = "GateControl",
        )
        val hevc = conditions(caps, "hevc")
        assertEquals("main|main 10", hevc["VideoProfile"])
        assertEquals("10", hevc["VideoBitDepth"])
        assertEquals("156", hevc["VideoLevel"])
        assertFalse(hevc.containsKey("Width"))
        // the TV's defaults: H.264 8 bits only, HEVC 10 bits
        val tv = JellyfinDeviceCaps()
        assertEquals("8", conditions(tv, "h264")["VideoBitDepth"])
        assertEquals("10", conditions(tv, "hevc")["VideoBitDepth"])
        assertTrue(JellyfinProfile.describe(caps).contains("hevc=L156/10bit"))
    }

    @Test
    fun `Android's levels in Jellyfin's count`() {
        assertEquals(41, MediaDeviceCaps.avcLevel(CodecProfileLevel.AVCLevel41))
        assertEquals(62, MediaDeviceCaps.avcLevel(CodecProfileLevel.AVCLevel62))
        assertEquals(123, MediaDeviceCaps.hevcLevel(CodecProfileLevel.HEVCMainTierLevel41))
        assertEquals(150, MediaDeviceCaps.hevcLevel(CodecProfileLevel.HEVCHighTierLevel5))
        assertNull(MediaDeviceCaps.avcLevel(-1))
    }
}
