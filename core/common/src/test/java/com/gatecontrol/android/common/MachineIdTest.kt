package com.gatecontrol.android.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MachineIdTest {

    @Test
    fun `fingerprint from string produces 64-char hex`() {
        val result = MachineId.fingerprintFromString("test-input")
        assertTrue(
            result.matches(Regex("^[a-f0-9]{64}$")),
            "Expected 64-char lowercase hex string but got: $result"
        )
    }

    @Test
    fun `fingerprint is deterministic`() {
        val input = "some-device-id"
        val first = MachineId.fingerprintFromString(input)
        val second = MachineId.fingerprintFromString(input)
        assertEquals(first, second, "Same input should always produce the same fingerprint")
    }

    @Test
    fun `different inputs produce different fingerprints`() {
        val first = MachineId.fingerprintFromString("device-a")
        val second = MachineId.fingerprintFromString("device-b")
        assertNotEquals(first, second, "Different inputs should produce different fingerprints")
    }
}
