package com.gatecontrol.android.ui.chat

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.gatecontrol.android.chat.ChatBridgeHints
import com.gatecontrol.android.chat.ChatBridgeLoginState
import com.gatecontrol.android.chat.ChatBridgeNetwork
import com.gatecontrol.android.chat.ChatBridgeStep
import com.gatecontrol.android.chat.ChatBridges
import com.gatecontrol.android.chat.ChatBridgesInfo
import com.gatecontrol.android.chat.ChatBridgesRepository
import com.gatecontrol.android.chat.ChatContactTyping
import com.gatecontrol.android.chat.ChatConversation
import com.gatecontrol.android.chat.ChatDeviceConfig
import com.gatecontrol.android.chat.ChatDeviceSettings
import com.gatecontrol.android.chat.ChatExtrasRepository
import com.gatecontrol.android.chat.ChatFeatures
import com.gatecontrol.android.chat.ChatJson
import com.gatecontrol.android.chat.ChatLimits
import com.gatecontrol.android.chat.ChatMediaStore
import com.gatecontrol.android.chat.ChatMessage
import com.gatecontrol.android.chat.ChatPeople
import com.gatecontrol.android.chat.ChatPerson
import com.gatecontrol.android.chat.ChatPersonSplit
import com.gatecontrol.android.chat.ChatPersonSuggestion
import com.gatecontrol.android.chat.ChatPrefs
import com.gatecontrol.android.chat.ChatProfile
import com.gatecontrol.android.chat.ChatRecognition
import com.gatecontrol.android.chat.ChatRepository
import com.gatecontrol.android.chat.ChatResult
import com.gatecontrol.android.chat.ChatSync
import com.gatecontrol.android.chat.ChatUpdateHint
import com.gatecontrol.android.chat.TimelineItem
import com.gatecontrol.android.notify.InboxCache
import com.gatecontrol.android.notify.PushRepository
import com.gatecontrol.android.ui.chat.settings.LinkedNotice
import com.gatecontrol.android.ui.chat.settings.LinkedServicesViewModel
import com.google.gson.JsonParser
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Messenger 1.21 (Chats v2, Teil B): what the view models make of persons — the list (the search over every name, the
 * chip per service, the suggestion card, merging, „Nicht jetzt“), the chat of a person (the mixed history, the source
 * chips, the answer over the server's way or the way of the quoted message, `active` and „gelesen“ on every way, a way
 * that opens its person), the chat info („Trennen“, „Mit Person zusammenführen …“) and the question after linking
 * WhatsApp. Mocked repositories, virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ChatPeopleViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val repository: ChatRepository = mockk(relaxed = true)
    private val extras: ChatExtrasRepository = mockk(relaxed = true)
    private val media: ChatMediaStore = mockk(relaxed = true)
    private val device: ChatDeviceSettings = mockk(relaxed = true)
    private val bridges: ChatBridgesRepository = mockk(relaxed = true)
    private val viewModels = mutableListOf<ViewModel>()

    private val conversations = MutableStateFlow<List<ChatConversation>>(emptyList())
    private val messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    private val profile = MutableStateFlow<ChatProfile?>(null)
    private val suggestions = MutableStateFlow<List<ChatPersonSuggestion>>(emptyList())

    private fun json(s: String) = JsonParser.parseString(s).asJsonObject

    private fun profile(vararg tokens: String) = ChatProfile(
        user = ChatPerson(7, "Kevin", null, "KE", null),
        prefs = ChatPrefs(),
        licensed = true,
        features = ChatFeatures(tokens = tokens.toSet()),
        limits = ChatLimits(),
        serverTime = null,
    )

    private val personJson = """{"id":7,"name":"Jenny Backes","nameFrom":"account","userId":3,"conversationId":41,"names":["Jenny Backes","Schatzimausi 💕"],
        "ways":[{"way":"gatecontrol","label":"GateControl","conversationId":41,"title":"Jenny Backes","unread":1,"can":{"send":true}},
                {"way":"whatsapp","label":"WhatsApp","conversationId":17,"title":"Schatzimausi 💕","unread":2,"bridge":{"network":"whatsapp","label":"WhatsApp","state":"ok","supports":["text"]},"can":{"send":true}}],
        "reply":{"way":"whatsapp","label":"WhatsApp","conversationId":17}}"""

    private val jenny = ChatJson.conversation(
        json(
            """{"id":41,"kind":"direct","title":"Jenny Backes","peer":{"userId":3,"name":"Jenny Backes"},"members":[{"userId":7,"name":"Kevin"},{"userId":3,"name":"Jenny Backes"}],
                "me":{"notify":"all"},"unread":3,"updatedAt":"2026-11-15T14:12:00.000Z","can":{"send":true},"person":$personJson}""",
        ),
    )!!
    private val wa = ChatJson.conversation(
        json("""{"id":17,"kind":"direct","title":"Schatzimausi 💕","members":[{"userId":7,"name":"Kevin"}],"me":{"notify":"all"},"unread":2,"bridge":{"network":"whatsapp","label":"WhatsApp","state":"ok","supports":["text"]},"can":{"send":true},"person":$personJson}"""),
    )!!
    private val opa = ChatJson.conversation(
        json("""{"id":18,"kind":"direct","title":"Opa","members":[{"userId":7,"name":"Kevin"}],"me":{"notify":"all"},"unread":0,"updatedAt":"2026-11-14T10:00:00.000Z","bridge":{"network":"whatsapp","label":"WhatsApp","state":"ok","supports":["text"]},"can":{"send":true}}"""),
    )!!
    private val kim = ChatJson.conversation(
        json("""{"id":50,"kind":"direct","title":"Kim","peer":{"userId":9,"name":"Kim"},"members":[{"userId":7,"name":"Kevin"},{"userId":9,"name":"Kim"}],"me":{"notify":"all"},"unread":0,"updatedAt":"2026-11-13T10:00:00.000Z","can":{"send":true}}"""),
    )!!

    private fun msg(id: Long, conv: Long, at: String, mine: Boolean = false) = ChatJson.message(
        json("""{"id":$id,"conversationId":$conv,"senderId":${if (mine) 7 else 3},"kind":"text","mine":$mine,"createdAt":"$at","text":"m$id","reactions":[]}"""),
    )!!

    private val suggestion = ChatPersonSuggestion(
        "17-3", 17, 3, "whatsapp", "WhatsApp", true, "own_whatsapp", "Schatzimausi 💕", null, null, "Jenny Backes", null, "JB", "#DB2777",
        "„Schatzimausi 💕“ bei WhatsApp ist vielleicht Jenny Backes.", "Jenny Backes hat das eigene WhatsApp mit GateControl verknüpft.",
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { repository.conversations } returns conversations
        every { repository.allConversations } returns conversations
        every { repository.channels } returns MutableStateFlow(emptyList())
        every { repository.channelsOn } returns MutableStateFlow(true)
        every { repository.profile } returns profile
        every { repository.sync } returns MutableStateFlow<ChatSync>(ChatSync.Synced)
        every { repository.typing } returns MutableStateFlow(mapOf(17L to mapOf(3L to Long.MAX_VALUE)))
        every { repository.contactTyping } returns MutableStateFlow<Map<Long, ChatContactTyping>>(emptyMap())
        every { repository.dropped } returns MutableStateFlow(emptySet())
        every { repository.activeConversation } returns MutableStateFlow(41L)
        every { repository.live } returns true
        every { repository.suggestions } returns suggestions
        every { repository.conversation(any()) } answers {
            val id = firstArg<Long>()
            kotlinx.coroutines.flow.flow { conversations.collect { l -> emit(l.firstOrNull { it.id == id }) } }
        }
        every { repository.personMessages(any()) } returns messages
        every { repository.personOutbox(any()) } returns flowOf(emptyList())
        every { repository.wayConversations(any()) } returns flowOf(mapOf(41L to jenny, 17L to wa))
        every { repository.messages(any()) } returns messages
        every { repository.outbox(any()) } returns flowOf(emptyList())
        every { repository.gaps(any()) } returns flowOf(emptyList())
        coEvery { repository.olderWays(any()) } returns emptySet()
        coEvery { repository.personLeadOf(any()) } returns null
        coEvery { repository.cachedConversation(any()) } answers { conversations.value.firstOrNull { it.id == firstArg<Long>() } }
        coEvery { repository.syncConversations(any()) } returns ChatSync.Synced
        every { extras.lists } returns MutableStateFlow(emptyList())
        every { extras.feed } returns MutableStateFlow(null)
        every { extras.actions } returns MutableStateFlow(null)
        every { extras.places } returns MutableStateFlow(null)
        every { device.config } returns MutableStateFlow(ChatDeviceConfig(sounds = false))
        every { device.wallpaperFile } returns File(context.filesDir, "none.jpg")
        every { media.outboxDir } returns File(context.filesDir, "chat-outbox")
    }

    @After
    fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    /** The view models tick every second: they end with the test body, so runTest's last idle wait ends. */
    private fun test(body: suspend TestScope.() -> Unit) = runTest(dispatcher) {
        try {
            body()
        } finally {
            viewModels.forEach { it.viewModelScope.cancel() }
        }
    }

    /** The view models tick every second: time moves on a little, never to the end. */
    private fun TestScope.settle() {
        advanceTimeBy(300)
        runCurrent()
    }

    private fun listVm(): ChatListViewModel {
        val push: PushRepository = mockk(relaxed = true)
        val inbox: InboxCache = mockk(relaxed = true)
        every { inbox.items } returns MutableStateFlow(emptyList())
        every { push.inbox } returns inbox
        return ChatListViewModel(repository, push, ChatUpdateHint(context), extras, bridges).also { viewModels += it }
    }

    private fun chatVm() = ConversationViewModel(context, repository, media, extras, device, ChatBridgeHints(context)).also { viewModels += it }

    // ─── the list ───────────────────────────────────────────────────

    @Test
    fun `the list - every name finds the person, the chip WhatsApp holds it, typing on any way`() = test {
        profile.value = profile("bridge_chats", "chat_people")
        conversations.value = listOf(jenny, opa, kim)
        val vm = listVm()
        val job = launch { vm.uiState.collect { } }
        settle()
        assertEquals(listOf("whatsapp" to "WhatsApp"), vm.uiState.value.networks)
        vm.setQuery("schatz")
        settle()
        assertEquals(listOf(41L), vm.uiState.value.conversations.map { it.id })
        vm.setQuery("")
        vm.setNet("whatsapp")
        settle()
        assertEquals(listOf(41L, 18L), vm.uiState.value.conversations.map { it.id })
        vm.setNet(com.gatecontrol.android.chat.ChatBridgeChat.NET_GC)
        settle()
        assertEquals(listOf(41L, 50L), vm.uiState.value.conversations.map { it.id })
        assertEquals("„schreibt …“ of the WhatsApp way at the person", listOf(3L), vm.uiState.value.typing[41L])
        job.cancel()
    }

    @Test
    fun `the suggestion card - merging opens the person, Nicht jetzt hides it`() = test {
        profile.value = profile("bridge_chats", "chat_people")
        suggestions.value = listOf(suggestion)
        conversations.value = listOf(jenny)
        coEvery { repository.mergePerson(any(), any(), any()) } returns ChatResult.Ok(jenny)
        coEvery { repository.answerSuggestion(any(), any()) } answers {
            suggestions.value = emptyList()
            ChatResult.Ok(Unit)
        }
        val vm = listVm()
        settle()
        coVerify { repository.loadSuggestions(false) }
        assertEquals(suggestion, vm.suggestion.value)
        vm.mergeSuggestion(suggestion, ChatPeople.NAME_ACCOUNT)
        settle()
        coVerify { repository.mergePerson(listOf(17L), 3L, "account") }
        assertEquals(ChatPeopleNotice.Merged(41L), vm.peopleNotice.first())
        vm.answerSuggestion(suggestion, ChatPeople.ACTION_LATER)
        settle()
        coVerify { repository.answerSuggestion(suggestion, "later") }
        assertNull(vm.suggestion.value)
    }

    @Test
    fun `without the word - no suggestions are asked for, the list as before`() = test {
        profile.value = profile("bridge_chats")
        conversations.value = listOf(opa, kim)
        val vm = listVm()
        settle()
        coVerify(exactly = 0) { repository.loadSuggestions(any()) }
        assertNull(vm.suggestion.value)
    }

    // ─── the chat of a person ───────────────────────────────────────

    @Test
    fun `the chat of a person - mixed by time, source chips, the answer over the server's way`() = test {
        profile.value = profile("bridge_chats", "chat_people")
        conversations.value = listOf(jenny, wa)
        messages.value = listOf(
            msg(905, 17, "2026-11-15T14:12:00.000Z"), msg(401, 41, "2026-11-15T12:02:00.000Z"),
            msg(402, 41, "2026-11-15T12:05:00.000Z", mine = true), msg(900, 17, "2026-11-15T13:40:00.000Z"),
        )
        val vm = chatVm()
        val job = launch { vm.uiState.collect { } }
        vm.bind(41)
        settle()
        coVerify { repository.openPerson(match { it.id == 41L }) }
        coVerify(exactly = 0) { repository.openConversation(any()) }
        val st = vm.uiState.value
        assertEquals(7L, st.person!!.id)
        assertEquals(listOf(401L, 402L, 900L, 905L), st.items.filterIsInstance<TimelineItem.Message>().map { it.message.id })
        assertEquals("the composer writes over WhatsApp (person.reply)", 17L, st.composer!!.id)
        assertEquals(listOf(3L), st.typing)
        // the source chip
        vm.setSource("gatecontrol")
        settle()
        assertEquals(listOf(401L, 402L), vm.uiState.value.items.filterIsInstance<TimelineItem.Message>().map { it.message.id })
        vm.setSource(null)
        settle()
        // sending: the server's way; an answer to a GateControl message over GateControl
        assertTrue(vm.send("Bis gleich"))
        settle()
        coVerify { repository.sendText(17, "Bis gleich", null) }
        vm.startReply(vm.uiState.value.items.filterIsInstance<TimelineItem.Message>().first().message)
        settle()
        assertEquals(41L, vm.uiState.value.composer!!.id)
        assertTrue(vm.send("Ja"))
        settle()
        coVerify { repository.sendText(41, "Ja", 401L) }
        // visible: active on every way, read on every way
        vm.setVisible(true)
        settle()
        verify { repository.setActivePerson(41, listOf(41L, 17L)) }
        coVerify { repository.markPersonRead(match { it.id == 41L }, 905) }
        // the search over all ways
        vm.search("Brot")
        settle()
        coVerify { repository.searchPerson(7, "Brot") }
        job.cancel()
    }

    @Test
    fun `a way of a person opens the person`() = test {
        profile.value = profile("bridge_chats", "chat_people")
        conversations.value = listOf(jenny, wa)
        coEvery { repository.personLeadOf(17) } returns 41L
        val vm = chatVm()
        vm.bind(17)
        settle()
        assertEquals(41L, vm.redirect.value)
        coVerify(exactly = 0) { repository.openConversation(17) }
    }

    @Test
    fun `without the word a chat is a chat - no redirect, its own messages`() = test {
        profile.value = profile("bridge_chats")
        conversations.value = listOf(opa)
        messages.value = listOf(msg(1, 18, "2026-11-14T10:00:00.000Z"))
        val vm = chatVm()
        val job = launch { vm.uiState.collect { } }
        vm.bind(18)
        settle()
        assertNull(vm.redirect.value)
        coVerify(exactly = 0) { repository.personLeadOf(any()) }
        coVerify { repository.openConversation(18) }
        assertNull(vm.uiState.value.person)
        assertEquals(listOf(1L), vm.uiState.value.items.filterIsInstance<TimelineItem.Message>().map { it.message.id })
        job.cancel()
    }

    // ─── the chat info ──────────────────────────────────────────────

    @Test
    fun `chat info - Trennen and Mit Person zusammenfuehren`() = test {
        profile.value = profile("bridge_chats", "chat_people")
        conversations.value = listOf(jenny, opa)
        coEvery { repository.splitWay(any(), any()) } returns ChatResult.Ok(ChatPersonSplit(null, emptyList()))
        coEvery { repository.mergePerson(any(), any(), any()) } returns ChatResult.Ok(jenny)
        coEvery { repository.personCandidates(any()) } returns ChatResult.Ok(com.gatecontrol.android.chat.ChatPersonCandidates(emptyList(), emptyList()))
        val vm = ChatInfoViewModel(context, repository, media, extras).also { viewModels += it }
        val job = launch { vm.conversation.collect { } }
        vm.bind(41)
        settle()
        vm.split(17)
        settle()
        coVerify { repository.splitWay(7, 17) }
        assertEquals(com.gatecontrol.android.R.string.chat_pp_split_done, vm.peopleDone.first())
        vm.loadCandidates()
        settle()
        coVerify { repository.personCandidates(41) }
        vm.merge(null, 18, "18")
        settle()
        coVerify { repository.mergePerson(listOf(41L, 18L), null, "18") }
        vm.archive(true)
        settle()
        coVerify { repository.archivePerson(7, true) }
        job.cancel()
    }

    // ─── the question after linking WhatsApp ────────────────────────

    @Test
    fun `after linking WhatsApp - asked once whether others may recognise the person`() = test {
        val net = ChatBridgeNetwork(ChatBridges.WHATSAPP, ChatBridges.NET_RUNNING, consent = true)
        coEvery { bridges.load() } returns ChatResult.Ok(ChatBridgesInfo(available = true, networks = listOf(net), recognition = ChatRecognition(true, "whatsapp", asked = false, allowed = false)))
        coEvery { bridges.start(any(), any()) } returns ChatResult.Ok(ChatBridgeLoginState("p1", false, ChatBridgeStep.Complete))
        coEvery { bridges.setRecognition(true) } returns ChatResult.Ok(ChatRecognition(true, "whatsapp", asked = true, allowed = true))
        val vm = LinkedServicesViewModel(bridges).also { viewModels += it }
        settle()
        assertFalse(vm.uiState.value.askRecognition)
        vm.begin(ChatBridges.WHATSAPP)
        settle()
        assertTrue(vm.uiState.value.askRecognition)
        assertEquals(LinkedNotice.Linked(ChatBridges.WHATSAPP), vm.notice.first())
        vm.answerRecognition(true)
        settle()
        coVerify { bridges.setRecognition(true) }
        assertFalse(vm.uiState.value.askRecognition)
        assertEquals(LinkedNotice.Recognition(true), vm.notice.first())
    }

    @Test
    fun `no question when it was answered or the server has none`() = test {
        val net = ChatBridgeNetwork(ChatBridges.WHATSAPP, ChatBridges.NET_RUNNING, consent = true)
        coEvery { bridges.load() } returns ChatResult.Ok(ChatBridgesInfo(available = true, networks = listOf(net), recognition = null))
        coEvery { bridges.start(any(), any()) } returns ChatResult.Ok(ChatBridgeLoginState("p1", false, ChatBridgeStep.Complete))
        val vm = LinkedServicesViewModel(bridges).also { viewModels += it }
        settle()
        vm.begin(ChatBridges.WHATSAPP)
        settle()
        assertFalse(vm.uiState.value.askRecognition)
    }
}
