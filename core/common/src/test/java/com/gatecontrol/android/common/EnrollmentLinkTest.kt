package com.gatecontrol.android.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class EnrollmentLinkTest {

    @Test
    fun `parses the link the server puts into the QR code`() {
        val link = EnrollmentLink.parse(
            "gatecontrol://enroll?url=https%3A%2F%2Fgate.example.com&code=AB12-CD34-EF56-7890",
        )
        assertEquals(EnrollmentLink("https://gate.example.com", "AB12-CD34-EF56-7890"), link)
    }

    @Test
    fun `keeps a non-default port and drops a path`() {
        val link = EnrollmentLink.parse(
            "gatecontrol://enroll?url=https%3A%2F%2Fgate.example.com%3A8443%2Fadmin&code=ab12cd34ef567890",
        )
        assertEquals("https://gate.example.com:8443", link?.serverUrl)
        assertEquals("AB12-CD34-EF56-7890", link?.code)
    }

    @Test
    fun `rejects other schemes, hosts and plain http servers`() {
        assertNull(EnrollmentLink.parse("gatecontrol://setup?url=https%3A%2F%2Fa.b&token=gc_x"))
        assertNull(EnrollmentLink.parse("https://enroll?url=https%3A%2F%2Fa.b&code=AB12CD34EF567890"))
        assertNull(EnrollmentLink.parse("gatecontrol://enroll?url=http%3A%2F%2Fa.b&code=AB12CD34EF567890"))
        assertNull(EnrollmentLink.parse("gatecontrol://enroll?url=https%3A%2F%2Fa.b"))
        assertNull(EnrollmentLink.parse("[Interface]\nPrivateKey = x"))
        assertNull(EnrollmentLink.parse(null))
    }

    @Test
    fun `normalizes hand-typed codes`() {
        assertEquals("AB12-CD34-EF56-7890", EnrollmentLink.normalizeCode(" ab12 cd34 ef56 7890 "))
        assertEquals("AB12-CD34-EF56-7890", EnrollmentLink.normalizeCode("AB12-CD34-EF56-7890"))
    }

    @Test
    fun `does not mistake tokens or garbage for a code`() {
        assertNull(EnrollmentLink.normalizeCode("gc_b71185344ccf348e"))
        assertNull(EnrollmentLink.normalizeCode("AB12-CD34-EF56"))
        assertNull(EnrollmentLink.normalizeCode("XY12-CD34-EF56-7890"))
        assertNull(EnrollmentLink.normalizeCode(""))
    }
}
