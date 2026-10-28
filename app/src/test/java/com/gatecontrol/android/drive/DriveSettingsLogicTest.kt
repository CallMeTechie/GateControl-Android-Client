package com.gatecontrol.android.drive

import com.gatecontrol.android.drive.vault.DriveVaultLockTimer
import com.gatecontrol.android.notify.model.PushMessage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Drive 1.11: the settings on this phone and the vault lock's timing. */
class DriveSettingsLogicTest {

    @Test
    fun `auto lock - only the offered minutes, else 3`() {
        assertEquals(listOf(1, 3, 5, 15), DriveSettingsLogic.AUTO_LOCK_CHOICES)
        listOf(1, 3, 5, 15).forEach { assertEquals(it, DriveSettingsLogic.autoLock(it)) }
        assertEquals(3, DriveSettingsLogic.autoLock(0))
        assertEquals(3, DriveSettingsLogic.autoLock(7))
        assertEquals(3, DriveSettingsLogic.autoLock(-1))
        assertEquals(3, DriveSettingsValues().autoLockMinutes)
        assertTrue(DriveSettingsValues().vaultBiometric)
        assertTrue(DriveSettingsValues().lockOnLeave)
    }

    @Test
    fun `upload only on wifi waits on a metered network`() {
        assertTrue(DriveSettingsLogic.uploadAllowed(wifiOnly = false, connected = true, unmetered = false))
        assertTrue(DriveSettingsLogic.uploadAllowed(wifiOnly = true, connected = true, unmetered = true))
        assertFalse(DriveSettingsLogic.uploadAllowed(wifiOnly = true, connected = true, unmetered = false))
        assertFalse(DriveSettingsLogic.uploadAllowed(wifiOnly = false, connected = false, unmetered = true))
    }

    private fun msg(topic: String) = PushMessage(
        seq = 1, id = 1, eventId = "e", topic = topic, priority = "normal", title = "t", body = "",
        createdAt = null, expiresAt = null, collapseKey = null, silent = false, route = null, actions = emptyList(), dataJson = null,
    )

    @Test
    fun `drive pushes - deadlines and vault requests can stay off this phone`() {
        val all = DriveSettingsValues()
        val none = all.copy(notifyDeadlines = false, notifyVaultRequests = false)
        assertTrue(DrivePush.shown(msg("plugin:gatecontrol-drive:deadline"), all))
        assertFalse(DrivePush.shown(msg("plugin:gatecontrol-drive:deadline"), none))
        assertFalse(DrivePush.shown(msg("plugin:gatecontrol-drive:vault"), none))
        assertTrue(DrivePush.shown(msg("plugin:gatecontrol-drive:vault"), none.copy(notifyVaultRequests = true)))
        // other Drive pushes and other plugins are never held back
        assertTrue(DrivePush.shown(msg("plugin:gatecontrol-drive:guard"), none))
        assertTrue(DrivePush.shown(msg("plugin:gatecontrol-door:deadline"), none))
    }

    @Test
    fun `vault lock - after the minutes without use`() {
        val min = 60_000L
        assertEquals(3 * min, DriveVaultLockTimer.remaining(now = 1_000, lastUse = 1_000, minutes = 3))
        assertEquals(min, DriveVaultLockTimer.remaining(now = 1_000 + 2 * min, lastUse = 1_000, minutes = 3))
        assertTrue(DriveVaultLockTimer.remaining(now = 1_000 + 3 * min, lastUse = 1_000, minutes = 3) <= 0)
        assertTrue(DriveVaultLockTimer.remaining(now = 1_000 + 2 * min, lastUse = 1_000, minutes = 1) < 0)
        assertEquals(15 * min, DriveVaultLockTimer.remaining(now = 5, lastUse = 5, minutes = 15))
    }

    @Test
    fun `vault lock - leaving the app locks unless switched off or the vault opened another app`() {
        assertTrue(DriveVaultLockTimer.lockOnStop(lockOnLeave = true, away = false))
        assertFalse(DriveVaultLockTimer.lockOnStop(lockOnLeave = true, away = true))
        assertFalse(DriveVaultLockTimer.lockOnStop(lockOnLeave = false, away = false))
    }
}
