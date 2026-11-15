package com.gatecontrol.android.ui.profile

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.profile.ProfileDetails
import com.gatecontrol.android.profile.ProfileDetailsJson
import com.gatecontrol.android.ui.chat.ChatDrawer
import com.gatecontrol.android.ui.chat.ChatDrawerActions
import com.gatecontrol.android.ui.chat.ChatDrawerState
import com.gatecontrol.android.ui.components.GcDrawerMe
import com.gatecontrol.android.ui.components.GcDrawerSheet
import com.gatecontrol.android.ui.components.LocalGcDrawerMe
import com.gatecontrol.android.ui.theme.GateControlTheme
import com.google.gson.JsonParser
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
 * The page „Profil“ with the fields (server 1.280), drawn in German, light and dark: the groups with who sees each
 * row, two numbers (confirmed via WhatsApp / not confirmed, „Erkennen“), the recognition's two switches and „Meine
 * Daten“; the sheet of a number; the side menu of Chats whose person opens the profile. Locked fields from the
 * directory, the numbers switched off on the server. With `PROFILE_SHOTS=<dir>` the drawn pages are saved there (not
 * part of CI). Only made-up numbers (zeros).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "de-w411dp-h2200dp")
class ProfileFieldsUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun details(extra: String = ""): ProfileDetails = ProfileDetailsJson.parse(
        JsonParser.parseString(
            """{"userId":7,"username":"jenny","displayName":"Jenny Backes","firstName":"Jenny","lastName":"Backes","about":"Meist ab 17 Uhr erreichbar",
            "birthday":{"date":"1986-03-14","yearHidden":true},"email":"jenny@familie-backes.example",
            "emails":[{"id":3,"address":"jenny.privat@example.com","state":"unconfirmed","codePending":true}],
            "language":"de","timezone":null,"serverTimezone":"Europe/Berlin","family":true,
            "visibility":{"firstName":"all","lastName":"all","about":"all","birthday":"family","email":"self","language":"self","timezone":"self"},
            "phones":[{"id":11,"kind":"mobile","number":"+4915100000000","visibility":"none","useForRecognition":true,"state":"whatsapp"},
                      {"id":12,"kind":"work","number":"+49300000000","visibility":"family","useForRecognition":false,"state":"unconfirmed"}],
            "recognition":{"byWhatsapp":false,"byPhone":true,"whatsappAnswered":false,"phoneAnswered":true,"whatsappLinked":true,"phonesForRecognition":1}
            $extra}""",
        ).asJsonObject,
    )!!

    private val content = mutableStateOf<@Composable () -> Unit>({})

    private fun show(dark: Boolean, body: @Composable () -> Unit) {
        content.value = body
        rule.setContent {
            GateControlTheme(darkTheme = dark) {
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize().testTag("shot")) { content.value() }
            }
        }
        rule.waitForIdle()
    }

    private fun shot(name: String, dark: Boolean) {
        val dir = System.getenv("PROFILE_SHOTS") ?: return
        val bmp = rule.onNodeWithTag("shot").captureToImage().asAndroidBitmap()
        File(dir).mkdirs()
        File(dir, "$name-${if (dark) "dunkel" else "hell"}.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun count(s: String) = rule.onAllNodesWithText(s, substring = true).fetchSemanticsNodes().size

    private val noActions = ProfileFieldActions({}, {}, {}, {}, { _, _ -> })

    @Composable
    private fun Page(d: ProfileDetails, actions: ProfileFieldActions = noActions) {
        Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ProfileFieldGroups(d, actions, saving = false, modifier = Modifier.padding(horizontal = 16.dp))
            ProfileDataGroup(exporting = false, onExport = {}, modifier = Modifier.padding(horizontal = 16.dp))
        }
    }

    private fun groups(dark: Boolean) {
        val opened = mutableListOf<String>()
        show(dark) {
            Page(details(), ProfileFieldActions({ opened += it.name }, { opened += "add" }, { opened += "phone:$it" }, { opened += "email:${it.id}" }, { _, _ -> }))
        }
        shot("profil-gruppen", dark)
        listOf("Über dich", "Telefonnummern", "E-Mail", "Geburtstag", "Sprache und Zeitzone", "Erkennung", "Meine Daten").forEach {
            assertTrue(it, count(it.uppercase()) >= 1)
        }
        assertEquals(1, count("+49 151 00000000"))
        assertEquals(1, count("Bestätigt über dein WhatsApp"))
        assertTrue(count("Nicht bestätigt") >= 2) // the work number and the further address
        assertEquals(1, rule.onAllNodesWithText("Erkennen").fetchSemanticsNodes().size)
        assertEquals(1, count("14. März · Jahr verborgen"))
        assertEquals(1, count("Deutsch · Wie der Server (Europe/Berlin)"))
        assertEquals(1, count("An meinem verknüpften WhatsApp"))
        assertEquals(1, count("1 Nummer zum Erkennen"))
        assertEquals(1, count("Meine Daten herunterladen"))
        // who sees each row, read out in full
        assertEquals(1, rule.onAllNodesWithContentDescription("Sichtbar für: Niemand").fetchSemanticsNodes().size)
        assertTrue(rule.onAllNodesWithContentDescription("Sichtbar für: Alle auf diesem Server").fetchSemanticsNodes().size >= 2)
        rule.onNodeWithText("+49 151 00000000", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Mobil").performClick()
        rule.onNodeWithText("Nummer hinzufügen").performClick()
        rule.onNodeWithText("jenny.privat@example.com").performClick()
        assertEquals(listOf("phone:11", "add", "email:3"), opened)
    }

    @Test fun `the groups - light`() = groups(dark = false)

    @Test fun `the groups - dark`() = groups(dark = true)

    private fun sheet(dark: Boolean) {
        val d = details()
        show(dark) {
            Box(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                PhoneSheetContent(
                    PhoneDraft(11, "mobile", "+49 151 00000000", "none", true, "whatsapp"),
                    d, saving = false, refusal = null, onChange = {}, onSave = {}, onRemove = {},
                )
            }
        }
        shot("profil-nummer", dark)
        listOf("Telefonnummer", "Art", "Mobil", "Geschäftlich", "Privat", "Sonstige", "Wer sieht sie?", "Niemand", "Familie", "Alle auf diesem Server",
            "Zum Erkennen nutzen", "Speichern", "Nummer entfernen", "Bestätigt über dein WhatsApp").forEach { assertTrue(it, count(it) >= 1) }
        assertEquals(1, count("Sieht niemand, auch kein Admin. GateControl nutzt sie nur zum Erkennen."))
    }

    @Test fun `the sheet of a number - light`() = sheet(dark = false)

    @Test fun `the sheet of a number - dark`() = sheet(dark = true)

    @Test
    fun `a new number - no Familie without a family, no remove`() {
        val d = ProfileDetailsJson.parse(JsonParser.parseString("""{"userId":7,"username":"j","family":false}""").asJsonObject)!!
        show(false) { PhoneSheetContent(PhoneDraft(), d, saving = false, refusal = null, onChange = {}, onSave = {}, onRemove = {}) }
        assertEquals(0, count("Familie"))
        assertEquals(0, count("Nummer entfernen"))
        assertEquals(1, count("Mit Ländervorwahl, zum Beispiel +49 151 …"))
    }

    @Test
    fun `fields from the directory are locked, numbers off on the server are not offered`() {
        show(false) {
            Page(details(""","locked":{"displayName":true,"email":true,"directory":"AD"},"features":{"phones":false,"emailConfirm":true,"recognition":true}"""))
        }
        assertEquals(2, count("Aus dem Verzeichnis, änderbar nur dort"))
        assertEquals(0, count("TELEFONNUMMERN"))
        assertEquals(0, count("An meinen Telefonnummern"))
        assertEquals(1, count("An meinem verknüpften WhatsApp"))
    }

    private fun drawer(dark: Boolean) {
        val events = mutableListOf<String>()
        show(dark) {
            CompositionLocalProvider(LocalGcDrawerMe provides GcDrawerMe("Jenny Backes", "JB", "#DB2777", null) { events += "profile" }) {
                ModalNavigationDrawer(
                    drawerState = rememberDrawerState(DrawerValue.Open),
                    drawerContent = {
                        GcDrawerSheet(onClose = { events += "close" }) {
                            ChatDrawer(
                                ChatDrawerState(
                                    name = "Jenny Backes", status = "Meist ab 17 Uhr erreichbar", channels = null, archived = 2, status14 = true,
                                    lists = null, listId = null, showArchived = false, linked = null,
                                ),
                                ChatDrawerActions({}, {}, {}, {}, {}, {}),
                            )
                        }
                    },
                ) { Box(Modifier.fillMaxSize()) }
            }
        }
        shot("seitenmenue-profil", dark)
        rule.onNodeWithContentDescription("Jenny Backes, Profil öffnen").performClick()
        // the menu closes first, then „Profil“ opens
        assertEquals(listOf("close", "profile"), events)
    }

    @Test fun `the person in the side menu opens the profile - light`() = drawer(dark = false)

    @Test fun `the person in the side menu opens the profile - dark`() = drawer(dark = true)

    @Test
    fun `without the own profile the menu's head is as before`() {
        show(false) {
            ModalNavigationDrawer(
                drawerState = rememberDrawerState(DrawerValue.Open),
                drawerContent = {
                    GcDrawerSheet {
                        ChatDrawer(
                            ChatDrawerState("Jenny Backes", null, null, 0, false, null, null, false, null),
                            ChatDrawerActions({}, {}, {}, {}, {}, {}),
                        )
                    }
                },
            ) { Box(Modifier.fillMaxSize()) }
        }
        assertEquals(1, count("Jenny Backes"))
        assertEquals(0, rule.onAllNodesWithContentDescription("Jenny Backes, Profil öffnen").fetchSemanticsNodes().size)
    }
}
