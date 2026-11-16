package com.gatecontrol.android.ui.chat.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.chat.ChatBridgeError
import com.gatecontrol.android.chat.ChatBridgeHistory
import com.gatecontrol.android.chat.ChatBridgeLogin
import com.gatecontrol.android.chat.ChatBridgeLoginState
import com.gatecontrol.android.chat.ChatBridgeNetwork
import com.gatecontrol.android.chat.ChatBridgeStep
import com.gatecontrol.android.chat.ChatBridges
import com.gatecontrol.android.chat.ChatBridgesInfo
import com.gatecontrol.android.chat.ChatBridgesRepository
import com.gatecontrol.android.chat.ChatQrMatrix
import com.gatecontrol.android.chat.ChatResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * „Verknüpfte Dienste“: the notes before the first link, the link by code and by QR code, waiting for the phone,
 * a code that ran out, leaving half-way, unlinking, a child's lock. The repository is a mock and the main
 * dispatcher a test dispatcher with virtual time — no thread of its own, no real waiting.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LinkedServicesViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val repository: ChatBridgesRepository = mockk(relaxed = true)
    private val viewModels = mutableListOf<ViewModel>()

    private val wa = ChatBridges.WHATSAPP

    private fun info(consent: Boolean = true, allowed: Boolean = true, vararg logins: ChatBridgeLogin) = ChatBridgesInfo(
        available = true,
        networks = listOf(ChatBridgeNetwork(wa, ChatBridges.NET_RUNNING, allowed, if (allowed) null else ChatBridges.LOCK_CHILD, consent, logins = logins.toList())),
    )

    private fun ok(step: ChatBridgeStep, pid: String = "p1"): ChatResult<ChatBridgeLoginState> = ChatResult.Ok(ChatBridgeLoginState(pid, false, step))
    private val waiting: ChatResult<ChatBridgeLoginState> = ChatResult.Ok(ChatBridgeLoginState("p1", true, null))
    private val phone = ChatBridgeStep.Phone("phone", "phone_number")
    private val code = ChatBridgeStep.Code("code", "ABCD-1234")

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { repository.load() } returns ChatResult.Ok(info())
        coEvery { repository.consent(any()) } returns ChatResult.Ok(Unit)
    }

    @AfterEach
    fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        dispatcher.scheduler.advanceUntilIdle()
        Dispatchers.resetMain()
    }

    private fun vm() = LinkedServicesViewModel(repository).also { viewModels += it }

    private fun LinkedServicesViewModel.link() = uiState.value.view as LinkedView.Link

    @Test
    fun `the overview loads - and says so when it cannot`() = scope.runTest {
        val vm = vm()
        assertTrue(vm.uiState.value.loading)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.loading)
        assertEquals(LinkedView.Overview, vm.uiState.value.view)
        assertEquals(wa, vm.uiState.value.info!!.networks.single().id)

        coEvery { repository.load() } returns ChatResult.Offline
        val offline = vm()
        advanceUntilIdle()
        assertNull(offline.uiState.value.info)
        assertEquals(ChatBridgeError.OFFLINE, offline.uiState.value.loadError)
        coEvery { repository.load() } returns ChatResult.Ok(info())
        offline.refresh()
        advanceUntilIdle()
        assertNull(offline.uiState.value.loadError)
        assertFalse(offline.back(), "on the overview the page itself is left")
    }

    @Test
    fun `the first link shows the notes - confirmed, the number is asked for`() = scope.runTest {
        coEvery { repository.load() } returns ChatResult.Ok(info(consent = false))
        coEvery { repository.start(wa, ChatBridges.FLOW_PHONE) } returns ok(phone)
        val vm = vm()
        advanceUntilIdle()
        vm.begin(wa)
        assertEquals(LinkedView.Notes(wa), vm.uiState.value.view)
        coVerify(exactly = 0) { repository.start(any(), any()) }

        vm.confirmNotes()
        assertEquals(LinkedView.Notes(wa, saving = true), vm.uiState.value.view)
        advanceUntilIdle()
        coVerify(exactly = 1) { repository.consent(wa) }
        assertEquals(LinkPhase.Phone("phone", "phone_number"), vm.link().phase)
        assertEquals(ChatBridges.FLOW_PHONE, vm.link().flow)
        assertTrue(vm.uiState.value.info!!.networks.single().consent, "confirmed once per service")
    }

    @Test
    fun `the notes are skipped once confirmed - and stay when the server refuses`() = scope.runTest {
        coEvery { repository.start(wa, ChatBridges.FLOW_PHONE) } returns ok(phone)
        val vm = vm()
        advanceUntilIdle()
        vm.begin(wa)
        assertInstanceOf(LinkedView.Link::class.java, vm.uiState.value.view)
        advanceUntilIdle()
        coVerify(exactly = 0) { repository.consent(any()) }

        coEvery { repository.load() } returns ChatResult.Ok(info(consent = false))
        coEvery { repository.consent(wa) } returns ChatResult.Offline
        val other = vm()
        advanceUntilIdle()
        other.begin(wa)
        other.confirmNotes()
        advanceUntilIdle()
        assertEquals(LinkedView.Notes(wa), other.uiState.value.view)
        assertEquals(LinkedNotice.Failed(ChatBridgeError.OFFLINE), other.notice.first())
        assertTrue(other.back())
        assertEquals(LinkedView.Overview, other.uiState.value.view)
    }

    @Test
    fun `link by code - number, code, waiting for the phone, linked`() = scope.runTest {
        coEvery { repository.start(wa, ChatBridges.FLOW_PHONE) } returns ok(phone)
        coEvery { repository.step(wa, "p1", "phone", mapOf("phone_number" to "+4915120000000")) } returns ok(code)
        coEvery { repository.step(wa, "p1", "code", null) } returnsMany listOf(waiting, waiting, ok(ChatBridgeStep.Complete))
        val vm = vm()
        advanceUntilIdle()
        vm.begin(wa)
        advanceUntilIdle()

        vm.submitPhone("01512 0000000")
        assertEquals(PhoneError.INVALID, (vm.link().phase as LinkPhase.Phone).error, "without the country code nothing is sent")
        coVerify(exactly = 0) { repository.step(any(), any(), any(), any()) }

        vm.submitPhone("+49 1512 0000000")
        assertTrue((vm.link().phase as LinkPhase.Phone).sending)
        dispatcher.scheduler.runCurrent()
        assertEquals("ABCD-1234", (vm.link().phase as LinkPhase.Code).code)

        coEvery { repository.load() } returns ChatResult.Ok(info(logins = arrayOf(ChatBridgeLogin("wa1", "+49 1512 0000000", ChatBridges.LOGIN_CONNECTED, null))))
        advanceUntilIdle()
        coVerify(exactly = 3) { repository.step(wa, "p1", "code", null) }
        assertEquals(LinkedView.Overview, vm.uiState.value.view)
        assertEquals(LinkedNotice.Linked(wa), vm.notice.first())
        assertEquals(ChatBridges.LOGIN_CONNECTED, vm.uiState.value.info!!.networks.single().logins.single().state)
        // nothing is left to drop at the bridge
        assertFalse(vm.back())
        verify(exactly = 0) { repository.cancel(any(), any()) }
    }

    @Test
    fun `a number the service does not take - the field stays`() = scope.runTest {
        coEvery { repository.start(wa, ChatBridges.FLOW_PHONE) } returns ok(phone)
        coEvery { repository.step(wa, "p1", "phone", any()) } returns ChatResult.Failed("bridge_failed", 502, "m_bad_json")
        val vm = vm()
        advanceUntilIdle()
        vm.begin(wa)
        advanceUntilIdle()
        vm.submitPhone("+49 1512 0000000")
        advanceUntilIdle()
        assertEquals(LinkPhase.Phone("phone", "phone_number", sending = false, error = PhoneError.REFUSED), vm.link().phase)
        // the state never holds the number
        assertFalse(vm.uiState.value.toString().contains("1512"))
    }

    @Test
    fun `the code ran out - a new one starts another attempt`() = scope.runTest {
        coEvery { repository.start(wa, ChatBridges.FLOW_PHONE) } returns ok(phone)
        coEvery { repository.step(wa, "p1", "phone", any()) } returns ok(code)
        coEvery { repository.step(wa, "p1", "code", null) } returnsMany listOf(waiting, ChatResult.Failed("bridge_failed", 502, "fi_mau_whatsapp_login_timeout"))
        val vm = vm()
        advanceUntilIdle()
        vm.begin(wa)
        advanceUntilIdle()
        vm.submitPhone("+4915120000000")
        advanceUntilIdle()
        assertEquals(LinkPhase.Failed(ChatBridgeError.EXPIRED), vm.link().phase)
        assertFalse(vm.uiState.value.toString().contains("ABCD"), "the code is gone with the attempt")
        verify(exactly = 0) { repository.cancel(any(), any()) }

        coEvery { repository.start(wa, ChatBridges.FLOW_PHONE) } returns ok(phone, pid = "p2")
        vm.retry()
        assertEquals(LinkPhase.Starting, vm.link().phase)
        advanceUntilIdle()
        assertEquals(LinkPhase.Phone("phone", "phone_number"), vm.link().phase)
        coVerify(exactly = 2) { repository.start(wa, ChatBridges.FLOW_PHONE) }
    }

    @Test
    fun `link by QR code - a renewed code is a new step - and the way back drops the login`() = scope.runTest {
        val first = ChatQrMatrix(1, listOf("1"))
        val second = ChatQrMatrix(1, listOf("0"))
        coEvery { repository.start(wa, ChatBridges.FLOW_PHONE) } returns ok(phone)
        coEvery { repository.start(wa, ChatBridges.FLOW_QR) } returns ok(ChatBridgeStep.Qr("qr1", first), pid = "p2")
        coEvery { repository.step(wa, "p2", "qr1", null) } returns ok(ChatBridgeStep.Qr("qr2", second), pid = "p2")
        coEvery { repository.step(wa, "p2", "qr2", null) } returns ChatResult.Ok(ChatBridgeLoginState("p2", true, null))
        val vm = vm()
        advanceUntilIdle()
        vm.begin(wa)
        advanceUntilIdle()

        vm.selectFlow(ChatBridges.FLOW_QR)
        verify(exactly = 1) { repository.cancel(wa, "p1") }
        assertEquals(ChatBridges.FLOW_QR, vm.link().flow)
        dispatcher.scheduler.runCurrent()
        // the first code was replaced by the second one at once; the page waits on that
        assertEquals(second, (vm.link().phase as LinkPhase.Qr).matrix)
        dispatcher.scheduler.advanceTimeBy(LinkedServicesViewModel.POLL_MS * 3)
        assertEquals(second, (vm.link().phase as LinkPhase.Qr).matrix)

        assertTrue(vm.back())
        assertEquals(LinkedView.Overview, vm.uiState.value.view)
        verify(exactly = 1) { repository.cancel(wa, "p2") }
        val before = dispatcher.scheduler.currentTime
        advanceUntilIdle()
        assertTrue(dispatcher.scheduler.currentTime - before < LinkedServicesViewModel.POLL_MS, "nobody asks the bridge any more")
    }

    @Test
    fun `a gap in the connection while a code shows is waited out - a long one ends the attempt`() = scope.runTest {
        coEvery { repository.start(wa, ChatBridges.FLOW_PHONE) } returns ok(phone)
        coEvery { repository.step(wa, "p1", "phone", any()) } returns ok(code)
        coEvery { repository.step(wa, "p1", "code", null) } returnsMany listOf(ChatResult.Offline, ChatResult.Offline, waiting) + List(4) { ChatResult.Offline }
        val vm = vm()
        advanceUntilIdle()
        vm.begin(wa)
        advanceUntilIdle()
        vm.submitPhone("+4915120000000")
        dispatcher.scheduler.advanceTimeBy(LinkedServicesViewModel.POLL_MS * 3 + 1)
        assertInstanceOf(LinkPhase.Code::class.java, vm.link().phase, "two calls without an answer: the code still shows")
        advanceUntilIdle()
        assertEquals(LinkPhase.Failed(ChatBridgeError.OFFLINE), vm.link().phase)
        verify(exactly = 1) { repository.cancel(wa, "p1") }
        coVerify(exactly = 7) { repository.step(wa, "p1", "code", null) }
    }

    @Test
    fun `no connection at the start, a step that cannot be shown`() = scope.runTest {
        coEvery { repository.start(wa, ChatBridges.FLOW_PHONE) } returns ChatResult.Offline
        coEvery { repository.start(wa, ChatBridges.FLOW_QR) } returns ok(ChatBridgeStep.Unsupported, pid = "p9")
        val vm = vm()
        advanceUntilIdle()
        vm.begin(wa)
        advanceUntilIdle()
        assertEquals(LinkPhase.Failed(ChatBridgeError.OFFLINE), vm.link().phase)
        vm.selectFlow(ChatBridges.FLOW_QR)
        advanceUntilIdle()
        assertEquals(LinkPhase.Failed(ChatBridgeError.UNSUPPORTED), vm.link().phase)
        verify(exactly = 1) { repository.cancel(wa, "p9") }
    }

    @Test
    fun `a child - nothing starts, and a lock that came in between leads back to the overview`() = scope.runTest {
        coEvery { repository.load() } returns ChatResult.Ok(info(consent = false, allowed = false))
        val vm = vm()
        advanceUntilIdle()
        vm.begin(wa)
        assertEquals(LinkedView.Overview, vm.uiState.value.view, "locked: no notes, no link")
        coVerify(exactly = 0) { repository.start(any(), any()) }

        // the parents took the permission back while the page was open
        coEvery { repository.load() } returns ChatResult.Ok(info())
        val late = vm()
        advanceUntilIdle()
        coEvery { repository.start(wa, ChatBridges.FLOW_PHONE) } returns ChatResult.Failed("bridge_locked", 403)
        coEvery { repository.load() } returns ChatResult.Ok(info(allowed = false))
        late.begin(wa)
        advanceUntilIdle()
        assertEquals(LinkedView.Overview, late.uiState.value.view)
        assertEquals(LinkedNotice.Failed(ChatBridgeError.LOCKED), late.notice.first())
        assertFalse(late.uiState.value.info!!.networks.single().allowed)
    }

    @Test
    fun `the notes were not confirmed after all - they show again`() = scope.runTest {
        coEvery { repository.start(wa, ChatBridges.FLOW_PHONE) } returns ChatResult.Failed("consent_required", 409)
        val vm = vm()
        advanceUntilIdle()
        vm.begin(wa)
        advanceUntilIdle()
        assertEquals(LinkedView.Notes(wa), vm.uiState.value.view)
    }

    @Test
    fun `unlink - the login that matters, a message, the overview anew`() = scope.runTest {
        coEvery { repository.load() } returns ChatResult.Ok(info(logins = arrayOf(ChatBridgeLogin("wa1", "+49 1512 0000000", ChatBridges.LOGIN_CONNECTED, null))))
        coEvery { repository.unlink(wa, "wa1") } returns ChatResult.Ok(Unit)
        val vm = vm()
        advanceUntilIdle()
        coEvery { repository.load() } returns ChatResult.Ok(info())
        vm.unlink(wa)
        assertEquals(wa, vm.uiState.value.unlinking)
        vm.unlink(wa)
        advanceUntilIdle()
        coVerify(exactly = 1) { repository.unlink(wa, "wa1") }
        assertNull(vm.uiState.value.unlinking)
        assertEquals(LinkedNotice.Unlinked(wa), vm.notice.first())
        assertTrue(vm.uiState.value.info!!.networks.single().logins.isEmpty())

        // nothing linked: nothing to unlink
        vm.unlink(wa)
        advanceUntilIdle()
        coVerify(exactly = 1) { repository.unlink(any(), any()) }
    }

    @Test
    fun `unlink refused - the page says why`() = scope.runTest {
        coEvery { repository.load() } returns ChatResult.Ok(info(logins = arrayOf(ChatBridgeLogin("wa1", "", ChatBridges.LOGIN_LOGGED_OUT, null))))
        coEvery { repository.unlink(wa, "wa1") } returns ChatResult.Failed("bridge_unreachable", 503, "timeout")
        val vm = vm()
        advanceUntilIdle()
        vm.unlink(wa, quiet = true)
        advanceUntilIdle()
        assertEquals(LinkedNotice.Failed(ChatBridgeError.UNREACHABLE), vm.notice.first())
    }

    @Test
    fun `leaving while the bridge still answers drops the login it started`() = scope.runTest {
        val answer = CompletableDeferred<ChatResult<ChatBridgeLoginState>>()
        coEvery { repository.start(wa, ChatBridges.FLOW_PHONE) } coAnswers { answer.await() }
        val vm = vm()
        advanceUntilIdle()
        vm.begin(wa)
        dispatcher.scheduler.runCurrent()
        assertTrue(vm.back())
        verify(exactly = 0) { repository.cancel(any(), any()) }
        answer.complete(ok(phone, pid = "late"))
        advanceUntilIdle()
        verify(exactly = 1) { repository.cancel(wa, "late") }
        assertEquals(LinkedView.Overview, vm.uiState.value.view)
    }

    // ─── Messenger 1.17: older messages ─────────────────────────────

    private fun withHistory(h: ChatBridgeHistory?) = info(true, true, ChatBridgeLogin("wa1", "", ChatBridges.LOGIN_CONNECTED, null)).copy(history = h)

    @Test
    fun `the choice of older messages - saved once, the answer shown, a refusal says why`() = scope.runTest {
        val h = ChatBridgeHistory(available = true, days = 30, pending = 40)
        coEvery { repository.load() } returns ChatResult.Ok(withHistory(h))
        val saved = CompletableDeferred<ChatResult<ChatBridgeHistory>>()
        coEvery { repository.setHistory(-1) } coAnswers { saved.await() }
        val vm = vm()
        advanceUntilIdle()

        vm.setHistory(30)
        vm.setHistory(5)
        advanceUntilIdle()
        coVerify(exactly = 0) { repository.setHistory(any()) }

        vm.setHistory(-1)
        assertTrue(vm.uiState.value.historySaving)
        vm.setHistory(0)
        advanceUntilIdle()
        saved.complete(ChatResult.Ok(h.copy(days = -1, pending = 0)))
        advanceUntilIdle()
        coVerify(exactly = 1) { repository.setHistory(-1) }
        coVerify(exactly = 0) { repository.setHistory(0) }
        assertFalse(vm.uiState.value.historySaving)
        assertEquals(-1, vm.uiState.value.info!!.history!!.days)
        assertEquals(LinkedNotice.HistorySaved, vm.notice.first())

        // switched off on the server meanwhile: said, and the page loads anew
        coEvery { repository.setHistory(0) } returns ChatResult.Failed("feature_off", 403)
        coEvery { repository.load() } returns ChatResult.Ok(withHistory(h.copy(available = false)))
        vm.setHistory(0)
        advanceUntilIdle()
        assertEquals(LinkedNotice.Failed(ChatBridgeError.OFF), vm.notice.first())
        assertFalse(vm.uiState.value.info!!.history!!.available)
        assertFalse(vm.uiState.value.historySaving)
    }

    @Test
    fun `no choice where the server has none or the administrator switched it off`() = scope.runTest {
        coEvery { repository.load() } returns ChatResult.Ok(withHistory(null))
        val vm = vm()
        advanceUntilIdle()
        vm.setHistory(0)
        coEvery { repository.load() } returns ChatResult.Ok(withHistory(ChatBridgeHistory(available = false, days = 30)))
        val off = vm()
        advanceUntilIdle()
        off.setHistory(0)
        advanceUntilIdle()
        coVerify(exactly = 0) { repository.setHistory(any()) }
    }

    // ─── Messenger 1.23: Signal, by QR code on a second screen ──────

    private fun signalInfo(linked: Boolean) = ChatBridgesInfo(
        available = true,
        networks = listOf(
            ChatBridgeNetwork(
                ChatBridges.SIGNAL, ChatBridges.NET_RUNNING, true, null, consent = true,
                logins = if (linked) listOf(ChatBridgeLogin("l1", "", ChatBridges.LOGIN_CONNECTED, null)) else emptyList(),
                flows = listOf(ChatBridges.FLOW_QR), historyMaxDays = 30, phoneConfirms = true,
            ),
        ),
    )

    @Test
    fun `Signal - the second screen first, the step goes to Windows, the page waits for the link`() = scope.runTest {
        coEvery { repository.load() } returns ChatResult.Ok(signalInfo(linked = false))
        coEvery { repository.handoff(any(), any()) } returns ChatResult.Ok(com.gatecontrol.android.chat.ChatBridgeHandoff(ChatBridges.SIGNAL, ChatBridges.HANDOFF_WINDOWS, null))
        val m = vm()
        advanceUntilIdle()
        m.begin(ChatBridges.SIGNAL)
        assertEquals(LinkedView.SecondScreen(ChatBridges.SIGNAL), m.uiState.value.view, "no QR code on the phone itself")
        coVerify(exactly = 0) { repository.start(any(), any()) }
        coEvery { repository.load() } returns ChatResult.Ok(signalInfo(linked = true))
        m.handoff(ChatBridges.HANDOFF_WINDOWS)
        advanceUntilIdle()
        coVerify { repository.handoff(ChatBridges.SIGNAL, ChatBridges.HANDOFF_WINDOWS) }
        assertEquals(LinkedView.Overview, m.uiState.value.view)
        assertEquals(ChatBridges.SIGNAL, m.uiState.value.phoneHistory, "the phone asks about the history now")
        m.dismissPhoneHistory()
        assertNull(m.uiState.value.phoneHistory)
    }

    @Test
    fun `Signal - another phone with Signal shows the code here after all`() = scope.runTest {
        coEvery { repository.load() } returns ChatResult.Ok(signalInfo(linked = false))
        coEvery { repository.start(any(), any()) } returns waiting
        val m = vm()
        advanceUntilIdle()
        m.begin(ChatBridges.SIGNAL)
        m.showCodeHere()
        advanceUntilIdle()
        coVerify { repository.start(ChatBridges.SIGNAL, ChatBridges.FLOW_QR) }
        verify(exactly = 0) { repository.endHandoff(any()) }
    }
}
