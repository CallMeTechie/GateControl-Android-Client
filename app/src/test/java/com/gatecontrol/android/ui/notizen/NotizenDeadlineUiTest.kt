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
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.drive.DDeadline
import com.gatecontrol.android.drive.DReminder
import com.gatecontrol.android.notizen.NDeadline
import com.gatecontrol.android.notizen.NDue
import com.gatecontrol.android.notizen.NOpen
import com.gatecontrol.android.notizen.NReminder
import com.gatecontrol.android.notizen.NSourceRef
import com.gatecontrol.android.notizen.NTask
import com.gatecontrol.android.notizen.NotizenFormat
import com.gatecontrol.android.ui.drive.DeadlineReminderRow
import com.gatecontrol.android.ui.theme.GateControlTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The reminder of a deadline in Drive as a task (plugin 1.7.0 / Drive 1.30.0, mockup „Frist aus Drive“): its row —
 * „Drive“, „Frist <Tag>“, the file, „3 Tage vorher“ — its line in the task with „Datei öffnen“, and in Drive „über
 * Notizen & Aufgaben“ at a reminder the hub rings. With `NOTIZEN_SHOTS=<dir>` the drawn pages are saved there (for a
 * look, not part of CI).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "de-w411dp-h900dp")
class NotizenDeadlineUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val open = NOpen("gatecontrol-drive", "node/41")
    private val words = Triple("heute", "morgen", "gestern")
    /** „Mo. 30. Nov.“ — as the rows write a day. */
    private val day = NotizenFormat.shortDate("2026-11-30", "de")

    private fun task(id: Long, title: String, due: String?, deadline: NDeadline?, overdue: Boolean = false) = NTask(
        id, 5, null, title, false, false, false, null, due?.let { NDue(it, null) }, overdue, null, null, null, null, false, null, 0,
        deadline?.let { NSourceRef("gatecontrol-drive", "deadline:$id") }, null, 0, 0,
        reminder = if (deadline != null && !overdue) NReminder("task:$id:1795762800000", null, 1795762800000, mine = true, snoozed = false) else null,
        deadline = deadline,
    )

    private fun shot(name: String, dark: Boolean) {
        rule.waitForIdle()
        System.getenv("NOTIZEN_SHOTS")?.let { dir ->
            val bmp = rule.onNodeWithTag("shot").captureToImage().asAndroidBitmap()
            File(dir).mkdirs()
            File(dir, "$name-${if (dark) "dark" else "light"}.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private fun showRows(dark: Boolean, showDay: Boolean = true) {
        rule.setContent {
            GateControlTheme(darkTheme = dark) {
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag("shot")) {
                    NSectionHeader("Heute", 4)
                    val rc = rowCtx("2026-11-27", "de", { null }, showDay = showDay)
                    NCard {
                        NEntryRow(task(77, "Kfz-Versicherung kündigen", "2026-11-30", NDeadline("2026-11-30", "cancellation", "Versicherungsschein.pdf", 3, open)), rc, {}, {})
                        NEntryRow(task(78, "Stromrechnung zahlen", "2026-11-28", NDeadline("2026-11-28", "payment", "Rechnung 2026-11.pdf", 1, open)), rc, {}, {})
                        NEntryRow(task(79, "Widerspruch Bescheid", "2026-11-20", NDeadline("2026-11-20", "objection", null, 0, open), overdue = true), rc, {}, {})
                        NEntryRow(task(80, "Altglas wegbringen", "2026-11-27", null), rc, {}, {}, divider = false)
                    }
                }
            }
        }
        shot("deadline-rows", dark)
    }

    @Test
    fun `the row - Drive, the day of the deadline, the file, how many days before`() {
        showRows(dark = false)
        rule.onNodeWithText("Frist $day").assertExists()
        rule.onNodeWithText("· Versicherungsschein.pdf · 3 Tage vorher").assertExists()
        rule.onNodeWithText("Frist morgen").assertExists()
        rule.onNodeWithText("· Rechnung 2026-11.pdf · 1 Tag vorher").assertExists()
        rule.onNodeWithText("· Erinnerung am Tag selbst").assertExists()
        // an ordinary task beside them: its day, no „Frist“
        rule.onNodeWithText("heute").assertExists()
    }

    @Test
    fun `the row - dark`() {
        showRows(dark = true)
        rule.onNodeWithText("Frist $day").assertExists()
    }

    @Test
    fun `the row - Mein Tag hides the day of a task, not the day of a deadline`() {
        showRows(dark = false, showDay = false)
        rule.onNodeWithText("Frist $day").assertExists()
        rule.onNodeWithText("heute").assertDoesNotExist()
    }

    private fun showLine(dark: Boolean, deadline: NDeadline, onOpen: (NOpen) -> Unit = {}) {
        rule.setContent {
            GateControlTheme(darkTheme = dark) {
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(vertical = 16.dp).testTag("shot")) {
                    DeadlineLine(deadline, "2026-11-30", "2026-11-27", "de", words, onOpen)
                }
            }
        }
        shot("deadline-line", dark)
    }

    @Test
    fun `the task - the deadline's line opens the file`() {
        var opened: NOpen? by mutableStateOf(null)
        showLine(dark = false, NDeadline("2026-11-30", "cancellation", "Versicherungsschein.pdf", 3, open)) { opened = it }
        rule.onNodeWithText("Frist $day · Versicherungsschein.pdf · 3 Tage vorher").assertExists()
        rule.onNodeWithText("Die Frist steht an der Datei in Drive.", substring = true).assertExists()
        rule.onNodeWithText("Datei öffnen").performClick()
        assertEquals(open, opened)
    }

    @Test
    fun `the task - dark, and a deadline without a file to open`() {
        showLine(dark = true, NDeadline(null, null, null, 0, null))
        // the day of the task stands in for the deadline's
        rule.onNodeWithText("Frist $day · Erinnerung am Tag selbst").assertExists()
        rule.onNodeWithText("Datei öffnen").assertDoesNotExist()
    }

    private fun showDrive(dark: Boolean, by: String) {
        rule.setContent {
            GateControlTheme(darkTheme = dark) {
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag("shot")) {
                    DeadlineReminderRow(DDeadline(12, "cancellation", "2099-11-30", "Kfz-Versicherung", DReminder("2099-11-27T07:00:00.000Z", 3, false, by))) { _, _ -> }
                }
            }
        }
        shot("deadline-drive-$by", dark)
    }

    @Test
    fun `Drive - ueber Notizen und Aufgaben at a reminder the hub rings`() {
        showDrive(dark = false, DReminder.BY_HUB)
        rule.onNodeWithText("über Notizen & Aufgaben").assertExists()
    }

    @Test
    fun `Drive - dark`() {
        showDrive(dark = true, DReminder.BY_HUB)
        rule.onNodeWithText("über Notizen & Aufgaben").assertExists()
    }

    @Test
    fun `Drive - its own reminder says nothing more`() {
        showDrive(dark = false, DReminder.BY_DRIVE)
        rule.onNodeWithText("über Notizen & Aufgaben").assertDoesNotExist()
    }
}
