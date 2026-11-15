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
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.gatecontrol.android.chat.ChatBridgeHints
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
import com.gatecontrol.android.chat.ChatPerson
import com.gatecontrol.android.chat.ChatPersonSuggestion
import com.gatecontrol.android.chat.ChatPrefs
import com.gatecontrol.android.chat.ChatProfile
import com.gatecontrol.android.chat.ChatRepository
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
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Chats v2, Teil B (Messenger 1.21), drawn in German, light and dark, with the real screens: the list with a person
 * (the logos of its ways behind the name) and the suggestion card, the dialog „Zu einer Person zusammenführen?“, the
 * chat of a person (source chips, the way's logo at each message, „Nachricht über WhatsApp“ and the line above), the
 * chat info („Wege von …“, „Trennen“, „Mit Person zusammenführen …“) and the question after linking WhatsApp.
 * With `BRIDGED_SHOTS=<dir>` the drawn pages are saved there (not part of CI).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "de-w411dp-h891dp")
class ChatPeopleUiTest {

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

    private fun personJson(reply: String = "whatsapp") = """{"id":7,"name":"Jenny Backes","nameFrom":"account","userId":3,"conversationId":41,"names":["Jenny Backes","Schatzimausi 💕"],
        "ways":[{"way":"gatecontrol","label":"GateControl","conversationId":41,"title":"Jenny Backes","unread":0,"can":{"send":true}},
                {"way":"whatsapp","label":"WhatsApp","conversationId":17,"title":"Schatzimausi 💕","unread":1,"bridge":{"network":"whatsapp","label":"WhatsApp","state":"ok","supports":["text","reaction","image","file","voice"]},"can":{"send":true}}],
        "reply":{"way":"$reply","label":"${if (reply == "whatsapp") "WhatsApp" else "GateControl"}","conversationId":${if (reply == "whatsapp") 17 else 41}}}"""

    private fun jenny(reply: String = "whatsapp") = ChatJson.conversation(
        json(
            """{"id":41,"kind":"direct","title":"Jenny Backes","peer":{"userId":3,"name":"Jenny Backes","initials":"JB","colorHex":"#DB2777"},
                "members":[{"userId":7,"name":"Kevin"},{"userId":3,"name":"Jenny Backes","initials":"JB","colorHex":"#DB2777"}],"me":{"notify":"all"},"unread":1,"updatedAt":"2026-11-15T14:12:00.000Z",
                "lastMessage":{"id":905,"conversationId":17,"kind":"text","mine":false,"createdAt":"2026-11-15T14:12:00.000Z","text":"Bis später, ich bring Brot mit","reactions":[],"way":"whatsapp",
                  "bridge":{"sender":{"contactId":12,"name":"Schatzimausi 💕"}}},
                "can":{"send":true,"setDisappearing":true},"readUpTo":402,"deliveredUpTo":402,"person":${personJson(reply)}}""",
        ),
    )!!

    private val wa = ChatJson.conversation(
        json(
            """{"id":17,"kind":"direct","title":"Schatzimausi 💕","members":[{"userId":7,"name":"Kevin"}],"me":{"notify":"all"},"unread":1,
                "bridge":{"network":"whatsapp","label":"WhatsApp","state":"ok","supports":["text","reaction","image","file","voice"]},"can":{"send":true,"react":true},"person":${personJson()}}""",
        ),
    )!!

    private val kim = ChatJson.conversation(
        json("""{"id":50,"kind":"direct","title":"Kim Muster","peer":{"userId":9,"name":"Kim Muster"},"members":[{"userId":7,"name":"Kevin"},{"userId":9,"name":"Kim Muster"}],"me":{"notify":"all"},"unread":0,"updatedAt":"2026-11-15T09:00:00.000Z",
            "lastMessage":{"id":700,"conversationId":50,"kind":"text","mine":false,"senderId":9,"createdAt":"2026-11-15T09:00:00.000Z","text":"Bis Samstag!","reactions":[]},"can":{"send":true}}"""),
    )!!

    private fun msg(id: Long, conv: Long, text: String, at: String, mine: Boolean = false, way: String) = ChatJson.message(
        json(
            """{"id":$id,"conversationId":$conv,"senderId":${if (mine) 7 else if (way == "gatecontrol") 3 else null},"kind":"text","mine":$mine,"createdAt":"2026-11-15T$at:00.000Z","text":"$text","reactions":[],"way":"$way"
                ${if (way == "whatsapp") ""","bridge":{"sender":${if (mine) "null" else """{"contactId":12,"name":"Schatzimausi 💕"}"""},"remote":false,"state":${if (mine) "\"read\"" else "null"},"reason":null,"placeholder":null}""" else ""}
                ${if (mine && way == "whatsapp") ""","status":"read"""" else ""}}""",
        ),
    )!!

    private val history = listOf(
        msg(401, 41, "Bist du heute Abend da?", "12:02", way = "gatecontrol"),
        msg(402, 41, "Ja, ab sieben", "12:05", mine = true, way = "gatecontrol"),
        msg(900, 17, "Kannst du Milch mitbringen? 🥛", "13:40", way = "whatsapp"),
        msg(901, 17, "Klar, mach ich", "13:42", mine = true, way = "whatsapp"),
        msg(905, 17, "Bis später, ich bring Brot mit", "14:12", way = "whatsapp"),
    )

