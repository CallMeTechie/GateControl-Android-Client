package com.gatecontrol.android.chat

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * „Speichern“ of an Ortsnachricht: the body of `POST place-messages` / `PUT place-messages/:id` for the case that failed
 * (a Familie rule place, 100 m, a text with emojis, a person's WhatsApp way fixed, every time, Mo–Fr 06:00–09:00,
 * „Vor dem Senden fragen“) checked field by field against the messenger's own checks ([PluginRules], a copy of
 * `create`/`cleanPlace`/`cleanText`/`cleanWindow` in plugins/gatecontrol-messenger/server/placemsgs.js, 1.27.0).
 *
 * The one rule that does not hold is the plugin's: Familie's places are `home` and `place:<id>` (gatecontrol-familie
 * services.places), but placemsgs.js takes a string familieId only as `[A-Za-z0-9_-]{1,64}` — `place:3` is refused
 * with `400 invalid`. [PluginRules] checks the id as Familie gives it; [PLUGIN_1_27_0_FAMILIE_ID] keeps the refusing
 * pattern as the evidence.
 */
class ChatPlaceSaveBodyTest {

    /** The checks of placemsgs.js `create` (1.27.0), with familieId as Familie's places give it. */
    private object PluginRules {
        val KEYS = setOf("deviceId", "trigger", "place", "text", "conversationId", "way", "repeat", "window", "ask", "enabled", "bridged", "channels", "people")
        val DEVICE_RE = Regex("^[A-Za-z0-9._:-]{1,128}$")
        val WAY_RE = Regex("^[a-z]{2,20}$")
        val HHMM_RE = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")
        val FAMILIE_ID = Regex("^[A-Za-z0-9_:.-]{1,64}$")

        private fun JsonElement?.isInt() = this != null && isJsonPrimitive && asJsonPrimitive.isNumber && asDouble == Math.floor(asDouble) && !asDouble.isInfinite()
        private fun JsonElement?.isNum() = this != null && isJsonPrimitive && asJsonPrimitive.isNumber
        private fun JsonElement?.isStr() = this != null && isJsonPrimitive && asJsonPrimitive.isString
        private fun JsonElement?.isBool() = this != null && isJsonPrimitive && asJsonPrimitive.isBoolean
        private fun JsonElement?.absent() = this == null || isJsonNull

        /** null: the plugin takes the body; else the field it refuses (`invalid`). */
        fun refusal(b: JsonObject): String? {
            b.keySet().firstOrNull { it !in KEYS }?.let { return "key:$it" }
            if (!b["deviceId"].isStr() || !DEVICE_RE.matches(b["deviceId"].asString)) return "deviceId"
            if (b["trigger"]?.takeIf { it.isStr() }?.asString !in setOf("enter", "exit")) return "trigger"
            val p = b["place"]?.takeIf { it.isJsonObject }?.asJsonObject ?: return "place"
            val name = p["name"]?.takeIf { it.isStr() }?.asString?.replace(Regex("[\\u0000-\\u001f\\u007f]"), " ")?.replace(Regex("\\s+"), " ")?.trim()
            if (name.isNullOrEmpty() || name.length > 80) return "place.name"
            val radius = p["radiusM"]
            if (!radius.absent() && (!radius.isInt() || radius!!.asInt !in 100..2000)) return "place.radiusM"
            val fid = p["familieId"]
            if (!fid.absent()) {
                val ok = when {
                    fid.isNum() -> fid.isInt() && fid!!.asLong > 0
                    fid.isStr() -> FAMILIE_ID.matches(fid!!.asString)
                    else -> false
                }
                if (!ok) return "place.familieId"
            }
            if (!p["lat"].isNum() || kotlin.math.abs(p["lat"].asDouble) > 90) return "place.lat"
            if (!p["lon"].isNum() || kotlin.math.abs(p["lon"].asDouble) > 180) return "place.lon"
            val text = b["text"]?.takeIf { it.isStr() }?.asString?.trim() ?: return "text"
            // JavaScript counts UTF-16 units, as Kotlin's String.length does
            if (text.isEmpty() || text.length > 1000) return "text"
            if (!b["conversationId"].isInt() || b["conversationId"].asLong <= 0) return "conversationId"
            val way = b["way"]
            if (!way.absent() && (!way.isStr() || !WAY_RE.matches(way!!.asString))) return "way"
            val repeat = b["repeat"]
            if (!repeat.absent() && repeat?.asString !in setOf("once", "always")) return "repeat"
            val w = b["window"]
            if (!w.absent()) {
                val o = w!!.takeIf { it.isJsonObject }?.asJsonObject ?: return "window"
                val days = o["days"]?.takeIf { it.isJsonArray }?.asJsonArray ?: return "window.days"
                if (days.size() !in 1..7 || days.any { !it.isInt() || it.asInt !in 1..7 }) return "window.days"
                val from = o["from"]?.takeIf { it.isStr() }?.asString.orEmpty()
                val to = o["to"]?.takeIf { it.isStr() }?.asString.orEmpty()
                if (!HHMM_RE.matches(from) || !HHMM_RE.matches(to) || from == to) return "window.time"
            }
            if (!b["ask"].absent() && !b["ask"].isBool()) return "ask"
            if (!b["enabled"].isBool() && !b["enabled"].absent()) return "enabled"
            return null
        }
    }

    /** The pattern placemsgs.js 1.27.0 checks a string familieId with — it refuses Familie's rule places. */
    private val PLUGIN_1_27_0_FAMILIE_ID = Regex("^[A-Za-z0-9_-]{1,64}$")

