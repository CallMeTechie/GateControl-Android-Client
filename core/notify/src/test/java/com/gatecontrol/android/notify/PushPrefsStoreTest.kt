package com.gatecontrol.android.notify

import com.gatecontrol.android.notify.model.QuietHours
import com.gatecontrol.android.notify.model.QuietInfo
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class PushPrefsStoreTest {

    @TempDir lateinit var dir: File

    @Test
    fun `defaults are off with mode always proposed`() = runBlocking {
        val s = testStore(dir).current()
        assertFalse(s.enabled)
        assertEquals(PushMode.ALWAYS, s.mode)
        assertFalse(s.modeChosen)
        assertFalse(s.everEnabled)
    }

    @Test
    fun `start hint dismissal is remembered`() = runBlocking {
        val store = testStore(dir)
        assertFalse(store.startHintDismissed.first())
        store.setStartHintDismissed(true)
        assertTrue(store.startHintDismissed.first())
    }

    @Test
    fun `last seq never moves backwards`() = runBlocking {
        val store = testStore(dir)
        assertNull(store.lastSeq())
        store.saveLastSeq(10)
        store.saveLastSeq(7)
        assertEquals(10L, store.lastSeq())
    }

    @Test
    fun `pending acks keep state and action`() = runBlocking {
        val store = testStore(dir)
        store.addPendingAcks(listOf(PendingAck(3, "delivered"), PendingAck(4, "read", "mute_1h")))
        assertEquals(listOf(PendingAck(3, "delivered"), PendingAck(4, "read", "mute_1h")), store.pendingAcks())
        store.removePendingAcks(listOf(PendingAck(3, "delivered")))
        assertEquals(listOf(PendingAck(4, "read", "mute_1h")), store.pendingAcks())
    }

    @Test
    fun `another server drops seq, acks and topics`() = runBlocking {
        val store = testStore(dir)
        assertFalse(store.bindServer("https://a.example"))
        store.saveLastSeq(10)
        store.addPendingAcks(listOf(PendingAck(10, "delivered")))
        assertFalse(store.bindServer("https://a.example"))
        assertEquals(10L, store.lastSeq())
        assertTrue(store.bindServer("https://b.example"))
        assertNull(store.lastSeq())
        assertTrue(store.pendingAcks().isEmpty())
    }

    @Test
    fun `local mutes expire`() = runBlocking {
        val store = testStore(dir)
        store.muteLocally("devices", untilMs = 2_000, now = 1_000)
        val s = store.current()
        assertTrue(s.isLocallyMuted("devices", 1_500))
        assertFalse(s.isLocallyMuted("devices", 2_500))
        assertFalse(s.isLocallyMuted("services", 1_500))
    }

    @Test
    fun `quiet hours are kept per server and unknown values change nothing`() = runBlocking {
        val store = testStore(dir)
        store.bindServer("https://a.example.com")
        assertEquals(QuietInfo.UNKNOWN, store.quiet.first())
        val hours = QuietInfo(true, QuietHours("22:00", "07:00", "Europe/Berlin", true))
        store.saveQuiet(hours)
        assertEquals(hours, store.quiet.first())
        store.saveQuiet(QuietInfo.UNKNOWN)
        assertEquals(hours, store.quiet.first())
        store.saveQuiet(QuietInfo.NONE)
        assertEquals(QuietInfo.NONE, store.quiet.first())
        store.saveQuiet(hours)
        store.bindServer("https://b.example.com")
        assertEquals(QuietInfo.UNKNOWN, store.quiet.first())
    }
}
