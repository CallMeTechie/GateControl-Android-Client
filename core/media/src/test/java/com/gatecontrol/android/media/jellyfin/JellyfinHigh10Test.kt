package com.gatecontrol.android.media.jellyfin

import android.media.MediaCodecInfo.CodecProfileLevel
import com.gatecontrol.android.media.MediaDecoder
import com.gatecontrol.android.media.MediaDeviceCaps
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A file that loaded forever on a phone ("Direkt · 1080p", the duration shown, no picture): H.264 High 10 (10 bits,
 * Level 4.1, 1080p50, about 3 Mbit/s), AAC stereo, MKV, no subtitles — as Jellyfin's media info has it (no path or
 * name). Hardly any Android decoder takes AVC High 10: it must never be played directly (nor copied when remuxed).
 */
class JellyfinHigh10Test {

    private val mediaSource = """
        {"Id":"v1","Container":"mkv","Bitrate":3200000,"RunTimeTicks":14400000000,"SupportsDirectPlay":true,
         "SupportsDirectStream":true,"SupportsTranscoding":true,"DefaultAudioStreamIndex":1,
         "MediaStreams":[
           {"Index":0,"Type":"Video","Codec":"h264","Profile":"High 10","Level":41,"BitDepth":10,"PixelFormat":"yuv420p10le",
            "Width":1920,"Height":1080,"AverageFrameRate":50,"RealFrameRate":50,"BitRate":3000000,"IsInterlaced":false,
            "VideoRange":"SDR","VideoRangeType":"SDR"},
           {"Index":1,"Type":"Audio","Codec":"aac","Profile":"LC","Channels":2,"ChannelLayout":"stereo","SampleRate":48000,
            "BitRate":192000,"IsDefault":true}
         ]}
    """.trimIndent()

    /** A usual phone: H.264 up to High at 5.2 in hardware (no High 10), HEVC Main 10. */
    private val phone = MediaDeviceCaps.fromCodecs(
        listOf(
            MediaDecoder(
                "video/avc",
                listOf(CodecProfileLevel.AVCProfileHigh to CodecProfileLevel.AVCLevel52, CodecProfileLevel.AVCProfileMain to CodecProfileLevel.AVCLevel52),
                maxWidth = 4096,
                maxHeight = 2304,
            ),
            MediaDecoder("video/hevc", listOf(CodecProfileLevel.HEVCProfileMain10 to CodecProfileLevel.HEVCMainTierLevel51), maxWidth = 4096, maxHeight = 2304),
            MediaDecoder("audio/mp4a-latm"),
        ),
        hdrScreen = false,
        name = "GateControl",
    )

    @Test
    fun `the media info is read - 10 bits from the profile and the pixel format`() {
        val v = JellyfinJson.version(JsonParser.parseString(mediaSource).asJsonObject)!!
        val video = v.video!!
        assertEquals("h264", video.codec)
        assertEquals("High 10", video.profile)
        assertEquals(41, video.level)
        assertEquals(10, video.bitDepth)
        assertEquals("yuv420p10le", video.pixelFormat)
        // without BitDepth the pixel format says it
        val noBits = JsonParser.parseString("""{"Index":0,"Type":"Video","Codec":"h264","PixelFormat":"yuv420p10le"}""").asJsonObject
        assertEquals(10, JellyfinJson.stream(noBits)!!.bitDepth)
        assertEquals(8, JellyfinJson.bitsOf("yuv420p"))
        assertEquals(10, JellyfinJson.bitsOf("p010le"))
        assertNull(JellyfinJson.bitsOf("rgb24"))
    }

    @Test
    fun `the phone does not play it - seen before asking, and the profile tells Jellyfin so`() {
        val v = JellyfinJson.version(JsonParser.parseString(mediaSource).asJsonObject)!!
        assertEquals("H.264 10-Bit (High 10)", JellyfinProfile.videoProblem(v.video, phone))
        // the device profile: H.264 only 8 bits and its 8-bit profiles, so Jellyfin converts the picture (no copy)
        val h264 = JellyfinProfile.build(phone, 1).getAsJsonArray("CodecProfiles").map { it.asJsonObject }.first { it.get("Codec")?.asString == "h264" }
        val conditions = h264.getAsJsonArray("Conditions").associate { c -> c.asJsonObject.get("Property").asString to c.asJsonObject.get("Value").asString }
        assertEquals("8", conditions["VideoBitDepth"])
        assertFalse(conditions.getValue("VideoProfile").split('|').contains("high 10"))
        assertEquals("52", conditions["VideoLevel"])
    }

    @Test
    fun `what plays stays direct - 8-bit H dot 264, HEVC Main 10 with its decoder`() {
        val v = JellyfinJson.version(JsonParser.parseString(mediaSource).asJsonObject)!!.video!!
        assertNull(JellyfinProfile.videoProblem(v.copy(profile = "High", bitDepth = 8, pixelFormat = "yuv420p"), phone))
        assertNull(JellyfinProfile.videoProblem(v.copy(codec = "hevc", profile = "Main 10", level = 150, bitDepth = 10), phone))
        // a level above the decoder's
        assertEquals("H.264 Level 61 (High)", JellyfinProfile.videoProblem(v.copy(profile = "High", bitDepth = 8, level = 61), phone))
        // a phone with a High 10 decoder plays it itself
        val high10 = MediaDeviceCaps.fromCodecs(
            listOf(MediaDecoder("video/avc", listOf(CodecProfileLevel.AVCProfileHigh10 to CodecProfileLevel.AVCLevel51))),
            hdrScreen = false,
            name = "GateControl",
        )
        assertNull(JellyfinProfile.videoProblem(v, high10))
        assertTrue(high10.h264.tenBit)
        // AV1 without a decoder at all
        assertEquals("AV1 (Main)", JellyfinProfile.videoProblem(v.copy(codec = "av1", profile = "Main"), phone))
    }
}
