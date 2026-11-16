package com.gatecontrol.android.remote

import com.gatecontrol.android.BuildConfig
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The helper's input as the phone takes it (contract „Fernzugriff Stufe 6“ §2): checked again, mapped, limited. */
class ScreenInputTest {

    private fun o(json: String): JsonObject = JsonParser.parseString(json).asJsonObject

    @Test
    fun `taps, long presses, paths, scrolls, keys and text from the relay`() {
        assertEquals(ControlInput.Tap(0.25, 0.75), ScreenInput.parse(o("""{"t":"input","k":"tap","x":0.25,"y":0.75}""")))
        assertEquals(ControlInput.LongPress(0.5, 0.5, 650), ScreenInput.parse(o("""{"k":"long","x":0.5,"y":0.5}""")))
        assertEquals(ControlInput.LongPress(0.5, 0.5, 1200), ScreenInput.parse(o("""{"k":"long","x":0.5,"y":0.5,"ms":1200}""")))
        assertEquals(ControlInput.Swipe(listOf(0.0 to 0.0, 1.0 to 0.5), 300), ScreenInput.parse(o("""{"k":"swipe","pts":[[0,0],[1,0.5]],"ms":300}""")))
        assertEquals(ControlInput.Scroll(0.5, 0.5, 0.0, -0.2), ScreenInput.parse(o("""{"k":"scroll","x":0.5,"y":0.5,"dx":0,"dy":-0.2}""")))
        assertEquals(ControlInput.Key("recents"), ScreenInput.parse(o("""{"k":"key","key":"recents"}""")))
        assertEquals(ControlInput.Text("Grüße 👋"), ScreenInput.parse(o("""{"k":"text","text":"Grüße 👋"}""")))
    }

    @Test
    fun `anything outside the contract is dropped`() {
        for (bad in listOf(
            """{"k":"tap","x":1.5,"y":0}""", """{"k":"tap","x":"0.5","y":0.5}""", """{"k":"tap"}""",
            """{"k":"long","x":0,"y":0,"ms":100}""", """{"k":"long","x":0,"y":0,"ms":650.5}""",
            """{"k":"swipe","pts":[[0,0]],"ms":300}""", """{"k":"swipe","pts":[[0,0],[2,0]],"ms":300}""", """{"k":"swipe","pts":[[0,0],[1,1]]}""",
            """{"k":"scroll","x":0.5,"y":0.5,"dx":0,"dy":0}""", """{"k":"scroll","x":0.5,"y":0.5,"dx":0,"dy":3}""",
            """{"k":"key","key":"power"}""", """{"k":"text","text":""}""", """{"k":"text","text":"a\nb"}""", """{"k":"shell","cmd":"reboot"}""",
        )) assertNull(ScreenInput.parse(o(bad)), bad)
        val long = "x".repeat(501)
        assertNull(ScreenInput.parse(o("""{"k":"text","text":"$long"}""")))
        assertNull(ScreenInput.parse(o("""{"k":"swipe","pts":[${(1..33).joinToString(",") { "[0,0]" }}],"ms":300}""")))
    }

    @Test
    fun `gestures in pixels of the display — a scroll swipes against the wheel`() {
        assertEquals(GesturePlan(listOf(539.5f to 1279.5f), 50), ScreenInput.plan(ControlInput.Tap(0.5, 0.5), 1080, 2560))
        assertEquals(1200L, ScreenInput.plan(ControlInput.LongPress(0.0, 1.0, 1200), 1080, 2560)!!.durationMs)
        val sw = ScreenInput.plan(ControlInput.Swipe(listOf(0.0 to 0.0, 1.0 to 1.0), 300), 1080, 2560)!!
        assertEquals(listOf(0f to 0f, 1079f to 2559f), sw.points)
        // wheel down (dy > 0): the content moves on, the finger goes up
        val sc = ScreenInput.plan(ControlInput.Scroll(0.5, 0.5, 0.0, 0.2), 1001, 1001)!!
        assertEquals(listOf(500f to 500f, 500f to 300f), sc.points)
        assertEquals(0f, ScreenInput.plan(ControlInput.Scroll(0.5, 0.1, 0.0, 0.33), 1001, 1001)!!.points[1].second, "stays on the screen")
        assertNull(ScreenInput.plan(ControlInput.Key("back"), 1080, 2560))
        assertNull(ScreenInput.plan(ControlInput.Tap(0.5, 0.5), 0, 0))
    }

