package com.gatecontrol.android.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DeviceMessageTest {

    @Test
    fun wrapsOneLineOfJsonAndReadsItBack() {
        val text = DeviceMessage.wrap("""{"type":"cast.cmd"}""")
        assertEquals("""gcmsg/1 {"type":"cast.cmd"}""", text)
        assertEquals("""{"type":"cast.cmd"}""", DeviceMessage.unwrap(text))
        assertEquals("""{"a":1}""", DeviceMessage.unwrap("""  gcmsg/1 {"a":1}  """))
    }

    @Test
    fun refusesWhatIsNoMessage() {
        assertNull(DeviceMessage.wrap("[1]"))
        assertNull(DeviceMessage.wrap("{\n}"))
        assertNull(DeviceMessage.wrap("{\"x\":\"" + "a".repeat(DeviceMessage.MAX_LENGTH) + "\"}"))
        assertNull(DeviceMessage.unwrap(null))
        assertNull(DeviceMessage.unwrap("Hallo"))
        assertNull(DeviceMessage.unwrap("gcmsg/2 {}"))
        assertNull(DeviceMessage.unwrap("gcmsg/1 nope"))
        assertNull(DeviceMessage.unwrap("x gcmsg/1 {}"))
    }

    @Test
    fun knowsAPushBodyByItsStart() {
        assertTrue(DeviceMessage.looksLike("""gcmsg/1 {"type":"cast.state","sess…"""))
        assertTrue(DeviceMessage.looksLike(" gcmsg/1 {"))
        assertFalse(DeviceMessage.looksLike("Ein Text gcmsg/1 {}"))
        assertFalse(DeviceMessage.looksLike(null))
    }
}
