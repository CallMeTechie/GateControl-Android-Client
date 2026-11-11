package com.gatecontrol.android.ui.familie

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.familie.FamilieDest
import com.gatecontrol.android.familie.internet.InternetBy
import com.gatecontrol.android.familie.internet.InternetChild
import com.gatecontrol.android.familie.internet.InternetLogic
import com.gatecontrol.android.familie.internet.InternetPayload
import com.gatecontrol.android.familie.internet.InternetPresence
import com.gatecontrol.android.familie.internet.InternetSchedule
import com.gatecontrol.android.familie.internet.InternetSnapshot
import com.gatecontrol.android.familie.internet.PauseRequest
import com.gatecontrol.android.familie.internet.WifiDevice
import com.gatecontrol.android.familie.internet.WifiPresence
import com.gatecontrol.android.familie.presence.PresenceCheck
import com.gatecontrol.android.familie.presence.PresenceMember
import com.gatecontrol.android.familie.presence.PresenceSense
import com.gatecontrol.android.familie.presence.PresenceState
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcRowDivider
import com.gatecontrol.android.ui.components.GcSwitchRow
import com.gatecontrol.android.ui.profile.PresenceNowRow
import com.gatecontrol.android.ui.profile.PresenceShareCard
import com.gatecontrol.android.ui.profile.WifiDevices
import com.gatecontrol.android.ui.theme.GateControlTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant
import java.time.ZoneId

