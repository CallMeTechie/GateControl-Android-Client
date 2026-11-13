package com.gatecontrol.android.ui.vault

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.ui.start.StartPagerPages
import com.gatecontrol.android.ui.theme.GateControlTheme
import com.gatecontrol.android.vault.VaultBiometric
import com.gatecontrol.android.vault.VaultRepository
import com.gatecontrol.android.vault.VaultState
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Vault pinned as a page beside Start: the fingerprint prompt of the locked Vault comes only when the pager rests on
 * the Vault page and the app is in front — not while Vault is composed beside the shown page (it appeared over
 * „Netzwerk“), not while swiping, not by itself again after „Abbrechen“ or a return from the background.
 *
 * The pages are Start's own ([StartPagerPages]) with the real unlock screen; the view model is a stand-in that counts
 * the prompts, so nothing here runs on another thread.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp")
class VaultUnlockPagerTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val ids = listOf("start", "network", "vault")
    private val vaultPage = ids.indexOf("vault")
    private val networkPage = ids.indexOf("network")

    /** The prompts shown so far: each one's answer, to give later. */
    private val prompts = mutableListOf<(VaultBiometric.Outcome) -> Unit>()

    private val viewModel = mockk<VaultViewModel>(relaxed = true).also { vm ->
        every { vm.state } returns MutableStateFlow(VaultState(known = true))
        every { vm.sync } returns MutableStateFlow<VaultRepository.Sync>(VaultRepository.Sync.Idle)
        every { vm.biometricEnabled() } returns true
        every { vm.unlockWithFinger(any(), any()) } answers { prompts += secondArg<(VaultBiometric.Outcome) -> Unit>() }
    }

    private lateinit var pager: PagerState

    private fun show(initialPage: Int) {
        rule.setContent {
            GateControlTheme(darkTheme = false) {
                pager = rememberPagerState(initialPage = initialPage) { ids.size }
                StartPagerPages(state = pager, ids = { ids }) { id ->
                    if (id == "vault") VaultUnlock(viewModel, onForgot = {}) else Box(Modifier.fillMaxSize()) { Text("page $id") }
                }
            }
        }
        rule.waitForIdle()
    }

    /** Swipes to [page] and lets the pager come to rest there. */
    private fun settleOn(page: Int) {
        rule.runOnIdle { runBlocking { pager.scrollToPage(page) } }
        rule.waitForIdle()
    }

    /** [page] in front with a part of the next page swiped in (that one is composed, the pager does not rest on it). */
    private fun showWithNextSwipedIn(page: Int) {
        rule.runOnIdle { runBlocking { pager.scrollToPage(page, pageOffsetFraction = 0.4f) } }
        rule.waitForIdle()
    }

    private fun vaultIsComposed() {
        rule.onNodeWithText(rule.activity.getString(R.string.vault_locked_title)).assertExists()
    }

    private fun background() {
        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        rule.waitForIdle()
    }

    private fun foreground() {
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        rule.waitForIdle()
    }

    @Test
    fun `Vault beside the shown page does not ask for the finger`() {
        show(initialPage = networkPage)
        showWithNextSwipedIn(networkPage)
        vaultIsComposed()
        assertEquals(networkPage, pager.settledPage)
        assertEquals(0, prompts.size)
    }

    @Test
    fun `resting on Vault asks exactly once`() {
        show(initialPage = networkPage)
        showWithNextSwipedIn(networkPage)
        assertEquals(0, prompts.size)
        settleOn(vaultPage)
        vaultIsComposed()
        assertEquals(1, prompts.size)
        rule.mainClock.advanceTimeBy(2_000)
        rule.waitForIdle()
        assertEquals(1, prompts.size)
    }

    @Test
    fun `the app opened on Vault asks once`() {
        show(initialPage = vaultPage)
        assertEquals(1, prompts.size)
    }

    @Test
    fun `a cancelled prompt does not come back by itself`() {
        show(initialPage = vaultPage)
        assertEquals(1, prompts.size)
        rule.runOnIdle { prompts.single()(VaultBiometric.Outcome.Cancelled) }
        rule.waitForIdle()
        vaultIsComposed()
        assertEquals(1, prompts.size)
        // … not after the app was left and opened again either
        background()
        foreground()
        assertEquals(1, prompts.size)
    }

    @Test
    fun `coming back from the background on another page does not ask`() {
        show(initialPage = networkPage)
        showWithNextSwipedIn(networkPage)
        background()
        foreground()
        vaultIsComposed()
        assertEquals(0, prompts.size)
    }

    @Test
    fun `leaving Vault and coming back asks again, once`() {
        show(initialPage = vaultPage)
        rule.runOnIdle { prompts.single()(VaultBiometric.Outcome.Cancelled) }
        showWithNextSwipedIn(networkPage)
        assertEquals(1, prompts.size)
        settleOn(vaultPage)
        assertEquals(2, prompts.size)
    }
}
