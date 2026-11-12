package com.gatecontrol.android.ui.dnsprotect

import com.gatecontrol.android.data.LicenseRepository
import com.gatecontrol.android.dnsprotect.DnsDuration
import com.gatecontrol.android.dnsprotect.DnsLogic
import com.gatecontrol.android.dnsprotect.DnsMay
import com.gatecontrol.android.dnsprotect.DnsPeople
import com.gatecontrol.android.dnsprotect.DnsPerson
import com.gatecontrol.android.dnsprotect.DnsRepository
import com.gatecontrol.android.dnsprotect.DnsResult
import com.gatecontrol.android.dnsprotect.DnsSnapshot
import com.gatecontrol.android.dnsprotect.DnsStatus
import com.gatecontrol.android.dnsprotect.DnsTarget
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DnsProtectViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val repository: DnsRepository = mockk(relaxed = true)
    private val license = LicenseRepository()
    private val snapshots = MutableStateFlow<DnsSnapshot?>(null)
    private val now = 1_000_000L

    private val active = DnsSnapshot(DnsStatus(DnsStatus.ACTIVE, may = DnsMay(pause = true, durations = listOf(5, 30, 60))), now)
    private val paused = DnsSnapshot(DnsStatus(DnsStatus.PAUSED, may = DnsMay(resume = true)), now)

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { repository.snapshot } returns snapshots
        coEvery { repository.refresh(any()) } coAnswers { ok(active) }
    }

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    /** What the repository does with an answer: it is the snapshot. */
    private fun ok(s: DnsSnapshot): DnsResult<DnsSnapshot> {
        snapshots.value = s
        return DnsResult.Ok(s)
    }

    private fun reported(state: String = "filtering") =
        license.updatePermissions(services = true, traffic = false, dns = false, rdp = false, vpnDns = LicenseRepository.VpnDns("gatecontrol-dns", state))

    private fun vm() = DnsProtectViewModel(repository, license) { now }

    private fun idle() = dispatcher.scheduler.advanceUntilIdle()

    @Test
    fun `without a reported DNS protection nothing is asked`() = runTest {
        val vm = vm()
        idle()
        assertNull(vm.ui.value.coarse)
        coVerify(exactly = 0) { repository.refresh(any()) }
    }

    @Test
    fun `a reported DNS protection loads its status, again only when its filter changed`() = runTest {
        reported("filtering")
        val vm = vm()
        idle()
        assertEquals("filtering", vm.ui.value.coarse)
        assertEquals(active, vm.ui.value.snapshot)
        assertTrue(vm.ui.value.loaded)
        // The same reported again (permissions reloaded): no second question by itself.
        reported("filtering")
        idle()
        coVerify(exactly = 1) { repository.refresh(any()) }
        // The filter went down: what that means for this device.
        reported("fallback")
        idle()
        assertEquals("fallback", vm.ui.value.coarse)
        coVerify(exactly = 2) { repository.refresh(any()) }
    }

    @Test
    fun `pause takes the status of the answer`() = runTest {
        reported()
        coEvery { repository.pause(DnsDuration.Minutes(30), DnsTarget.Own, now) } coAnswers { ok(paused) }
        val vm = vm()
        idle()
        vm.pause(DnsDuration.Minutes(30))
        assertTrue(vm.ui.value.working)
        idle()
        assertFalse(vm.ui.value.working)
        assertEquals(DnsStatus.PAUSED, vm.ui.value.snapshot?.status?.state)
        assertNull(vm.ui.value.notice)
        // The answer was the status: no second call.
        coVerify(exactly = 1) { repository.refresh(any()) }
    }

    @Test
    fun `a refused change shows the server's sentence and reloads the status`() = runTest {
        reported()
        coEvery { repository.pause(any(), any(), any()) } returns DnsResult.Failed("too_long", 403, "Höchstens 60 Minuten.")
        val vm = vm()
        idle()
        vm.pause(DnsDuration.Minutes(240))
        idle()
        assertEquals(DnsNotice.Refused("Höchstens 60 Minuten."), vm.ui.value.notice)
        coVerify(exactly = 2) { repository.refresh(any()) }
        // The next change starts clean.
        coEvery { repository.resume(any(), any()) } coAnswers { ok(active) }
        vm.resume()
        idle()
        assertNull(vm.ui.value.notice)
    }

    @Test
    fun `forever not allowed and filter down reload without a message`() = runTest {
        reported()
        val vm = vm()
        idle()
        for (code in listOf("forever_not_allowed" to 403, "not_filtering" to 409, "forbidden" to 403)) {
            coEvery { repository.pause(any(), any(), any()) } returns DnsResult.Failed(code.first, code.second, "Satz.")
            vm.pause(DnsDuration.Forever)
            idle()
            assertNull(vm.ui.value.notice, code.first)
        }
        coVerify(exactly = 4) { repository.refresh(any()) }
    }

    @Test
    fun `no network keeps the status and says so, an answer clears it`() = runTest {
        reported()
        val vm = vm()
        idle()
        coEvery { repository.refresh(any()) } returns DnsResult.Offline
        vm.refresh()
        idle()
        assertEquals(DnsNotice.Offline, vm.ui.value.notice)
        assertEquals(active, vm.ui.value.snapshot)
        coEvery { repository.refresh(any()) } coAnswers { ok(active) }
        vm.refresh()
        idle()
        assertNull(vm.ui.value.notice)
    }

    @Test
    fun `plugin not reachable - try later`() = runTest {
        reported()
        coEvery { repository.refresh(any()) } returns DnsResult.Failed("not_running", 503)
        val vm = vm()
        idle()
        assertEquals(DnsNotice.Busy, vm.ui.value.notice)
        assertFalse(vm.ui.value.gone)
        assertTrue(vm.ui.value.loaded)
    }

    @Test
    fun `gone hides the card and asks the permissions again`() = runTest {
        reported()
        coEvery { repository.refresh(any()) } coAnswers {
            snapshots.value = null
            DnsResult.Failed("off", 409, "DNS-Schutz ist aus.")
        }
        val vm = vm()
        idle()
        assertTrue(vm.ui.value.gone)
        assertNull(vm.ui.value.snapshot)
        coVerify(exactly = 1) { repository.reloadPermissions() }
        // The server reports it again later (another plugin id or after it was gone): asked afresh.
        license.clearVpnDns()
        idle()
        coEvery { repository.refresh(any()) } coAnswers { ok(active) }
        reported()
        idle()
        assertFalse(vm.ui.value.gone)
        assertEquals(active, vm.ui.value.snapshot)
    }

    @Test
    fun `too many requests stretch the interval until an answer`() = runTest {
        reported()
        val vm = vm()
        idle()
        assertEquals(DnsLogic.POLL_MS, vm.ui.value.pollMs)
        coEvery { repository.refresh(any()) } returns DnsResult.Failed("rate_limited", 429)
        vm.refresh()
        idle()
        assertEquals(DnsLogic.POLL_SLOW_MS, vm.ui.value.pollMs)
        coEvery { repository.refresh(any()) } coAnswers { ok(active) }
        vm.refresh()
        idle()
        assertEquals(DnsLogic.POLL_MS, vm.ui.value.pollMs)
    }

    @Test
    fun `a change for somebody else reloads the people`() = runTest {
        reported()
        val people = DnsPeople(people = listOf(DnsPerson(4, "Lea", may = DnsMay(pause = true, durations = listOf(5)))))
        coEvery { repository.people() } returns DnsResult.Ok(people)
        coEvery { repository.pause(DnsDuration.Minutes(5), DnsTarget.User(4), now) } coAnswers { ok(active) }
        val vm = vm()
        idle()
        vm.loadPeople()
        idle()
        assertEquals(people, vm.ui.value.people)
        vm.pause(DnsDuration.Minutes(5), DnsTarget.User(4))
        idle()
        coVerify(exactly = 2) { repository.people() }
    }

    @Test
    fun `people that cannot be loaded say so`() = runTest {
        reported()
        coEvery { repository.people() } returns DnsResult.Offline
        val vm = vm()
        idle()
        vm.loadPeople()
        idle()
        assertTrue(vm.ui.value.peopleFailed)
        assertNull(vm.ui.value.people)
        assertFalse(vm.ui.value.peopleLoading)
    }
}
