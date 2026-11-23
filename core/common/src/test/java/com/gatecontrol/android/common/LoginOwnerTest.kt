package com.gatecontrol.android.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class LoginOwnerTest {
    @Test
    fun `no server, no owner`() {
        assertEquals("", LoginOwner.key("", "token"))
        assertEquals("", LoginOwner.key("  ", "token"))
    }

    @Test
    fun `24 hex characters per server and token`() {
        val key = LoginOwner.key("https://gc.example.com", "t1")
        assertEquals(24, key.length)
        assertEquals(key, LoginOwner.key("https://gc.example.com", "t1"))
        assertNotEquals(key, LoginOwner.key("https://gc.example.com", "t2"))
        assertNotEquals(key, LoginOwner.key("https://other.example.com", "t1"))
    }
}
