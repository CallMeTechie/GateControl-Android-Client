package com.gatecontrol.android.ui.notizen

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gatecontrol.android.R
import com.gatecontrol.android.notizen.NDue
import com.gatecontrol.android.notizen.NEntry
import com.gatecontrol.android.notizen.NItem
import com.gatecontrol.android.notizen.NTask
import com.gatecontrol.android.notizen.NotizenEvents
import com.gatecontrol.android.notizen.NotizenEventsJson
import com.gatecontrol.android.notizen.NotizenJson
import com.gatecontrol.android.notizen.NotizenOutbox
import com.gatecontrol.android.notizen.QuickParse
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.components.GcSegmented
import com.gatecontrol.android.ui.components.GcSubpageBar
import com.gatecontrol.android.ui.theme.GateControlTheme
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * „Termine in Schnell hinzufügen“ (plugin 1.9.0, Bilder 1–10 of the draft): the switch „Aufgabe | Termin | Notiz“,
 * the question „Das sieht wie ein Termin aus“, the chips of an event (the dashed „Ort: <Titel>?“), the card „Kein
 * Kalender vorhanden“, the rows of events on „Mein Tag“ (no tick, the calendar's colour), the switches per calendar.
 * With `NOTIZEN_SHOTS=<dir>` the drawn pages are saved there (for a look, not part of CI).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "de-w411dp-h1100dp")
class NotizenEventUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val calls = mutableListOf<String>()

    /** Light or dark: one content per test, the theme switched in place for the second picture. */
    private val dark = mutableStateOf(false)

    private fun both(name: String) {
        dark.value = false
        shot(name, false)
        dark.value = true
        shot(name, true)
        dark.value = false
        rule.waitForIdle()
    }

    private fun shot(name: String, dark: Boolean) {
        rule.waitForIdle()
        System.getenv("NOTIZEN_SHOTS")?.let { dir ->
            val bmp = rule.onNodeWithTag("shot").captureToImage().asAndroidBitmap()
            File(dir).mkdirs()
            File(dir, "$name-${if (dark) "dunkel" else "hell"}.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private val calendars = NotizenEventsJson.calendars(
        JsonParser.parseString(
            """{ "state": "ok", "calendars": [
              { "id": 7, "key": "cal:7", "name": "Privat", "color": "#4F8DF5", "kind": "own", "writable": true, "target": true, "isDefault": true, "area": "private", "shown": true },
              { "id": null, "key": "overlay:familie", "name": "Familie", "color": "#8B5CF6", "kind": "family", "writable": false, "target": false, "isDefault": false, "area": "private", "shown": true },
              { "id": 9, "key": "cal:9", "name": "Feiertage", "color": "#6B7280", "kind": "subscribed", "writable": false, "target": false, "isDefault": false, "area": "private", "shown": false },
              { "id": 12, "key": "cal:12", "name": "Aufgaben", "color": "#6B7280", "kind": "tasks", "writable": true, "target": false, "isDefault": false, "area": "private", "shown": false } ],
              "defaultId": 7, "reminders": { "timed": 15, "allDay": 360 }, "timeZone": "Europe/Berlin" }""",
        ).asJsonObject,
    )!!

    private val ctx = QuickParse.Ctx("2026-11-27", now = "10:00")

    /** The line read in event mode, as the view model makes the draft of it. */
    private fun draftOf(text: String): NotizenEvents.Draft {
        val p = QuickParse.parse(text, ctx.copy(mode = "event"))
        val time = p.time
        return NotizenEvents.Draft(p.title, p.date ?: ctx.today, time, p.endTime, p.location, 7, false, null, false, p.repeat)
    }

    /** The page „Schnell hinzufügen“ as the screen lays it out (its view model needs the server; the parts are the real ones). */
    @Composable
    private fun QuickPage(kind: String, text: String, content: @Composable () -> Unit) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag("shot")) {
            GcSubpageBar(stringResource(R.string.notizen_quick_title), {}, stringResource(R.string.notizen_back))
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                GcSegmented(
                    listOf(
                        NotizenQuickViewModel.KIND_TASK to stringResource(R.string.notizen_quick_task),
                        NotizenQuickViewModel.KIND_EVENT to stringResource(R.string.notizen_quick_event),
                        NotizenQuickViewModel.KIND_NOTE to stringResource(R.string.notizen_quick_note),
                    ),
                    kind,
                    { calls += "kind:$it" },
                )
                Text(stringResource(if (kind == NotizenQuickViewModel.KIND_EVENT) R.string.notizen_new_event else R.string.notizen_new_task), style = MaterialTheme.typography.titleMedium)
                Text(
                    text,
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp),
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface)
                        .border(1.dp, GateControlTheme.extraColors.border2, RoundedCornerShape(16.dp)).padding(14.dp),
                )
                content()
                GcPrimaryButton(stringResource(R.string.notizen_add), {})
            }
        }
    }

    @Composable
    private fun colors() = listOf("date", "time", "repeat", "important", "list", "person", "place").associateWith { markColor(it) }

    private fun showEvent(text: String, name: String) {
        rule.setContent {
            GateControlTheme(darkTheme = dark.value) {
                QuickPage(NotizenQuickViewModel.KIND_EVENT, text) {
                    val d = draftOf(text)
                    Text(stringResource(R.string.notizen_quick_understood), style = MaterialTheme.typography.labelLarge, color = GateControlTheme.extraColors.muted)
                    QuickEventChips(
                        d, calendars.default, NotizenEvents.defaultReminder(calendars, d.allDay), "de", colors(),
                        onDialog = { calls += "dialog:$it" }, onTitleAsPlace = { calls += "place:${d.title}" }, onDropRepeat = {}, onMore = { calls += "more" },
                    )
                }
            }
        }
        both(name)
    }

    @Test
    fun `Bild 1 - an event with a time, the dashed place, calendar and reminder of the calendar`() {
        showEvent("Freitag 4. Dezember 14 Uhr Fashion Outlet Zweibrücken", name = "01-termin-bausteine")
        rule.onNodeWithText("Fr. 4. Dez.").assertExists()
        rule.onNodeWithText("14:00–15:00").assertExists()
        rule.onNodeWithText("Kalender: Privat").assertExists()
        rule.onNodeWithText("15 Minuten vorher").assertExists()
        rule.onNodeWithText("Titel: „Fashion Outlet Zweibrücken“. Ein Tipp auf den gestrichelten Baustein übernimmt den Text auch als Ort.").assertExists()
        rule.onNodeWithText("Ort: Fashion Outlet Zweibrücken?").performClick()
        rule.onNodeWithText("Mehr Angaben …").performClick()
        rule.onNodeWithText("Kalender: Privat").performClick()
        rule.onNodeWithText("15 Minuten vorher").performClick()
        assertEquals(listOf("place:Fashion Outlet Zweibrücken", "more", "dialog:$EV_CALENDAR", "dialog:$EV_REMIND"), calls)
    }

    @Test
    fun `Bild 2 - without a time all day, the place after im, the reminder the day before`() {
        showEvent("Freitag 4. Dezember Einkaufen im Fashion Outlet Zweibrücken", name = "02-ganztaegig-ort-mit-im")
        rule.onNodeWithText("Ganztägig").assertExists()
        rule.onNodeWithText("Ort: Fashion Outlet Zweibrücken").assertExists()
        rule.onNodeWithText("Am Vortag um 18:00", substring = true).assertExists()
        rule.onNodeWithText("Titel: „Einkaufen“. Ohne Uhrzeit gilt der Termin für den ganzen Tag.").assertExists()
        rule.onAllNodesWithText("Ort: Fashion Outlet Zweibrücken?").assertCountEquals(0)
        rule.onNodeWithText("Ort: Fashion Outlet Zweibrücken").performClick()
        assertEquals(listOf("dialog:$EV_PLACE"), calls)
    }

    @Test
    fun `Bild 3 - the app asks, it never switches by itself`() {
        val line = "Freitag 4. Dezember 14 Uhr Fashion Outlet Zweibrücken"
        // only a date AND a time ask; the line „Freitag Müll rausbringen“ does not
        assertEquals(true, QuickParse.parse(line, ctx).looksLikeEvent)
        assertEquals(false, QuickParse.parse("Freitag Müll rausbringen", ctx).looksLikeEvent)
        rule.setContent {
            GateControlTheme(darkTheme = dark.value) {
                QuickPage(NotizenQuickViewModel.KIND_TASK, line) {
                    QuickEventAsk(onYes = { calls += "yes" }, onNo = { calls += "no" })
                }
            }
        }
        both("03-frage-als-termin")
        rule.onNodeWithText("Das sieht wie ein Termin aus.", substring = true).assertExists()
        rule.onNodeWithText("Als Termin anlegen").performClick()
        rule.onNodeWithText("Aufgabe lassen").performClick()
        assertEquals(listOf("yes", "no"), calls)
    }

    @Test
    fun `Bild 4 - the place is a text only`() {
        val text = mutableStateOf("Fashion Outlet Zweibrücken")
        rule.setContent {
            GateControlTheme(darkTheme = dark.value) {
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag("shot").padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.notizen_ev_place_title), style = MaterialTheme.typography.headlineSmall)
                    EventPlaceForm(text.value) { text.value = it }
                    GcPrimaryButton(stringResource(R.string.notizen_ev_place_take), {})
                    Text(stringResource(R.string.notizen_ev_place_remove), color = MaterialTheme.colorScheme.error)
                }
            }
        }
        both("04-ort-des-termins")
        rule.onNodeWithText("Ort des Termins").assertExists()
        rule.onNodeWithText("Ort oder Adresse").assertExists()
        rule.onNodeWithText("Nur ein Text. Er steht im Kalendereintrag. Es gibt keine Erinnerung beim Ankommen und keine Standort-Abfrage.").assertExists()
        // no map, no search, no remembered places
        rule.onAllNodesWithText("Beim Ankommen").assertCountEquals(0)
        rule.onAllNodesWithText("Suchen").assertCountEquals(0)
    }

    @Test
    fun `the calendar choice shows only calendars a new event may go to`() {
        val d = draftOf("morgen 9 Uhr Zahnarzt")
        rule.setContent {
            GateControlTheme {
                QuickEventDialogs(
                    EV_CALENDAR, d, "2026-11-27", calendars.calendars, calendars.default, 15,
                    onDue = {}, onPlace = {}, onCalendar = { calls += "cal:$it" }, onReminder = {}, onClose = {},
                )
            }
        }
        rule.onNodeWithText("Privat").assertExists()
        rule.onAllNodesWithText("Feiertage").assertCountEquals(0)
        rule.onAllNodesWithText("Aufgaben").assertCountEquals(0)
        rule.onAllNodesWithText("Familie").assertCountEquals(0)
        rule.onNodeWithText("Privat").performClick()
        assertEquals(listOf("cal:7"), calls)
    }

    @Test
    fun `Bild 9 - no calendar - the switch stays, why, save as a task`() {
        rule.setContent {
            GateControlTheme(darkTheme = dark.value) {
                QuickPage(NotizenQuickViewModel.KIND_EVENT, "Freitag 4. Dezember 14 Uhr Fashion Outlet Zweibrücken") {
                    QuickEventBlocked("no_mail", enabled = true) { calls += "task" }
                }
            }
        }
        both("09-kein-kalender")
        rule.onNodeWithText("Kein Kalender vorhanden").assertExists()
        rule.onNodeWithText("Termine brauchen den Kalender aus dem Plugin „Mail“. Es ist nicht eingerichtet. Frag die Person, die den Server verwaltet.").assertExists()
        rule.onNodeWithText("Termin").assertExists()
        rule.onNodeWithText("Als Aufgabe mit Datum speichern").performClick()
        assertEquals(listOf("task"), calls)
    }

    private fun item(json: String): NItem = NotizenJson.item(JsonParser.parseString(json).asJsonObject)!!

    private fun event(id: Long, title: String, date: String, time: String?, location: String?, cal: String, color: String, eventId: Long?) = item(
        """{ "id": $id, "kind": "event", "title": "$title", ${location?.let { "\"subtitle\": \"$it\"," } ?: ""}
            "due": { "date": "$date" ${time?.let { ", \"time\": \"$it\"" } ?: ""} }, "show": { "date": "$date" ${time?.let { ", \"time\": \"$it\"" } ?: ""} },
            "allDay": ${time == null}, "canComplete": false, "area": "private",
            "source": { "plugin": "gatecontrol-mail", "label": "Mail", "open": { "plugin": "gatecontrol-mail", "route": "${eventId?.let { "event/$it" } ?: "calendar/$date"}" } },
            "event": { "end": null, ${location?.let { "\"location\": \"$it\"," } ?: ""} "calendar": { "key": "cal:1", "name": "$cal", "color": "$color", "kind": "own" },
                       "eventId": ${eventId ?: "null"}, "recurring": false, "writable": true } }""",
    )

    private fun task(id: Long, title: String, due: NDue? = null) =
        NTask(id, 3, null, title, false, false, false, null, due, false, null, null, null, null, false, null, 0, null, null, 0, 0)

    private val school = item(
        """{ "id": 40, "kind": "event", "title": "Elternabend", "subtitle": "Schule · Aula", "due": { "date": "2026-11-27", "time": "19:00" },
            "show": { "date": "2026-11-27", "time": "19:00" }, "canComplete": false, "source": { "plugin": "gatecontrol-schule", "label": "Schule" } }""",
    )

    private fun nav() = NotizenNav(
        task = { calls += "task:$it" }, item = { calls += "item:$it" }, view = {}, quick = {}, note = {}, newNote = {}, sources = {}, import = {}, search = {},
        vault = {}, openSource = { calls += "open:${it.plugin}/${it.route}"; true }, back = {},
    )

    private fun showDay(withCalendar: Boolean) {
        val today = "2026-11-27"
        val events = if (withCalendar) listOf<NEntry>(event(318, "Zahnarzt", today, "16:30", "Praxis am Markt", "Privat", "#4F8DF5", 912), school) else listOf<NEntry>(school)
        val upcoming = if (withCalendar) {
            listOf<NEntry>(
                task(5, "Reifen wechseln", NDue("2026-11-30", null)),
                event(320, "Fashion Outlet Zweibrücken", "2026-12-04", "14:00", "Fashion Outlet Zweibrücken", "Privat", "#4F8DF5", 914),
                event(321, "Geburtstag Oma", "2026-12-06", null, null, "Familie", "#8B5CF6", null),
            )
        } else {
            listOf<NEntry>(task(6, "Fashion Outlet Zweibrücken", NDue("2026-12-04", "14:00")))
        }
        val waiting = NotizenEvents.itemOf(
            NotizenOutbox.Op(NotizenOutbox.CREATE, -2, false, 0, NotizenOutbox.EVENT, NotizenEvents.body(NotizenEvents.Draft("Kino", today, "20:00", "21:00", null, null, false, null, false, null), "aaaaaaaa-1"), "aaaaaaaa-1"),
        )!!
        rule.setContent {
            GateControlTheme(darkTheme = dark.value) {
                val rc = rowCtx(today, "de", { null }, showDay = false)
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag("shot")) {
                    NSectionHeader("Termine heute", events.size + if (withCalendar) 1 else 0)
                    EntryCard(if (withCalendar) events + waiting else events, rc.copy(timeFirst = true), { calls += "toggle:${it.uid}" }, nav())
                    NSectionHeader("Heute", 2)
                    EntryCard(listOf(task(1, "Paket abholen"), task(2, "Rechnung bezahlen")), rc, {}, nav())
                    NSectionHeader("Demnächst", upcoming.size, action = "Geplant", onAction = {})
                    EntryCard(upcoming, rc.copy(showDay = true), {}, nav())
                }
            }
        }
        both(if (withCalendar) "07-mein-tag-mit-terminen" else "10-mein-tag-ohne-kalender")
    }

    @Test
    fun `Bild 7 - My Day with events - the calendar's sign, no tick, a tap opens Mail's page`() {
        showDay(withCalendar = true)
        rule.onNodeWithText("Praxis am Markt · Privat").assertExists()
        rule.onNodeWithText("16:30").assertExists()
        rule.onNodeWithText("Fr. 4. Dez. · Fashion Outlet Zweibrücken · Privat").assertExists()
        rule.onNodeWithText("So. 6. Dez. · ganztägig · Familie").assertExists()
        rule.onNodeWithText("20:00").assertExists()
        rule.onNodeWithText("wartet auf Verbindung", substring = true).assertExists()
        // never „Mail“ as where it comes from; the calendar's sign says which calendar
        rule.onAllNodesWithText("Mail").assertCountEquals(0)
        rule.onAllNodesWithContentDescription("Termin im Kalender Privat").assertCountEquals(2)
        // Schule's event stays as it was (time first, no event of the calendar)
        rule.onNodeWithText("Elternabend").assertExists()
        rule.onNodeWithText("Zahnarzt").performClick()
        rule.onNodeWithText("Geburtstag Oma").performClick()
        rule.onNodeWithText("Kino").performClick()
        // the family's calendar has no number in Mail: its details; a waiting one opens nothing
        assertEquals(listOf("open:gatecontrol-mail/event/912", "item:321"), calls)
    }

    @Test
    fun `Bild 10 - without a calendar all goes on as before`() {
        showDay(withCalendar = false)
        rule.onNodeWithText("Fashion Outlet Zweibrücken").assertExists()
        rule.onNodeWithText("Elternabend").assertExists()
    }

    @Test
    fun `Bild 8 - Quellen - a switch per calendar`() {
        rule.setContent {
            GateControlTheme(darkTheme = dark.value) {
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag("shot")) {
                    NSectionHeader(stringResource(R.string.notizen_set_events), null)
                    NCard {
                        calendars.calendars.forEach { c -> CalendarSwitch(c, true) { calls += "${c.key}:$it" } }
                    }
                    Text(stringResource(R.string.notizen_set_events_hint), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))
                }
            }
        }
        both("08-quellen-kalender")
        rule.onNodeWithText("Standard für neue Termine").assertExists()
        rule.onNodeWithText("Familienkalender").assertExists()
        rule.onNodeWithText("abonniert").assertExists()
        rule.onNodeWithText("stehen schon als Aufgabe da").assertExists()
        rule.onNodeWithText("Feiertage").performClick()
        rule.onNodeWithText("Privat").performClick()
        assertEquals(listOf("cal:9:true", "cal:7:false"), calls)
    }
}
