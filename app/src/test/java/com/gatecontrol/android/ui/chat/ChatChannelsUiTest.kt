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
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.gatecontrol.android.chat.ChatBridgeHints
import com.gatecontrol.android.chat.ChatBridgeNetwork
import com.gatecontrol.android.chat.ChatBridgeLogin
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
import com.gatecontrol.android.chat.ChatList
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
import com.google.gson.JsonPrimitive
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Chats v2, Teil A (Messenger 1.20), drawn in German, light and dark, with the real screens: the head of „Chats“ with
 * ☰ and the TV symbol (its grey dot), the side menu, the page „Kanäle“, a channel (link preview, format, links, only
 * read, the bell, the note, the own reaction), a group where only the admins write, „Erneut versuchen“ of a file, and
 * without the word the list of before. With `BRIDGED_SHOTS=<dir>` the drawn pages are saved there (not part of CI).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "de-w411dp-h891dp")
class ChatChannelsUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val repository: ChatRepository = mockk(relaxed = true)
    private val extras: ChatExtrasRepository = mockk(relaxed = true)
    private val media: ChatMediaStore = mockk(relaxed = true)
    private val device: ChatDeviceSettings = mockk(relaxed = true)
    private val bridges: ChatBridgesRepository = mockk(relaxed = true)
    private val hints = ChatBridgeHints(context)
    private val viewModels = mutableListOf<ViewModel>()

    private val conversations = MutableStateFlow<List<ChatConversation>>(emptyList())
    private val channels = MutableStateFlow<List<ChatConversation>>(emptyList())
    private val channelsOn = MutableStateFlow(true)
    private val messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    private val profile = MutableStateFlow<ChatProfile?>(null)

    private fun json(s: String) = JsonParser.parseString(s).asJsonObject

    private fun profile(vararg tokens: String) = ChatProfile(
        user = ChatPerson(7, "Kevin", null, "KE", null),
        prefs = ChatPrefs(),
        licensed = true,
        features = ChatFeatures(tokens = tokens.toSet()),
        limits = ChatLimits(),
        serverTime = null,
    )

    private fun conv(
        id: Long,
        title: String,
        channel: Boolean = true,
        reason: String? = if (channel) "channel" else null,
        unread: Int = 0,
        last: String = "2026-11-15T14:05:00.000Z",
        text: String = "Kritische Lücke in VPN-Router X200",
        notify: String = if (channel) "none" else "all",
        state: String = "ok",
        kind: String = "group",
    ) = ChatJson.conversation(
        json(
            """{"id":$id,"kind":"$kind","title":"$title","members":[{"userId":7,"name":"Kevin","role":"member"}],"me":{"role":"member","notify":"$notify"},"muted":${notify == "none"},
                "unread":$unread,"updatedAt":"$last",
                "lastMessage":{"id":${id * 10},"conversationId":$id,"kind":"text","mine":false,"createdAt":"$last","text":${JsonPrimitive(text)},"reactions":[],"bridge":{"sender":${if (channel) "null" else """{"contactId":12,"name":"Kim Muster"}"""},"channel":$channel}},
                "bridge":{"network":"whatsapp","label":"WhatsApp","state":"$state","closed":false,"channel":$channel,"readOnly":${reason != null},${if (channel) "\"reactions\":\"own\"," else ""}"supports":["text","reply","reaction","image","file"]},
                "can":{"send":${reason == null},"sendReason":${reason?.let { "\"$it\"" }},"react":true,"pin":true,"editInfo":false,"manageMembers":false,"leave":false,"setDisappearing":false}}""",
        ),
    )!!

    private fun msg(id: Long, conv: Long, text: String?, bridge: String, extra: String = "", mine: Boolean = false) = ChatJson.message(
        json(
            """{"id":$id,"conversationId":$conv,"senderId":${if (mine) 7 else null},"kind":"text","mine":$mine,"createdAt":"2026-11-15T14:05:00.000Z",
                "text":${text?.let { JsonPrimitive(it).toString() } ?: "null"},"reactions":[],"bridge":$bridge$extra}""",
        ),
    )!!

    private val post = """{"sender":null,"channel":true,"remote":false,"state":null,"reason":null,"placeholder":null}"""
    private val kim = """{"sender":{"contactId":12,"name":"Kim Muster"},"remote":false,"state":null,"reason":null,"placeholder":null}"""

    private val sicherheit = conv(41, "Sicherheit kompakt", unread = 3)
    private val stadtwerke = conv(42, "Stadtwerke Beispielstadt", unread = 1, last = "2026-11-15T11:40:00.000Z", text = "Wasserleitung Hauptstraße: Arbeiten bis Freitag")
    private val wetter = conv(43, "Wetterwarnungen Nord", last = "2026-11-14T18:00:00.000Z", text = "Sturmböen ab heute Abend")

    private val postText = "Jetzt patchen: Für die Router-Serie X200 ist ein Update erschienen.\n\nBetroffen sind Firmware-Stände vor 4.2.1.\n\nhttps://sicherheit-kompakt.example/news/x200"

    private fun channelPosts() = listOf(
        msg(
            410, 41, postText, post,
            ""","marks":[{"type":"bold","start":0,"end":14},{"type":"italic","start":${postText.indexOf("4.2.1")},"end":${postText.indexOf("4.2.1") + 5}}],
               "preview":{"url":"https://sicherheit-kompakt.example/news/x200","title":"Kritische Lücke in VPN-Router X200: Update jetzt einspielen","description":"Angreifer können ohne Anmeldung Befehle ausführen.","site":"sicherheit-kompakt.example",
                 "image":{"mediaId":"0f3c0f3c0f3c0f3c0f3c0f3c0f3c0f3c","mime":"image/jpeg","w":640,"h":360,"state":"loading"}},
               "reactions":[{"emoji":"👍","count":1,"userIds":[7]}]""",
        ),
    )

    @After
    fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
    }

    private fun stubRepository() {
        every { repository.conversations } returns conversations
        every { repository.allConversations } returns conversations
        every { repository.channels } returns channels
        every { repository.channelsOn } returns channelsOn
        every { repository.conversation(any()) } answers {
            val id = firstArg<Long>()
            kotlinx.coroutines.flow.flow { kotlinx.coroutines.flow.combine(conversations, channels) { a, b -> a + b }.collect { list -> emit(list.firstOrNull { it.id == id }) } }
        }
        every { repository.messages(any()) } returns messages
        every { repository.outbox(any()) } returns flowOf(emptyList())
        every { repository.gaps(any()) } returns flowOf(emptyList())
        every { repository.profile } returns profile
        every { repository.typing } returns MutableStateFlow(emptyMap())
        every { repository.contactTyping } returns MutableStateFlow<Map<Long, ChatContactTyping>>(emptyMap())
        every { repository.dropped } returns MutableStateFlow(emptySet())
        every { repository.sync } returns MutableStateFlow<ChatSync>(ChatSync.Synced)
        every { repository.activeConversation } returns MutableStateFlow(null)
        every { repository.live } returns true
        coEvery { repository.hasOlder(any()) } returns false
        coEvery { repository.cachedConversation(any()) } answers { (conversations.value + channels.value).firstOrNull { it.id == firstArg<Long>() } }
        coEvery { repository.syncConversations(any()) } returns ChatSync.Synced
        coEvery { repository.syncChannels(any()) } returns ChatSync.Synced
        every { extras.actions } returns MutableStateFlow(null)
        every { extras.places } returns MutableStateFlow(null)
        every { extras.lists } returns MutableStateFlow(listOf(ChatList("l1", "Verein", listOf(5L, 6L, 7L)), ChatList("l2", "Arbeit", listOf(8L))))
        every { extras.feed } returns MutableStateFlow(null)
        every { device.config } returns MutableStateFlow(ChatDeviceConfig(sounds = false))
        every { device.wallpaperFile } returns File(context.filesDir, "none.jpg")
        every { device.unmetered() } returns true
        every { media.outboxDir } returns File(context.filesDir, "chat-outbox")
        coEvery { bridges.load() } returns ChatResult.Ok(
            ChatBridgesInfo(
                available = true,
                networks = listOf(ChatBridgeNetwork(ChatBridges.WHATSAPP, ChatBridges.NET_RUNNING, consent = true, logins = listOf(ChatBridgeLogin("l1", "+49 170 1", ChatBridges.LOGIN_CONNECTED, null)))),
            ),
        )
    }

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
        return ChatListViewModel(repository, push, ChatUpdateHint(context), extras, bridges).also { viewModels += it }
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

    private fun count(s: String) = rule.onAllNodesWithText(s, substring = true).fetchSemanticsNodes().size

    private fun described(s: String) = rule.onAllNodesWithContentDescription(s).fetchSemanticsNodes().size

    // ─── the head of „Chats“ and the side menu ──────────────────────

    @Test
    fun `Chats - menu and TV symbol with its grey dot, the gear in the menu's foot`() {
        stubRepository()
        profile.value = profile("bridge_chats", "bridge_channels", "msg_tools", "linked_services")
        conversations.value = listOf(conv(31, "Kim Muster", channel = false, kind = "direct", text = "Bis Samstag!"))
        channels.value = listOf(sicherheit, stadtwerke, wetter)
        for (dark in listOf(false, true)) {
            var opened = 0
            val vm = listViewModel()
            draw(dark) { ChatListScreen(onOpen = {}, onOpenInbox = {}, onNewChat = {}, onOpenUpdates = {}, onOpenChannels = { opened++ }, viewModel = vm) }
            rule.waitUntil(5_000) { count("Kim Muster") > 0 }
            shot("50-chats-kopf", dark)
            rule.onNodeWithContentDescription("Menü").assertIsDisplayed()
            rule.onNodeWithContentDescription("Kanäle, neue Beiträge").assertIsDisplayed()
            rule.onNodeWithTag("channels-dot", useUnmergedTree = true).assertIsDisplayed()
            assertEquals("the gear left the head", 0, described("Chat-Einstellungen"))
            assertEquals("the channels are not in the list", 0, count("Sicherheit kompakt"))
            assertEquals("no chip „Kanäle“ — only the side menu's row", 1, rule.onAllNodesWithText("Kanäle").fetchSemanticsNodes().size)
            rule.onNodeWithContentDescription("Kanäle, neue Beiträge").performClick()
            assertEquals(1, opened)
            // the side menu
            rule.onNodeWithContentDescription("Menü").performClick()
            rule.waitUntil(5_000) { count("verbunden") > 0 }
            rule.waitForIdle()
            shot("51-chats-menue", dark)
            rule.onNodeWithText("ANSICHTEN").assertIsDisplayed()
            rule.onNodeWithText("Alle Chats").assertIsDisplayed()
            rule.onNodeWithText("4 neu").assertIsDisplayed()
            rule.onNodeWithText("MEINE LISTEN").assertIsDisplayed()
            assertEquals("the list's chip and the menu's row", 2, count("Verein"))
            rule.onNodeWithText("VERKNÜPFTE DIENSTE").assertIsDisplayed()
            rule.onNodeWithText("verbunden").assertIsDisplayed()
            rule.onNodeWithText("Dienst verknüpfen").assertIsDisplayed()
            for (icon in listOf("Blockiert", "Speicher", "Geräte", "Hilfe", "Einstellungen")) rule.onNodeWithContentDescription(icon).assertIsDisplayed()
            rule.onNodeWithText("Kanäle").performClick()
            assertEquals(2, opened)
        }
    }

    @Test
    fun `Chats without the word - no TV symbol, a channel is a read-only chat as before`() {
        stubRepository()
        channelsOn.value = false
        profile.value = profile("bridge_chats")
        conversations.value = listOf(conv(31, "Kim Muster", channel = false, kind = "direct", text = "Bis Samstag!"), sicherheit)
        val vm = listViewModel()
        draw(false) { ChatListScreen(onOpen = {}, onOpenInbox = {}, onNewChat = {}, onOpenUpdates = {}, viewModel = vm) }
        rule.waitUntil(5_000) { count("Kim Muster") > 0 }
        rule.onNodeWithText("Sicherheit kompakt").assertIsDisplayed()
        assertEquals(0, described("Kanäle, neue Beiträge"))
        assertEquals(0, described("Kanäle"))
    }

    // ─── the page „Kanäle“ ──────────────────────────────────────────

    @Test
    fun `the page Kanaele - rows like muted chats, the note, the empty page`() {
        stubRepository()
        profile.value = profile("bridge_chats", "bridge_channels")
        channels.value = listOf(wetter, sicherheit, stadtwerke)
        for (dark in listOf(false, true)) {
            var opened: Long? = null
            val vm = ChatChannelsViewModel(repository, extras).also { viewModels += it }
            draw(dark) { ChatChannelsScreen(onBack = {}, onOpen = { opened = it }, viewModel = vm) }
            rule.waitUntil(5_000) { count("Sicherheit kompakt") > 0 }
            shot("52-kanaele", dark)
            rule.onNodeWithText("WhatsApp").assertIsDisplayed()
            rule.onNodeWithText("Benachrichtigungen sind für Kanäle aus. Die Glocke im Kanal schaltet sie ein.").assertIsDisplayed()
            assertEquals("a struck-through bell per muted channel", 3, described("Stumm"))
            assertEquals("each with the service's logo", 3, described("über WhatsApp"))
            rule.onNodeWithText("3").assertIsDisplayed()
            rule.onNodeWithText("Sicherheit kompakt").performClick()
            assertEquals(41L, opened)
        }
        channels.value = emptyList()
        val vm = ChatChannelsViewModel(repository, extras).also { viewModels += it }
        draw(false) { ChatChannelsScreen(onBack = {}, onOpen = {}, viewModel = vm) }
        rule.waitUntil(5_000) { count("Du folgst keinem Kanal") > 0 }
        shot("53-kanaele-leer", false)
        rule.onNodeWithText("Du folgst keinem Kanal. Kanäle abonnierst du in WhatsApp unter „Aktuelles“.").assertIsDisplayed()
    }

    // ─── a channel ──────────────────────────────────────────────────

    private fun chat(dark: Boolean, c: ChatConversation) {
        hints.setSeen("whatsapp", true)
        val vm = ConversationViewModel(context, repository, media, extras, device, hints).also { viewModels += it }
        val home = homeViewModel()
        draw(dark) { ConversationScreen(conversationId = c.id, onBack = {}, onOpenInfo = {}, onOpenImage = {}, onOpenLinked = {}, viewModel = vm, homeViewModel = home) }
    }

    @Test
    fun `a channel - preview, format and links, only read, the bell, the note, the own reaction`() {
        stubRepository()
        profile.value = profile("bridge_chats", "bridge_channels", "bridge_media")
        channels.value = listOf(sicherheit)
        messages.value = channelPosts()
        coEvery { repository.patch(any(), any()) } returns ChatResult.Failed("offline", 0)
        for (dark in listOf(false, true)) {
            chat(dark, sicherheit)
            rule.waitUntil(5_000) { count("Kritische Lücke in VPN-Router X200: Update jetzt einspielen") > 0 }
            shot("54-kanal", dark)
            rule.onNodeWithText("Kanal · WhatsApp").assertIsDisplayed()
            rule.onNodeWithText("Nur lesen. In Kanälen schreibt nur der Kanal.").assertIsDisplayed()
            assertEquals("no input", 0, rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size)
            rule.onNodeWithText("Ein Kanal von WhatsApp. Hier liest du nur mit, Beiträge schreibt der Kanal. Benachrichtigungen sind für diesen Kanal aus.").assertIsDisplayed()
            rule.onNodeWithText("Einschalten").assertIsDisplayed()
            rule.onNodeWithText("Bild wird geladen …").assertIsDisplayed()
            rule.onNodeWithText("Angreifer können ohne Anmeldung Befehle ausführen.").assertIsDisplayed()
            rule.onNodeWithText("Reaktionen anderer zeigt WhatsApp nur in der WhatsApp-App. Hier siehst du deine eigene.").assertIsDisplayed()
            assertEquals("a channel's post has no sender's name", 0, count("Kim Muster"))
            // the text with its format; the address is a link in it
            rule.onNodeWithText(postText).assertIsDisplayed()
            rule.onNodeWithContentDescription("Benachrichtigungen für diesen Kanal einschalten").performClick()
            rule.waitForIdle()
            coVerify { repository.patch(41, match { it.toString() == """{"notify":"all"}""" }) }
            // the message menu: reacting, but no answer
            rule.onNodeWithText(postText).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnLongClick)
            rule.waitUntil(3_000) { count("👍") > 1 }
            assertEquals(0, count("Antworten"))
        }
    }

    @Test
    fun `a channel - empty, and while the bridge is away`() {
        stubRepository()
        profile.value = profile("bridge_chats", "bridge_channels")
        val away = conv(44, "Wetterwarnungen Nord", state = "unreachable", unread = 0)
        channels.value = listOf(away)
        messages.value = emptyList()
        for (dark in listOf(false, true)) {
            chat(dark, away)
            rule.waitUntil(5_000) { count("Noch keine Beiträge") > 0 }
            shot("55-kanal-leer-bruecke-weg", dark)
            rule.onNodeWithText("Noch keine Beiträge. Neue Beiträge erscheinen hier, sobald der Kanal etwas veröffentlicht.").assertIsDisplayed()
            rule.onNodeWithText("WhatsApp ist gerade nicht erreichbar. Neue Beiträge kommen an, sobald die Brücke wieder läuft.").assertIsDisplayed()
        }
    }

    // ─── a group where only the admins write; a file to try again ───

    @Test
    fun `a group where only the admins write - the bar instead of the input, reacting stays, links in every chat`() {
        stubRepository()
        profile.value = profile("bridge_chats", "bridge_channels", "bridge_media")
        val g = conv(32, "Elternrunde 4b", channel = false, reason = "admins_only")
        conversations.value = listOf(g)
        messages.value = listOf(
            msg(320, 32, "Elternabend am Dienstag, Infos unter www.schule.example/termine.", kim),
            msg(
                321, 32, null,
                """{"sender":{"contactId":12,"name":"Kim Muster"},"remote":false,"state":null,"reason":null,"placeholder":{"kind":"image","name":"plan.jpg","state":"failed","reason":"unavailable","retry":true}}""",
            ),
        )
        coEvery { repository.fetchBridged(any()) } answers { ChatResult.Ok(firstArg()) }
        for (dark in listOf(false, true)) {
            chat(dark, g)
            rule.waitUntil(5_000) { count("Elternabend am Dienstag") > 0 }
            shot("56-gruppe-nur-admins", dark)
            rule.onNodeWithText("Nur Admins schreiben in dieser Gruppe.").assertIsDisplayed()
            assertEquals("no input", 0, rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size)
            rule.onNodeWithText("Erneut versuchen").assertIsDisplayed().performClick()
            rule.waitForIdle()
            coVerify { repository.fetchBridged(match { it.id == 321L }) }
            rule.onNodeWithText("Elternabend am Dienstag, Infos unter www.schule.example/termine.").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnLongClick)
            rule.waitUntil(3_000) { count("👍") > 0 }
            assertEquals("reacting stays", true, count("👍") > 0)
            assertEquals(0, count("Antworten"))
        }
    }
}
