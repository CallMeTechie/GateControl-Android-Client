package com.gatecontrol.android.ui.notizen

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.familie.locate.FamiliePermissions
import com.gatecontrol.android.notizen.NFamilyMoved
import com.gatecontrol.android.notizen.NMe
import com.gatecontrol.android.notizen.NPerson
import com.gatecontrol.android.notizen.NPlace
import com.gatecontrol.android.notizen.NTask
import com.gatecontrol.android.notizen.NotizenJson
import com.gatecontrol.android.notizen.NotizenTurns
import com.gatecontrol.android.notizen.TurnWords
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
 * „Wer ist dran?“, stars and the family's list (plugin 1.6.0, mockups „Anlegen“, „Sterne“, „Umzug“): a task's row with
 * whose turn it is, its stars and „Erledigt von …“; the choice „Eine Person · Reihum · Wer zuerst“; the note „Umzug
 * abgeschlossen“; who sees „Annehmen“ at a place. With `NOTIZEN_SHOTS=<dir>` the drawn pages are saved there (for a
 * look, not part of CI).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "de-w411dp-h900dp")
class NotizenTurnUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val lena = NPerson(4, "Lena Berg")
    private val tim = NPerson(5, "Tim Berg")
    private val papa = NPerson(2, "Papa")

    private fun me(id: Int = 2, features: String = """"taskTurns","taskStars","familyTasks","doneFeedback","placeMine","places"""", parent: Boolean = true): NMe = NotizenJson.me(
        JsonParser.parseString(
            """{"ok":true,"user":{"id":$id,"name":"Papa"},"lang":"de","today":"2026-11-14","time":"10:14","features":[$features],"prefs":{},
            "familyTasks":{"available":true,"bound":true,"listId":12,"parent":$parent,"stars":true,"starsMax":10,"moved":null}}""",
        ).asJsonObject,
    )!!

    private fun task(id: Long, title: String, turn: String?, assignee: NPerson?, stars: Int = 0, done: Boolean = false, doneBy: NPerson? = null) = NTask(
        id, 12, null, title, false, false, done, null, null, false, null, null, null, assignee, false, null, 0, null, null, 0, 0,
        turn = turn, people = if (turn == null) emptyList() else listOf(lena, tim), stars = stars, doneBy = doneBy, family = true,
    )

    private fun shot(name: String, dark: Boolean) {
        rule.waitForIdle()
        System.getenv("NOTIZEN_SHOTS")?.let { dir ->
            val bmp = rule.onNodeWithTag("shot").captureToImage().asAndroidBitmap()
            File(dir).mkdirs()
            File(dir, "$name-${if (dark) "dark" else "light"}.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private fun showRows(dark: Boolean, me: NMe = me(), name: String = "turn-rows") {
        rule.setContent {
            GateControlTheme(darkTheme = dark) {
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag("shot")) {
                    NSectionHeader("Familie", 4)
                    val rc = rowCtx("2026-11-14", "de", { null }, me = me)
                    NCard {
                        NEntryRow(task(1, "Spülmaschine ausräumen", TurnWords.ROTATE, lena, stars = 2), rc, {}, {}, showAssignee = true)
                        NEntryRow(task(2, "Müll rausbringen", TurnWords.ROTATE, papa), rc, {}, {}, showAssignee = true)
                        NEntryRow(task(3, "Blumen gießen", TurnWords.FIRST, null, stars = 1), rc, {}, {}, showAssignee = true)
                        NEntryRow(task(4, "Zimmer aufräumen", null, lena, stars = 2, done = true, doneBy = lena), rc, {}, {}, divider = false)
                    }
                }
            }
        }
        shot(name, dark)
    }

    @Test
    fun `the row - whose turn it is, the stars, Erledigt von`() {
        showRows(dark = false)
        rule.onNodeWithText("Reihum · Lena ist dran").assertExists()
        rule.onNodeWithText("Reihum · du bist dran").assertExists()
        rule.onNodeWithText("Wer zuerst").assertExists()
        rule.onAllNodesWithText("2 Sterne").assertCountEquals(2)
        rule.onNodeWithText("1 Stern").assertExists()
        rule.onNodeWithText("Erledigt von Lena").assertExists()
    }

    @Test
    fun `the row - dark`() {
        showRows(dark = true)
        rule.onNodeWithText("Reihum · Lena ist dran").assertExists()
    }

    @Test
    fun `the row - without the stars of the Familie plugin no stars show, the turn does`() {
        showRows(dark = false, me = me(features = """"taskTurns""""), name = "turn-rows-no-stars")
        rule.onAllNodesWithText("2 Sterne").assertCountEquals(0)
        rule.onNodeWithText("Wer zuerst").assertExists()
    }

    private val saved = mutableListOf<Pair<String, List<Long>>>()

    private fun showChoice(dark: Boolean) {
        rule.setContent {
            GateControlTheme(darkTheme = dark) {
                var mode by androidx.compose.runtime.remember { mutableStateOf(TurnWords.ROTATE) }
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag("shot").padding(16.dp)) {
                    TurnChoice(mode, { mode = it; saved += it to emptyList() })
                }
            }
        }
        shot("turn-choice", dark)
    }

    @Test
    fun `Wer ist dran - the three choices and what they mean`() {
        showChoice(dark = false)
        rule.onNodeWithText("Wer ist dran?").assertExists()
        rule.onNodeWithText("Eine Person").assertExists()
        rule.onNodeWithText("Reihum").assertExists()
        rule.onNodeWithText("Reihum: Nach dem Abhaken ist die nächste Person dran. Wer zuerst: Die Aufgabe geht an alle, der Erste erledigt sie für alle.").assertExists()
        rule.onNodeWithText("Wer zuerst").performClick()
        assertEquals(listOf(TurnWords.FIRST to emptyList<Long>()), saved)
    }

    @Test
    fun `Wer ist dran - dark`() {
        showChoice(dark = true)
        rule.onNodeWithText("Wer ist dran?").assertExists()
    }

    @Test
    fun `the dialog of a task - a turn needs two people`() {
        rule.setContent {
            GateControlTheme {
                TurnDialog(task(1, "Spülmaschine ausräumen", null, lena), listOf(papa, lena, tim), 2, { m, p -> saved += m to p }, {})
            }
        }
        rule.onNodeWithText("Reihum").performClick()
        rule.onNodeWithText("Dafür braucht es mindestens zwei Personen.").assertExists()
        rule.onNodeWithText("Speichern").assertIsNotEnabled()
        rule.onNodeWithText("Tim Berg").performClick()
        rule.onNodeWithText("Reihum · zuerst Lena").assertExists()
        rule.onNodeWithText("Speichern").assertIsEnabled().performClick()
        assertEquals(listOf(TurnWords.ROTATE to listOf(4L, 5L)), saved)
    }

    @Test
    fun `the dialog of a task - back to one person`() {
        rule.setContent {
            GateControlTheme {
                TurnDialog(task(1, "Spülmaschine ausräumen", TurnWords.FIRST, null), listOf(papa, lena, tim), 2, { m, p -> saved += m to p }, {})
            }
        }
        rule.onNodeWithText("Eine Person").performClick()
        rule.onNodeWithText("Speichern").performClick()
        assertEquals(listOf(NotizenTurns.ONE to listOf(4L, 5L)), saved)
    }

    @Test
    fun `Umzug abgeschlossen - the numbers, Ansehen and OK`() {
        val calls = mutableListOf<String>()
        rule.setContent {
            GateControlTheme { FamilyMovedDialog(NFamilyMoved("2026-11-14T10:00:00Z", 12, 3, false), { calls += "view" }, { calls += "ok" }) }
        }
        rule.onNodeWithText("Umzug abgeschlossen").assertExists()
        rule.onNodeWithText("Aus Familie übernommen: 12 Aufgaben und 3 Orts-Erinnerungen.").assertExists()
        rule.onNodeWithText("Die Sterne zählt weiter das Familien-Plugin.").assertExists()
        rule.onNodeWithText("Ansehen").performClick()
        rule.onNodeWithText("OK").performClick()
        assertEquals(listOf("view", "ok"), calls)
    }

    private fun place(consent: String, canConsent: Boolean?) =
        NPlace(NPlace.PLACE, 1, "Schule", null, 52.5, 13.4, 150, null, false, NPlace.ENTER, null, null, 2, consent, false, false, 0, null, mine = canConsent, canConsent = canConsent)

    @Test
    fun `a place - Annehmen only for the person who may consent, others see the state`() {
        val state = mutableStateOf(place(NPlace.PENDING, true) to me(id = 4))
        val calls = mutableListOf<Boolean>()
        rule.setContent {
            GateControlTheme {
                val (p, m) = state.value
                PlaceNotes(p, m, false, FamiliePermissions.GeofenceReadiness.READY, { calls += it }, {})
            }
        }
        rule.onNodeWithText("Annehmen").performClick()
        rule.onNodeWithText("Ablehnen").performClick()
        assertEquals(listOf(true, false), calls)
        // a third person of the shared list (someone else set it, too): only the state
        state.value = place(NPlace.PENDING, false) to me(id = 5)
        rule.waitForIdle()
        rule.onAllNodesWithText("Annehmen").assertCountEquals(0)
        rule.onNodeWithText("Wartet auf Zustimmung: Das Handy der zuständigen Person achtet erst danach auf den Ort.").assertExists()
        // declined: the person asked may still agree
        state.value = place(NPlace.DECLINED, true) to me(id = 4)
        rule.waitForIdle()
        rule.onNodeWithText("Annehmen").assertExists()
        rule.onAllNodesWithText("Ablehnen").assertCountEquals(0)
        // an older plugin (no `placeMine`): the guess as before — someone else set it and it waits
        state.value = place(NPlace.PENDING, null) to me(id = 5, features = """"places"""")
        rule.waitForIdle()
        rule.onNodeWithText("Annehmen").assertExists()
    }
}