    private val device = "android-0011223344556677"

    // A Familie rule place as GET familie/places gives it (gatecontrol-familie: id "place:<id>")
    private val werkstatt = ChatPlaceMessages.fromFamilie(ChatPlace("place:3", "Werkstatt", 51.2345, 7.6543, 150))!!.copy(radiusM = 100)

    private val draft = ChatPmDraft(
        trigger = ChatPlaceMessages.ENTER,
        place = werkstatt,
        text = "Bin gleich da 🚲✨ – bis später 👋🏽",
        conversationId = 40,
        way = "whatsapp",
        repeat = ChatPlaceMessages.ALWAYS,
        window = ChatPmWindow(listOf(1, 2, 3, 4, 5), "06:00", "09:00"),
        ask = true,
        enabled = true,
    )

    @Test
    fun `the failed case - every field as the plugin expects it`() {
        val b = ChatPlaceMessages.body(draft, device)!!
        assertEquals(
            JsonParser.parseString(
                """{"deviceId":"$device","trigger":"enter",
                "place":{"familieId":"place:3","name":"Werkstatt","lat":51.2345,"lon":7.6543,"radiusM":100},
                "text":"Bin gleich da 🚲✨ – bis später 👋🏽","conversationId":40,"way":"whatsapp","repeat":"always",
                "window":{"days":[1,2,3,4,5],"from":"06:00","to":"09:00"},"ask":true,"enabled":true}""",
            ),
            b,
        )
        // numbers stay numbers, the radius an integer
        assertTrue(b["place"].asJsonObject["radiusM"].asJsonPrimitive.isNumber)
        assertEquals("100", b["place"].asJsonObject["radiusM"].toString())
        assertEquals("40", b["conversationId"].toString())
        assertNull(PluginRules.refusal(b))
    }

    @Test
    fun `the plugin 1_27_0 refuses Familie's rule place - the cause of the toast`() {
        // the id Familie gives a rule place and its home
        assertFalse(PLUGIN_1_27_0_FAMILIE_ID.matches("place:3"))
        assertTrue(PLUGIN_1_27_0_FAMILIE_ID.matches("home"))
        // everything else of the body passes the plugin's checks
        val b = ChatPlaceMessages.body(draft, device)!!
        val withoutId = b.deepCopy().also { it["place"].asJsonObject.remove("familieId") }
        assertNull(PluginRules.refusal(withoutId))
    }

    @Test
    fun `Familie's ids home, place and numbers go out as Familie gives them and come back`() {
        for ((id, json) in listOf("home" to "\"home\"", "place:3" to "\"place:3\"", "7" to "7")) {
            val p = ChatPlaceMessages.fromFamilie(ChatPlace(id, "Zuhause", 51.0, 7.0, 150))!!
            val out = ChatPlaceMessages.placeJson(p)
            assertEquals(json, out["familieId"].toString())
            // the answer of the server is read back with the same id (the editor finds the Familie chip again)
            assertEquals(id, ChatPlaceMessages.place(out)!!.familieId)
            assertNull(PluginRules.refusal(ChatPlaceMessages.body(draft.copy(place = p), device)!!))
        }
    }

    @Test
    fun `emojis - the text is cut as the server counts and never inside an emoji`() {
        val emoji = "👋" // two UTF-16 units, as JavaScript counts
        val long = "a" + emoji.repeat(600)
        val b = ChatPlaceMessages.body(draft.copy(text = long), device)!!
        val text = b["text"].asString
        assertTrue(text.length <= ChatPlaceMessages.TEXT_MAX)
        assertFalse(Character.isHighSurrogate(text.last()))
        assertNull(PluginRules.refusal(b))
        assertEquals("a", ChatPlaceMessages.clipText("a$emoji", 2))
        assertEquals("a$emoji", ChatPlaceMessages.clipText("a$emoji", 3))
    }

    @Test
    fun `a window the server refuses is not sent - the same time for start and end`() {
        val same = ChatPmWindow(listOf(1, 2, 3, 4, 5), "06:00", "06:00")
        assertFalse(ChatPlaceMessages.windowOk(same))
        assertNull(ChatPlaceMessages.body(draft.copy(window = same), device))
        assertTrue(ChatPlaceMessages.windowOk(null))
        assertTrue(ChatPlaceMessages.windowOk(ChatPmWindow(listOf(5, 6), "22:00", "06:00")))
    }

    @Test
    fun `the way and the radius limits as the plugin takes them`() {
        for (way in listOf("auto", "gatecontrol", "whatsapp", "signal", "telegram")) {
            assertNull(PluginRules.refusal(ChatPlaceMessages.body(draft.copy(way = way), device)!!), way)
        }
        for (r in listOf(10, 100, 2000, 5000)) {
            val b = ChatPlaceMessages.body(draft.copy(place = werkstatt.copy(radiusM = r)), device)!!
            assertNull(PluginRules.refusal(b), "radius $r")
        }
    }

    @Test
    fun `the server's sentence of an error is kept`() {
        val err = JsonParser.parseString("""{"ok":false,"code":"invalid","error":"Ungültige Eingabe."}""").asJsonObject
        assertEquals("invalid", ChatJson.errorCode(err))
        assertEquals("Ungültige Eingabe.", ChatJson.errorText(err))
        assertEquals("Kaputt.", ChatJson.errorText(JsonParser.parseString("""{"message":"Kaputt."}""").asJsonObject))
        assertNull(ChatJson.errorText(JsonParser.parseString("""{"code":"invalid"}""").asJsonObject))
        assertNull(ChatJson.errorText(null))
    }
}
