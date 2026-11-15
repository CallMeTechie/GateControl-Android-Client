package com.gatecontrol.android.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.chat.ChatBridges
import com.gatecontrol.android.chat.ChatBridgesRepository
import com.gatecontrol.android.chat.ChatConversation
import com.gatecontrol.android.chat.ChatFeatures
import com.gatecontrol.android.chat.ChatJson
import com.gatecontrol.android.chat.ChatLimits
import com.gatecontrol.android.chat.ChatNewChatContacts
import com.gatecontrol.android.chat.ChatNewContacts
import com.gatecontrol.android.chat.ChatNumberChat
import com.gatecontrol.android.chat.ChatNumberStarted
import com.gatecontrol.android.chat.ChatPerson
import com.gatecontrol.android.chat.ChatPrefs
import com.gatecontrol.android.chat.ChatProfile
import com.gatecontrol.android.chat.ChatRepository
import com.gatecontrol.android.chat.ChatResult
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
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * "Neuer Chat" with contacts (Messenger 1.18): the list on opening and while typing (one call after the pause, older
 * answers dropped), a WhatsApp contact (chat at once, an expired reference reloads), an address book contact (one
 * number checked on the tap, several numbers to choose from, not at WhatsApp, too many checks, the bridge away), the
 * number of the search, the chat there is — and without the word the page of 1.16.0. Mocked repository, virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NewChatContactsViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val repository: ChatRepository = mockk(relaxed = true)
    private val bridges: ChatBridgesRepository = mockk(relaxed = true)
    private val viewModels = mutableListOf<ViewModel>()
    private val wa = ChatBridges.WHATSAPP
    private val ref = "k" + "A".repeat(48)

    private fun profile(vararg tokens: String) = ChatProfile(
        user = ChatPerson(7, "Anna", null, "AN", null),
        prefs = ChatPrefs(),
        licensed = true,
        features = ChatFeatures(tokens = tokens.toSet()),
        limits = ChatLimits(),
        serverTime = null,
    )

    private fun list(state: String = "ok", number: String = "null") = ChatNewContacts.list(
        JsonParser.parseString(
            """{"network":"whatsapp","gatecontrol":[{"userId":3,"name":"Marc","conversationId":12},{"userId":4,"name":"Jona","conversationId":null}],
            "whatsapp":{"state":"$state","total":2,"items":[{"ref":"$ref","name":"Lena Berger","conversationId":null,"alsoInAddressBook":true},
              {"ref":"${ref}B","name":"Kim Muster","conversationId":31}]},
            "addressBook":{"state":"$state","total":2,"items":[
              {"contactId":5,"name":"Paul Hoffmann","phones":[{"key":"mobile","kind":"mobile","number":"+49 160 98765432"}]},
              {"contactId":6,"name":"Sabine Wolf","phones":[{"key":"home","kind":"home","number":"+49 30 12345678","landline":true},{"key":"mobile","kind":"mobile","number":"+49 151 23456780"}]}]},
            "number":$number,"numberEntry":true,"limit":{"perHour":10,"left":3,"retryAfterS":0}}""",
        ).asJsonObject,
    )!!

    private fun conv(id: Long): ChatConversation = ChatJson.conversation(
        JsonParser.parseString(
            """{"id":$id,"kind":"direct","title":"Paul Hoffmann","peer":null,"members":[{"userId":7,"name":"Anna"}],"me":{"role":"member","notify":"all"},
                "bridge":{"network":"whatsapp","label":"WhatsApp","state":"ok","supports":["text"]},"can":{"send":true}}""",
        ).asJsonObject,
    )!!

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { repository.profile } returns MutableStateFlow(profile("bridge_chats", "bridge_new_chat", "bridge_contacts"))
        coEvery { repository.people() } returns ChatResult.Ok(listOf(ChatPerson(3, "Marc", null, "MA", null)))
        coEvery { repository.newChatContacts(wa, any()) } returns ChatResult.Ok(list())
        ChatNumberStarted.clear()
    }

    @AfterEach
    fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        dispatcher.scheduler.advanceUntilIdle()
        Dispatchers.resetMain()
        ChatNumberStarted.clear()
    }

    private fun vm() = NewChatViewModel(repository, bridges).also {
        viewModels += it
        it.now = { dispatcher.scheduler.currentTime }
    }

    private fun opened(): ChatNewChatContacts = vmState!!.data!!
    private var vmState: ContactsUi? = null

    private suspend fun ready(): NewChatViewModel {
        val vm = vm()
        vm.contacts()
        dispatcher.scheduler.advanceUntilIdle()
        vmState = vm.state.value.contacts
        return vm
    }

    @Test
    fun `opening - the list once, the page of 1_16 not asked`() = scope.runTest {
        val vm = ready()
        assertTrue(vm.withContacts)
        assertEquals(2, opened().gatecontrol.size)
        coVerify(exactly = 1) { repository.newChatContacts(wa, "") }
        coVerify(exactly = 0) { bridges.load() }
        assertTrue(vm.state.value.numberNetworks.isEmpty())
    }

    @Test
    fun `typing - one call after the pause with the last search, an older answer is dropped`() = scope.runTest {
        val vm = ready()
        vm.setQuery("b")
        advanceTimeBy(100)
        vm.setQuery("be")
        advanceTimeBy(100)
        vm.setQuery("ber")
        advanceTimeBy(ChatNewContacts.DEBOUNCE_MS + 10)
        advanceUntilIdle()
        coVerify(exactly = 0) { repository.newChatContacts(wa, "b") }
        coVerify(exactly = 0) { repository.newChatContacts(wa, "be") }
        coVerify(exactly = 1) { repository.newChatContacts(wa, "ber") }
        assertEquals("ber", vm.state.value.contacts!!.query)
        // a slow answer for "ka" arrives after the one for "kat": the later search stands
        coEvery { repository.newChatContacts(wa, "ka") } coAnswers { kotlinx.coroutines.delay(2_000); ChatResult.Ok(list(state = "unreachable")) }
        vm.setQuery("ka")
        advanceTimeBy(ChatNewContacts.DEBOUNCE_MS + 10)
        vm.setQuery("kat")
        advanceUntilIdle()
        assertEquals(ChatNewContacts.STATE_OK, vm.state.value.contacts!!.data!!.whatsapp!!.state)
    }

    @Test
    fun `a WhatsApp contact - the chat at once, the first line without a number`() = scope.runTest {
        val vm = ready()
        coEvery { repository.openContactChat(wa, ref) } returns ChatResult.Ok(ChatNumberChat(conv(44), true))
        vm.tapContact(opened().whatsapp!!.items[0])
        advanceUntilIdle()
        assertEquals(44L, vm.open.first())
        val line = ChatNumberStarted.get(44)!!
        assertEquals(ChatNumberStarted.Kind.CONTACT, line.kind)
        assertEquals("Lena Berger", line.name)
        assertNull(line.display)
    }

    @Test
    fun `a WhatsApp contact whose reference ran out - the list anew`() = scope.runTest {
        val vm = ready()
        coEvery { repository.openContactChat(wa, ref) } returns ChatResult.Failed("contact_expired", 409)
        vm.tapContact(opened().whatsapp!!.items[0])
        advanceUntilIdle()
        coVerify(exactly = 2) { repository.newChatContacts(wa, "") }
        assertNull(vm.state.value.contacts!!.error)
    }

    @Test
    fun `a row with a chat there is - opened, nothing goes to WhatsApp`() = scope.runTest {
        val vm = ready()
        vm.tapContact(opened().whatsapp!!.items[1])
        advanceUntilIdle()
        assertEquals(31L, vm.open.first())
        coVerify(exactly = 0) { repository.openContactChat(any(), any()) }
        coVerify { repository.refreshConversation(31) }
        vm.tapAccount(opened().gatecontrol[0])
        advanceUntilIdle()
        assertEquals(12L, vm.open.first())
        coVerify(exactly = 0) { repository.openDirect(3) }
    }

    @Test
    fun `an address book contact with one number - checked on the tap, the chat opens with the first line`() = scope.runTest {
        val vm = ready()
        coEvery { repository.openBookChat(wa, 5, "mobile") } coAnswers { kotlinx.coroutines.delay(500); ChatResult.Ok(ChatNumberChat(conv(45), true)) }
        vm.tapBook(opened().addressBook!!.items[0])
        advanceTimeBy(100)
        assertEquals(BookRow.Checking("mobile"), vm.state.value.contacts!!.rows[5])
        advanceUntilIdle()
        assertEquals(45L, vm.open.first())
        val line = ChatNumberStarted.get(45)!!
        assertEquals(ChatNumberStarted.Kind.BOOK, line.kind)
        assertEquals("+49 160 98765432", line.display)
        assertNull(vm.state.value.contacts!!.rows[5])
    }

    @Test
    fun `several numbers - the choice opens, a number is checked, not at WhatsApp marks it`() = scope.runTest {
        val vm = ready()
        val sabine = opened().addressBook!!.items[1]
        vm.tapBook(sabine)
        assertEquals(6L, vm.state.value.contacts!!.pick)
        coVerify(exactly = 0) { repository.openBookChat(any(), any(), any()) }
        coEvery { repository.openBookChat(wa, 6, "home") } returns ChatResult.Failed("not_found", 404, "not_on_network")
        vm.tapNumber(sabine, sabine.phones[0])
        advanceUntilIdle()
        val c = vm.state.value.contacts!!
        assertNull(c.pick)
        assertEquals(BookRow.NotOn("home"), c.rows[6])
        assertTrue(c.data!!.addressBook!!.items[1].phones.first { it.key == "home" }.notOnNetwork)
        // tapping the row again closes nothing it should not: the choice comes back, the note goes
        vm.tapBook(c.data!!.addressBook!!.items[1])
        assertEquals(6L, vm.state.value.contacts!!.pick)
        assertNull(vm.state.value.contacts!!.rows[6])
    }

    @Test
    fun `too many checks - from when, with the brake of the list`() = scope.runTest {
        val vm = ready()
        coEvery { repository.openBookChat(wa, 5, "mobile") } returns ChatResult.Failed("rate_limited", 429, retryAfterS = 600)
        vm.tapBook(opened().addressBook!!.items[0])
        advanceUntilIdle()
        val row = vm.state.value.contacts!!.rows[5]
        assertInstanceOf(BookRow.RateLimited::class.java, row)
        row as BookRow.RateLimited
        assertEquals(10, row.checks)
        assertEquals(600_000L, row.untilMs - 0L, "now + retryAfterS")
    }

    @Test
    fun `the bridge away, signed out meanwhile`() = scope.runTest {
        val vm = ready()
        coEvery { repository.openBookChat(wa, 5, "mobile") } returns ChatResult.Failed("bridge_unreachable", 503)
        vm.tapBook(opened().addressBook!!.items[0])
        advanceUntilIdle()
        assertEquals(BookRow.Unreachable("mobile"), vm.state.value.contacts!!.rows[5])
        coEvery { repository.openBookChat(wa, 5, "mobile") } returns ChatResult.Failed("bridge_logged_out", 409)
        coEvery { repository.newChatContacts(wa, "") } returns ChatResult.Ok(list(state = "logged_out"))
        vm.tapBook(opened().addressBook!!.items[0])
        advanceUntilIdle()
        val c = vm.state.value.contacts!!
        assertTrue(c.rows.isEmpty())
        assertEquals(ChatNewContacts.STATE_LOGGED_OUT, c.data!!.whatsapp!!.state)
        assertTrue(c.offline)
        // greyed: a tap does nothing
        vm.tapBook(c.data!!.addressBook!!.items[0])
        advanceUntilIdle()
        coVerify(exactly = 2) { repository.openBookChat(any(), any(), any()) }
    }

    @Test
    fun `a number in the search - handed to the page Nummer eingeben, never checked here`() = scope.runTest {
        coEvery { repository.newChatContacts(wa, any()) } returns ChatResult.Ok(list(number = """{"e164":"+4917155501230","show":"+49 171 55501230","conversationId":null}"""))
        val vm = ready()
        vm.tapNumberHit(opened().number!!)
        assertEquals(wa, vm.numberPage.first())
        assertEquals("+4917155501230", ChatNumberHandoff.take(wa))
        assertNull(ChatNumberHandoff.take(wa), "taken once")
        coVerify(exactly = 0) { repository.openBookChat(any(), any(), any()) }
        vm.enterNumber()
        assertEquals(wa, vm.numberPage.first())
        assertNull(ChatNumberHandoff.take(wa))
    }

    @Test
    fun `without bridge_contacts - the page of 1_16, no list asked`() = scope.runTest {
        every { repository.profile } returns MutableStateFlow(profile("bridge_chats", "bridge_new_chat"))
        val vm = vm()
        vm.contacts()
        advanceUntilIdle()
        assertNull(vm.state.value.contacts)
        coVerify(exactly = 0) { repository.newChatContacts(any(), any()) }
        coVerify { bridges.load() }
        assertNotNull(vm.state.value.people)
    }
}
