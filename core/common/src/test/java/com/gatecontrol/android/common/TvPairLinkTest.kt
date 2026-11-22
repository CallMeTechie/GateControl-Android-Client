package com.gatecontrol.android.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TvPairLinkTest {

    private val id = "AbCdEfGhIjKlMnOpQrSt_-"

    @Test
    fun `parses the link of the TV's QR code`() {
        val link = TvPairLink.parse("gatecontrol://tv-pair?url=https%3A%2F%2Fgate.example.com&id=$id")
        assertEquals(TvPairLink("https://gate.example.com", id), link)
    }

    @Test
    fun `keeps a non-default port, drops path and default port`() {
        assertEquals(
            "https://gate.example.com:8443",
            TvPairLink.parse("gatecontrol://tv-pair?url=https%3A%2F%2Fgate.example.com%3A8443%2Fx&id=$id")?.serverUrl,
        )
        assertEquals(
            "https://gate.example.com",
            TvPairLink.parse("GATECONTROL://TV-PAIR?url=https%3A%2F%2Fgate.example.com%3A443&id=$id")?.serverUrl,
        )
    }

    @Test
    fun `round trip of build and parse`() {
        val raw = TvPairLink.build("https://gate.example.com:8443", id)
        assertEquals(TvPairLink("https://gate.example.com:8443", id), TvPairLink.parse(raw))
    }

    @Test
    fun `rejects other links, http servers and bad ids`() {
        assertNull(TvPairLink.parse("gatecontrol://enroll?url=https%3A%2F%2Fgate.example.com&code=AB12CD34EF567890"))
        assertNull(TvPairLink.parse("https://tv-pair?url=https%3A%2F%2Fgate.example.com&id=$id"))
        assertNull(TvPairLink.parse("gatecontrol://tv-pair?url=http%3A%2F%2Fgate.example.com&id=$id"))
        assertNull(TvPairLink.parse("gatecontrol://tv-pair?url=https%3A%2F%2Fgate.example.com"))
        assertNull(TvPairLink.parse("gatecontrol://tv-pair?url=https%3A%2F%2Fgate.example.com&id=short"))
        assertNull(TvPairLink.parse("gatecontrol://tv-pair?url=https%3A%2F%2Fgate.example.com&id=${id}x"))
        assertNull(TvPairLink.parse("gatecontrol://tv-pair?url=https%3A%2F%2Fgate.example.com&id=AbCdEfGhIjKlMnOpQrSt%2F%2B"))
        assertNull(TvPairLink.parse("gatecontrol://tv-pair?url=%E0%A4%A&id=$id"))
        assertNull(TvPairLink.parse(""))
        assertNull(TvPairLink.parse(null))
    }

    @Test
    fun `normalizes display codes as the server does`() {
        assertEquals("K7Q4MZAB", TvPairCode.normalize("k7q4-mzab"))
        assertEquals("K7Q4MZAB", TvPairCode.normalize(" K7Q4 MZAB "))
        assertEquals("K7Q4MZAB", TvPairCode.normalize("K7Q4MZAB"))
        // 0, O, 1, I and L are not in the alphabet
        assertNull(TvPairCode.normalize("K0Q4MZAB"))
        assertNull(TvPairCode.normalize("KOQ4MZAB"))
        assertNull(TvPairCode.normalize("K1Q4MZAB"))
        assertNull(TvPairCode.normalize("KLQ4MZAB"))
        assertNull(TvPairCode.normalize("K7Q4MZA"))
        assertNull(TvPairCode.normalize("K7Q4MZABC"))
        assertNull(TvPairCode.normalize(null))
    }

    @Test
    fun `formats and cleans input`() {
        assertEquals("K7Q4-MZAB", TvPairCode.format("K7Q4MZAB"))
        assertEquals("K7Q4-MZAB", TvPairCode.format("k7q4 mzab"))
        assertEquals("nonsense", TvPairCode.format("nonsense"))
        assertEquals("K7Q4MZAB", TvPairCode.cleanInput("k7q4-mzab-xy"))
        assertEquals("K7Q", TvPairCode.cleanInput("k 7 q 0 1"))
    }
}
