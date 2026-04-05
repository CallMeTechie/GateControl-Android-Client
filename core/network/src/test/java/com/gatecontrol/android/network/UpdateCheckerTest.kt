package com.gatecontrol.android.network

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UpdateCheckerTest {

    // --- isNewer: higher remote version ---

    @Test
    fun `isNewer detects higher major version`() {
        assertTrue(UpdateChecker.isNewer(remote = "2.0.0", local = "1.9.9"))
    }

    @Test
    fun `isNewer detects higher minor version`() {
        assertTrue(UpdateChecker.isNewer(remote = "1.5.0", local = "1.4.99"))
    }

    @Test
    fun `isNewer detects higher patch version`() {
        assertTrue(UpdateChecker.isNewer(remote = "1.0.10", local = "1.0.9"))
    }

    @Test
    fun `isNewer detects higher version with extra patch segment`() {
        assertTrue(UpdateChecker.isNewer(remote = "1.0.1", local = "1.0.0"))
    }

    // --- isNewer: same or older version ---

    @Test
    fun `isNewer rejects same version`() {
        assertFalse(UpdateChecker.isNewer(remote = "1.2.3", local = "1.2.3"))
    }

    @Test
    fun `isNewer rejects older major version`() {
        assertFalse(UpdateChecker.isNewer(remote = "1.0.0", local = "2.0.0"))
    }

    @Test
    fun `isNewer rejects older minor version`() {
        assertFalse(UpdateChecker.isNewer(remote = "1.3.0", local = "1.4.0"))
    }

    @Test
    fun `isNewer rejects older patch version`() {
        assertFalse(UpdateChecker.isNewer(remote = "1.0.8", local = "1.0.9"))
    }

    // --- malformed input ---

    @Test
    fun `isNewer handles non-numeric segment in remote`() {
        assertFalse(UpdateChecker.isNewer(remote = "1.x.0", local = "1.0.0"))
    }

    @Test
    fun `isNewer handles non-numeric segment in local`() {
        assertFalse(UpdateChecker.isNewer(remote = "1.0.0", local = "1.a.0"))
    }

    @Test
    fun `isNewer handles empty remote string`() {
        assertFalse(UpdateChecker.isNewer(remote = "", local = "1.0.0"))
    }

    @Test
    fun `isNewer handles empty local string`() {
        assertFalse(UpdateChecker.isNewer(remote = "", local = ""))
    }

    @Test
    fun `isNewer handles versions without patch component`() {
        assertTrue(UpdateChecker.isNewer(remote = "2.1", local = "2.0"))
        assertFalse(UpdateChecker.isNewer(remote = "2.0", local = "2.1"))
        assertFalse(UpdateChecker.isNewer(remote = "1.0", local = "1.0"))
    }
}
