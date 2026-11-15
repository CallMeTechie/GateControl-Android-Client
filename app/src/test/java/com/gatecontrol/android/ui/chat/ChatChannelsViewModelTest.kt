package com.gatecontrol.android.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.chat.ChatBridgeNetwork
import com.gatecontrol.android.chat.ChatBridges
import com.gatecontrol.android.chat.ChatBridgesInfo
import com.gatecontrol.android.chat.ChatBridgesRepository
import com.gatecontrol.android.chat.ChatChannelSummary
import com.gatecontrol.android.chat.ChatConversation
import com.gatecontrol.android.chat.ChatExtrasRepository
import com.gatecontrol.android.chat.ChatFeatures
import com.gatecontrol.android.chat.ChatJson
import com.gatecontrol.android.chat.ChatLimits
import com.gatecontrol.android.chat.ChatPerson
import com.gatecontrol.android.chat.ChatPrefs
import com.gatecontrol.android.chat.ChatProfile
import com.gatecontrol.android.chat.ChatRepository
import com.gatecontrol.android.chat.ChatResult
import com.gatecontrol.android.chat.ChatSync
import com.gatecontrol.android.chat.ChatUpdateHint
import com.gatecontrol.android.notify.InboxCache
import com.gatecontrol.android.notify.PushRepository
import com.google.gson.JsonParser
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Messenger 1.20: the page „Kanäle“ (loaded on opening, newest post first, the search, archived ones folded away, the
 * bell, „Als gelesen markieren“) and what the chat list takes from the channels — the TV symbol's numbers, a search
 * that finds channels too, the side menu's linked services. Mocked repository, virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatChannelsViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val repository: ChatRepository = mockk(relaxed = true)
    private val extras: ChatExtrasRepository = mockk(relaxed = true)
    private val viewModels = mutableListOf<ViewModel>()
    private val channels = MutableStateFlow<List<ChatConversation>>(emptyList())
    private val chats = MutableStateFlow<List<ChatConversation>>(emptyList())
    private val on = MutableStateFlow(true)
    private val profile = MutableStateFlow<ChatProfile?>(null)

    private fun profile(vararg tokens: String) = ChatProfile(
        user = ChatPerson(7, "Anna", null, "AN", null),
        prefs = ChatPrefs(),
        licensed = true,
        features = ChatFeatures(tokens = tokens.toSet()),
        limits = ChatLimits(),
        serverTime = null,
    )

    private fun channel(id: Long, title: String, last: String, unread: Int = 0, archived: Boolean = false, text: String = "Neuer Beitrag", channel: Boolean = true) = ChatJson.conversation(
        JsonParser.parseString(
            """{"id":$id,"kind":"group","title":"$title","members":[{"userId":7,"name":"Anna"}],"me":{"notify":"none"},"muted":true,"unread":$unread,"archived":$archived,"updatedAt":"$last",
                "lastMessage":{"id":${id * 10},"conversationId":$id,"kind":"text","mine":false,"createdAt":"$last","text":"$text","reactions":[],"bridge":{"sender":null,"channel":$channel}},
                "bridge":{"network":"whatsapp","label":"WhatsApp","state":"ok","channel":$channel,"readOnly":$channel,"supports":["text"]},
                "can":{"send":${!channel},"sendReason":${if (channel) "\"channel\"" else "null"},"react":true}}""",
        ).asJsonObject,
    )!!

    private val a = channel(41, "Sicherheit kompakt", "2026-11-15T14:05:00.000Z", unread = 3, text = "Kritische Lücke")
    private val b = channel(42, "Stadtwerke Beispielstadt", "2026-11-15T11:40:00.000Z", unread = 1, text = "Wasserleitung")
    private val c = channel(43, "Wetterwarnungen Nord", "2026-11-14T18:00:00.000Z", archived = true, text = "Sturmböen")

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { repository.channels } returns channels
        every { repository.conversations } returns chats
        every { repository.allConversations } returns chats
        every { repository.channelsOn } returns on
        every { repository.profile } returns profile
        every { repository.sync } returns MutableStateFlow<ChatSync>(ChatSync.Synced)
        every { repository.typing } returns MutableStateFlow(emptyMap())
        every { repository.contactTyping } returns MutableStateFlow(emptyMap())
        every { repository.live } returns true
        coEvery { repository.syncChannels(any()) } returns ChatSync.Synced
        coEvery { repository.syncConversations(any()) } returns ChatSync.Synced
        every { extras.lists } returns MutableStateFlow(emptyList())
        every { extras.feed } returns MutableStateFlow(null)
    }

    @AfterEach
    fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    private fun channelsVm() = ChatChannelsViewModel(repository, extras).also { viewModels += it }

    @Test
    fun `the page loads on opening - newest post first, the archived ones folded away`() = runTest(dispatcher) {
        channels.value = listOf(c, b, a)
        val vm = channelsVm()
        val job = launch { vm.uiState.collect { } }
        advanceUntilIdle()
        coVerify(exactly = 1) { repository.syncChannels(any()) }
        val s = vm.uiState.value
        assertEquals(listOf(41L, 42L), s.rows.map { it.id })
        assertEquals(listOf(43L), s.archived.map { it.id })
        assertEquals(listOf("WhatsApp"), s.networks)
        assertTrue(s.loaded)
        assertTrue(s.any)
        // the search: the name and the post, archived ones too
        vm.setQuery("sturm")
        advanceUntilIdle()
        assertEquals(listOf(43L), vm.uiState.value.rows.map { it.id })
        assertEquals(emptyList<ChatConversation>(), vm.uiState.value.archived)
        vm.setQuery("")
        vm.toggleArchived()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.showArchived)
        job.cancel()
    }

    @Test
    fun `no channel - the page says how to follow one`() = runTest(dispatcher) {
        val vm = channelsVm()
        val job = launch { vm.uiState.collect { } }
        advanceUntilIdle()
        assertTrue(vm.uiState.value.loaded)
        assertEquals(false, vm.uiState.value.any)
        job.cancel()
    }

    @Test
    fun `the bell, read and archive go to the server`() = runTest(dispatcher) {
        coEvery { repository.patch(any(), any()) } returns ChatResult.Failed("x", 0)
        val vm = channelsVm()
        vm.setPush(a, true)
        vm.markRead(a)
        vm.archive(a)
        advanceUntilIdle()
        coVerify { repository.patch(41, match { it.toString() == """{"notify":"all"}""" }) }
        coVerify { repository.markRead(41) }
        coVerify { extras.archive(41, true) }
        vm.setPush(a, false)
        advanceUntilIdle()
        coVerify { repository.patch(41, match { it.toString() == """{"notify":"none"}""" }) }
        assertTrue(!channelPush(a), "a channel starts without its push")
    }

    /** The list's view model ticks every second ("schreibt …"): time moves on a little, never until idle. */
    private fun kotlinx.coroutines.test.TestScope.step() {
        advanceTimeBy(300)
        runCurrent()
    }

    /** Its ticker ends with its scope — else the test's clock would run on for ever. */
    private fun stop() = viewModels.forEach { it.viewModelScope.cancel() }

    private fun listVm(bridges: ChatBridgesRepository = mockk(relaxed = true)): ChatListViewModel {
        val push: PushRepository = mockk(relaxed = true)
        val inbox: InboxCache = mockk(relaxed = true)
        every { inbox.items } returns MutableStateFlow(emptyList())
        every { push.inbox } returns inbox
        val hint: ChatUpdateHint = mockk(relaxed = true)
        every { hint.dismissed } returns MutableStateFlow(null)
        return ChatListViewModel(repository, push, hint, extras, bridges).also { viewModels += it }
    }

    @Test
    fun `the chat list - the TV symbol's numbers, channels found by the search, none without the word`() = runTest(dispatcher) {
        profile.value = profile("bridge_chats", "bridge_channels")
        channels.value = listOf(a, b)
        chats.value = listOf(channel(5, "Kim Muster", "2026-11-15T12:00:00.000Z", channel = false, text = "Hallo"))
        val vm = listVm()
        val job = launch { vm.uiState.collect { } }
        val job2 = launch { vm.channels.collect { } }
        step()
        assertEquals(ChatChannelSummary(2, 4), vm.channels.value)
        assertEquals(listOf(5L), vm.uiState.value.conversations.map { it.id }, "the channels are not in the list")
        assertEquals(0, vm.uiState.value.unreadChats, "nor in „Ungelesen“")
        vm.setQuery("stadtwerke")
        step()
        assertEquals(listOf(42L), vm.uiState.value.conversations.map { it.id }, "the search finds a channel")
        on.value = false
        step()
        assertNull(vm.channels.value, "without the word: no TV symbol")
        job.cancel()
        job2.cancel()
        stop()
    }

    @Test
    fun `the side menu - the linked services when opened, none without the word`() = runTest(dispatcher) {
        val bridges: ChatBridgesRepository = mockk(relaxed = true)
        coEvery { bridges.load() } returns ChatResult.Ok(ChatBridgesInfo(available = true, networks = listOf(ChatBridgeNetwork(ChatBridges.WHATSAPP, ChatBridges.NET_RUNNING))))
        profile.value = profile("bridge_chats")
        val vm = listVm(bridges)
        vm.loadLinked()
        step()
        assertNull(vm.linked.value)
        coVerify(exactly = 0) { bridges.load() }
        profile.value = profile("bridge_chats", ChatBridges.FEATURE)
        vm.loadLinked()
        step()
        assertEquals(listOf(ChatBridges.WHATSAPP), vm.linked.first()?.map { it.id })
        // „Archiv“ and „Alle Chats“ of the menu
        vm.showArchive()
        step()
        val job = launch { vm.uiState.collect { } }
        step()
        assertTrue(vm.uiState.value.showArchived)
        vm.showAll()
        step()
        assertEquals(false, vm.uiState.value.showArchived)
        assertEquals(ChatFilter.All, vm.uiState.value.filter)
        job.cancel()
        stop()
    }
}
