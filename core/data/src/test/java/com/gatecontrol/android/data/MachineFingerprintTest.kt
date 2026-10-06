package com.gatecontrol.android.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MachineFingerprintTest {

    private var stored: String? = null
    private var randomCalls = 0
    private var androidIdCalls = 0

    private fun provider(androidId: String?, random: ByteArray = ByteArray(32) { 7 }) = MachineFingerprint(
        androidIdProvider = { androidIdCalls++; androidId },
        loadFallback = { stored },
        saveFallback = { stored = it },
        randomBytes = { randomCalls++; random },
    )

    @Test
    fun `fingerprint is 64 lowercase hex chars`() {
        val fp = provider("a1b2c3d4e5f60718").get()
        assertTrue(MachineFingerprint.FORMAT.matches(fp), fp)
    }

    @Test
    fun `fingerprint is sha256 of ANDROID_ID so existing bindings keep working`() {
        // sha256("a1b2c3d4e5f60718") — the value app versions before this change sent.
        val expected = MachineFingerprint.sha256Hex("a1b2c3d4e5f60718".toByteArray())
        assertEquals(expected, provider("a1b2c3d4e5f60718").get())
        assertEquals(
            "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
            MachineFingerprint.sha256Hex("test".toByteArray()),
        )
    }

    @Test
    fun `fingerprint is stable and cached`() {
        val p = provider("a1b2c3d4e5f60718")
        val first = p.get()
        assertEquals(first, p.get())
        assertEquals(1, androidIdCalls)
        assertEquals(first, provider("a1b2c3d4e5f60718").get())
        assertEquals(first.take(8), p.shortId())
    }

    @Test
    fun `different devices get different fingerprints`() {
        assertNotEquals(provider("a1b2c3d4e5f60718").get(), provider("0011223344556677").get())
    }

    @Test
    fun `invalid ANDROID_ID falls back to a stored random id`() {
        listOf(null, "", "   ", "9774d56d682e549c", "9774D56D682E549C", "0000000000000000").forEach {
            stored = null
            randomCalls = 0
            assertFallback(it)
        }
    }

    private fun assertFallback(androidId: String?) {
        val fp = provider(androidId).get()
        assertTrue(MachineFingerprint.FORMAT.matches(fp))
        assertEquals(1, randomCalls)
        assertEquals("07".repeat(32), stored)
        // Never the hash of the broken value itself (shared by many devices).
        assertNotEquals(MachineFingerprint.sha256Hex((androidId ?: "").toByteArray()), fp)
        assertNotEquals(MachineFingerprint.sha256Hex("unknown".toByteArray()), fp)
    }

    @Test
    fun `fallback id is reused across instances`() {
        val first = provider(null).get()
        val second = provider(null, random = ByteArray(32) { 9 }).get()
        assertEquals(first, second)
        assertEquals(1, randomCalls)
    }

    @Test
    fun `devices without ANDROID_ID do not share a fingerprint`() {
        val a = provider(null, random = ByteArray(32) { 1 }).get()
        stored = null
        val b = provider(null, random = ByteArray(32) { 2 }).get()
        assertNotEquals(a, b)
    }

    @Test
    fun `corrupt stored fallback is replaced`() {
        stored = "not-hex"
        provider(null).get()
        assertEquals("07".repeat(32), stored)
    }

    @Test
    fun `ANDROID_ID failure falls back`() {
        val p = MachineFingerprint(
            androidIdProvider = { throw SecurityException("nope") },
            loadFallback = { stored },
            saveFallback = { stored = it },
            randomBytes = { ByteArray(32) { 3 } },
        )
        assertTrue(MachineFingerprint.FORMAT.matches(p.get()))
        assertEquals("03".repeat(32), stored)
    }
}
