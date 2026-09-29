package com.gatecontrol.android.ui.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class LogLineParseTest {

    @Test
    fun `parses FileLoggingTree line`() {
        val e = parseLogLine("2026-04-07 09:55:57.123 W/TunnelMonitor: Handshake older than 180 s")
        assertEquals(LogEntry("09:55:57", 'W', "TunnelMonitor", "Handshake older than 180 s"), e)
    }

    @Test
    fun `keeps colons inside the message`() {
        val e = parseLogLine("2026-04-07 09:55:57.123 I/Api: GET https://gate.example.com/api: 200")
        assertEquals("GET https://gate.example.com/api: 200", e?.message)
        assertEquals("Api", e?.tag)
    }

    @Test
    fun `returns null for continuation lines`() {
        assertNull(parseLogLine("    at com.gatecontrol.android.Foo.bar(Foo.kt:12)"))
        assertNull(parseLogLine(""))
    }
}
