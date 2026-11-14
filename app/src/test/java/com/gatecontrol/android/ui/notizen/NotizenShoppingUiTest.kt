package com.gatecontrol.android.ui.notizen

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.gatecontrol.android.familie.shopping.ShoppingCompleted
import com.gatecontrol.android.familie.shopping.ShoppingItem
import com.gatecontrol.android.familie.shopping.ShoppingList
import com.gatecontrol.android.familie.shopping.ShoppingLogic
import com.gatecontrol.android.familie.shopping.ShoppingSnapshot
import com.gatecontrol.android.familie.shopping.ShoppingTaskRef
import com.gatecontrol.android.notizen.NAttached
import com.gatecontrol.android.notizen.NDue
import com.gatecontrol.android.notizen.NTask
import com.gatecontrol.android.ui.theme.GateControlTheme
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
 * A shopping list of „Notizen & Aufgaben“ (plugin 1.4.0, mockups „Abhaken wie gewohnt“ and „Aufgabe mit Liste in ‚Mein
 * Tag‘“): which task it belongs to and how far it is, the open entries first, the amounts; and a task's row with
 * „Einkauf 3/8“. With `NOTIZEN_SHOTS=<dir>` the drawn pages are saved there (for a look, not part of CI).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "de-w411dp-h891dp")
class NotizenShoppingUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val uid = "6f1c2a90-1111-4c1d-9a00-000000000001"
    private val list = ShoppingList(uid, "Einkauf", icon = "cart", listNo = 12, canEdit = true, canManage = false, family = true, shared = true, tasks = listOf(ShoppingTaskRef(41, "Bei Lidl einkaufen")))
    private val items = listOf(
        ShoppingItem("i1", uid, "Milch", "2 l", checked = true),
        ShoppingItem("i2", uid, "Brot", checked = true),
        ShoppingItem("i3", uid, "Äpfel", "1 kg", checked = true),
        ShoppingItem("i4", uid, "Butter"),
        ShoppingItem("i5", uid, "Nudeln", "2 Pck."),
        ShoppingItem("i6", uid, "Kaffee"),
        ShoppingItem("i7", uid, "Joghurt", "4"),
        ShoppingItem("i8", uid, "Spülmittel"),
    )

    private val calls = mutableListOf<String>()
    private val actions = HubShopActions(
        toggle = { calls += "toggle:${it.id}" },
        edit = { calls += "edit:${it.id}" },
        add = { t, q -> calls += "add:$t:$q" },
        clearChecked = { calls += "clear" },
        openTask = { calls += "task:$it" },
    )

    private fun task(list: NAttached?) = NTask(
        41, 3, null, "Bei Lidl einkaufen", false, false, false, null, NDue("2026-11-14", "10:00"), false, null, null, null, null, true, null, 0, null, null, 0, 0,
        attachedList = list,
    )

    private fun show(dark: Boolean = false, list: ShoppingList = this.list, items: List<ShoppingItem> = this.items, done: List<ShoppingCompleted> = emptyList(), name: String = "list") {
        rule.setContent {
            GateControlTheme(darkTheme = dark) {
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag("shot")) {
                    // the row of the task in „Mein Tag“ …
                    NSectionHeader("Heute", 1)
                    NCard {
                        NEntryRow(
                            task(NAttached(12, uid, "Einkauf", "cart", items.count { !it.checked }, items.size, items.count { it.checked }, true)),
                            rowCtx("2026-11-14", "de", { null }, showDay = false, openList = { calls += "list:$it" }),
                            {}, { calls += "open" }, divider = false,
                        )
                    }
                    // … and the list it carries
                    NSectionHeader(list.name, null)
                    HubShopList(list, ShoppingLogic.ordered(ShoppingSnapshot(listOf(list), items), list.id), actions)
                    allDoneText(done)?.let { Text(it, Modifier.testTag("done")) }
                }
            }
        }
        rule.waitForIdle()
        System.getenv("NOTIZEN_SHOTS")?.let { dir ->
            val bmp = rule.onNodeWithTag("shot").captureToImage().asAndroidBitmap()
            File(dir).mkdirs()
            File(dir, "shopping-$name-${if (dark) "dark" else "light"}.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test
    fun `the list - whose task, how far, the open entries first with their amounts`() {
        show()
        rule.onNodeWithText("gehört zu „Bei Lidl einkaufen“ · 3 von 8").assertExists()
        rule.onNodeWithText("2 Pck.").assertExists()
        // the open ones stand above the ticked ones
        assertTrue(rule.onNodeWithText("Butter").getBoundsInRoot().top < rule.onNodeWithText("Milch").getBoundsInRoot().top)
        assertTrue(rule.onNodeWithText("Spülmittel").getBoundsInRoot().top < rule.onNodeWithText("Milch").getBoundsInRoot().top)
        rule.onNodeWithText("Abgehakte entfernen").assertExists()

        rule.onNodeWithText("Butter").performClick()
        rule.onNodeWithText("Milch").performClick()
        rule.onNodeWithText("Nudeln").performTouchInput { longClick() }
        rule.onNodeWithText("gehört zu „Bei Lidl einkaufen“ · 3 von 8").performClick()
        rule.onNodeWithText("Abgehakte entfernen").performClick()
        assertEquals(listOf("toggle:i4", "toggle:i1", "edit:i5", "task:41", "clear"), calls)
    }

    @Test
    fun `the task's row - Einkauf 3 of 8 opens the list, the row the task`() {
        show(dark = true)
        rule.onNodeWithText("Einkauf 3/8").assertExists().performClick()
        rule.onAllNodesWithText("Bei Lidl einkaufen").assertCountEquals(1)
        // beside the mark (the row's far end): the task itself
        rule.onNodeWithText("Bei Lidl einkaufen").performTouchInput { click(Offset(width - 12f, 12f)) }
        assertEquals(listOf("list:$uid", "open"), calls)
    }

    @Test
    fun `a list I may only read - nothing to tick, no Abgehakte entfernen`() {
        show(list = list.copy(canEdit = false, family = false, tasks = null), name = "readonly")
        rule.onNodeWithText("Du kannst diese Liste nur ansehen.").assertExists()
        rule.onNodeWithText("3 von 8 abgehakt").assertExists()
        rule.onAllNodesWithText("Abgehakte entfernen").assertCountEquals(0)
        rule.onNodeWithText("Butter").performClick()
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `everything ticked off - the sentence with the task, an empty list says so`() {
        show(items = items.map { it.copy(checked = true) }, done = listOf(ShoppingCompleted(41, "Bei Lidl einkaufen", uid)), name = "done")
        rule.onNodeWithText("Alles abgehakt. „Bei Lidl einkaufen“ ist erledigt.").assertExists()
        rule.onNodeWithText("gehört zu „Bei Lidl einkaufen“ · 8 von 8").assertExists()
        rule.onNodeWithText("Einkauf 8/8").assertExists()
    }

    @Test
    fun `an empty list and two tasks done at once`() {
        show(items = emptyList(), done = listOf(ShoppingCompleted(41, "A", uid), ShoppingCompleted(42, "B", uid)), name = "empty")
        rule.onNodeWithText("Die Liste ist leer. Schreib unten den ersten Punkt.").assertExists()
        rule.onNodeWithText("gehört zu „Bei Lidl einkaufen“").assertExists()
        rule.onNodeWithText("Alles abgehakt. 2 Aufgaben sind erledigt.").assertExists()
    }
}
