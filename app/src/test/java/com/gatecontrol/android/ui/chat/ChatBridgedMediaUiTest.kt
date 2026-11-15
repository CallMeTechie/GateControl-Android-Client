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
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onParent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.gatecontrol.android.chat.ChatBridgeHints
import com.gatecontrol.android.chat.ChatBridgeLogin
import com.gatecontrol.android.chat.ChatBridgeNetwork
import com.gatecontrol.android.chat.ChatBridgeNewChat
import com.gatecontrol.android.chat.ChatBridges
import com.gatecontrol.android.chat.ChatBridgesInfo
import com.gatecontrol.android.chat.ChatBridgesRepository
import com.gatecontrol.android.chat.ChatContactTyping
import com.gatecontrol.android.chat.ChatConversation
import com.gatecontrol.android.chat.ChatCountry
import com.gatecontrol.android.chat.ChatDeviceConfig
import com.gatecontrol.android.chat.ChatDeviceSettings
import com.gatecontrol.android.chat.ChatExtrasRepository
import com.gatecontrol.android.chat.ChatFeatures
import com.gatecontrol.android.chat.ChatJson
import com.gatecontrol.android.chat.ChatLimits
import com.gatecontrol.android.chat.ChatMediaStore
import com.gatecontrol.android.chat.ChatMessage
import com.gatecontrol.android.chat.ChatNewChat
import com.gatecontrol.android.chat.ChatPerson
import com.gatecontrol.android.chat.ChatPrefs
import com.gatecontrol.android.chat.ChatProfile
import com.gatecontrol.android.chat.ChatRepository
import com.gatecontrol.android.chat.ChatResult
import com.gatecontrol.android.chat.ChatSync
import com.gatecontrol.android.familie.PluginAvailability
import com.gatecontrol.android.familie.PluginDirectory
import com.gatecontrol.android.familie.places.RulePlaceWatcher
import com.gatecontrol.android.ui.theme.GateControlTheme
import com.google.gson.JsonParser
import io.mockk.coEvery
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
 * Messenger 1.15.0, files over a bridge and a new chat by number, drawn in German, light and dark, with the real
 * screens: a chat with a file on its way, files that came, files that did not (with the reason), a location; the paper
 * clip with files only and the microphone; "WhatsApp-Chat mit Nummer" under "Neue Gruppe" and every state of its page.
 * With `BRIDGED_SHOTS=<dir>` the drawn pages are saved there (for a look, not part of CI).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "de-w411dp-h891dp")
class ChatBridgedMediaUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val repository: ChatRepository = mockk(relaxed = true)
    private val bridges: ChatBridgesRepository = mockk(relaxed = true)
    private val extras: ChatExtrasRepository = mockk(relaxed = true)
    private val media: ChatMediaStore = mockk(relaxed = true)
    private val device: ChatDeviceSettings = mockk(relaxed = true)
    private val hints = ChatBridgeHints(context)
    private val viewModels = mutableListOf<ViewModel>()

    private val conversations = MutableStateFlow<List<ChatConversation>>(emptyList())
    private val messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    private val profile = MutableStateFlow<ChatProfile?>(null)

    private fun json(s: String) = JsonParser.parseString(s).asJsonObject

    private fun profile(vararg tokens: String) = ChatProfile(
        user = ChatPerson(7, "Anna", null, "AN", null),
        prefs = ChatPrefs(),
        licensed = true,
        features = ChatFeatures(tokens = tokens.toSet()),
        limits = ChatLimits(),
        serverTime = null,
    )

    private val withFiles = """["text","reply","reaction","edit","delete","read","typing","image","video","voice","file"]"""
    private val types = """{"image":["image/jpeg","image/png","image/webp"],"video":["video/mp4"],"voice":["audio/ogg","audio/mp4","audio/aac","audio/mpeg"],"file":null}"""

    private fun conv(kind: String = "direct", title: String = "Kim Muster", supports: String = withFiles, mediaTypes: String? = types) = ChatJson.conversation(
        json(
            """{"id":31,"kind":"$kind","title":"$title","peer":null,"members":[{"userId":7,"name":"Anna","role":"member"}],
                "me":{"role":"member","notify":"all","lastReadId":99},"unread":0,"updatedAt":"2026-11-15T09:58:00.000Z",
                "bridge":{"network":"whatsapp","label":"WhatsApp","state":"ok","closed":false,"supports":$supports${mediaTypes?.let { ""","mediaTypes":$it""" } ?: ""}},
                "can":{"send":true,"pin":true,"editInfo":false,"manageMembers":false,"leave":false,"setDisappearing":false}}""",
        ),
    )!!

    private val kim = """"sender":{"contactId":12,"name":"Kim Muster","avatarMediaId":null},"remote":false,"state":null,"reason":null"""

    private fun msg(id: Long, kind: String, text: String?, bridge: String, extra: String = "", mine: Boolean = false) = ChatJson.message(
        json(
            """{"id":$id,"conversationId":31,"senderId":${if (mine) 7 else null},"kind":"$kind","mine":$mine,"createdAt":"2026-11-15T09:${10 + id % 40}:00.000Z",
                "text":${text?.let { "\"$it\"" }},"reactions":[],"bridge":$bridge$extra}""",
        ),
    )!!

    private fun ph(kind: String, name: String?, state: String?, reason: String? = null, geo: String = "") =
        """{$kim,"placeholder":{"kind":"$kind","name":${name?.let { "\"$it\"" }},"state":${state?.let { "\"$it\"" }},"reason":${reason?.let { "\"$it\"" }}$geo}}"""

    private fun history() = listOf(
        msg(1, "text", "Schau mal, unser Grill 🙂", "{$kim,\"placeholder\":null}"),
        msg(2, "image", "Letztes Jahr", "{$kim,\"placeholder\":null}", ""","media":{"id":"0123456789abcdef0123456789abcdef","mime":"image/jpeg","bytes":81234},"meta":{"w":1200,"h":900}"""),
        msg(3, "voice", null, "{$kim,\"placeholder\":null}", ""","media":{"id":"1123456789abcdef0123456789abcdef","mime":"audio/ogg","bytes":12000},"meta":{"durationMs":14000,"waveform":[10,80,200,120,40,90,160,220,60,30]}"""),
        msg(4, "file", null, "{$kim,\"placeholder\":null}", ""","media":{"id":"2123456789abcdef0123456789abcdef","mime":"video/mp4","bytes":2400000},"video":{"durationMs":21000,"width":1280,"height":720},"meta":{"fileName":"grill.mp4"}"""),
        msg(5, "file", null, "{$kim,\"placeholder\":null}", ""","media":{"id":"3123456789abcdef0123456789abcdef","mime":"application/pdf","bytes":340000},"meta":{"fileName":"Einladung.pdf"}"""),
        msg(6, "text", null, ph("image", "grill-2025.jpg", "loading")),
        msg(7, "text", null, ph("video", "urlaub.mp4", "failed", "too_large")),
        msg(8, "text", null, ph("file", "rechnung.exe", "failed", "infected")),
        msg(9, "text", "Brandenburger Tor", ph("location", null, null, geo = ""","geo":{"lat":52.5163,"lon":13.3777}""")),
        msg(10, "image", "Passt!", """{"sender":null,"remote":false,"state":"read","reason":null,"placeholder":null}""", ""","status":"read","media":{"id":"4123456789abcdef0123456789abcdef","mime":"image/webp","bytes":50000},"meta":{"w":800,"h":600}""", mine = true),
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
        every { repository.contactTyping } returns MutableStateFlow<Map<Long, ChatContactTyping>>(emptyMap())
        every { repository.dropped } returns MutableStateFlow(emptySet())
        every { repository.sync } returns MutableStateFlow<ChatSync>(ChatSync.Synced)
        every { repository.activeConversation } returns MutableStateFlow(null)
        every { repository.live } returns true
        coEvery { repository.hasOlder(any()) } returns false
        coEvery { repository.cachedConversation(any()) } answers { conversations.value.firstOrNull { it.id == firstArg<Long>() } }
        coEvery { repository.syncConversations(any()) } returns ChatSync.Synced
        coEvery { repository.people() } returns ChatResult.Ok(listOf(ChatPerson(9, "Marc", null, "MA", "#84CC16")))
        every { extras.actions } returns MutableStateFlow(null)
        every { extras.places } returns MutableStateFlow(null)
        every { extras.lists } returns MutableStateFlow(emptyList())
        every { extras.feed } returns MutableStateFlow(null)
        every { device.config } returns MutableStateFlow(ChatDeviceConfig(sounds = false))
        every { device.wallpaperFile } returns File(context.filesDir, "none.jpg")
        every { device.unmetered() } returns true
        every { media.outboxDir } returns File(context.filesDir, "chat-outbox")
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

    /** The attach sheet lives in a window of its own: its content, from the tile "Galerie" up. */
    private fun sheetShot(name: String, dark: Boolean) {
        val dir = System.getenv("BRIDGED_SHOTS") ?: return
        val bmp = runCatching { rule.onNodeWithText("Galerie").onParent().onParent().captureToImage().asAndroidBitmap() }.getOrNull() ?: return
        File(dir, "$name-${if (dark) "dunkel" else "hell"}.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun count(s: String) = rule.onAllNodesWithText(s, substring = true).fetchSemanticsNodes().size

    private fun chatScreen(dark: Boolean, c: ChatConversation) {
        hints.setSeen("whatsapp", true)
        val vm = ConversationViewModel(context, repository, media, extras, device, hints).also { viewModels += it }
        val home = homeViewModel()
        draw(dark) { ConversationScreen(conversationId = c.id, onBack = {}, onOpenInfo = {}, onOpenImage = {}, onOpenLinked = {}, viewModel = vm, homeViewModel = home) }
    }

    // ─── files in a bridged chat ────────────────────────────────────

    @Test
    fun `a chat with files - on their way, arrived, not arrived, a location, the paper clip and the microphone`() {
        stubRepository()
        profile.value = profile("bridge_chats", "bridge_media")
        conversations.value = listOf(conv())
        messages.value = history()
        for (dark in listOf(false, true)) {
            chatScreen(dark, conv())
            rule.waitUntil(5_000) { count("Brandenburger Tor") > 0 }
            shot("10-chat-dateien", dark)
            rule.onNodeWithText("Bild · grill-2025.jpg – wird geladen …").assertIsDisplayed()
            rule.onNodeWithText("Video · urlaub.mp4 – zu groß für GateControl; in WhatsApp ansehen").assertIsDisplayed()
            rule.onNodeWithText("Datei · rechnung.exe – gesperrt – Schadsoftware gefunden").assertIsDisplayed()
            rule.onNodeWithText("Standort").assertIsDisplayed()
            rule.onNodeWithText("Auf der Karte ansehen").assertIsDisplayed()
            assertEquals("no 'folgt' any more", 0, count("folgt"))
            // what came is shown as in an own chat; older lines scrolled out of view are still there
            assertEquals(1, count("Passt!"))
            // input: the paper clip with files only, the microphone (AAC in MP4 is taken)
            rule.onNodeWithContentDescription("Anhängen").assertIsDisplayed()
            rule.onNodeWithContentDescription("Für eine Sprachnachricht gedrückt halten").assertIsDisplayed()
            rule.onNodeWithContentDescription("Anhängen").performClick()
            rule.waitForIdle()
            rule.waitUntil(3_000) { count("Galerie") > 0 }
            sheetShot("11-anhang-nur-dateien", dark)
            rule.onNodeWithText("Galerie").assertIsDisplayed()
            rule.onNodeWithText("Datei").assertIsDisplayed()
            assertEquals("no location tile over a bridge (the one 'Standort' is the line in the chat)", 1, rule.onAllNodesWithText("Standort").fetchSemanticsNodes().size)
            assertEquals(0, count("Abstimmung"))
        }
    }

    @Test
    fun `a chat of a server without files - text only as before`() {
        stubRepository()
        profile.value = profile("bridge_chats")
        val old = conv(supports = """["text","reply","reaction"]""", mediaTypes = null)
        conversations.value = listOf(old)
        messages.value = listOf(msg(6, "text", "Kommt ihr?", ph("image", "grill-2025.jpg", null)))
        chatScreen(false, old)
        rule.waitUntil(5_000) { count("Kommt ihr?") > 0 }
        rule.onNodeWithText("Bild · grill-2025.jpg – folgt; in WhatsApp ansehen").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithContentDescription("Anhängen").fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodesWithContentDescription("Für eine Sprachnachricht gedrückt halten").fetchSemanticsNodes().size)
    }

    // ─── a new chat by number ───────────────────────────────────────

    @Test
    fun `a chat just started by number - the first line names the number`() {
        stubRepository()
        profile.value = profile("bridge_chats", "bridge_media", "bridge_new_chat")
        val c = conv(title = "Lena Berger")
        conversations.value = listOf(c)
        messages.value = emptyList()
        com.gatecontrol.android.chat.ChatNumberStarted.put(c.id, "+49 151 23456789", "Lena Berger")
        for (dark in listOf(false, true)) {
            chatScreen(dark, c)
            rule.waitUntil(5_000) { count("Neuer Chat über WhatsApp") > 0 }
            shot("41-chat-neu-per-nummer", dark)
            rule.onNodeWithText("Neuer Chat über WhatsApp mit +49 151 23456789. Lena Berger sieht deine Nachrichten in der gewohnten WhatsApp-App.").assertIsDisplayed()
        }
        com.gatecontrol.android.chat.ChatNumberStarted.clear()
    }


    private fun wa(newChat: ChatBridgeNewChat? = ChatBridgeNewChat(true, perHour = 10, left = 9, retryAfterS = 0)) = ChatBridgesInfo(
        available = true,
        networks = listOf(ChatBridgeNetwork(ChatBridges.WHATSAPP, ChatBridges.NET_RUNNING, logins = listOf(ChatBridgeLogin("l1", "+49 170 1", ChatBridges.LOGIN_CONNECTED, null)), newChat = newChat)),
    )

    @Test
    fun `Neuer Chat - the row under Neue Gruppe only with the word and a link`() {
        stubRepository()
        coEvery { bridges.load() } returns ChatResult.Ok(wa())
        for (dark in listOf(false, true)) {
            profile.value = profile("bridge_chats", "bridge_new_chat")
            val vm = NewChatViewModel(repository, bridges).also { viewModels += it }
            var picked: String? = null
            draw(dark) { NewChatScreen(group = false, onBack = {}, onNewGroup = {}, onOpened = {}, onNewNumber = { picked = it }, viewModel = vm) }
            rule.waitUntil(5_000) { count("WhatsApp-Chat mit Nummer") > 0 }
            shot("20-neuer-chat", dark)
            rule.onNodeWithText("Nummer eingeben und prüfen").assertIsDisplayed()
            rule.onNodeWithText("WhatsApp-Chat mit Nummer").performClick()
            assertEquals("whatsapp", picked)
        }
        // a child whose parents keep new chats off: no row
        coEvery { bridges.load() } returns ChatResult.Ok(wa(ChatBridgeNewChat(false, ChatNewChat.R_CHILD_NEW_CHAT_OFF)))
        val vm = NewChatViewModel(repository, bridges).also { viewModels += it }
        draw(false) { NewChatScreen(group = false, onBack = {}, onNewGroup = {}, onOpened = {}, viewModel = vm) }
        rule.waitUntil(5_000) { count("Marc") > 0 }
        assertEquals(0, count("mit Nummer"))
        // without the word nobody is asked
        profile.value = profile("bridge_chats")
        val vm2 = NewChatViewModel(repository, bridges).also { viewModels += it }
        draw(false) { NewChatScreen(group = false, onBack = {}, onNewGroup = {}, onOpened = {}, viewModel = vm2) }
        rule.waitUntil(5_000) { count("Marc") > 0 }
        assertEquals(0, count("mit Nummer"))
    }

    private fun page(name: String, ui: NumberChatUi, check: () -> Unit = {}) {
        for (dark in listOf(false, true)) {
            draw(dark) {
                NewNumberChatContent(ui = ui, name = "WhatsApp", onBack = {}, onInput = {}, onCountry = {}, onCheck = {}, onStart = {}, onOther = {}, onOpenLinked = {})
            }
            rule.waitUntil(3_000) { count("WhatsApp-Chat mit Nummer") > 0 }
            shot(name, dark)
            check()
        }
    }

    private val net = ChatBridges.WHATSAPP
    private fun ui(input: String = "0151 23456789", phase: NumberPhase = NumberPhase.Input, perHour: Int? = 10, country: ChatCountry = ChatNewChat.DEFAULT_COUNTRY) =
        NumberChatUi(network = net, loading = false, country = country, input = input, phase = phase, perHour = perHour)

    @Test
    fun `the page - every state of the draft`() {
        page("30-nummer-leer", ui(input = "")) {
            rule.onNodeWithText("Telefonnummer").assertIsDisplayed()
            rule.onNodeWithText("Mit oder ohne 0 am Anfang, Leerzeichen sind egal. Andere Länder: Vorwahl wählen oder Nummer mit + einfügen.").assertIsDisplayed()
            // Messenger 1.18: the system's contact picker is gone (contacts come from GateControl only)
            assertEquals(0, rule.onAllNodesWithContentDescription("Aus Kontakten wählen").fetchSemanticsNodes().size)
            rule.onNodeWithText("+49").assertIsDisplayed()
            rule.onNodeWithTag("newnum-primary").assertIsNotEnabled()
            rule.onNodeWithText("Zum Prüfen geht die Nummer über deine Brücke an WhatsApp. GateControl schreibt sie nicht ins Protokoll; gespeichert wird sie nur, wenn du den Chat startest.").assertIsDisplayed()
        }
        page("31-nummer-eingabe", ui()) {
            rule.onNodeWithText("Wird geprüft als +49 151 23456789").assertIsDisplayed()
            rule.onNodeWithTag("newnum-primary").assertIsEnabled()
            rule.onNodeWithText("Prüfen").assertIsDisplayed()
        }
        page("32-nummer-wird-geprueft", ui(phase = NumberPhase.Checking)) {
            rule.onNodeWithText("WhatsApp wird gefragt …").assertIsDisplayed()
            rule.onNodeWithText("Wird geprüft …").assertIsDisplayed()
        }
        page("33-nummer-gefunden", ui(phase = NumberPhase.Found("Lena Berger", "+49 151 23456789", null))) {
            rule.onNodeWithText("Lena Berger").assertIsDisplayed()
            rule.onNodeWithText("Hat WhatsApp").assertIsDisplayed()
            rule.onNodeWithText("Chat starten").assertIsDisplayed()
            rule.onNodeWithText("Andere Nummer").assertIsDisplayed()
        }
        page("34-nummer-chat-gibt-es-schon", ui(phase = NumberPhase.Found("Lena Berger", "+49 151 23456789", 31))) {
            rule.onNodeWithText("Ihr habt schon einen Chat").assertIsDisplayed()
            rule.onNodeWithText("Chat öffnen").assertIsDisplayed()
        }
        page("35-nummer-kein-whatsapp", ui(phase = NumberPhase.NotFound("+49 151 23456789"))) {
            rule.onNodeWithText("Kein WhatsApp unter dieser Nummer").assertIsDisplayed()
            rule.onNodeWithText("+49 151 23456789 ist bei WhatsApp nicht registriert.", substring = true).assertIsDisplayed()
        }
        page("36-nummer-ungueltig", ui(input = "0151 234")) {
            rule.onNodeWithText("Das ist keine vollständige Nummer – für ein deutsches Handy fehlen noch Ziffern.").assertIsDisplayed()
            rule.onNodeWithTag("newnum-primary").assertIsNotEnabled()
        }
        page("37-nummer-zu-viele", ui(phase = NumberPhase.RateLimited(java.util.Calendar.getInstance().apply { set(2026, 10, 15, 15, 12, 0) }.timeInMillis))) {
            rule.onNodeWithText("Zu viele Prüfungen").assertIsDisplayed()
            rule.onNodeWithText("Du hast in der letzten Stunde 10 Nummern geprüft. Damit WhatsApp dein Konto nicht sperrt, geht die nächste Prüfung ab 15:12.").assertIsDisplayed()
            rule.onNodeWithTag("newnum-primary").assertIsNotEnabled()
        }
        page("38-nummer-nicht-erreichbar", ui(phase = NumberPhase.Unreachable)) {
            rule.onNodeWithText("Brücke nicht erreichbar").assertIsDisplayed()
            rule.onNodeWithText("Erneut versuchen").assertIsDisplayed()
        }
        page("39-nummer-abgemeldet", ui(phase = NumberPhase.Blocked(ChatNewChat.Blocked.LOGGED_OUT))) {
            rule.onNodeWithText("WhatsApp ist abgemeldet").assertIsDisplayed()
            rule.onNodeWithText("Neu verknüpfen").assertIsDisplayed()
            assertEquals("no field while nothing can be checked", 0, count("Telefonnummer"))
        }
        page("40-nummer-kind-gesperrt", ui(phase = NumberPhase.Blocked(ChatNewChat.Blocked.LOCKED))) {
            rule.onNodeWithText("Von deinen Eltern gesperrt").assertIsDisplayed()
            rule.onNodeWithText("Schließen").assertIsDisplayed()
        }
    }

    @Test
    fun `the page with its view model - check, found, start`() {
        stubRepository()
        coEvery { bridges.load() } returns ChatResult.Ok(wa())
        coEvery { bridges.resolve(any(), "+4915123456789") } returns ChatResult.Ok(com.gatecontrol.android.chat.ChatNumberLookup(true, "Lena Berger", null))
        val vm = NewNumberChatViewModel(bridges, repository).also { viewModels += it }
        var opened: Long? = null
        coEvery { repository.openNumberChat(any(), any()) } returns ChatResult.Ok(com.gatecontrol.android.chat.ChatNumberChat(conv(title = "Lena Berger"), true))
        draw(false) { NewNumberChatScreen(network = net, onBack = {}, onOpened = { opened = it }, onOpenLinked = {}, viewModel = vm) }
        rule.waitUntil(5_000) { count("Telefonnummer") > 0 }
        rule.onNodeWithTag("newnum-field").performClick()
        vm.setInput("0151 23456789")
        rule.waitForIdle()
        rule.onNodeWithText("Prüfen").performClick()
        rule.waitUntil(5_000) { count("Lena Berger") > 0 }
        rule.onNodeWithText("Chat starten").performClick()
        rule.waitUntil(5_000) { opened != null }
        assertEquals(31L, opened)
    }
}