    private val suggestion = ChatPersonSuggestion(
        "18-4", 18, 4, "whatsapp", "WhatsApp", true, "own_whatsapp", "Opa ❤️", null, null, "Karl Backes", null, "KB", "#2563EB",
        "„Opa ❤️“ bei WhatsApp ist vielleicht Karl Backes.", "Karl Backes hat das eigene WhatsApp mit GateControl verknüpft, und es ist dein Kontakt „Opa ❤️“.",
    )

    @After
    fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
    }

    private fun stubRepository() {
        every { repository.conversations } returns conversations
        every { repository.allConversations } returns conversations
        every { repository.channels } returns MutableStateFlow(emptyList())
        every { repository.channelsOn } returns MutableStateFlow(true)
        every { repository.conversation(any()) } answers {
            val id = firstArg<Long>()
            kotlinx.coroutines.flow.flow { conversations.collect { list -> emit(list.firstOrNull { it.id == id }) } }
        }
        every { repository.messages(any()) } returns messages
        every { repository.personMessages(any()) } returns messages
        every { repository.outbox(any()) } returns flowOf(emptyList())
        every { repository.personOutbox(any()) } returns flowOf(emptyList())
        every { repository.wayConversations(any()) } answers { kotlinx.coroutines.flow.flow { conversations.collect { l -> emit(l.associateBy { it.id }) } } }
        every { repository.gaps(any()) } returns flowOf(emptyList())
        every { repository.profile } returns profile
        every { repository.typing } returns MutableStateFlow(emptyMap())
        every { repository.contactTyping } returns MutableStateFlow<Map<Long, ChatContactTyping>>(emptyMap())
        every { repository.dropped } returns MutableStateFlow(emptySet())
        every { repository.sync } returns MutableStateFlow<ChatSync>(ChatSync.Synced)
        every { repository.activeConversation } returns MutableStateFlow(null)
        every { repository.suggestions } returns suggestions
        every { repository.live } returns true
        coEvery { repository.hasOlder(any()) } returns false
        coEvery { repository.olderWays(any()) } returns emptySet()
        coEvery { repository.personLeadOf(any()) } returns null
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

    // ─── the list ───────────────────────────────────────────────────

    @Test
    fun `Chats - a person with the logos of its ways, the suggestion card and its dialog`() {
        stubRepository()
        profile.value = profile("bridge_chats", "bridge_channels", "chat_people")
        conversations.value = listOf(jenny(), kim)
        suggestions.value = listOf(suggestion)
        for (dark in listOf(false, true)) {
            val vm = listViewModel()
            draw(dark) { ChatListScreen(onOpen = {}, onOpenInbox = {}, onNewChat = {}, onOpenUpdates = {}, viewModel = vm) }
            rule.waitUntil(5_000) { count("Opa ❤️") > 0 }
            shot("60-chats-person-vorschlag", dark)
            rule.onNodeWithText("Jenny Backes").assertIsDisplayed()
            assertTrue("the logos behind the name", described("über WhatsApp") >= 1 && described("über GateControl") >= 1)
            rule.onNodeWithText("„Opa ❤️“ bei WhatsApp ist vielleicht Karl Backes.").assertIsDisplayed()
            rule.onNodeWithText("Zu einem Chat zusammenführen?").assertIsDisplayed()
            rule.onNodeWithText("Ansehen").performClick()
            rule.waitUntil(5_000) { count("Zu einer Person zusammenführen?") > 0 }
            rule.waitForIdle()
            shot("61-zusammenfuehren-dialog", dark)
            rule.onNodeWithText("WhatsApp-Chat").assertIsDisplayed()
            rule.onNodeWithText("Beide Chats erscheinen dann als ein Chat mit gemeinsamem Verlauf. Das siehst nur du.").assertIsDisplayed()
            rule.onNodeWithText("NAME IN DEINER LISTE").assertIsDisplayed()
            rule.onNodeWithText("Zusammenführen").assertIsDisplayed()
            rule.onNodeWithText("Nicht jetzt").assertExists()
            rule.onNodeWithText("Nicht mehr vorschlagen").assertExists()
        }
    }

    @Test
    fun `Chats without the word - no card, no logos behind names`() {
        stubRepository()
        profile.value = profile("bridge_chats")
        conversations.value = listOf(kim)
        suggestions.value = listOf(suggestion)
        val vm = listViewModel()
        draw(false) { ChatListScreen(onOpen = {}, onOpenInbox = {}, onNewChat = {}, onOpenUpdates = {}, viewModel = vm) }
        rule.waitUntil(5_000) { count("Kim Muster") > 0 }
        assertEquals(0, count("Zu einem Chat zusammenführen?"))
        assertEquals(0, described("über GateControl"))
    }

    // ─── the chat of a person ───────────────────────────────────────

    private fun chat(dark: Boolean) {
        hints.setSeen("whatsapp", true)
        val vm = ConversationViewModel(context, repository, media, extras, device, hints).also { viewModels += it }
        val home = homeViewModel()
        draw(dark) { ConversationScreen(conversationId = 41, onBack = {}, onOpenInfo = {}, onOpenImage = {}, onOpenLinked = {}, viewModel = vm, homeViewModel = home) }
    }

    @Test
    fun `the chat of a person - mixed history, the way's logo at each message, the answer over WhatsApp`() {
        stubRepository()
        profile.value = profile("bridge_chats", "bridge_channels", "chat_people")
        conversations.value = listOf(jenny(), wa)
        messages.value = history
        for (dark in listOf(false, true)) {
            chat(dark)
            rule.waitUntil(5_000) { count("Bis später, ich bring Brot mit") > 0 }
            shot("62-person-chat", dark)
            rule.onNodeWithText("GateControl · WhatsApp").assertIsDisplayed()
            rule.onNodeWithText("Alle").assertIsDisplayed()
            rule.onNodeWithTag("person-sources").assertIsDisplayed()
            assertTrue("a logo at each message", described("über WhatsApp") >= 3 && described("über GateControl") >= 2)
            rule.onNodeWithText("Nachricht über WhatsApp").assertIsDisplayed()
            rule.onNodeWithText("Geht über WhatsApp. Die Verschlüsselung von WhatsApp endet an der Brücke neben deinem Server.").assertIsDisplayed()
            // the source chip: only WhatsApp
            rule.onNodeWithText("WhatsApp").performClick()
            rule.waitUntil(5_000) { count("Ja, ab sieben") == 0 }
            shot("63-person-chat-whatsapp", dark)
            rule.onNodeWithText("Kannst du Milch mitbringen? 🥛").assertIsDisplayed()
        }
    }

    @Test
    fun `the chat of a person - the answer over GateControl, no line above the composer`() {
        stubRepository()
        profile.value = profile("bridge_chats", "chat_people")
        conversations.value = listOf(jenny(reply = "gatecontrol"), wa)
        messages.value = history
        for (dark in listOf(false, true)) {
            chat(dark)
            rule.waitUntil(5_000) { count("Bis später, ich bring Brot mit") > 0 }
            shot("64-person-chat-gatecontrol", dark)
            rule.onNodeWithText("Nachricht über GateControl").assertIsDisplayed()
            assertEquals(0, count("Die Verschlüsselung von WhatsApp endet"))
        }
    }

    // ─── the chat info ──────────────────────────────────────────────

    @Test
    fun `the info of a person - Wege von, Trennen, the name, Mit Person zusammenfuehren`() {
        stubRepository()
        profile.value = profile("bridge_chats", "msg_tools", "chat_people")
        conversations.value = listOf(jenny(), wa)
        for (dark in listOf(false, true)) {
            val vm = ChatInfoViewModel(context, repository, media, extras).also { viewModels += it }
            draw(dark) { ChatInfoScreen(conversationId = 41, onBack = {}, onLeft = {}, onOpenMedia = {}, viewModel = vm) }
            rule.waitUntil(5_000) { count("Stummschalten") > 0 }
            rule.onNodeWithText("WEGE VON JENNY BACKES").performScrollTo()
            rule.waitForIdle()
            shot("65-person-info", dark)
            rule.onNodeWithText("Nur für dich zusammengeführt – Jenny Backes sieht davon nichts.").assertIsDisplayed()
            rule.onNodeWithText("Konto Jenny Backes").assertIsDisplayed()
            rule.onNodeWithText("als „Schatzimausi 💕“").assertIsDisplayed()
            assertEquals(2, rule.onAllNodesWithText("Trennen").fetchSemanticsNodes().size)
            rule.onNodeWithText("Name in deiner Liste").assertIsDisplayed()
            rule.onNodeWithText("Mit Person zusammenführen …").performScrollTo().assertIsDisplayed()
            rule.onNodeWithTag("person-way-17").performScrollTo()
            rule.onAllNodesWithText("Trennen")[1].performClick()
            rule.waitUntil(5_000) { count("Den WhatsApp-Chat wieder getrennt zeigen?") > 0 }
            shot("66-trennen", dark)
            rule.onNodeWithText("Den WhatsApp-Chat wieder getrennt zeigen? Es geht keine Nachricht verloren.").assertIsDisplayed()
        }
    }

    // ─── the question after linking WhatsApp ────────────────────────

    @Test
    fun `the question after linking WhatsApp`() {
        for (dark in listOf(false, true)) {
            draw(dark) { RecognitionDialog(busy = false, onAnswer = {}, onDismiss = {}) }
            rule.waitUntil(5_000) { count("Dürfen andere auf diesem Server dich an deinem WhatsApp erkennen?") > 0 }
            shot("67-erkennung", dark)
            rule.onNodeWithText("Du kannst das jederzeit in „Mein Profil“ unter „Erkennung“ ändern.").assertIsDisplayed()
            rule.onNodeWithText("Ja, erkennen lassen").assertIsDisplayed()
            rule.onNodeWithText("Nein").assertIsDisplayed()
        }
    }
}
