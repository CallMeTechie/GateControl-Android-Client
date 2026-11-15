package com.gatecontrol.android.ui.chat

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.gatecontrol.android.chat.ChatBridgeChat
import com.gatecontrol.android.chat.ChatBridgeHints
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
import com.gatecontrol.android.chat.ChatPerson
import com.gatecontrol.android.chat.ChatPrefs
import com.gatecontrol.android.chat.ChatProfile
import com.gatecontrol.android.chat.ChatRepository
import com.gatecontrol.android.chat.ChatResult
import com.gatecontrol.android.chat.ChatSync
import com.gatecontrol.android.chat.ChatUpdateHint
import com.gatecontrol.android.familie.PluginAvailability
import com.gatecontrol.android.familie.PluginDirectory
import com.gatecontrol.android.familie.places.RulePlaceWatcher
import com.gatecontrol.android.notify.InboxCache
import com.gatecontrol.android.notify.PushRepository
import com.gatecontrol.android.ui.theme.GateControlTheme
import com.google.gson.JsonParser
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Chats over a bridge (Messenger 1.14.0) drawn in German, light and dark, with the real screens and their view
 * models over a mocked repository: the list (the service's mark, a filter per service, no "only on your server"),
 * the chat (the note at the first visit, the strip, a contact's messages, a placeholder line, a message that did
 * not go out with "Erneut senden", text-only input) and every state in which it cannot send. With
 * `BRIDGED_SHOTS=<dir>` the drawn pages are saved there (for a look, not part of CI).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "de-w411dp-h891dp")
class ChatBridgedUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val repository: ChatRepository = mockk(relaxed = true)
    private val extras: ChatExtrasRepository = mockk(relaxed = true)
    private val media: ChatMediaStore = mockk(relaxed = true)
    private val device: ChatDeviceSettings = mockk(relaxed = true)
    private val hints = ChatBridgeHints(context)
    private val viewModels = mutableListOf<ViewModel>()

    private val conversations = MutableStateFlow<List<ChatConversation>>(emptyList())
    private val messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    private val profile = MutableStateFlow<ChatProfile?>(null)
    private val contactTyping = MutableStateFlow<Map<Long, ChatContactTyping>>(emptyMap())

    private fun json(s: String) = JsonParser.parseString(s).asJsonObject

    private fun profile(vararg tokens: String) = ChatProfile(
        user = ChatPerson(7, "Anna", null, "AN", null),
        prefs = ChatPrefs(),
        licensed = true,
        features = ChatFeatures(tokens = tokens.toSet()),
        limits = ChatLimits(),
        serverTime = null,
    )

    private fun conv(
        id: Long = 31,
        kind: String = "direct",
        title: String? = "Kim Muster",
        state: String = "ok",
        closed: Boolean = false,
        bridged: Boolean = true,
        last: String? = null,
        unread: Int = 0,
    ): ChatConversation {
        val send = state == "ok" && !closed
        val bridge = if (bridged) """{"network":"whatsapp","label":"WhatsApp","state":"$state","closed":$closed,"supports":["text","reply","reaction","edit","delete","read","typing"]}""" else "null"
        val peer = if (bridged) "null" else """{"userId":9,"name":"$title","initials":"MA","colorHex":"#84CC16"}"""
        return ChatJson.conversation(
            json(
                """{"id":$id,"kind":"$kind","title":${title?.let { "\"$it\"" }},"peer":$peer,"members":[{"userId":7,"name":"Anna","role":"member"}],
                    "me":{"role":"member","notify":"all","lastReadId":90},"unread":$unread,"readUpTo":93,"deliveredUpTo":93,"updatedAt":"2026-11-14T20:58:00.000Z",
                    "lastMessage":${last ?: "null"},"bridge":$bridge,
                    "can":{"send":$send,"pin":true,"editInfo":false,"manageMembers":false,"leave":false,"setDisappearing":false}}""",
            ),
        )!!
    }

    private fun msg(id: Long, mine: Boolean, text: String?, bridge: String, extra: String = "", conv: Long = 31) = ChatJson.message(
        json(
            """{"id":$id,"conversationId":$conv,"senderId":${if (mine) 7 else null},"kind":"text","mine":$mine,"createdAt":"2026-11-14T19:58:00.000Z",
                "text":${text?.let { "\"$it\"" }},"reactions":[],"bridge":$bridge$extra}""",
        ),
    )!!

    private val kim = """{"sender":{"contactId":12,"name":"Kim Muster"},"remote":false,"state":null,"reason":null,"placeholder":null}"""
    private fun own(state: String, reason: String? = null) = """{"sender":null,"remote":false,"state":"$state","reason":${reason?.let { "\"$it\"" }},"placeholder":null}"""

    /** The chat of the portal's screenshots: a quote with a reaction, two placeholders, a text, a message that did not go out. */
    private fun history() = listOf(
        msg(91, false, "Kommt ihr Samstag zum Grillen? Ab 16 Uhr 🙂", kim),
        msg(
            92, true, "Gern! Wir bringen Salat mit.", own("read"),
            ""","status":"read","replyTo":{"id":91,"senderId":null,"kind":"text","text":"Kommt ihr Samstag zum Grillen? Ab 16 Uhr 🙂","deleted":false,"missing":false,"contactId":12,"senderName":"Kim Muster"},
                "reactions":[{"emoji":"👍","count":1,"userIds":[],"contactIds":[12]}]""",
        ),
        msg(93, false, "So sah es letztes Jahr aus", """{"sender":{"contactId":12,"name":"Kim Muster"},"remote":false,"state":null,"reason":null,"placeholder":{"kind":"image","name":"grill-2025.jpg"}}"""),
        msg(94, false, null, """{"sender":{"contactId":12,"name":"Kim Muster"},"remote":false,"state":null,"reason":null,"placeholder":{"kind":"unsupported","name":null}}"""),
        msg(95, false, "Passt, dann bis Samstag!", kim),
        msg(96, true, "Sollen wir noch Getränke holen?", own("failed", "network_error"), ""","status":"failed""""),
    )

    @After
    fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
    }

    private fun stubRepository() {
        every { repository.conversations } returns conversations
        every { repository.conversation(any()) } answers { val id = firstArg<Long>(); kotlinx.coroutines.flow.flow { conversations.collect { list -> emit(list.firstOrNull { it.id == id }) } } }
        every { repository.messages(any()) } returns messages
        every { repository.outbox(any()) } returns flowOf(emptyList())
        every { repository.gaps(any()) } returns flowOf(emptyList())
        every { repository.profile } returns profile
        every { repository.typing } returns MutableStateFlow(emptyMap())
        every { repository.contactTyping } returns contactTyping
        every { repository.dropped } returns MutableStateFlow(emptySet())
        every { repository.sync } returns MutableStateFlow<ChatSync>(ChatSync.Synced)
        every { repository.activeConversation } returns MutableStateFlow(null)
        every { repository.live } returns true
        coEvery { repository.hasOlder(any()) } returns false
        coEvery { repository.cachedConversation(any()) } answers { conversations.value.firstOrNull { it.id == firstArg<Long>() } }
        coEvery { repository.syncConversations(any()) } returns ChatSync.Synced
        every { extras.actions } returns MutableStateFlow(null)
        every { extras.places } returns MutableStateFlow(null)
        every { extras.lists } returns MutableStateFlow(emptyList())
        every { extras.feed } returns MutableStateFlow(null)
        every { device.config } returns MutableStateFlow(ChatDeviceConfig(sounds = false))
        every { device.wallpaperFile } returns File(context.filesDir, "none.jpg")
        every { device.unmetered() } returns true
        every { media.outboxDir } returns File(context.filesDir, "chat-outbox")
    }

    private fun conversationViewModel() = ConversationViewModel(context, repository, media, extras, device, hints).also { viewModels += it }

    private fun homeViewModel(): ChatHomeViewModel {
        val plugins: PluginDirectory = mockk(relaxed = true)
        every { plugins.state } returns MutableStateFlow(PluginAvailability())
        val familie: com.gatecontrol.android.familie.FamilieRepository = mockk(relaxed = true)
        every { familie.profile } returns MutableStateFlow(null)
        val places: RulePlaceWatcher = mockk(relaxed = true)
        every { places.share } returns MutableStateFlow(false)
        every { places.supported } returns MutableStateFlow(false)
        return ChatHomeViewModel(mockk(relaxed = true), extras, repository, mockk(relaxed = true), familie, mockk(relaxed = true), plugins, places).also { viewModels += it }
    }

    private fun listViewModel(): ChatListViewModel {
        val push: PushRepository = mockk(relaxed = true)
        val inbox: InboxCache = mockk(relaxed = true)
        every { inbox.items } returns MutableStateFlow(emptyList())
        every { push.inbox } returns inbox
        return ChatListViewModel(repository, push, ChatUpdateHint(context), extras).also { viewModels += it }
    }

    private class Shown(val dark: Boolean, val content: @androidx.compose.runtime.Composable () -> Unit)

    private var shown by mutableStateOf<Shown?>(null)
    private var content = false

    private fun draw(dark: Boolean, screen: @androidx.compose.runtime.Composable () -> Unit) {
        shown = Shown(dark, screen)
        if (!content) {
            content = true
            rule.setContent {
                val s = shown ?: return@setContent
                key(s) {
                    GateControlTheme(darkTheme = s.dark) {
                        Scaffold(containerColor = MaterialTheme.colorScheme.background, modifier = Modifier.testTag("shot")) { inner ->
                            Box(Modifier.padding(inner).fillMaxSize()) { s.content() }
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun shot(name: String, dark: Boolean) {
        val dir = System.getenv("BRIDGED_SHOTS") ?: return
        val bmp = rule.onNodeWithTag("shot").captureToImage().asAndroidBitmap()
        File(dir).mkdirs()
        File(dir, "$name-${if (dark) "dunkel" else "hell"}.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun text(s: String) = rule.onNodeWithText(s, substring = true)

    private fun count(s: String) = rule.onAllNodesWithText(s, substring = true).fetchSemanticsNodes().size

    /** The chat [c] with [list] as its messages, light and dark. */
    private fun chat(name: String, c: ChatConversation, list: List<ChatMessage> = history(), seen: Boolean = true, onLinked: (() -> Unit)? = {}, check: () -> Unit) {
        stubRepository()
        profile.value = profile("bridge_chats", "msg_tools")
        conversations.value = listOf(c)
        messages.value = list
        for (dark in listOf(false, true)) {
            hints.setSeen("whatsapp", seen)
            val vm = conversationViewModel()
            val home = homeViewModel()
            draw(dark) { ConversationScreen(conversationId = c.id, onBack = {}, onOpenInfo = {}, onOpenImage = {}, onOpenLinked = onLinked, viewModel = vm, homeViewModel = home) }
            rule.waitUntil(5_000) { count(list.lastOrNull()?.text ?: "Kim Muster") > 0 }
            shot(name, dark)
            check()
        }
    }

    // ─── the list ───────────────────────────────────────────────────

    @Test
    fun `the list - the service's mark, a filter per service, and no claim that everything stays on the server`() {
        stubRepository()
        profile.value = profile("bridge_chats")
        val lastTheirs = """{"id":95,"conversationId":32,"senderId":null,"kind":"text","mine":false,"createdAt":"2026-11-14T19:58:00.000Z","text":"Wer bringt Kuchen mit?","reactions":[],
            "bridge":{"sender":{"contactId":14,"name":"Robin"},"remote":false,"state":null,"reason":null,"placeholder":null}}"""
        val lastFailed = """{"id":96,"conversationId":31,"senderId":7,"kind":"text","mine":true,"createdAt":"2026-11-14T19:58:00.000Z","text":"Sollen wir noch Getränke holen?","reactions":[],"status":"failed",
            "bridge":{"sender":null,"remote":false,"state":"failed","reason":"network_error","placeholder":null}}"""
        val lastPlain = """{"id":50,"conversationId":5,"senderId":7,"kind":"text","mine":true,"createdAt":"2026-11-14T19:50:00.000Z","text":"Bist du schon unterwegs?","reactions":[],"status":"read"}"""
        conversations.value = listOf(
            conv(31, last = lastFailed),
            conv(32, kind = "group", title = "Elternrunde 4b", last = lastTheirs, unread = 1),
            conv(5, title = "Marc", bridged = false, last = lastPlain),
            conv(33, title = null),
        )
        for (dark in listOf(false, true)) {
            val vm = listViewModel()
            draw(dark) { ChatListScreen(onOpen = {}, onOpenInbox = {}, onNewChat = {}, onOpenUpdates = {}, viewModel = vm) }
            rule.waitUntil(5_000) { count("Elternrunde 4b") > 0 }
            shot("01-liste", dark)
            rule.onNodeWithText("Kim Muster").assertIsDisplayed()
            text("Robin: Wer bringt Kuchen mit?").assertIsDisplayed()
            rule.onNodeWithText("Unbekannter Kontakt").assertIsDisplayed()
            assertEquals("a contact without a name is no deleted account", 0, count("Gelöschtes Konto"))
            assertEquals("three chats over WhatsApp carry its mark", 3, rule.onAllNodesWithContentDescription("über WhatsApp").fetchSemanticsNodes().size)
            assertEquals("the message that did not go out", 1, rule.onAllNodesWithContentDescription("Nicht gesendet").fetchSemanticsNodes().size)
            assertEquals("the sentence is not true for somebody with chats over a bridge", 0, count("Alle Chats liegen nur auf deinem GateControl-Server"))
            assertEquals("no update hint: the app knows the word", 0, count("neuere App-Version"))
            // a filter per service
            rule.onNodeWithText("Alle").assertIsSelected()
            rule.onNodeWithText("Nur GateControl").performClick()
            rule.waitForIdle()
            rule.onNodeWithText("Nur GateControl").assertIsSelected()
            rule.onNodeWithText("Marc").assertIsDisplayed()
            assertEquals(0, count("Elternrunde 4b"))
            rule.onNodeWithText("WhatsApp").performClick()
            rule.waitForIdle()
            shot("02-liste-filter-whatsapp", dark)
            rule.onNodeWithText("Elternrunde 4b").assertIsDisplayed()
            assertEquals(0, count("Marc"))
            rule.onNodeWithText("Alle").performClick()
            rule.waitForIdle()
            rule.onNodeWithText("Marc").assertIsDisplayed()
        }
    }

    @Test
    fun `the list without a chat over a bridge is what it was`() {
        stubRepository()
        profile.value = profile("linked_services")
        conversations.value = listOf(conv(5, title = "Marc", bridged = false))
        val vm = listViewModel()
        draw(false) { ChatListScreen(onOpen = {}, onOpenInbox = {}, onNewChat = {}, onOpenUpdates = {}, viewModel = vm) }
        rule.waitUntil(5_000) { count("Marc") > 0 }
        shot("03-liste-ohne-bruecke", false)
        text("Alle Chats liegen nur auf deinem GateControl-Server").assertIsDisplayed()
        assertEquals(0, count("Nur GateControl"))
        assertEquals(0, rule.onAllNodesWithContentDescription("über WhatsApp").fetchSemanticsNodes().size)
        // the first chat over a bridge arrives: the sentence goes, the filters come — whatever the profile still says
        conversations.value = conversations.value + conv(31)
        rule.waitUntil(5_000) { count("Nur GateControl") > 0 }
        assertEquals(0, count("Alle Chats liegen nur auf deinem GateControl-Server"))
    }

    // ─── the chat ───────────────────────────────────────────────────

    @Test
    fun `the chat - where it runs, a contact's messages, placeholders, a message that did not go out`() {
        var resent = 0
        coEvery { repository.resend(any()) } answers { resent++; ChatResult.Ok(firstArg()) }
        chat("04-chat", conv()) {
            rule.onNodeWithText("über WhatsApp").assertIsDisplayed()
            rule.onNodeWithText("Dieser Chat läuft über WhatsApp – nicht nur über deinen Server.").assertIsDisplayed()
            rule.onNodeWithText("Mehr").assertIsDisplayed()
            // the quote names the contact, not "GateControl"
            assertEquals(0, count("GateControl"))
            assertTrue(rule.onAllNodesWithText("Kim Muster").fetchSemanticsNodes().size >= 2)
            rule.onNodeWithText("Bild · grill-2025.jpg – folgt; in WhatsApp ansehen").assertIsDisplayed()
            rule.onNodeWithText("So sah es letztes Jahr aus").assertIsDisplayed()
            rule.onNodeWithText("Nachricht dieser Art wird hier noch nicht angezeigt – in WhatsApp ansehen.").assertIsDisplayed()
            rule.onNodeWithText("Nicht gesendet. Der Dienst war nicht erreichbar.").assertIsDisplayed()
            rule.onNodeWithText("Erneut senden").performClick()
            // text only: no attachments, no voice message, the service in the placeholder
            rule.onNodeWithText("Nachricht über WhatsApp").assertIsDisplayed()
            assertEquals(0, rule.onAllNodesWithContentDescription("Anhängen").fetchSemanticsNodes().size)
            assertEquals(0, rule.onAllNodesWithContentDescription("Für eine Sprachnachricht gedrückt halten").fetchSemanticsNodes().size)
        }
        assertEquals(2, resent)
        coVerify(exactly = 2) { repository.resend(match { it.id == 96L }) }
    }

    @Test
    fun `the first visit - the note, Verstanden, then the strip, and Mehr brings it back`() {
        chat("05-chat-erster-besuch", conv(), seen = false) {
            rule.onNodeWithText("Chat über WhatsApp").assertIsDisplayed()
            text("Dein Gegenüber schreibt mit der gewohnten App").assertIsDisplayed()
            text("dort endet die Ende-zu-Ende-Verschlüsselung").assertIsDisplayed()
            text("Bilder, Dateien und Sprachnachrichten folgen").assertIsDisplayed()
            text("gibt es in einem Chat über WhatsApp nicht").assertIsDisplayed()
            assertEquals(0, count("Dieser Chat läuft über WhatsApp"))
            rule.onNodeWithText("Verstanden").performClick()
            rule.waitForIdle()
            assertTrue("whatsapp" in hints.seen.value)
            assertEquals(0, count("Dein Gegenüber schreibt"))
            rule.onNodeWithText("Dieser Chat läuft über WhatsApp – nicht nur über deinen Server.").assertIsDisplayed()
            rule.onNodeWithText("Mehr").performClick()
            rule.waitForIdle()
            rule.onNodeWithText("Chat über WhatsApp").assertIsDisplayed()
            assertFalse("whatsapp" in hints.seen.value)
        }
    }

    @Test
    fun `sending a text - through the outbox, with the answer mark`() {
        stubRepository()
        profile.value = profile("bridge_chats")
        conversations.value = listOf(conv())
        messages.value = history()
        hints.setSeen("whatsapp", true)
        val vm = conversationViewModel()
        val home = homeViewModel()
        draw(false) { ConversationScreen(conversationId = 31, onBack = {}, onOpenInfo = {}, onOpenImage = {}, viewModel = vm, homeViewModel = home) }
        rule.waitUntil(5_000) { count("Passt, dann bis Samstag!") > 0 }
        rule.onNode(hasSetTextAction()).performTextInput("Bis dann!")
        rule.waitForIdle()
        rule.onAllNodesWithContentDescription("Senden").fetchSemanticsNodes().let { assertEquals(1, it.size) }
        rule.onNode(androidx.compose.ui.test.hasContentDescription("Senden")).performClick()
        rule.waitForIdle()
        coVerify(timeout = 3_000) { repository.sendText(31, "Bis dann!", null) }
        // what a message may be used for: a failed one cannot be edited, one written in WhatsApp itself neither
        val failed = history().last()
        assertFalse(vm.canEdit(failed))
        assertFalse(ChatBridgeChat.actsOut(conv(), failed))
    }

    @Test
    fun `a group of the service - who wrote, who types`() {
        fun from(id: Long, contact: Long, name: String?, text: String) =
            msg(id, false, text, """{"sender":{"contactId":$contact,"name":${name?.let { "\"$it\"" }}},"remote":false,"state":null,"reason":null,"placeholder":null}""", conv = 32)
        val list = listOf(from(101, 14, "Robin", "Wer bringt Kuchen mit?"), from(102, 15, null, "Ich!"), msg(103, true, "Super, danke.", own("delivered"), ""","status":"delivered"""", conv = 32))
        contactTyping.value = mapOf(32L to ChatContactTyping(listOf("Robin"), Long.MAX_VALUE))
        chat("06-chat-gruppe", conv(32, kind = "group", title = "Elternrunde 4b"), list) {
            rule.onNodeWithText("Elternrunde 4b").assertIsDisplayed()
            rule.onNodeWithText("Robin schreibt …").assertIsDisplayed()
            rule.onNodeWithText("Robin").assertIsDisplayed()
            rule.onNodeWithText("Unbekannter Kontakt").assertIsDisplayed()
            assertEquals(1, rule.onAllNodesWithContentDescription("Zugestellt").fetchSemanticsNodes().size)
        }
    }

    // ─── when it cannot send ────────────────────────────────────────

    @Test
    fun `the bridge is away - a warning, the history stays, no input`() {
        chat("07-chat-nicht-erreichbar", conv(state = "unreachable")) {
            text("Die Brücke zu WhatsApp ist gerade nicht erreichbar").assertIsDisplayed()
            rule.onNodeWithText("Senden über WhatsApp geht gerade nicht – der Verlauf bleibt lesbar.").assertIsDisplayed()
            rule.onNodeWithText("Passt, dann bis Samstag!").assertIsDisplayed()
            assertEquals(0, rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size)
            assertEquals("nothing can be sent again while nothing is taken", 0, count("Erneut senden"))
            rule.onNodeWithText("Nicht gesendet. Der Dienst war nicht erreichbar.").assertIsDisplayed()
            assertEquals(0, count("Neu verknüpfen"))
        }
    }

    @Test
    fun `signed out by the service - Neu verknuepfen leads to Verknuepfte Dienste`() {
        var opened = 0
        chat("08-chat-abgemeldet", conv(state = "logged_out"), onLinked = { opened++ }) {
            text("WhatsApp hat die Brücke abgemeldet").assertIsDisplayed()
            assertEquals(0, rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size)
            rule.onNodeWithText("Neu verknüpfen").performClick()
        }
        assertEquals(2, opened)
    }

    @Test
    fun `not linked any more, locked for a child, switched off, no longer in the chat`() {
        var opened = 0
        chat("09-chat-nicht-verknuepft", conv(state = "unlinked"), onLinked = { opened++ }) {
            text("Dein Konto bei WhatsApp ist nicht verknüpft").assertIsDisplayed()
            rule.onNodeWithText("Verknüpfen").performClick()
            assertEquals(0, rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size)
        }
        assertEquals(2, opened)
        chat("10-chat-gesperrt", conv(state = "locked")) {
            rule.onNodeWithText("Für dein Konto gesperrt, bis deine Eltern den Dienst freischalten.").assertIsDisplayed()
            assertEquals("a child cannot link by itself", 0, count("Verknüpfen"))
            assertEquals(0, rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size)
        }
        chat("11-chat-aus", conv(state = "off")) {
            text("Die Brücke zu WhatsApp ist auf diesem Server ausgeschaltet").assertIsDisplayed()
            assertEquals(0, rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size)
        }
        chat("12-chat-geschlossen", conv(closed = true)) {
            assertEquals("above the messages and in place of the input", 2, count("Du bist bei WhatsApp nicht mehr in diesem Chat"))
            assertEquals(0, rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size)
        }
        // without a way to the settings (the bubble) the bar has no button
        chat("13-chat-abgemeldet-blase", conv(state = "logged_out"), onLinked = null) {
            text("WhatsApp hat die Brücke abgemeldet").assertIsDisplayed()
            assertEquals(0, count("Neu verknüpfen"))
        }
    }

    // ─── chat info ──────────────────────────────────────────────────

    private fun info(name: String, c: ChatConversation, vararg tokens: String, check: () -> Unit) {
        stubRepository()
        profile.value = profile(*tokens)
        conversations.value = listOf(c)
        for (dark in listOf(false, true)) {
            val vm = ChatInfoViewModel(context, repository, media, extras).also { viewModels += it }
            draw(dark) { ChatInfoScreen(conversationId = c.id, onBack = {}, onLeft = {}, onOpenMedia = {}, viewModel = vm) }
            rule.waitUntil(5_000) { count("Stummschalten") > 0 }
            shot(name, dark)
            check()
        }
    }

    @Test
    fun `the info of a chat over a bridge - where it runs, what does not work, nothing of members or files`() {
        info("15-info", conv(32, kind = "group", title = "Elternrunde 4b"), "bridge_chats", "msg_tools", "disappearing") {
            rule.onNodeWithText("Gruppe · über WhatsApp").assertIsDisplayed()
            text("Dieser Chat läuft über WhatsApp. Die Ende-zu-Ende-Verschlüsselung des Dienstes endet an der Brücke").assertIsDisplayed()
            text("Was hier nicht geht: Bilder, Dateien und Sprachnachrichten (folgen)").assertIsDisplayed()
            assertEquals(0, count("Alle Chats liegen nur auf deinem GateControl-Server"))
            rule.onNodeWithText("Stummschalten").assertIsDisplayed()
            rule.onNodeWithText("Archivieren").assertIsDisplayed()
            assertEquals("the people of the group are contacts of the service", 0, count("Mitglieder"))
            assertEquals(0, count("Medien & Dateien"))
            assertEquals(0, count("Selbstlöschende Nachrichten"))
            assertEquals(0, count("Umbenennen"))
            assertEquals(0, count("verlassen"))
        }
    }

    @Test
    fun `the info of a chat inside GateControl - only on your server is said only while that is true`() {
        info("16-info-gatecontrol", conv(5, title = "Marc", bridged = false), "linked_services") {
            text("Alle Chats liegen nur auf deinem GateControl-Server").assertIsDisplayed()
            rule.onNodeWithText("Medien & Dateien").assertIsDisplayed()
        }
        // the person has chats over a bridge: the sentence is not true any more, in no chat
        info("17-info-gatecontrol-mit-bruecke", conv(5, title = "Marc", bridged = false), "linked_services", "bridge_chats") {
            assertEquals(0, count("Alle Chats liegen nur auf deinem GateControl-Server"))
            rule.onNodeWithText("Medien & Dateien").assertIsDisplayed()
        }
    }

    @Test
    fun `a chat inside GateControl is what it was`() {
        val plain = ChatJson.message(json("""{"id":50,"conversationId":5,"senderId":9,"kind":"text","mine":false,"createdAt":"2026-11-14T19:50:00.000Z","text":"Bist du schon unterwegs?","reactions":[]}"""))!!
        chat("14-chat-gatecontrol", conv(5, title = "Marc", bridged = false), listOf(plain)) {
            rule.onNodeWithText("Marc").assertIsDisplayed()
            assertEquals(0, count("WhatsApp"))
            rule.onNodeWithText("Nachricht").assertIsDisplayed()
            assertEquals(1, rule.onAllNodesWithContentDescription("Anhängen").fetchSemanticsNodes().size)
        }
    }
}
