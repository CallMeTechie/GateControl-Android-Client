package com.gatecontrol.android.terminal

import com.gatecontrol.android.familie.PluginAvailability
import com.gatecontrol.android.familie.PluginDirectory
import com.gatecontrol.android.remote.RemoteApi
import com.gatecontrol.android.remote.RemoteResult
import com.gatecontrol.android.remote.TmuxKillAnswer
import com.google.gson.JsonPrimitive
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Ending tmux sessions and closing windows from the sheet (plugin 1.8): the new list from the answer, `tmux_gone` and
 * `tmux_changed` load again without an error, other errors are shown, nothing without `kill`, and „Fenster öffnen“
 * hands the window to the terminal.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SshDevicesViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val api = mockk<RemoteApi>()
    private val sessions = mockk<TermSessions>(relaxed = true)
    private val plugins = mockk<PluginDirectory>()

    private val device = SshDevice(2, "appserver", null, true, "term", "gc-appserver", "off")
    private val win0 = TmuxWindow("@1", 0, "bash", true, "bash")
    private val win1 = TmuxWindow("@2", 1, "htop", false, "htop")
    private val main = TmuxSession("gc-appserver", 2, 0, 2_000L, true, "$1", listOf(win0, win1), created = JsonPrimitive(1763380000))
    private val build = TmuxSession("build", 1, 0, 1_000L, true, "$3", listOf(win0), created = JsonPrimitive(1763200000))
    private val list = TmuxList(listOf(main, build), "gc-appserver", "pinned", kill = true)
    private val after = list.copy(sessions = listOf(build))

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { sessions.ui } returns MutableStateFlow(null)
        every { plugins.state } returns MutableStateFlow(PluginAvailability())
        coEvery { api.tmuxSessions(2) } returns RemoteResult.Ok(list)
    }

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private fun model() = SshDevicesViewModel(api, sessions, plugins)

    private fun loaded(vm: SshDevicesViewModel) = (vm.sheet.value!!.tmux as TmuxState.Loaded).list

    @Test
    fun `ending a session shows the new list of the answer`() = runTest(dispatcher) {
        val vm = model()
        vm.select(device)
        advanceUntilIdle()
        coEvery { api.tmuxKill(2, main, null) } returns RemoteResult.Ok(TmuxKillAnswer(after))
        vm.kill(TmuxKill.Session(main))
        assertEquals(true, vm.sheet.value!!.killing)
        advanceUntilIdle()
        assertEquals(after, loaded(vm))
        assertFalse(vm.sheet.value!!.killing)
        assertNull(vm.sheet.value!!.killError)
        coVerify(exactly = 1) { api.tmuxSessions(2) }
    }

    @Test
    fun `gone or changed meanwhile loads again without an error`() = runTest(dispatcher) {
        val vm = model()
        vm.select(device)
        advanceUntilIdle()
        coEvery { api.tmuxKill(2, main, win1) } returns RemoteResult.Failed("tmux_gone", 404)
        coEvery { api.tmuxSessions(2) } returns RemoteResult.Ok(after)
        vm.kill(TmuxKill.Window(main, win1))
        advanceUntilIdle()
        assertNull(vm.sheet.value!!.killError)
        assertEquals(after, loaded(vm))

        coEvery { api.tmuxKillDetached(2, any()) } returns RemoteResult.Failed("tmux_changed", 409)
        vm.kill(TmuxKill.Detached(listOf(build)))
        advanceUntilIdle()
        assertNull(vm.sheet.value!!.killError)
        coVerify(exactly = 3) { api.tmuxSessions(2) }
    }

    @Test
    fun `other errors are shown, the list stays`() = runTest(dispatcher) {
        val vm = model()
        vm.select(device)
        advanceUntilIdle()
        coEvery { api.tmuxKill(2, build, null) } returns RemoteResult.Failed("auth_failed", 502)
        vm.kill(TmuxKill.Session(build))
        advanceUntilIdle()
        assertEquals("auth_failed", vm.sheet.value!!.killError)
        assertEquals(list, loaded(vm))
    }

    @Test
    fun `without kill nothing is sent`() = runTest(dispatcher) {
        coEvery { api.tmuxSessions(2) } returns RemoteResult.Ok(list.copy(kill = false))
        val vm = model()
        vm.select(device)
        advanceUntilIdle()
        vm.kill(TmuxKill.Session(main))
        advanceUntilIdle()
        coVerify(exactly = 0) { api.tmuxKill(any(), any(), any()) }
    }

    @Test
    fun `open a window attaches the session and hands the window on`() = runTest(dispatcher) {
        val ticket = TermTicket("R".repeat(43), 0L, false, "gc-appserver", 2, "appserver")
        coEvery { api.termTicket(2, TmuxChoice.Attach("gc-appserver"), "off", false) } returns RemoteResult.Ok(ticket)
        val vm = model()
        vm.select(device)
        advanceUntilIdle()
        vm.connect(TmuxChoice.Attach("gc-appserver"), false, 1)
        advanceUntilIdle()
        verify { sessions.start(ticket, "appserver", 1) }
        assertNull(vm.sheet.value)
    }
}
