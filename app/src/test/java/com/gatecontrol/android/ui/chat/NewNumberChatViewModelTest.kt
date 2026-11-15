package com.gatecontrol.android.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.chat.ChatBridgeLogin
import com.gatecontrol.android.chat.ChatBridgeNetwork
import com.gatecontrol.android.chat.ChatBridgeNewChat
import com.gatecontrol.android.chat.ChatBridges
import com.gatecontrol.android.chat.ChatBridgesInfo
import com.gatecontrol.android.chat.ChatBridgesRepository
import com.gatecontrol.android.chat.ChatConversation
import com.gatecontrol.android.chat.ChatCountry
import com.gatecontrol.android.chat.ChatJson
import com.gatecontrol.android.chat.ChatNewChat
import com.gatecontrol.android.chat.ChatNumberChat
import com.gatecontrol.android.chat.ChatNumberLookup
import com.gatecontrol.android.chat.ChatRepository
import com.gatecontrol.android.chat.ChatResult
import com.google.gson.JsonParser
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * "WhatsApp-Chat mit Nummer": loading the service's state, typing, one check per "Prüfen", found / not found / the
 * chat there was, "Chat starten" opens the chat, too many checks (and back after the wait), a refusal of the bridge,
 * "Andere Nummer". Mocked repositories, virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NewNumberChatViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val bridges: ChatBridgesRepository = mockk(relaxed = true)
    private val repository: ChatRepository = mockk(relaxed = true)
    private val viewModels = mutableListOf<ViewModel>()
    private val wa = ChatBridges.WHATSAPP

    private fun info(newChat: ChatBridgeNewChat? = ChatBridgeNewChat(true, perHour = 10, left = 7, retryAfterS = 0)) = ChatBridgesInfo(
        available = true,
        networks = listOf(ChatBridgeNetwork(wa, ChatBridges.NET_RUNNING, logins = listOf(ChatBridgeLogin("l1", "+49 170 1", ChatBridges.LOGIN_CONNECTED, null)), newChat = newChat)),
    )

    private fun conv(id: Long): ChatConversation = ChatJson.conversation(
        JsonParser.parseString(
            """{"id":$id,"kind":"direct","title":"Lena Berger","peer":null,"members":[{"userId":7,"name":"Anna"}],"me":{"role":"member","notify":"all"},
                "bridge":{"network":"whatsapp","label":"WhatsApp","state":"ok","supports":["text"]},"can":{"send":true}}""",
        ).asJsonObject,
    )!!

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { bridges.load() } returns ChatResult.Ok(info())
    }

    @AfterEach
    fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        dispatcher.scheduler.advanceUntilIdle()
        Dispatchers.resetMain()
    }

    private fun vm() = NewNumberChatViewModel(bridges, repository).also {
        viewModels += it
        it.now = { dispatcher.scheduler.currentTime }
    }

    @Test
    fun `typing asks nothing - Pruefen asks once, found, Chat starten opens the new chat`() = scope.runTest {
        coEvery { bridges.resolve(wa, "+4915123456789") } returns ChatResult.Ok(ChatNumberLookup(true, "Lena Berger", null))
        coEvery { repository.openNumberChat(wa, "+4915123456789") } returns ChatResult.Ok(ChatNumberChat(conv(44), true))
        val vm = vm()
        vm.bind(wa)
        advanceUntilIdle()
        assertEquals(NumberPhase.Input, vm.state.value.phase)
        vm.setInput("0151 234")
        assertFalse(vm.state.value.number.ok)
        vm.check()
        vm.setInput("0151 23456789")
        advanceUntilIdle()
        coVerify(exactly = 0) { bridges.resolve(any(), any()) }
        assertEquals("+49 151 23456789", vm.state.value.number.display)
        vm.check()
        assertEquals(NumberPhase.Checking, vm.state.value.phase)
        advanceUntilIdle()
        val found = vm.state.value.phase as NumberPhase.Found
        assertEquals("Lena Berger", found.name)
        assertEquals("+49 151 23456789", found.display)
        vm.start()
        advanceUntilIdle()
        assertEquals(44L, vm.open.first())
        coVerify(exactly = 1) { bridges.resolve(any(), any()) }
        coVerify(exactly = 1) { repository.openNumberChat(wa, "+4915123456789") }
        assertFalse(vm.state.value.toString().contains("151"), "no number in toString")
        // the first line of the new chat (memory only)
        assertEquals("+49 151 23456789", com.gatecontrol.android.chat.ChatNumberStarted.get(44)!!.display)
    }

    @Test
    fun `the chat there was - Chat oeffnen opens it without starting one`() = scope.runTest {
        coEvery { bridges.resolve(any(), any()) } returns ChatResult.Ok(ChatNumberLookup(true, "Lena Berger", 31))
        val vm = vm()
        vm.bind(wa)
        advanceUntilIdle()
        vm.setInput("+49 151 23456789")
        vm.check()
        advanceUntilIdle()
        assertEquals(31L, (vm.state.value.phase as NumberPhase.Found).existing)
        vm.start()
        advanceUntilIdle()
        assertEquals(31L, vm.open.first())
        coVerify(exactly = 1) { repository.refreshConversation(31) }
        coVerify(exactly = 0) { repository.openNumberChat(any(), any()) }
    }

    @Test
    fun `not at the service, then Andere Nummer, then editing drops a result`() = scope.runTest {
        coEvery { bridges.resolve(any(), any()) } returns ChatResult.Ok(ChatNumberLookup(false, null, null))
        val vm = vm()
        vm.bind(wa)
        advanceUntilIdle()
        vm.setCountry(ChatCountry("AT", "43"))
        vm.setInput("0664 1234567")
        vm.check()
        advanceUntilIdle()
        assertEquals("+43 664 1234567", (vm.state.value.phase as NumberPhase.NotFound).display)
        vm.setInput("0664 1234568")
        assertEquals(NumberPhase.Input, vm.state.value.phase)
        vm.other()
        assertEquals("", vm.state.value.input)
        // a number given to the page brings its own country
        vm.given("+41 79 123 45 67")
        assertEquals("CH", vm.state.value.country.iso)
        assertTrue(vm.state.value.number.ok)
    }

    @Test
    fun `too many checks - the button waits, and after the wait it works again`() = scope.runTest {
        coEvery { bridges.resolve(any(), any()) } returns ChatResult.Failed("rate_limited", 429, retryAfterS = 60)
        val vm = vm()
        vm.bind(wa)
        advanceUntilIdle()
        vm.setInput("0151 23456789")
        vm.check()
        advanceTimeBy(1_000)
        val rate = vm.state.value.phase as NumberPhase.RateLimited
        assertEquals(60_000L, rate.untilMs!! - 0L, "from the server's retryAfterS")
        assertEquals(10, vm.state.value.perHour)
        vm.check()
        coVerify(exactly = 1) { bridges.resolve(any(), any()) }
        advanceTimeBy(60_000)
        advanceUntilIdle()
        assertEquals(NumberPhase.Input, vm.state.value.phase)
    }

    @Test
    fun `a limit used up already when the page opens`() = scope.runTest {
        coEvery { bridges.load() } returns ChatResult.Ok(info(ChatBridgeNewChat(true, perHour = 10, left = 0, retryAfterS = 300)))
        val vm = vm()
        vm.bind(wa)
        advanceTimeBy(10)
        assertInstanceOf(NumberPhase.RateLimited::class.java, vm.state.value.phase)
    }

    @Test
    fun `signed out, locked, not reachable - what the page says first and after a refusal`() = scope.runTest {
        coEvery { bridges.load() } returns ChatResult.Ok(info(ChatBridgeNewChat(false, ChatNewChat.R_LOGGED_OUT)))
        val vm = vm()
        vm.bind(wa)
        advanceUntilIdle()
        assertEquals(NumberPhase.Blocked(ChatNewChat.Blocked.LOGGED_OUT), vm.state.value.phase)
        vm.setInput("0151 23456789")
        assertEquals("", vm.state.value.input, "no field while blocked")

        coEvery { bridges.load() } returns ChatResult.Ok(info())
        coEvery { bridges.resolve(any(), any()) } returns ChatResult.Failed("bridge_unreachable", 503)
        val vm2 = vm()
        vm2.bind(wa)
        advanceUntilIdle()
        vm2.setInput("0151 23456789")
        vm2.check()
        advanceUntilIdle()
        assertEquals(NumberPhase.Unreachable, vm2.state.value.phase)
        assertEquals("0151 23456789", vm2.state.value.input, "the input stays")

        coEvery { bridges.resolve(any(), any()) } returns ChatResult.Failed("bridge_locked", 403)
        vm2.check()
        advanceUntilIdle()
        assertEquals(NumberPhase.Blocked(ChatNewChat.Blocked.LOCKED), vm2.state.value.phase)
    }

    @Test
    fun `the server does not take the number - said under the field, nothing started`() = scope.runTest {
        coEvery { bridges.resolve(any(), any()) } returns ChatResult.Failed("invalid_number", 400)
        val vm = vm()
        vm.bind(wa)
        advanceUntilIdle()
        vm.setInput("0151 23456789")
        vm.check()
        advanceUntilIdle()
        assertEquals(NumberPhase.InvalidServer, vm.state.value.phase)
        vm.start()
        coVerify(exactly = 0) { repository.openNumberChat(any(), any()) }
    }

    @Test
    fun `a number handed over by Neuer Chat - in the field and checked at once`() = scope.runTest {
        coEvery { bridges.resolve(wa, "+4917155501230") } returns ChatResult.Ok(ChatNumberLookup(true, "Paul", null))
        ChatNumberHandoff.put(wa, "+4917155501230")
        val vm = vm()
        vm.bind(wa)
        advanceUntilIdle()
        coVerify(exactly = 1) { bridges.resolve(wa, "+4917155501230") }
        assertInstanceOf(NumberPhase.Found::class.java, vm.state.value.phase)
        assertEquals("+4917155501230", vm.state.value.input)
        // taken once: the next page starts empty
        val again = vm()
        again.bind(wa)
        advanceUntilIdle()
        assertEquals("", again.state.value.input)
    }
}
