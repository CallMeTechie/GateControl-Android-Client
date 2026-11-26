package com.gatecontrol.android.drive

import com.gatecontrol.android.drive.DriveOfflineStoreLogic.AccountAction
import com.gatecontrol.android.drive.DriveOfflineStoreLogic.Login
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Offline copies only go on a real sign-out or another server — never on a login unreadable for a moment. */
class DriveOfflineAccountTest {

    private val a = Login("https://a.example.com", "tok", serverStored = true, tokenStored = true)

    @Test
    fun `a busy keystore (stored but unreadable) keeps the copies`() {
        val unreadableUrl = a.copy(serverUrl = "")
        val unreadableToken = a.copy(token = "")
        val both = a.copy(serverUrl = "", token = "")
        assertEquals(AccountAction.KEEP, DriveOfflineStoreLogic.accountChange(a, unreadableUrl, a.serverUrl))
        assertEquals(AccountAction.KEEP, DriveOfflineStoreLogic.accountChange(a, unreadableToken, a.serverUrl))
        assertEquals(AccountAction.KEEP, DriveOfflineStoreLogic.accountChange(a, both, a.serverUrl))
        assertEquals(AccountAction.KEEP, DriveOfflineStoreLogic.accountChange(null, unreadableUrl, a.serverUrl))
    }

    @Test
    fun `a lost address with the token kept keeps the copies, and its restore too`() {
        val lost = a.copy(serverUrl = "", serverStored = false)
        assertEquals(AccountAction.KEEP, DriveOfflineStoreLogic.accountChange(a, lost, a.serverUrl))
        assertEquals(AccountAction.KEEP, DriveOfflineStoreLogic.accountChange(null, a, a.serverUrl))
    }

    @Test
    fun `a real sign-out wipes`() {
        val out = Login("", "", serverStored = false, tokenStored = false)
        assertEquals(AccountAction.WIPE, DriveOfflineStoreLogic.accountChange(a, out, a.serverUrl))
        assertEquals(AccountAction.WIPE, DriveOfflineStoreLogic.accountChange(null, out))
    }

    @Test
    fun `another server wipes, another token on the same server forgets the vaults`() {
        val b = a.copy(serverUrl = "https://b.example.com")
        assertEquals(AccountAction.WIPE, DriveOfflineStoreLogic.accountChange(a, b, a.serverUrl))
        assertEquals(AccountAction.FORGET_VAULTS, DriveOfflineStoreLogic.accountChange(a, a.copy(token = "tok2"), a.serverUrl))
        assertEquals(AccountAction.KEEP, DriveOfflineStoreLogic.accountChange(a, a, a.serverUrl))
    }

    @Test
    fun `without a known login before, the copies' server decides`() {
        val b = a.copy(serverUrl = "https://b.example.com")
        assertEquals(AccountAction.WIPE, DriveOfflineStoreLogic.accountChange(null, b, a.serverUrl))
        assertEquals(AccountAction.KEEP, DriveOfflineStoreLogic.accountChange(null, b, ""))
    }
}
