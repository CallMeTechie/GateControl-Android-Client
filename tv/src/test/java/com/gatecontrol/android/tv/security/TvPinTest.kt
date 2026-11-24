package com.gatecontrol.android.tv.security

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class TvPinTest {

    // Few iterations: the rules are the same, the test stays fast.
    private fun hash(pin: String) = TvPinHash.hash(pin, iterations = 1_000)

    @Test
    fun `only 4 to 8 digits are a PIN`() {
        assertTrue(TvPinHash.isValid("1234"))
        assertTrue(TvPinHash.isValid("12345678"))
        assertFalse(TvPinHash.isValid("123"))
        assertFalse(TvPinHash.isValid("123456789"))
        assertFalse(TvPinHash.isValid("12a4"))
        assertFalse(TvPinHash.isValid(""))
        assertThrows<IllegalArgumentException> { TvPinHash.hash("12") }
    }

    @Test
    fun `the hash holds no PIN and verifies only the right one`() {
        val h = hash("4711")
        assertFalse(h.contains("4711"))
        assertTrue(h.startsWith("v1$1000$"))
        assertTrue(TvPinHash.verify("4711", h))
        assertFalse(TvPinHash.verify("4712", h))
        assertFalse(TvPinHash.verify("4711", null))
        assertFalse(TvPinHash.verify("4711", "v1\$1000\$broken"))
        assertFalse(TvPinHash.verify("4711", "v2" + h.removePrefix("v1")))
    }

    @Test
    fun `every hash has its own salt`() {
        assertNotEquals(hash("1234"), hash("1234"))
    }

    @Test
    fun `the default strength is the stored one`() {
        val h = TvPinHash.hash("2580")
        assertTrue(h.startsWith("v1$${TvPinHash.ITERATIONS}$"))
        assertTrue(TvPinHash.verify("2580", h))
    }

    @Test
    fun `five wrong PINs lock for 30 s, each further one doubles it up to 30 min`() {
        assertEquals(0L, TvPinLock.lockMs(4))
        assertEquals(30_000L, TvPinLock.lockMs(5))
        assertEquals(60_000L, TvPinLock.lockMs(6))
        assertEquals(120_000L, TvPinLock.lockMs(7))
        assertEquals(30 * 60_000L, TvPinLock.lockMs(20))
        assertEquals(30 * 60_000L, TvPinLock.lockMs(500))
        assertEquals(5, TvPinLock.attemptsLeft(0))
        assertEquals(1, TvPinLock.attemptsLeft(4))
        assertEquals(0, TvPinLock.attemptsLeft(9))
    }

    @Test
    fun `wrong PINs count, a lock refuses even the right one, a right PIN resets`() {
        var state = TvPinRules.setPin(TvPinState(), hash("1357"))
        var now = 1_000_000L
        repeat(4) {
            val (next, result) = TvPinRules.check(state, "0000", now)
            state = next
            assertTrue(result is TvPinCheck.Wrong && result.lockedUntil == 0L)
        }
        val (locked, fifth) = TvPinRules.check(state, "0000", now)
        assertEquals(TvPinCheck.Wrong(0, now + 30_000L), fifth)
        state = locked
        assertEquals(TvPinCheck.Locked(now + 30_000L), TvPinRules.check(state, "1357", now + 10_000L).second)
        now += 31_000L
        val (ok, result) = TvPinRules.check(state, "1357", now)
        assertEquals(TvPinCheck.Ok, result)
        assertEquals(0, ok.failures)
    }

    @Test
    fun `per function with or without PIN, only once a PIN exists`() {
        val none = TvPinState()
        assertFalse(none.needsPin(TvPinFunction.DOOR))
        assertEquals(none, TvPinRules.setRequired(none, TvPinFunction.SETTINGS, true))
        // the first PIN switches the door on, nothing else
        val withPin = TvPinRules.setPin(none, hash("2468"))
        assertTrue(withPin.needsPin(TvPinFunction.DOOR))
        assertFalse(withPin.needsPin(TvPinFunction.SETTINGS))
        val settings = TvPinRules.setRequired(withPin, TvPinFunction.SETTINGS, true)
        assertTrue(settings.needsPin(TvPinFunction.SETTINGS))
        val doorOff = TvPinRules.setRequired(settings, TvPinFunction.DOOR, false)
        assertFalse(doorOff.needsPin(TvPinFunction.DOOR))
        // a changed PIN keeps the choice
        val changed = TvPinRules.setPin(doorOff, hash("9999"))
        assertEquals(setOf(TvPinFunction.SETTINGS), changed.required)
        // removed: nothing asks
        val removed = TvPinRules.removePin(changed)
        assertFalse(removed.needsPin(TvPinFunction.SETTINGS))
        assertFalse(removed.hasPin)
    }

    @Test
    fun `without a PIN every check passes`() {
        assertEquals(TvPinCheck.Ok, TvPinRules.check(TvPinState(), "0000", 0L).second)
    }

    @Test
    fun `functions keep their stored names`() {
        for (f in TvPinFunction.entries) assertEquals(f, TvPinFunction.fromWire(f.wire))
        assertEquals(null, TvPinFunction.fromWire("other"))
        assertEquals(listOf(TvPinFunction.DOOR, TvPinFunction.SETTINGS, TvPinFunction.DRIVE, TvPinFunction.NOTES, TvPinFunction.MESSAGES), TvPinFunction.AVAILABLE)
        assertEquals("notes", TvPinFunction.NOTES.wire)
        assertEquals("messages", TvPinFunction.MESSAGES.wire)
        // Notizen and Nachrichten stay open after the PIN; the door and the settings ask each time
        assertEquals(listOf(TvPinFunction.NOTES, TvPinFunction.MESSAGES), TvPinFunction.entries.filter { it.holds })
        assertEquals(listOf(TvPinFunction.MESSAGES), TvPinFunction.entries.filter { it.defaultOn })
    }

    @Test
    fun `Notizen stays open ten minutes after the last key`() {
        val opened = TvPinHold.open(emptyMap(), TvPinFunction.NOTES, now = 1_000L)
        assertTrue(TvPinHold.isOpen(opened, TvPinFunction.NOTES, 1_000L + TvPinHold.HOLD_MS - 1))
        assertFalse(TvPinHold.isOpen(opened, TvPinFunction.NOTES, 1_000L + TvPinHold.HOLD_MS))
        // a key keeps it open from then on
        val touched = TvPinHold.touch(opened, now = 500_000L)
        assertTrue(TvPinHold.isOpen(touched, TvPinFunction.NOTES, 500_000L + TvPinHold.HOLD_MS - 1))
        // what ran out stays closed, a key does not open it again
        assertEquals(emptyMap<TvPinFunction, Long>(), TvPinHold.touch(opened, now = 1_000L + TvPinHold.HOLD_MS))
        // a function without hold never stays open
        assertFalse(TvPinHold.isOpen(TvPinHold.open(emptyMap(), TvPinFunction.SETTINGS, 0L), TvPinFunction.SETTINGS, 1L))
        assertFalse(TvPinHold.isOpen(opened, TvPinFunction.DRIVE, 2_000L))
    }

    @Test
    fun `the unlocks close on the frame and the screen saver`() {
        var now = 0L
        val unlocks = TvPinUnlocks().apply { clock = { now } }
        assertFalse(unlocks.isOpen(TvPinFunction.NOTES))
        unlocks.opened(TvPinFunction.NOTES)
        now = 9 * 60_000L
        unlocks.touch()
        now = 18 * 60_000L
        assertTrue(unlocks.isOpen(TvPinFunction.NOTES))
        unlocks.lockAll()
        assertFalse(unlocks.isOpen(TvPinFunction.NOTES))
    }

    @Test
    fun `Nachrichten asks for the PIN by default until it is switched off`() {
        val none = TvPinState()
        // no PIN: nothing asks, the first opening offers one
        assertFalse(none.needsPin(TvPinFunction.MESSAGES))
        assertTrue(none.offersPin(TvPinFunction.MESSAGES))
        assertFalse(none.offersPin(TvPinFunction.NOTES))
        val asked = TvPinRules.offered(none, TvPinFunction.MESSAGES)
        assertFalse(asked.offersPin(TvPinFunction.MESSAGES))
        // a PIN: Nachrichten is „mit PIN“ without being chosen
        val withPin = TvPinRules.setPin(asked, hash("2468"))
        assertTrue(withPin.needsPin(TvPinFunction.MESSAGES))
        assertFalse(withPin.offersPin(TvPinFunction.MESSAGES))
        // switched off it stays off, also across a changed PIN; on again
        val off = TvPinRules.setRequired(withPin, TvPinFunction.MESSAGES, false)
        assertFalse(off.needsPin(TvPinFunction.MESSAGES))
        assertFalse(TvPinRules.setPin(off, hash("1357")).needsPin(TvPinFunction.MESSAGES))
        assertTrue(TvPinRules.setRequired(off, TvPinFunction.MESSAGES, true).needsPin(TvPinFunction.MESSAGES))
        // removing the PIN does not offer it again
        val removed = TvPinRules.removePin(withPin)
        assertFalse(removed.needsPin(TvPinFunction.MESSAGES))
        assertFalse(removed.offersPin(TvPinFunction.MESSAGES))
    }

    @Test
    fun `Nachrichten stays open ten minutes like Notizen`() {
        var now = 0L
        val unlocks = TvPinUnlocks().apply { clock = { now } }
        unlocks.opened(TvPinFunction.MESSAGES)
        now = TvPinHold.HOLD_MS - 1
        assertTrue(unlocks.isOpen(TvPinFunction.MESSAGES))
        now = TvPinHold.HOLD_MS
        assertFalse(unlocks.isOpen(TvPinFunction.MESSAGES))
    }

    @Test
    fun `dictating keeps the PIN open for a while, leaving the app locks`() {
        // stage 3d known problem: the dictation (Alexa) took the front and Nachrichten locked mid-answer
        assertEquals(TvLeave.LOCK, TvPinLeave.onStop(inApp = false, typing = false))
        assertEquals(TvLeave.WAIT, TvPinLeave.onStop(inApp = false, typing = true))
        assertEquals(TvLeave.KEEP, TvPinLeave.onStop(inApp = true, typing = false))
        assertFalse(TvPinLeave.lockOnReturn(null, 10_000L))
        assertFalse(TvPinLeave.lockOnReturn(1_000L, 1_000L + TvPinLeave.DICTATION_GRACE_MS))
        assertTrue(TvPinLeave.lockOnReturn(1_000L, 1_000L + TvPinLeave.DICTATION_GRACE_MS + 1))
    }
}