    @Test
    fun `text goes in at the cursor, backspace takes one character or the selection`() {
        assertEquals("Hallo Welt" to 7, ScreenInput.insert("Hallo elt", 6, 6, "W"))
        assertEquals("ab" to 2, ScreenInput.insert("a", -1, -1, "b"))
        assertEquals("Hi!" to 3, ScreenInput.insert("Hallo", 1, 5, "i!"))
        assertEquals("ab" to 2, ScreenInput.insert("a", 9, 9, "b"), "a broken selection appends")
        assertEquals("Hallo" to 5, ScreenInput.backspace("Hallo!", 6, 6))
        assertEquals("Ho" to 1, ScreenInput.backspace("Hallo", 1, 4))
        assertEquals("" to 0, ScreenInput.backspace("", 0, 0))
        assertEquals("a" to 1, ScreenInput.backspace("a👋", 3, 3), "a whole emoji")
    }

    @Test
    fun `the phone limits on its own — 30 a second, text 5 a second and 4000 characters in 10 s`() {
        var t = 0L
        val l = InputLimiter { t }
        assertEquals(30, (1..45).count { l.allow(ControlInput.Tap(0.5, 0.5)) })
        t += 1000
        assertEquals(5, (1..8).count { l.allow(ControlInput.Text("abc")) })
        t += 1000
        val big = ControlInput.Text("x".repeat(500))
        assertEquals(5, (1..6).count { l.allow(big) })
        t += 1000
        assertEquals(2, (1..5).count { l.allow(big) }, "4000 characters in 10 s")
        t += 10_000
        assertTrue(l.allow(big))
    }

    @Test
    fun `the hello says control, the phone goes down to view, a failed input names no content`() {
        val hello = JsonParser.parseString(ScreenProtocol.hello(720, 1280, 15, false, "1.121.0", control = true)).asJsonObject
        assertTrue(hello["control"].asBoolean)
        assertFalse(JsonParser.parseString(ScreenProtocol.hello(720, 1280, 15, false, "1.121.0")).asJsonObject["control"].asBoolean)
        assertEquals("""{"t":"mode","mode":"view","reason":"sender"}""", ScreenProtocol.viewOnly("sender"))
        assertEquals("""{"t":"mode","mode":"view","reason":"sender"}""", ScreenProtocol.viewOnly("<x>"))
        assertEquals("""{"t":"input_fail","k":"text","reason":"no_field"}""", ScreenProtocol.inputFail("text", "no_field"))
        assertEquals("""{"t":"input_fail","reason":"blocked"}""", ScreenProtocol.inputFail("<k>", "whatever"))
    }

    @Test
    fun `what the relay says about the mode and the input`() {
        assertEquals(ScreenProtocol.Message.Ready("Tom", null, true), ScreenProtocol.parse("""{"t":"ready","role":"sender","mode":"control","peer":{"name":"Tom"}}"""))
        assertEquals(ScreenProtocol.Message.Ready("Tom", null, false), ScreenProtocol.parse("""{"t":"ready","role":"sender","mode":"view","modeReason":"viewer_only","peer":{"name":"Tom"}}"""))
        assertEquals(ScreenProtocol.Message.Mode(false, "sender_no_control"), ScreenProtocol.parse("""{"t":"mode","mode":"view","reason":"sender_no_control"}"""))
        assertEquals(ScreenProtocol.Message.Input(ControlInput.Key("back")), ScreenProtocol.parse("""{"t":"input","k":"key","key":"back"}"""))
        assertNull(ScreenProtocol.parse("""{"t":"input","k":"key","key":"power"}"""))
    }

    @Test
    fun `control exists only in the GitHub build`() {
        assertEquals(BuildConfig.DISTRIBUTION == "github", ScreenControl.supported)
        assertEquals("com.gatecontrol.android.remote.control.GcScreenControlService", ScreenControl.SERVICE_CLASS)
    }
}
