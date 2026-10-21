package com.gatecontrol.android.ui.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.push.routes.NotifyDest
import com.gatecontrol.android.push.routes.NotifyRouteGroup
import com.gatecontrol.android.push.routes.NotifyRouteTopic
import com.gatecontrol.android.push.routes.NotifyRoutes
import com.gatecontrol.android.push.routes.NotifyRoutesRepository
import com.gatecontrol.android.push.routes.RoutesLoad
import com.gatecontrol.android.push.routes.RoutesSave
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotifyRoutesViewModelTest {

    private val repository: NotifyRoutesRepository = mockk()
    private val viewModels = mutableListOf<ViewModel>()

    private val msgs = NotifyRouteTopic("plugin:gatecontrol-schule:messages", "Nachrichten der Schule", NotifyDest.INBOX, false, null)
    private val changes = NotifyRouteTopic("plugin:gatecontrol-schule:changes", "Stundenplan-Änderungen", NotifyDest.OWN, false, null)
    private val security = NotifyRouteTopic("security", "Sicherheit", NotifyDest.INBOX, true, null)
    private val routes = NotifyRoutes(
        sink = true,
        canFamily = true,
        groups = listOf(
            NotifyRouteGroup("plugin:gatecontrol-schule", "Schule", listOf(msgs, changes)),
            NotifyRouteGroup("core", "GateControl", listOf(security)),
        ),
    )

    @BeforeEach
    fun setUp() {
        // Unconfined: every step runs straight away, no timing involved.
        Dispatchers.setMain(Dispatchers.Unconfined)
        coEvery { repository.load() } returns RoutesLoad.Ok(routes)
    }

    @AfterEach
    fun tearDown() {
        runBlocking { viewModels.forEach { it.viewModelScope.coroutineContext[Job]!!.cancelAndJoin() } }
        Dispatchers.resetMain()
    }

    private fun vm() = NotifyRoutesViewModel(repository).also { viewModels += it }

    private fun <T> await(block: suspend () -> T): T = runBlocking { withTimeout(5_000) { block() } }

    @Test
    fun `loads the routes on open`() {
        val vm = vm()
        val s = vm.uiState.value
        assertFalse(s.loading)
        assertEquals(routes, s.routes)
        assertFalse(s.unsupported)
    }

    @Test
    fun `an older server says so, a failure offers a retry`() {
        coEvery { repository.load() } returns RoutesLoad.Unsupported
        assertTrue(vm().uiState.value.unsupported)
        coEvery { repository.load() } returns RoutesLoad.Failed
        val vm = vm()
        assertTrue(vm.uiState.value.failed)
        assertNull(vm.uiState.value.routes)
        coEvery { repository.load() } returns RoutesLoad.Ok(routes)
        vm.refresh()
        assertEquals(routes, vm.uiState.value.routes)
        assertFalse(vm.uiState.value.failed)
    }

    @Test
    fun `locked topics and a server without chats open no choice`() {
        val vm = vm()
        vm.pick(security.id)
        assertNull(vm.uiState.value.picking)
        vm.pick(msgs.id)
        assertEquals(msgs, vm.uiState.value.picking)
        vm.dismiss()
        assertNull(vm.uiState.value.picking)

        coEvery { repository.load() } returns RoutesLoad.Ok(routes.copy(sink = false))
        val noSink = vm()
        noSink.pick(msgs.id)
        assertNull(noSink.uiState.value.picking)
    }

    @Test
    fun `a choice is shown at once and kept with the server's answer`() {
        val answer = CompletableDeferred<RoutesSave>()
        coEvery { repository.set(msgs.id, NotifyDest.FAMILY) } coAnswers { answer.await() }
        val vm = vm()
        vm.pick(msgs.id)
        vm.choose(NotifyDest.FAMILY)
        // Closed and shown before the server answered.
        assertNull(vm.uiState.value.picking)
        assertEquals(msgs.id, vm.uiState.value.saving)
        assertEquals(NotifyDest.FAMILY, vm.uiState.value.routes!!.topic(msgs.id)!!.dest)
        // A second choice while saving is ignored.
        vm.pick(changes.id)
        assertNull(vm.uiState.value.picking)

        val server = routes.with(msgs.id, NotifyDest.FAMILY).let { r ->
            r.copy(groups = r.groups.map { g -> g.copy(topics = g.topics.map { if (it.id == msgs.id) it.copy(familySetBy = "Marc") else it }) })
        }
        answer.complete(RoutesSave.Ok(server))
        await { vm.uiState.first { it.saving == null } }
        assertEquals("Marc", vm.uiState.value.routes!!.topic(msgs.id)!!.familySetBy)
    }

    @Test
    fun `a plain ok reloads what the server has`() {
        coEvery { repository.set(changes.id, NotifyDest.INBOX) } returns RoutesSave.Ok(null)
        val vm = vm()
        val after = routes.with(changes.id, NotifyDest.INBOX)
        coEvery { repository.load() } returns RoutesLoad.Ok(after)
        vm.pick(changes.id)
        vm.choose(NotifyDest.INBOX)
        await { vm.uiState.first { it.saving == null } }
        assertEquals(after, vm.uiState.value.routes)
    }

    @Test
    fun `a refusal puts the topic back and says why`() {
        coEvery { repository.set(msgs.id, NotifyDest.FAMILY) } returns RoutesSave.Refused(NotifyRoutesRepository.ERR_FAMILY)
        val vm = vm()
        vm.pick(msgs.id)
        vm.choose(NotifyDest.FAMILY)
        assertEquals(RoutesNotice(NotifyRoutesRepository.ERR_FAMILY), await { vm.notice.first() })
        assertNull(vm.uiState.value.saving)
        assertEquals(msgs, vm.uiState.value.routes!!.topic(msgs.id))
        // Reloaded after the refusal (once on open, once now).
        coVerify(exactly = 2) { repository.load() }
    }

    @Test
    fun `the same destination or one not offered sends nothing`() {
        val vm = vm()
        vm.pick(changes.id)
        vm.choose(NotifyDest.OWN)
        assertNull(vm.uiState.value.saving)
        coEvery { repository.load() } returns RoutesLoad.Ok(routes.copy(canFamily = false))
        val child = vm()
        child.pick(msgs.id)
        child.choose(NotifyDest.FAMILY)
        assertNull(child.uiState.value.saving)
        coVerify(exactly = 0) { repository.set(any(), any()) }
    }

    @Test
    fun `entries show only when the server has the page`() {
        val supported = MutableStateFlow<Boolean?>(null)
        every { repository.supported } returns supported
        coEvery { repository.probe() } coAnswers { supported.value = false }
        val hidden = NotifyRoutesEntryViewModel(repository).also { viewModels += it }
        assertFalse(await { hidden.visible.first() })
        coEvery { repository.probe() } coAnswers { supported.value = true }
        supported.value = null
        val shown = NotifyRoutesEntryViewModel(repository).also { viewModels += it }
        assertTrue(await { shown.visible.first { it } })
    }
}
