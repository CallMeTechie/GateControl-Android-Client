package com.gatecontrol.android.ui.dnsprotect

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
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.dnsprotect.DnsAll
import com.gatecontrol.android.dnsprotect.DnsCounters
import com.gatecontrol.android.dnsprotect.DnsDevice
import com.gatecontrol.android.dnsprotect.DnsDuration
import com.gatecontrol.android.dnsprotect.DnsLogic
import com.gatecontrol.android.dnsprotect.DnsMay
import com.gatecontrol.android.dnsprotect.DnsPause
import com.gatecontrol.android.dnsprotect.DnsPeople
import com.gatecontrol.android.dnsprotect.DnsPerson
import com.gatecontrol.android.dnsprotect.DnsPersonDevice
import com.gatecontrol.android.dnsprotect.DnsScheduleEntry
import com.gatecontrol.android.dnsprotect.DnsSnapshot
import com.gatecontrol.android.dnsprotect.DnsStatus
import com.gatecontrol.android.dnsprotect.DnsTarget
import com.gatecontrol.android.dnsprotect.DnsTopDomain
import com.gatecontrol.android.ui.theme.GateControlTheme
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
import java.util.TimeZone

/**
 * "DNS-Schutz" as the approved mockup (section D) shows it — Thursday, 12 Nov, 14:48 in Berlin: the screen active,
 * suspended and with the filter down, "Unterbrechen nicht erlaubt", the tile on Start in its states and "Für andere
 * unterbrechen". With `DNS_SHOTS=<dir>` the drawn pages are saved there (for a look, not part of CI).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "de-w411dp-h1400dp")
class DnsProtectUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val zone = TimeZone.getDefault()
    private val now = DnsLogic.parseIso("2026-11-12T13:48:00.000Z")!!
    private val serverTime = "2026-11-12T13:48:00.000Z"
    private val top = listOf(DnsTopDomain("app-measurement.com", 148), DnsTopDomain("doubleclick.net", 121), DnsTopDomain("graph.facebook.com", 97))
    private val device = DnsDevice(7, "Annas Handy", "filtered")
    private val counters = DnsCounters("device", 4212, 712, 16.9)

    private val active = DnsStatus(
        DnsStatus.ACTIVE, device = device, counters = counters, top = top, serverTime = serverTime,
        may = DnsMay(pause = true, durations = listOf(5, 30, 60), maxMinutes = 60),
    )
    private val paused = active.copy(
        state = DnsStatus.PAUSED,
        pause = DnsPause("peer", until = "2026-11-12T14:12:10.000Z", since = "2026-11-12T13:42:10.000Z", byName = "Anna", byMe = true, remainingSeconds = 1450),
        may = DnsMay(resume = true, extend = true, durations = listOf(5, 30, 60), maxMinutes = 60),
    )
    private val fallback = active.copy(state = DnsStatus.FALLBACK, outageSince = "2026-11-12T13:31:00.000Z", may = DnsMay(reason = "fallback"))
    private val locked = DnsStatus(
        DnsStatus.ACTIVE, device = DnsDevice(21, "Leas Handy", "filtered"), profileName = "Kinder", serverTime = serverTime,
        counters = DnsCounters("device", 3310, 893, 27.0), may = DnsMay(reason = "profile"),
        today = listOf(DnsScheduleEntry("21:00", "07:00", "categories", listOf("social", "games", "video"))),
    )

    private val content = mutableStateOf<@Composable () -> Unit>({})
    private var shown = false

    @Before
    fun berlin() = TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"))

    @After
    fun restore() = TimeZone.setDefault(zone)

    private fun page(dark: Boolean = false, content: @Composable () -> Unit) {
        this.content.value = content
        // A second page of a test replaces the first one's content.
        if (!shown) {
            shown = true
            rule.setContent {
                GateControlTheme(darkTheme = dark) {
                    Column(
                        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState()).padding(16.dp).testTag("shot"),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) { this@DnsProtectUiTest.content.value() }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun shot(name: String) {
        val dir = System.getenv("DNS_SHOTS") ?: return
        val bmp = runCatching { rule.onNodeWithTag("shot").captureToImage().asAndroidBitmap() }.getOrNull() ?: return
        File(dir).mkdirs()
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun ui(status: DnsStatus, notice: DnsNotice? = null, people: DnsPeople? = null) =
        DnsUiState(coarse = "filtering", snapshot = DnsSnapshot(status, now), loaded = true, notice = notice, people = people)

    @Composable
    private fun Screen(
        state: DnsUiState,
        onPause: (DnsDuration) -> Unit = {},
        onResume: () -> Unit = {},
        onOthers: () -> Unit = {},
    ) = DnsProtectCards(state, state.snapshot!!, DnsLogic.remainingSeconds(state.snapshot!!, now), onPause, onResume, onOthers)

    private fun count(text: String) = rule.onAllNodesWithText(text).fetchSemanticsNodes().size

    @Test
    fun `active - status, counters, the chips the server allows, top domains`() {
        val chosen = mutableListOf<DnsDuration>()
        page { Screen(ui(active), onPause = { chosen += it }) }
        shot("screen-active")
        rule.onNodeWithText("Schutz aktiv").assertIsDisplayed()
        rule.onNodeWithText("Werbung und Schadseiten werden gesperrt").assertIsDisplayed()
        rule.onNodeWithText("4.212").assertIsDisplayed()
        rule.onNodeWithText("dieses Gerät").assertIsDisplayed()
        rule.onNodeWithText("712").assertIsDisplayed()
        rule.onNodeWithText("16,9 %").assertIsDisplayed()
        rule.onNodeWithText("Schutz unterbrechen").assertIsDisplayed()
        rule.onNodeWithText("5 Min").performClick()
        rule.onNodeWithText("30 Min").performClick()
        rule.onNodeWithText("1 Std").performClick()
        assertEquals(listOf(DnsDuration.Minutes(5), DnsDuration.Minutes(30), DnsDuration.Minutes(60)), chosen.toList())
        assertEquals(0, count("4 Std"))
        // Not released by the admin: greyed out, and the reason below.
        rule.onNodeWithText("Bis ich wieder einschalte").assertIsNotEnabled()
        rule.onNodeWithText("Gilt für dieses Gerät und endet von selbst. „Bis ich wieder einschalte“ hat dein Admin nicht freigegeben.").assertIsDisplayed()
        assertEquals(0, count("Wieder einschalten"))
        rule.onNodeWithText("TOP GESPERRTE DOMAINS").assertIsDisplayed()
        rule.onNodeWithText("app-measurement.com").assertIsDisplayed()
        rule.onNodeWithText("148").assertIsDisplayed()
        assertEquals(0, count("Für andere unterbrechen"))
    }

    @Test
    fun `durations above the longest allowed one are greyed out, four hours and forever only when allowed`() {
        page { Screen(ui(active.copy(may = DnsMay(pause = true, durations = listOf(5), maxMinutes = 5)))) }
        rule.onNodeWithText("30 Min").assertIsNotEnabled()
        rule.onNodeWithText("1 Std").assertIsNotEnabled()
        val chosen = mutableListOf<DnsDuration>()
        page { Screen(ui(active.copy(may = DnsMay(pause = true, durations = listOf(5, 30, 60, 240), maxMinutes = 240, forever = true))), onPause = { chosen += it }) }
        shot("screen-active-all-durations")
        rule.onNodeWithText("4 Std").performClick()
        rule.onNodeWithText("Bis ich wieder einschalte").performClick()
        assertEquals(listOf(DnsDuration.Minutes(240), DnsDuration.Forever), chosen.toList())
        rule.onNodeWithText("Gilt für dieses Gerät und endet von selbst.").assertIsDisplayed()
    }

    @Test
    fun `suspended - the countdown in the status and in the chosen chip, switching back on`() {
        var resumed = 0
        page { Screen(ui(paused), onResume = { resumed++ }) }
        shot("screen-paused")
        rule.onNodeWithText("unterbrochen · 24:10").assertIsDisplayed()
        rule.onNodeWithText("bis 15:12 Uhr · schaltet sich von selbst ein").assertIsDisplayed()
        // The 30-minute chip counts; the others cannot be tapped.
        rule.onNodeWithText("24:10").assertIsDisplayed()
        assertEquals(0, count("30 Min"))
        rule.onNodeWithText("5 Min").assertIsNotEnabled()
        rule.onNodeWithText("1 Std").assertIsNotEnabled()
        rule.onNodeWithText("Unterbrochen von dir um 14:42 für 30 Minuten. Solange wird auf diesem Gerät nichts gesperrt.").assertIsDisplayed()
        rule.onNodeWithText("Wieder einschalten").performClick()
        assertEquals(1, resumed)
    }

    @Test
    fun `suspended for all by an admin - no way back on for a user`() {
        val byAdmin = paused.copy(
            pause = DnsPause("all", openEnd = true, since = "2026-11-12T13:42:10.000Z", byName = "Marc"),
            may = DnsMay(reason = "paused_for_all"),
        )
        page { Screen(ui(byAdmin)) }
        shot("screen-paused-for-all")
        rule.onNodeWithText("Unterbrochen").assertIsDisplayed()
        rule.onNodeWithText("bis wieder eingeschaltet wird").assertIsDisplayed()
        rule.onNodeWithText("Unterbrochen von Marc um 14:42. Ein Admin hat für alle Geräte unterbrochen – nur ein Admin schaltet wieder ein.").assertIsDisplayed()
        assertEquals(0, count("Wieder einschalten"))
    }

    @Test
    fun `filter down - unfiltered since, no way to suspend`() {
        page(dark = true) { Screen(ui(fallback)) }
        shot("screen-fallback-dark")
        rule.onNodeWithText("Schutz ausgefallen").assertIsDisplayed()
        rule.onNodeWithText("seit 14:31 Uhr · Internet läuft ungefiltert").assertIsDisplayed()
        rule.onNodeWithText("Der Filter auf dem Server antwortet nicht.").assertIsDisplayed()
        assertEquals(0, count("Schutz unterbrechen"))
        assertEquals(0, count("5 Min"))
        rule.onNodeWithText("TOP GESPERRTE DOMAINS").assertIsDisplayed()
    }

    @Test
    fun `suspending not allowed - the notice in place of the chips, the times of today`() {
        page { Screen(ui(locked)) }
        shot("screen-not-allowed")
        rule.onNodeWithText("Profil „Kinder“ · Werbung und Schadseiten werden gesperrt").assertIsDisplayed()
        rule.onNodeWithText("Unterbrechen nicht erlaubt").assertIsDisplayed()
        rule.onNodeWithText("21:00–07:00 Uhr gesperrt: Soziale Netzwerke, Spiele, Videos.").assertIsDisplayed()
        assertEquals(0, count("Schutz unterbrechen"))
        // No list delivered: the section is left out.
        assertEquals(0, count("TOP GESPERRTE DOMAINS"))
        // "Nur Admins": the state, no button, no notice.
        page { Screen(ui(locked.copy(may = DnsMay(reason = "admins_only")))) }
        assertEquals(0, count("Unterbrechen nicht erlaubt"))
        assertEquals(0, count("Schutz unterbrechen"))
    }

    @Test
    fun `not filtered, counters of the whole server, no connection`() {
        val ownDns = DnsStatus(
            DnsStatus.UNFILTERED, device = DnsDevice(7, "Annas Handy", "ownDns", "9.9.9.9"),
            counters = DnsCounters("server", 90210, 12000, 13.3), may = DnsMay(reason = "not_filtered"),
        )
        page { Screen(ui(ownDns, notice = DnsNotice.Offline)) }
        shot("screen-unfiltered-offline")
        rule.onNodeWithText("Wird nicht gefiltert").assertIsDisplayed()
        rule.onNodeWithText("Dieses Gerät nutzt einen eigenen DNS-Server (9.9.9.9).").assertIsDisplayed()
        rule.onNodeWithText("alle Geräte des Servers").assertIsDisplayed()
        rule.onNodeWithText("Keine Verbindung zum Server. Du siehst den letzten bekannten Stand.").assertIsDisplayed()
        assertEquals(0, count("Schutz unterbrechen"))
    }

    @Test
    fun `a refused change shows the server's sentence`() {
        page { Screen(ui(active, notice = DnsNotice.Refused("Höchstens 60 Minuten."))) }
        rule.onNodeWithText("Höchstens 60 Minuten.").assertIsDisplayed()
    }

    @Test
    fun `the tile on Start - active, suspended, down, nothing without a DNS protection`() {
        var opened = 0
        var resumed = 0
        page {
            listOf(active, paused, fallback, locked).forEach { s ->
                DnsStartTile(ui(s), DnsLogic.remainingSeconds(DnsSnapshot(s, now), now), onOpen = { opened++ }, onResume = { resumed++ })
            }
            // Before the first status: only what the permissions tell.
            DnsStartTile(DnsUiState(coarse = "filtering"), null, onOpen = {}, onResume = {})
            // No DNS protection reported, or gone: no tile.
            DnsStartTile(DnsUiState(coarse = null), null, onOpen = {}, onResume = {})
            DnsStartTile(DnsUiState(coarse = "filtering", gone = true), null, onOpen = {}, onResume = {})
        }
        shot("start-tiles")
        assertEquals(2, count("DNS-Schutz aktiv"))
        rule.onNodeWithText("712 von 4.212 gesperrt · 16,9 %").assertIsDisplayed()
        rule.onNodeWithText("noch 24:10 · bis 15:12 Uhr").assertIsDisplayed()
        rule.onNodeWithText("DNS-Schutz ausgefallen").assertIsDisplayed()
        rule.onNodeWithText("Internet läuft ungefiltert").assertIsDisplayed()
        rule.onNodeWithText("Zustand wird geladen…").assertIsDisplayed()
        // One "Unterbrechen" (Lea's profile has none), it opens the screen; "Einschalten" switches on right here.
        assertEquals(1, count("Unterbrechen"))
        rule.onNodeWithText("Unterbrechen").performClick()
        assertEquals(1, opened)
        rule.onNodeWithText("Einschalten").performClick()
        assertEquals(1, resumed)
    }

    @Test
    fun `for others - the entry for admins and parents, people, devices and only their allowed durations`() {
        var others = 0
        page { Screen(ui(active.copy(manage = true)), onOthers = { others++ }) }
        rule.onNodeWithText("Für andere unterbrechen").performClick()
        assertEquals(1, others)

        val child = DnsMay(pause = true, durations = listOf(5, 30, 60), maxMinutes = 60)
        val people = DnsPeople(
            all = DnsAll(DnsMay(pause = true, durations = listOf(5, 30, 60, 240), maxMinutes = 10080, forever = true)),
            people = listOf(
                DnsPerson(2, "Marc", self = true, may = child, devices = listOf(DnsPersonDevice(7, "Marcs Handy", "filtered", may = child))),
                DnsPerson(
                    4, "Lea", may = child,
                    devices = listOf(
                        DnsPersonDevice(21, "Leas Handy", "filtered", suspended = true, pause = DnsPause("peer", until = "2026-11-12T14:12:00.000Z"), may = DnsMay(resume = true)),
                        DnsPersonDevice(22, "Leas Tablet", "filtered", may = child),
                    ),
                ),
                DnsPerson(5, "Tim", may = DnsMay(reason = "not_filtered"), devices = listOf(DnsPersonDevice(30, "Tims PC", "ownDns", may = DnsMay(reason = "not_filtered")))),
            ),
        )
        val open = mutableStateOf<String?>(null)
        val paused = mutableListOf<Pair<DnsDuration, DnsTarget>>()
        val resumed = mutableListOf<DnsTarget>()
        page {
            Column(Modifier.background(MaterialTheme.colorScheme.surface)) {
                DnsOthersList(ui(active, people = people), open.value, { open.value = it }, { d, t -> paused += d to t }, { resumed += it }, onRetry = {})
            }
        }
        rule.onNodeWithText("Alle VPN-Geräte").assertIsDisplayed()
        rule.onNodeWithText("Marc (du)").assertIsDisplayed()
        rule.onNodeWithText("teilweise unterbrochen").assertIsDisplayed()
        rule.onNodeWithText("unterbrochen bis 15:12 Uhr").assertIsDisplayed()
        rule.onNodeWithText("Wird nicht gefiltert.").assertIsDisplayed()
        // Lea: all her devices for 30 minutes.
        rule.onNodeWithText("Lea").performClick()
        rule.waitForIdle()
        shot("others")
        assertEquals(0, count("4 Std"))
        assertEquals(0, count("Bis wieder eingeschaltet wird"))
        rule.onNodeWithText("30 Min").performClick()
        assertEquals(listOf<Pair<DnsDuration, DnsTarget>>(DnsDuration.Minutes(30) to DnsTarget.User(4)), paused.toList())
        // Her suspended phone: back on.
        rule.onNodeWithText("Einschalten").performClick()
        assertEquals(listOf<DnsTarget>(DnsTarget.Peer(21)), resumed.toList())
        // An admin's "Alle VPN-Geräte": four hours and without an end.
        rule.onNodeWithText("Alle VPN-Geräte").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Bis wieder eingeschaltet wird").performClick()
        assertEquals(DnsDuration.Forever to DnsTarget.All, paused.last())
    }
}
