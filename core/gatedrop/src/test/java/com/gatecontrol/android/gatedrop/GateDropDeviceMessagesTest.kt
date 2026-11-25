package com.gatecontrol.android.gatedrop

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GateDropDeviceMessagesTest {

    private fun entry(json: String): GdEntry = GateDropJson.entry(JsonParser.parseString(json).asJsonObject)!!

    private val own = """{"id":"0123456789abcdef01234567","role":"received","createdAt":"2026-11-25T18:00:00Z",
        "from":{"userId":3,"name":"Anna Muster","deviceId":"dev-tv_1","deviceName":"Wohnzimmer-TV","own":true},
        "text":"gcmsg/1 {\"type\":\"cast.state\"}","items":[],"deliveries":[]}"""

    @Test
    fun `the sender's own flag and device come from the send`() {
        val e = entry(own)
        assertEquals(true, e.fromOwn)
        assertEquals("dev-tv_1", e.fromDevice)
        assertEquals("Wohnzimmer-TV", e.from)
    }

    @Test
    fun `a text of an own device is a device message`() {
        val m = GateDropDeviceMessages.of(entry(own))!!
        assertEquals("0123456789abcdef01234567", m.sendId)
        assertEquals("dev-tv_1", m.fromDevice)
        assertEquals("""{"type":"cast.state"}""", m.json)
        assertTrue(GateDropDeviceMessages.isDeviceMessage(entry(own)))
    }

    @Test
    fun `another person, the portal, files or a plain text are none`() {
        assertNull(GateDropDeviceMessages.of(entry(own.replace("\"own\":true", "\"own\":false"))))
        assertNull(GateDropDeviceMessages.of(entry(own.replace(",\"own\":true", ""))))
        assertNull(GateDropDeviceMessages.of(entry(own.replace("\"deviceId\":\"dev-tv_1\",", ""))))
        assertNull(GateDropDeviceMessages.of(entry(own.replace("\"deviceId\":\"dev-tv_1\"", "\"deviceId\":\"bad id!\""))))
        assertNull(GateDropDeviceMessages.of(entry(own.replace("gcmsg/1 ", "Hallo "))))
        assertNull(
            GateDropDeviceMessages.of(
                entry(own.replace("\"items\":[]", "\"items\":[{\"index\":0,\"name\":\"a.txt\",\"bytes\":3,\"mime\":\"text/plain\",\"mediaId\":\"m1\"}]")),
            ),
        )
        assertFalse(GateDropDeviceMessages.isDeviceMessage(entry(own.replace("gcmsg/1 ", "Hallo "))))
    }
}