/**
 * „Internet zu Hause“ as the approved mockup shows it (Wednesday, 11 Nov, 17:52 in Berlin): the cards of the four
 * states with their buttons, the notice of a rule, the two dialogs, the card on the start screen for parents and for a
 * child, and the Wi-Fi detection in the profile. With `INTERNET_SHOTS=<dir>` the drawn pages are saved there (for a
 * look, not part of CI).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "de-w411dp-h1400dp")
class InternetUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val berlin = ZoneId.of("Europe/Berlin")
    private val now = Instant.parse("2026-11-11T16:52:10Z").toEpochMilli()
    private val evening = InternetSchedule("2026-11-11T20:00:00Z", "2026-11-12T05:00:00Z", all = false, categories = InternetSchedule.CATEGORIES, active = false)

    private val lea = InternetChild(
        4, "Lea", color = "#EAB308", presence = InternetPresence(PresenceState.HOME, "2026-11-11T15:40:00Z", "wifi"),
        nextSchedule = evening, profile = "Kinder", devices = 2, filtered = true,
    )
    private val tim = InternetChild(
        5, "Tim", color = "#F97316", presence = InternetPresence(PresenceState.HOME, "2026-11-11T16:05:00Z", "app"),
        paused = "2026-11-11T17:30:00Z", pausedBy = InternetBy(2, "Anna", "2026-11-11T16:30:00Z", ruleId = 12, ruleTitle = "Abendessen"),
        devices = 2, filtered = true,
    )
    private val mia = InternetChild(
        6, "Mia", color = "#EC4899", presence = InternetPresence(PresenceState.AWAY, "2026-11-11T14:10:00Z", "app"),
        extended = "2026-11-11T20:30:00Z", extendedBy = InternetBy(3, "Marc", "2026-11-11T15:50:00Z"),
        nextSchedule = evening, devices = 1, filtered = true,
    )
    private val ben = InternetChild(7, "Ben", color = "#A855F7", presence = InternetPresence(PresenceState.NOT_SHARED, null, null))
    private val broken = InternetChild(8, "Jo", error = "unavailable")

    private val content = mutableStateOf<@Composable () -> Unit>({})

    private fun page(dark: Boolean = false, content: @Composable () -> Unit) {
        this.content.value = content
        rule.setContent {
            GateControlTheme(darkTheme = dark) {
                Column(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState()).padding(16.dp).testTag("shot"),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) { this@InternetUiTest.content.value() }
            }
        }
        rule.waitForIdle()
    }

    private fun shot(name: String, dialog: Boolean = false) {
        val dir = System.getenv("INTERNET_SHOTS") ?: return
        val node = if (dialog) rule.onNode(isDialog()) else rule.onNodeWithTag("shot")
        val bmp = runCatching { node.captureToImage().asAndroidBitmap() }.getOrNull() ?: return
        File(dir).mkdirs()
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Composable
    private fun Card(child: InternetChild, manage: Boolean = true, onButton: (InternetLogic.Button) -> Unit = {}, onOpenRule: (Int) -> Unit = {}) =
        InternetChildCard(child, InternetLogic.state(child, now), manage, berlin, enabled = true, onButton = onButton, onOpenRule = onOpenRule)

    private fun count(text: String) = rule.onAllNodesWithText(text).fetchSemanticsNodes().size

    @Test
    fun `the parents' page - four states, their buttons and the notice of the rule`() {
        val pressed = mutableListOf<Pair<Int, InternetLogic.Button>>()
        var openedRule: Int? = null
        page {
            listOf(lea, tim, mia, ben, broken).forEach { c -> Card(c, onButton = { pressed += c.userId to it }, onOpenRule = { openedRule = it }) }
            InternetUnavailable()
        }
        shot("page-parents")
        // Lea: on.
        rule.onNodeWithText("Internet an").assertIsDisplayed()
        rule.onNodeWithText("zu Hause · WLAN").assertIsDisplayed()
        rule.onNodeWithText("Nächste Sperrzeit ab 21:00: Soziale Netzwerke, Spiele und Videos · 2 Geräte").assertIsDisplayed()
        // Tim: paused by a rule.
        rule.onNodeWithText("pausiert bis 18:30").assertIsDisplayed()
        rule.onNodeWithText("zu Hause · App").assertIsDisplayed()
        rule.onNodeWithText("Internet auf 2 Geräten aus. GateControl und erlaubte Seiten gehen weiter.").assertIsDisplayed()
        rule.onNodeWithText("Pausiert von der Regel „Abendessen“ · Anna · 17:30").assertIsDisplayed()
        // Mia: longer today, away.
        rule.onNodeWithText("heute länger bis 21:30").assertIsDisplayed()
        rule.onNodeWithText("unterwegs").assertIsDisplayed()
        rule.onNodeWithText("Sperrzeit ab 21:00 ausgesetzt · von Marc um 16:50 · 1 Gerät · Die Extra-Zeit wirkt, sobald Mia zu Hause ist.").assertIsDisplayed()
        // Ben: not filtered, shares nothing — no presence, no buttons.
        rule.onNodeWithText("nicht gefiltert").assertIsDisplayed()
        rule.onNodeWithText("Keine Geräte im Heimnetz zugeordnet. Ein Admin ordnet sie im Netzwerk-Wächter zu. Pausieren hätte keine Wirkung.").assertIsDisplayed()
        rule.onNodeWithText("Zustand gerade nicht lesbar. Zum Aktualisieren nach unten ziehen.").assertIsDisplayed()
        rule.onNodeWithText("Auf diesem Server gibt es kein „Internet zu Hause“.").performScrollTo().assertIsDisplayed()

        // Buttons: Lea "Pausieren" + "Heute länger"; Tim "Fortsetzen" + "+30 Min."; Mia "Pausieren" + "+30 Min." + "Extra-Zeit beenden".
        assertEquals(2, count("Pausieren"))
        assertEquals(1, count("Heute länger"))
        assertEquals(1, count("Fortsetzen"))
        assertEquals(2, count("+30 Min."))
        assertEquals(1, count("Extra-Zeit beenden"))
        rule.onNodeWithText("Fortsetzen").performScrollTo().performClick()
        rule.onNodeWithText("Extra-Zeit beenden").performScrollTo().performClick()
        rule.onNodeWithText("Heute länger").performScrollTo().performClick()
        assertEquals(listOf(5 to InternetLogic.Button.RESUME, 6 to InternetLogic.Button.END_EXTRA, 4 to InternetLogic.Button.EXTEND), pressed)
        rule.onNodeWithText("Regel öffnen").performScrollTo().performClick()
        assertEquals(12, openedRule)
    }

    @Test
    fun `a rule without a title, a pause by hand and the dark theme`() {
        page(dark = true) {
            Card(tim.copy(pausedBy = tim.pausedBy!!.copy(ruleTitle = "")))
            Card(tim.copy(userId = 9, pausedBy = InternetBy(3, "Marc", "2026-11-11T16:30:00Z")))
            Card(lea.copy(nextSchedule = evening.copy(all = true, categories = emptyList())))
            Card(lea.copy(userId = 10, nextSchedule = null, devices = 1))
            Card(ben.copy(devices = 3))
        }
        shot("page-variants-dark")
        rule.onNodeWithText("Pausiert von einer Regel ohne Namen · Anna · 17:30").assertIsDisplayed()
        rule.onNodeWithText("Internet auf 2 Geräten aus. GateControl und erlaubte Seiten gehen weiter. Von Marc um 17:30.").assertIsDisplayed()
        rule.onAllNodesWithText("Regel öffnen").assertCountEquals(1)
        rule.onNodeWithText("Nächste Sperrzeit ab 21:00: gesamtes Internet · 2 Geräte").assertIsDisplayed()
        rule.onNodeWithText("Keine Sperrzeiten · 1 Gerät").assertIsDisplayed()
        rule.onNodeWithText("Kein Gateway mit DNS-Filter kennt diese Geräte. Pausieren hätte keine Wirkung.").assertIsDisplayed()
    }

    @Test
    fun `a child sees its own card without buttons and without the link to the rule`() {
        page {
            Card(tim, manage = false)
            Card(lea, manage = false)
        }
        shot("page-child")
        rule.onNodeWithText("Das Internet zu Hause ist auf deinen 2 Geräten pausiert. GateControl und erlaubte Seiten gehen weiter.").assertIsDisplayed()
        rule.onNodeWithText("Pausiert von der Regel „Abendessen“ · Anna · 17:30").assertIsDisplayed()
        listOf("Fortsetzen", "+30 Min.", "Pausieren", "Heute länger", "Regel öffnen").forEach { assertEquals(it, 0, count(it)) }
    }

    @Test
    fun `the card on the start screen for parents and for a child`() {
        val opened = mutableListOf<FamilieDest>()
        val parents = InternetSnapshot(InternetPayload(true, true, listOf(lea, tim, mia, ben), "2026-11-11T16:52:10Z", "Europe/Berlin"), System.currentTimeMillis())
        page { InternetHomeCard(parents, selected = false, onOpen = { opened += it }) }
        shot("home-parents")
        rule.onNodeWithText("Internet zu Hause").assertIsDisplayed()
        rule.onNodeWithText("Internet an").assertIsDisplayed()
        rule.onNodeWithText("Pause bis 18:30").assertIsDisplayed()
        rule.onNodeWithText("länger bis 21:30").assertIsDisplayed()
        rule.onNodeWithText("nicht gefiltert").assertIsDisplayed()
        rule.onNodeWithText("keine Geräte").assertIsDisplayed()
        rule.onNodeWithText("Tim").performClick()
        rule.onNodeWithText("Öffnen").performClick()
        assertEquals(listOf<FamilieDest>(FamilieDest.Internet, FamilieDest.Internet), opened)

        val child = InternetSnapshot(InternetPayload(true, false, listOf(tim), "2026-11-11T16:52:10Z", "Europe/Berlin"), System.currentTimeMillis())
        content.value = { InternetHomeCard(child, selected = false, onOpen = {}) }
        rule.waitForIdle()
        shot("home-child")
        rule.onNodeWithText("Pause bis 18:30").assertIsDisplayed()
        rule.onNodeWithText("Regel „Abendessen“ · noch 38 Min.").assertIsDisplayed()
        rule.onNodeWithText("pausiert").assertIsDisplayed()
    }

    @Test
    fun `pause - 30 minutes by default, or until a time of day`() {
        var asked: PauseRequest? = null
        page { InternetPauseDialog(lea, now, berlin, onConfirm = { asked = it }, onDismiss = {}) }
        rule.onNodeWithText("Pause für Lea").assertIsDisplayed()
        listOf("30 Min.", "1 Std.", "2 Std.", "bis Uhrzeit").forEach { rule.onNodeWithText(it).assertIsDisplayed() }
        rule.onNodeWithText("Bis 18:22 Uhr. Lea bekommt eine Mitteilung in GateControl.").assertIsDisplayed()
        rule.onNodeWithText("bis Uhrzeit").performClick()
        rule.onNodeWithText("Pause bis").assertIsDisplayed()
        rule.onNodeWithText("19:00").assertIsDisplayed()
        rule.onNodeWithText("Heute bis 19:00 Uhr. Ist die Uhrzeit schon vorbei, gilt sie für morgen. Lea bekommt eine Mitteilung in GateControl.").assertIsDisplayed()
        shot("dialog-pause", dialog = true)
        rule.onNodeWithText("Pausieren").performClick()
        assertEquals(PauseRequest.Until("19:00"), asked)

        rule.onNodeWithText("1 Std.").performClick()
        rule.onNodeWithText("Pausieren").performClick()
        assertEquals(PauseRequest.Minutes(60), asked)
    }

    @Test
    fun `longer today - from the start of the blocked time or from now`() {
        var asked: Pair<Int, Boolean>? = null
        page { InternetExtendDialog(lea, now, berlin, onConfirm = { m, fromNow -> asked = m to fromNow }, onDismiss = {}) }
        rule.onNodeWithText("Heute länger für Lea").assertIsDisplayed()
        listOf("15 Min.", "30 Min.", "1 Std.", "Beginn der Sperrzeit (21:00)", "jetzt").forEach { rule.onNodeWithText(it).assertIsDisplayed() }
        rule.onNodeWithText("Die Sperrzeit beginnt heute erst um 21:30. Lea bekommt eine Mitteilung in GateControl.").assertIsDisplayed()
        shot("dialog-extend", dialog = true)
        rule.onNodeWithText("Erlauben").performClick()
        assertEquals(30 to false, asked)
        rule.onNodeWithText("jetzt").performClick()
        rule.onNodeWithText("Extra-Zeit bis 18:22 Uhr. Lea bekommt eine Mitteilung in GateControl.").assertIsDisplayed()
        rule.onNodeWithText("15 Min.").performClick()
        rule.onNodeWithText("Erlauben").performClick()
        assertEquals(15 to true, asked)

        // No blocked time left today: the choice is gone, the extra time starts now.
        asked = null
        content.value = { InternetExtendDialog(lea.copy(userId = 11, nextSchedule = null), now, berlin, onConfirm = { m, fromNow -> asked = m to fromNow }, onDismiss = {}) }
        rule.waitForIdle()
        assertEquals(0, count("Zählt ab".uppercase()))
        assertEquals(0, count("jetzt"))
        rule.onNodeWithText("Erlauben").performClick()
        assertEquals(30 to false, asked)
        assertNull(InternetLogic.extendFromSchedule(lea.copy(nextSchedule = null), now, berlin))
    }

    @Test
    fun `the profile - wifi detection, the source and my own devices`() {
        val toggled = mutableListOf<Pair<Int, Boolean>>()
        val wifi = WifiPresence(
            available = true, consent = true,
            devices = listOf(
                WifiDevice(41, "Annas Handy", "2026-11-11T16:50:00Z", counts = true, gateway = "Pi Keller"),
                WifiDevice(42, "Annas Laptop", null, counts = false, gateway = "Pi Keller"),
            ),
        )
        page {
            PresenceShareCard(true, PresenceCheck.OK, PresenceSense.HOME, saving = false, shareError = null, onShare = {}, onClearError = {}) {
                GcRowDivider()
                GcSwitchRow("WLAN-Erkennung verwenden", null, checked = true, onCheckedChange = {}, icon = GcIcons.Wifi)
                PresenceNowRow(PresenceMember(2, PresenceState.HOME, null, shares = true, source = "wifi"))
            }
            WifiDevices(wifi, saving = false, error = null, onCounts = { id, on -> toggled += id to on })
        }
        shot("profile-wifi-on")
        rule.onNodeWithText("Gerade").assertIsDisplayed()
        rule.onNodeWithText("zu Hause").assertIsDisplayed()
        rule.onNodeWithText("Quelle: WLAN").assertIsDisplayed()
        rule.onNodeWithText("MEINE GERÄTE IM HEIM-WLAN").assertIsDisplayed()
        rule.onNodeWithText("noch nicht gesehen · Pi Keller · zählt nicht").assertIsDisplayed()
        rule.onNodeWithText("Annas Laptop").performClick()
        assertEquals(listOf(42 to true), toggled)

        content.value = {
            PresenceShareCard(true, PresenceCheck.OK, PresenceSense.HOME, saving = false, shareError = null, onShare = {}, onClearError = {}) {
                PresenceNowRow(PresenceMember(2, PresenceState.AWAY, null, shares = true, source = "app"))
            }
            WifiDevices(wifi.copy(consent = false), saving = false, error = null, onCounts = { _, _ -> })
        }
        rule.waitForIdle()
        shot("profile-wifi-off")
        rule.onNodeWithText("Quelle: App").assertIsDisplayed()
        rule.onNodeWithText("Ohne WLAN-Erkennung zählt nur, was dieses Handy meldet.").assertIsDisplayed()
        assertEquals("the devices only show with the detection on", 0, count("Annas Handy"))
    }
}
