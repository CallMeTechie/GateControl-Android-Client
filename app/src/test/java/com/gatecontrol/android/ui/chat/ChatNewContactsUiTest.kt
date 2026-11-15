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
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.chat.ChatBridgesRepository
import com.gatecontrol.android.chat.ChatFeatures
import com.gatecontrol.android.chat.ChatLimits
import com.gatecontrol.android.chat.ChatNewChatContacts
import com.gatecontrol.android.chat.ChatNewContacts
import com.gatecontrol.android.chat.ChatPerson
import com.gatecontrol.android.chat.ChatPrefs
import com.gatecontrol.android.chat.ChatProfile
import com.gatecontrol.android.chat.ChatRepository
import com.gatecontrol.android.chat.ChatResult
import com.gatecontrol.android.ui.theme.GateControlTheme
import com.google.gson.JsonParser
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Messenger 1.18, "Neuer Chat" with contacts, drawn in German, light and dark, with the real screen and view model:
 * every state of the draft (all there, search, loading, bridge away, signed out, a child without new chats, without
 * Mail, nothing found) and of a tapped address book contact (which number, checking, not at WhatsApp, too many checks).
 * With `BRIDGED_SHOTS=<dir>` the drawn pages are saved there (for a look, not part of CI).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "de-w411dp-h891dp")
class ChatNewContactsUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val repository: ChatRepository = mockk(relaxed = true)
    private val bridges: ChatBridgesRepository = mockk(relaxed = true)
    private val viewModels = mutableListOf<ViewModel>()
    private val ref = "k" + "A".repeat(48)

    private fun profile(vararg tokens: String) = ChatProfile(
        user = ChatPerson(7, "Anna", null, "AN", null),
        prefs = ChatPrefs(),
        licensed = true,
        features = ChatFeatures(tokens = tokens.toSet()),
        limits = ChatLimits(),
        serverTime = null,
    )

    private val gc = """[{"userId":1,"name":"Anna","initials":"AN","colorHex":"#F97316"},{"userId":2,"name":"Jona","initials":"JO","colorHex":"#1A56C4"},
        {"userId":3,"name":"Marc","initials":"MA","colorHex":"#84CC16","conversationId":12}]"""
    private val waItems = """[{"ref":"${ref}1","name":"Kim Muster","conversationId":31},{"ref":"${ref}2","name":"Lena Berger","alsoInAddressBook":true},
        {"ref":"${ref}3","name":"Tom Richter"},{"ref":"${ref}4","name":"Yusuf Demir"}]"""
    private val bookItems = """[{"contactId":5,"name":"Paul Hoffmann","phones":[{"key":"mobile","kind":"mobile","number":"+49 160 98765432"}]},
        {"contactId":6,"name":"Sabine Wolf","phones":[{"key":"home","kind":"home","number":"+49 30 12345678","landline":true},{"key":"mobile","kind":"mobile","number":"+49 151 23456780"},{"key":"work","kind":"work","number":"+49 170 2345678"}]},
        {"contactId":7,"name":"Jonas Berger","conversationId":40,"phones":[{"key":"mobile","kind":"mobile","number":"+49 152 34567890"}]},
        {"contactId":8,"name":"Zora Alt","phones":[{"key":"mobile","kind":"mobile","number":"+49 171 1111111"}]}]"""

    private fun list(
        gatecontrol: String = gc,
        wa: String? = """{"state":"ok","total":86,"items":$waItems}""",
        book: String? = """{"state":"ok","total":41,"items":$bookItems}""",
        number: String = "null",
        entry: Boolean = true,
    ): ChatNewChatContacts = ChatNewContacts.list(
        JsonParser.parseString("""{"network":"whatsapp","gatecontrol":$gatecontrol,"whatsapp":$wa,"addressBook":$book,"number":$number,"numberEntry":$entry,"limit":{"perHour":10,"left":0,"retryAfterS":0}}""").asJsonObject,
    )!!

    @Before
    fun setUp() {
        every { repository.profile } returns MutableStateFlow(profile("bridge_chats", "bridge_new_chat", "bridge_contacts"))
        coEvery { repository.people() } returns ChatResult.Ok(listOf(ChatPerson(1, "Anna", null, "AN", "#F97316"), ChatPerson(2, "Jona", null, "JO", "#1A56C4"), ChatPerson(3, "Marc", null, "MA", "#84CC16")))
    }

    @After
    fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
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

    /** "Neuer Chat" with [answer] for every search; [then] acts on the view model before the picture. */
    private fun page(
        name: String,
        answer: suspend () -> ChatResult<ChatNewChatContacts>,
        wait: String,
        query: String? = null,
        then: (NewChatViewModel) -> Unit = {},
        check: () -> Unit = {},
    ): NewChatViewModel? {
        coEvery { repository.newChatContacts(any(), any()) } coAnswers { answer() }
        var last: NewChatViewModel? = null
        for (dark in listOf(false, true)) {
            val vm = NewChatViewModel(repository, bridges).also { viewModels += it }
            last = vm
            draw(dark) { NewChatScreen(group = false, onBack = {}, onNewGroup = {}, onOpened = {}, viewModel = vm) }
            rule.waitUntil(5_000) { count("Neue Gruppe") > 0 }
            query?.let { vm.setQuery(it) }
            rule.waitUntil(5_000) { count(wait) > 0 }
            then(vm)
            rule.waitForIdle()
            shot(name, dark)
            check()
        }
        return last
    }

    @Test
    fun `the list - every state of the draft`() {
        page("50-kontakte-alles-da", { ChatResult.Ok(list()) }, "Kim Muster") {
            rule.onNodeWithText("GATECONTROL · 3").assertIsDisplayed()
            rule.onNodeWithText("WHATSAPP · 86").assertIsDisplayed()
            rule.onNodeWithText("auch in deinem Adressbuch").assertIsDisplayed()
            rule.onNodeWithText("Alle 4 WhatsApp-Kontakte").assertIsDisplayed()
            assertEquals("three rows before Alle …", 0, count("Yusuf Demir"))
            assertEquals(1, count("AUS DEINEM ADRESSBUCH · 41"))
            assertEquals(1, count("Nicht dabei? Nummer eingeben"))
            assertEquals(1, count("Deine WhatsApp-Kontakte bleiben auf deinem Server."))
            assertEquals("no row of 1.16.0", 0, count("WhatsApp-Chat mit Nummer"))
        }
        page("51-kontakte-suche", { ChatResult.Ok(list(gatecontrol = "[]", wa = """{"state":"ok","total":1,"items":[{"ref":"${ref}2","name":"Lena Berger","alsoInAddressBook":true}]}""", book = """{"state":"ok","total":1,"items":[{"contactId":7,"name":"Jonas Berger","conversationId":40,"phones":[{"key":"mobile","kind":"mobile","number":"+49 152 34567890"}]}]}""")) }, "Jonas Berger", query = "ber") {
            assertEquals("Neue Gruppe goes while searching", 0, count("Neue Gruppe"))
            assertEquals(0, count("Alle "))
            rule.onNodeWithText("Mobil · +49 152 34567890").assertIsDisplayed()
        }
        val never = CompletableDeferred<ChatResult<ChatNewChatContacts>>()
        page("52-kontakte-laedt", { never.await() }, "Marc") {
            rule.onNodeWithText("WHATSAPP").assertIsDisplayed()
            assertEquals(0, count("Kim Muster"))
        }
        page("53-kontakte-bruecke-weg", { ChatResult.Ok(list(wa = """{"state":"unreachable","items":[],"total":0}""", book = """{"state":"unreachable","total":41,"items":$bookItems}""")) }, "gerade nicht verfügbar") {
            rule.onNodeWithText("WhatsApp-Kontakte gerade nicht verfügbar").assertIsDisplayed()
            rule.onNodeWithText("Erneut versuchen").assertIsDisplayed()
            rule.onNodeWithText("Antippen geht wieder, sobald WhatsApp erreichbar ist.").assertIsDisplayed()
        }
        page("54-kontakte-abgemeldet", { ChatResult.Ok(list(wa = """{"state":"logged_out","items":[],"total":0}""", book = """{"state":"logged_out","total":41,"items":$bookItems}""")) }, "ist abgemeldet") {
            rule.onNodeWithText("Neu verknüpfen").assertIsDisplayed()
            rule.onNodeWithText("Antippen geht wieder, sobald WhatsApp neu verknüpft ist.").assertIsDisplayed()
        }
        page("55-kontakte-kind", { ChatResult.Ok(list(wa = null, book = null, entry = false)) }, "Jona") {
            assertEquals(0, count("WHATSAPP"))
            assertEquals(0, count("Nummer eingeben"))
            assertEquals(0, count("Deine WhatsApp-Kontakte"))
        }
        page("56-kontakte-ohne-mail", { ChatResult.Ok(list(book = null)) }, "Kim Muster") {
            assertEquals(0, count("ADRESSBUCH"))
            assertEquals(1, count("Nicht dabei? Nummer eingeben"))
        }
        page("57-kontakte-kein-treffer", { ChatResult.Ok(list(gatecontrol = "[]", wa = """{"state":"ok","items":[],"total":0}""", book = null)) }, "Niemand mit", query = "Zora") {
            rule.onNodeWithText("Niemand mit „Zora“ gefunden").assertIsDisplayed()
            rule.onNodeWithText("Gesucht in GateControl, in deinen WhatsApp-Kontakten und in deinem Adressbuch.").assertIsDisplayed()
            rule.onNodeWithText("Nummer eingeben").assertIsDisplayed()
            assertEquals(0, count("Nicht dabei?"))
        }
        page("58-kontakte-nummer", { ChatResult.Ok(list(gatecontrol = "[]", wa = """{"state":"ok","items":[],"total":0}""", book = null, number = """{"e164":"+4917155501230","show":"+49 171 55501230","conversationId":null}""")) }, "bei WhatsApp prüfen", query = "0171 55501230") {
            rule.onNodeWithText("+49 171 55501230 bei WhatsApp prüfen").assertIsDisplayed()
        }
    }

    @Test
    fun `an address book contact - which number, checking, not at WhatsApp, too many checks`() {
        // the list a little scrolled, as the draft shows it: without the accounts on top
        val answer: suspend () -> ChatResult<ChatNewChatContacts> = { ChatResult.Ok(list(gatecontrol = "[]")) }
        page("60-adressbuch-welche-nummer", answer, "Sabine Wolf", then = { vm -> vm.tapBook(vm.state.value.contacts!!.data!!.addressBook!!.items[1]) }) {
            rule.onNodeWithText("WELCHE NUMMER?").assertIsDisplayed()
            rule.onNodeWithText("Festnetz").assertIsDisplayed()
            rule.onNodeWithText("Geschäftlich").assertIsDisplayed()
        }
        val never = CompletableDeferred<ChatResult<com.gatecontrol.android.chat.ChatNumberChat>>()
        coEvery { repository.openBookChat(any(), 5, "mobile") } coAnswers { never.await() }
        page("61-adressbuch-wird-geprueft", answer, "Paul Hoffmann", then = { vm -> vm.tapBook(vm.state.value.contacts!!.data!!.addressBook!!.items[0]) }) {
            rule.onNodeWithText("Prüft bei WhatsApp …").assertIsDisplayed()
        }
        coEvery { repository.openBookChat(any(), 5, "mobile") } returns ChatResult.Failed("not_found", 404, "not_on_network")
        page("62-adressbuch-nicht-bei-whatsapp", answer, "Paul Hoffmann", then = { vm -> vm.tapBook(vm.state.value.contacts!!.data!!.addressBook!!.items[0]) }) {
            rule.waitUntil(3_000) { count("nicht bei WhatsApp") > 0 }
            rule.onNodeWithText("Unter +49 160 98765432 gibt es kein WhatsApp. Hat Paul eine andere Nummer, gib sie unten ein.").assertIsDisplayed()
        }
        coEvery { repository.openBookChat(any(), 5, "mobile") } returns ChatResult.Failed("rate_limited", 429, retryAfterS = 600)
        page("63-adressbuch-zu-viele", answer, "Paul Hoffmann", then = { vm -> vm.tapBook(vm.state.value.contacts!!.data!!.addressBook!!.items[0]) }) {
            rule.waitUntil(3_000) { count("ab ") > 0 }
            rule.onNodeWithText("Du hast in der letzten Stunde 10 Nummern geprüft. WhatsApp-Kontakte und bestehende Chats gehen weiter.").assertIsDisplayed()
        }
    }

    @Test
    fun `Nicht dabei - the page Nummer eingeben`() {
        coEvery { repository.newChatContacts(any(), any()) } returns ChatResult.Ok(list())
        val vm = NewChatViewModel(repository, bridges).also { viewModels += it }
        var page: String? = null
        draw(false) { NewChatScreen(group = false, onBack = {}, onNewGroup = {}, onOpened = {}, onNewNumber = { page = it }, viewModel = vm) }
        rule.waitUntil(5_000) { count("Nicht dabei?") > 0 }
        rule.onNodeWithTag("ncc-enter-number").performClick()
        rule.waitUntil(3_000) { page != null }
        assertEquals("whatsapp", page)
    }
}
