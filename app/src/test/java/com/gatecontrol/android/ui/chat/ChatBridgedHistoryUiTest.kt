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
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
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
 * Messenger 1.17.0, older history over a bridge, drawn in German, light and dark, with the real chat screen: older
 * messages at their own time before the newer ones, files of the history with "Laden" (asked for once, then "wird
 * geladen …"), one WhatsApp no longer has, and what an answer that refuses does. With `BRIDGED_SHOTS=<dir>` the drawn
 * pages are saved there (for a look, not part of CI).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "de-w411dp-h891dp")
class ChatBridgedHistoryUiTest {

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
        val vm = ConversationViewModel(context, repository, media, extras, device, hints, mockk(relaxed = true)).also { viewModels += it }
        val home = homeViewModel()
        draw(dark) { ConversationScreen(conversationId = c.id, onBack = {}, onOpenInfo = {}, onOpenImage = {}, onOpenLinked = {}, viewModel = vm, homeViewModel = home) }
    }

    // ─── older history ──────────────────────────────────────────────

    private fun older() = listOf(
        msg(1, "text", "Weißt du noch, letzten Sommer?", "{$kim,\"placeholder\":null}", ""","createdAt":"2025-07-12T18:04:00.000Z" """.trim()),
        msg(2, "text", null, ph("image", "IMG-2041.jpg", "on_request")),
        msg(3, "text", null, ph("video", "VID-0007.mp4", "on_request")),
        msg(4, "text", null, ph("file", "Rechnung-2024.pdf", "failed", "gone")),
        msg(5, "text", null, ph("image", "IMG-2042.jpg", "loading")),
        msg(6, "text", "Und heute wieder Grillen?", "{$kim,\"placeholder\":null}"),
    )

    @Test
    fun `older history - files on request with Laden, one gone, one on its way`() {
        stubRepository()
        profile.value = profile("bridge_chats", "bridge_media", "bridge_history")
        conversations.value = listOf(conv())
        messages.value = older()
        for (dark in listOf(false, true)) {
            chatScreen(dark, conv())
            rule.waitUntil(5_000) { count("Und heute wieder Grillen?") > 0 }
            shot("50-chat-aelterer-verlauf", dark)
            rule.onNodeWithText("Bild · IMG-2041.jpg – älterer Verlauf").assertIsDisplayed()
            rule.onNodeWithText("Video · VID-0007.mp4 – älterer Verlauf").assertIsDisplayed()
            rule.onNodeWithText("Datei · Rechnung-2024.pdf – nicht mehr da; in WhatsApp ansehen").assertIsDisplayed()
            rule.onNodeWithText("Bild · IMG-2042.jpg – wird geladen …").assertIsDisplayed()
            assertEquals("one Laden per file on request", 2, count("Laden"))
            assertEquals(0, count("folgt"))
        }
    }

    @Test
    fun `Laden - asked for once, the answer shows wird geladen`() {
        stubRepository()
        profile.value = profile("bridge_chats", "bridge_media", "bridge_history")
        conversations.value = listOf(conv())
        val ask = msg(2, "text", null, ph("image", "IMG-2041.jpg", "on_request"))
        messages.value = listOf(ask)
        val answer = CompletableDeferred<ChatResult<ChatMessage>>()
        coEvery { repository.fetchBridged(any()) } coAnswers { answer.await() }
        chatScreen(false, conv())
        rule.waitUntil(5_000) { count("älterer Verlauf") > 0 }
        rule.onNodeWithText("Laden").performClick()
        rule.waitForIdle()
        // the second tap while the server answers asks nothing
        rule.onNodeWithText("Laden").performClick()
        rule.waitForIdle()
        shot("51-laden-angefragt", false)
        val loading = msg(2, "text", null, ph("image", "IMG-2041.jpg", "loading"))
        answer.complete(ChatResult.Ok(loading))
        messages.value = listOf(loading)
        rule.waitUntil(5_000) { count("wird geladen") > 0 }
        rule.onNodeWithText("Bild · IMG-2041.jpg – wird geladen …").assertIsDisplayed()
        assertEquals(0, count("Laden"))
        coVerify(exactly = 1) { repository.fetchBridged(any()) }
    }

    @Test
    fun `Laden refused - the button comes back, nothing to fetch any more is said`() {
        stubRepository()
        profile.value = profile("bridge_chats", "bridge_media", "bridge_history")
        conversations.value = listOf(conv())
        messages.value = listOf(msg(2, "text", null, ph("image", "IMG-2041.jpg", "on_request")))
        coEvery { repository.fetchBridged(any()) } returns ChatResult.Failed("invalid", 400)
        chatScreen(true, conv())
        rule.waitUntil(5_000) { count("älterer Verlauf") > 0 }
        rule.onNodeWithText("Laden").performClick()
        rule.waitForIdle()
        rule.waitUntil(3_000) { org.robolectric.shadows.ShadowToast.getTextOfLatestToast() != null }
        assertEquals("Diese Datei lässt sich nicht mehr laden.", org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
        // tappable again
        rule.onNodeWithText("Laden").performClick()
        rule.waitForIdle()
        coVerify(exactly = 2) { repository.fetchBridged(any()) }
    }
}
