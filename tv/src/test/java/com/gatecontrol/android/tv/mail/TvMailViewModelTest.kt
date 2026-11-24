package com.gatecontrol.android.tv.mail

import com.gatecontrol.android.mail.MAddress
import com.gatecontrol.android.mail.MBulkUndo
import com.gatecontrol.android.mail.MResult
import com.gatecontrol.android.mail.MSend
import com.gatecontrol.android.mail.MailBulk
import com.gatecontrol.android.tv.input.TvComposerKit
import com.gatecontrol.android.tv.mail.TvMailFixtures.account
import com.gatecontrol.android.tv.mail.TvMailFixtures.draft
import com.gatecontrol.android.tv.mail.TvMailFixtures.me
import com.gatecontrol.android.tv.mail.TvMailFixtures.message
import com.gatecontrol.android.tv.mail.TvMailFixtures.summary
import com.gatecontrol.android.tv.zuhause.TvSourceState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** The page „Mail“: read after 3 s, answering through a draft with „Rückgängig“, archive with „Rückgängig“, the accounts. */
@OptIn(ExperimentalCoroutinesApi::class)
class TvMailViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val data = MutableStateFlow(
        TvMailData(
            me = me(account(1), account(2)),
            meState = TvSourceState.OK,
            lists = mapOf(1L to listOf(summary(1, unread = true), summary(2, unread = true))),
            listStates = mapOf(1L to TvSourceState.OK),
        ),
    )
    private val chosen = MutableStateFlow(setOf(1L))
    private val mail = mockk<TvMail>(relaxed = true) { every { data } returns this@TvMailViewModelTest.data }
    private val settings = mockk<TvMailSettings>(relaxed = true) { every { accounts } returns chosen }
    private val kit = mockk<TvComposerKit>(relaxed = true)

    @BeforeEach
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.vm(): TvMailViewModel {
        val vm = TvMailViewModel(mail, settings, kit)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.ui.collect {} }
        return vm
    }

    @Test
    fun `the list - the inbox of the chosen accounts, the other ones offered in the settings`() = runTest(dispatcher) {
        val vm = vm()
        runCurrent()
        val ui = vm.ui.value
        assertEquals(listOf(2L, 1L), ui.items.map { it.id })
        assertEquals(listOf(1L), ui.accounts.map { it.id })
        assertEquals(listOf(1L, 2L), ui.allAccounts.map { it.id })
        assertEquals(2, ui.unread)
        assertEquals(TvSourceState.OK, ui.listState)
    }

    @Test
    fun `a mail counts as read only after 3 s on the screen`() = runTest(dispatcher) {
        coEvery { mail.message(1) } returns MResult.Ok(message(1, unread = true))
        val vm = vm()
        vm.select(summary(1, unread = true))
        runCurrent()
        assertEquals(1L, vm.ui.value.message?.id)
        advanceTimeBy(TvMailLogic.READ_AFTER_MS - 1)
        runCurrent()
        coVerify(exactly = 0) { mail.markRead(any()) }
        advanceTimeBy(2)
        runCurrent()
        coVerify(exactly = 1) { mail.markRead(1) }
        assertEquals(false, vm.ui.value.message?.summary?.unread)
    }

    @Test
    fun `leafing through the list marks nothing read`() = runTest(dispatcher) {
        coEvery { mail.message(1) } returns MResult.Ok(message(1, unread = true))
        coEvery { mail.message(2) } returns MResult.Ok(message(2, unread = true))
        val vm = vm()
        vm.select(summary(1, unread = true))
        runCurrent()
        advanceTimeBy(2_000)
        vm.select(summary(2, unread = true))
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()
        coVerify(exactly = 0) { mail.markRead(any()) }
        advanceTimeBy(1_500)
        runCurrent()
        coVerify(exactly = 0) { mail.markRead(1) }
        coVerify(exactly = 1) { mail.markRead(2) }
    }

    @Test
    fun `answer - the servers draft, the text sent, undo opens it again with the text`() = runTest(dispatcher) {
        val d = draft(5)
        coEvery { mail.startDraft(TvMailMode.REPLY, 1) } returns MResult.Ok(d)
        coEvery { mail.send(d, TvMailMode.REPLY, 1, "Ja, passt.", null, any()) } returns MResult.Ok(TvMailSentOk(MSend(77, null, null), 10_000))
        coEvery { mail.cancelSend(77) } returns MResult.Ok(5L)
        coEvery { mail.draft(5) } returns MResult.Ok(d)
        val vm = vm()
        vm.answer(TvMailMode.REPLY, 1)
        runCurrent()
        val w = vm.ui.value.compose as TvMailCompose.Writing
        assertEquals(5L, w.draft.id)
        assertEquals("", w.initial)

        assertTrue(vm.send("Ja, passt."))
        runCurrent()
        assertEquals(TvMailCompose.Closed, vm.ui.value.compose)
        assertEquals(77L, vm.ui.value.sent?.outboxId)

        advanceTimeBy(3_000)
        vm.undoSend()
        runCurrent()
        coVerify(exactly = 1) { mail.cancelSend(77) }
        assertNull(vm.ui.value.sent)
        val again = vm.ui.value.compose as TvMailCompose.Writing
        assertEquals("Ja, passt.", again.initial)
        assertEquals(TvMailMessage.SEND_STOPPED, vm.ui.value.note)
        // the draft stays on the server (it is open again)
        coVerify(exactly = 0) { mail.discard(any()) }
    }

    @Test
    fun `sent - after the window undo goes and says sent`() = runTest(dispatcher) {
        val d = draft(5)
        coEvery { mail.startDraft(TvMailMode.REPLY, 1) } returns MResult.Ok(d)
        coEvery { mail.send(any(), any(), any(), any(), any(), any()) } returns MResult.Ok(TvMailSentOk(MSend(77, null, null), 10_000))
        val vm = vm()
        vm.answer(TvMailMode.REPLY, 1)
        runCurrent()
        assertTrue(vm.send("Ja"))
        advanceTimeBy(10_001)
        runCurrent()
        assertNull(vm.ui.value.sent)
        assertEquals(TvMailMessage.SENT, vm.ui.value.note)
        coVerify(exactly = 0) { mail.cancelSend(any()) }
    }

    @Test
    fun `not sent - the composer stays, nothing to undo`() = runTest(dispatcher) {
        val d = draft(5)
        coEvery { mail.startDraft(TvMailMode.REPLY, 1) } returns MResult.Ok(d)
        coEvery { mail.send(any(), any(), any(), any(), any(), any()) } returns MResult.Offline
        val vm = vm()
        vm.answer(TvMailMode.REPLY, 1)
        runCurrent()
        assertEquals(false, vm.send("Ja"))
        runCurrent()
        assertTrue(vm.ui.value.compose is TvMailCompose.Writing)
        assertNull(vm.ui.value.sent)
        assertEquals(TvMailMessage.OFFLINE, vm.ui.value.note)
    }

    @Test
    fun `closed without sending - the draft goes, unless it went to the phone`() = runTest(dispatcher) {
        val d = draft(5)
        coEvery { mail.startDraft(any(), 1) } returns MResult.Ok(d)
        val vm = vm()
        vm.answer(TvMailMode.REPLY, 1)
        runCurrent()
        vm.closeCompose()
        runCurrent()
        coVerify(exactly = 1) { mail.discard(5) }

        vm.answer(TvMailMode.REPLY, 1)
        runCurrent()
        val w = vm.ui.value.compose as TvMailCompose.Writing
        every { kit.handoff(any()) } returns com.gatecontrol.android.tv.input.TvHandoff({ emptyList() }, { _, _ -> com.gatecontrol.android.tv.gatedrop.TvDropSend.Failed("x") })
        vm.handoff(w).send(mockk(relaxed = true), "Text fürs Handy")
        coVerify { mail.save(d, TvMailMode.REPLY, 1, "Text fürs Handy", null) }
        vm.closeCompose()
        runCurrent()
        coVerify(exactly = 1) { mail.discard(5) }
    }

    @Test
    fun `forward - only to someone Mail knows`() = runTest(dispatcher) {
        val d = draft(6, to = emptyList())
        val jonas = MAddress("Jonas", "jonas@example.com")
        coEvery { mail.startDraft(TvMailMode.FORWARD, 1) } returns MResult.Ok(d)
        coEvery { mail.forwardTargets("") } returns listOf(jonas)
        val vm = vm()
        vm.answer(TvMailMode.FORWARD, 1)
        runCurrent()
        val p = vm.ui.value.compose as TvMailCompose.Picking
        assertEquals(listOf(jonas), p.targets)
        vm.pick(jonas)
        runCurrent()
        val w = vm.ui.value.compose as TvMailCompose.Writing
        assertEquals(listOf(jonas), w.to)
        assertEquals(TvMailMode.FORWARD, w.mode)
    }

    @Test
    fun `archive - out of the list, undo with the servers token`() = runTest(dispatcher) {
        coEvery { mail.move(2, MailBulk.Action.ARCHIVE) } returns TvMailMoved(TvMailDone.OK, MBulkUndo("tok-1", "archive", 1, null))
        coEvery { mail.undoMove("tok-1") } returns TvMailDone.OK
        val vm = vm()
        vm.archive(2)
        runCurrent()
        assertEquals("tok-1", vm.ui.value.moved?.token)
        vm.undoMove()
        runCurrent()
        coVerify(exactly = 1) { mail.undoMove("tok-1") }
        assertNull(vm.ui.value.moved)
        assertEquals(TvMailMessage.BACK, vm.ui.value.note)
    }

    @Test
    fun `the accounts on the TV - switched in the settings, the inboxes load again`() = runTest(dispatcher) {
        val vm = vm()
        vm.setAccount(2, true)
        verify { settings.setAccount(2, true) }
        verify { mail.refreshAsync(true) }
    }
}
