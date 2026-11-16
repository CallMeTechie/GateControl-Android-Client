package com.gatecontrol.android.remote

import com.gatecontrol.android.familie.PluginAvailability
import com.gatecontrol.android.network.ClientPlugin
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The relay `gc-screen.v1` as the phone speaks it (contract „Fernzugriff Stufe 5“ §2/§3). */
class ScreenProtocolTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    // SPS of Constrained Baseline, level 3.1 (profile 0x42, constraints 0xE0, level 0x1F), then PPS, as MediaCodec hands it
    private val csd = bytes(0, 0, 0, 1, 0x67, 0x42, 0xE0, 0x1F, 0x8D, 0x68, 0, 0, 0, 1, 0x68, 0xCE, 0x3C, 0x80)
    private val idr = bytes(0, 0, 0, 1, 0x65, 0x88, 0x84, 0x00)

    @Test
    fun `a frame is the twelve-byte head and the access unit unchanged`() {
        val f = ScreenProtocol.frame(true, 0x0102030405060708L, idr)
        assertEquals(12 + idr.size, f.size)
        assertArrayEquals(bytes(1, 1, 0, 0, 1, 2, 3, 4, 5, 6, 7, 8), f.copyOfRange(0, 12))
        assertArrayEquals(idr, f.copyOfRange(12, f.size))
        assertEquals(true to 0x0102030405060708L, ScreenProtocol.head(f))
        val d = ScreenProtocol.frame(false, 33_333L, idr, 4, 4)
        assertEquals(false to 33_333L, ScreenProtocol.head(d))
        assertArrayEquals(bytes(0x65, 0x88, 0x84, 0x00), d.copyOfRange(12, d.size))
        assertNull(ScreenProtocol.head(bytes(2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 9)), "another version")
        assertNull(ScreenProtocol.head(ByteArray(12)), "no payload")
        // a negative time never becomes a huge one
        assertEquals(false to 0L, ScreenProtocol.head(ScreenProtocol.frame(false, -5L, idr)))
    }

    @Test
    fun `the codec string comes from the SPS — avc1 PPCCLL`() {
        assertEquals("avc1.42E01F", ScreenProtocol.codecString(csd))
        // three-byte start codes, main profile 4.0
        assertEquals("avc1.4D0028", ScreenProtocol.codecString(bytes(0, 0, 1, 0x67, 0x4D, 0x00, 0x28, 0xAB, 0, 0, 1, 0x68, 0xEE)))
        assertNull(ScreenProtocol.codecString(idr), "no SPS")
        assertNull(ScreenProtocol.codecString(bytes(0, 0, 1, 0x67, 0x42)), "too short")
        assertTrue(ScreenProtocol.hasSps(csd + idr))
        assertFalse(ScreenProtocol.hasSps(idr))
        assertEquals(listOf(7, 8), ScreenProtocol.nalUnits(csd).map { it.first })
    }

    @Test
    fun `the encoder size keeps the screen's shape, even numbers, within the relay's limits`() {
        assertEquals(720 to 1280, ScreenProtocol.encodeSize(1080, 1920))
        assertEquals(1280 to 576, ScreenProtocol.encodeSize(2400, 1080))
        // a small screen is not scaled up
        assertEquals(480 to 800, ScreenProtocol.encodeSize(480, 800))
        // a tall 4K screen: the short edge stays ≤ 1080 even at the full edge
        val (w, h) = ScreenProtocol.encodeSize(2160, 3840, 1920)
        assertTrue(w <= 1080 && h <= 1920 && w % 2 == 0 && h % 2 == 0, "$w x $h")
        assertEquals(720 to 1280, ScreenProtocol.encodeSize(0, 0))
    }

    @Test
    fun `hello, config, state and the token header`() {
        val hello = JsonParser.parseString(ScreenProtocol.hello(720, 1280, 15, false, "1.120.0")).asJsonObject
        assertEquals("hello", hello["t"].asString)
        assertEquals("sender", hello["role"].asString)
        assertEquals(720, hello["width"].asInt)
        assertEquals("display", hello["scope"].asString)
        assertEquals("android", hello["app"].asJsonObject["platform"].asString)
        assertEquals(30, JsonParser.parseString(ScreenProtocol.hello(720, 1280, 90, true, "x")).asJsonObject["fps"].asInt)
        assertEquals("""{"t":"config","codec":"avc1.42E01F","width":720,"height":1280}""", ScreenProtocol.config("avc1.42E01F", 720, 1280))
        assertEquals("""{"t":"state","paused":true,"reason":"locked"}""", ScreenProtocol.state(true, "locked"))
        assertEquals("gc-screen.v1, token.abc", ScreenProtocol.protocolHeader("abc"))
    }

    @Test
    fun `what the relay says — unknown types are ignored, codes are short words only`() {
        assertEquals(ScreenProtocol.Message.Ready("Tom Berger", 4_000_000), ScreenProtocol.parse("""{"t":"ready","role":"sender","mode":"view","peer":{"name":"Tom Berger"},"limits":{"maxBitrate":4000000}}"""))
        assertEquals(ScreenProtocol.Message.Viewer(true, "Tom"), ScreenProtocol.parse("""{"t":"viewer","state":"joined","name":"Tom"}"""))
        assertEquals(ScreenProtocol.Message.Viewer(false, null), ScreenProtocol.parse("""{"t":"viewer","state":"left"}"""))
        assertEquals(ScreenProtocol.Message.Keyframe, ScreenProtocol.parse("""{"t":"keyframe"}"""))
        assertEquals(ScreenProtocol.Message.Rate(300_000), ScreenProtocol.parse("""{"t":"rate","bitrate":1}"""))
        assertEquals(ScreenProtocol.Message.End("viewer"), ScreenProtocol.parse("""{"t":"end","reason":"viewer"}"""))
        assertEquals(ScreenProtocol.Message.End("other"), ScreenProtocol.parse("""{"t":"end","reason":"<script>"}"""))
        assertEquals(ScreenProtocol.Message.Error("rate_exceeded"), ScreenProtocol.parse("""{"t":"error","code":"rate_exceeded"}"""))
        assertNull(ScreenProtocol.parse("""{"t":"input","x":1}"""))
        assertNull(ScreenProtocol.parse("nope"))
    }

    @Test
    fun `the card shows only with the plugin Fernzugriff`() {
        assertTrue(PluginAvailability.from(listOf(ClientPlugin(REMOTE_PLUGIN, "Fernzugriff", "1.3.0", null))).remote)
        assertFalse(PluginAvailability.from(listOf(ClientPlugin("gatecontrol-door", "Tür", "1", null))).remote)
    }
}
