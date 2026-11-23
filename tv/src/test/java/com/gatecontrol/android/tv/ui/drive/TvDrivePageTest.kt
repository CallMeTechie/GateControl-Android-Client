package com.gatecontrol.android.tv.ui.drive

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import com.gatecontrol.android.drive.DArea
import com.gatecontrol.android.drive.DNode
import com.gatecontrol.android.tv.drive.TvDriveLogic
import com.gatecontrol.android.tv.drive.TvDriveOpen
import com.gatecontrol.android.tv.drive.TvDriveStack
import com.gatecontrol.android.tv.drive.TvDriveUi
import com.gatecontrol.android.tv.ui.theme.GateControlTvTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The page "Drive" with the remote: the areas, OK into a folder, ◀ ▶ through its cards, a file the TV cannot open. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
class TvDrivePageTest {

    @get:Rule
    val rule = createComposeRule()

    private val family = DArea("fam", "family", "Familie", "write", "member", rootId = 100, archived = false)
    private val mine = DArea("me", "mine", "Meine Dateien", "manage", "owner", rootId = 1, archived = false)

    private fun node(id: Long, name: String, mime: String? = null, dir: Boolean = false) = DNode(
        id = id, areaId = "fam", parentId = 100, name = name, isDir = dir, size = 2048, mime = mime, mediaId = if (dir) null else "media$id-abcdef",
        rev = 1, mtime = null, updatedAt = null, updatedBy = null,
    )

    private val items = listOf(node(2, "Urlaub", dir = true), node(3, "IMG_2041.jpg", "image/jpeg"), node(4, "Archiv.zip", "application/zip"))

    @Test
    fun `areas, OK into one, the remote through its cards`() {
        var ui by mutableStateOf(TvDriveUi(areas = listOf(mine, family)))
        val pressed = mutableListOf<Long>()
        rule.setContent {
            GateControlTvTheme {
                TvDrivePage(
                    ui = ui,
                    previews = emptyMap(),
                    onStart = {},
                    onArea = { a -> ui = ui.copy(stack = ui.stack.open(a.rootId, a.label), items = items) },
                    onItem = { n ->
                        pressed += n.id
                        TvDriveLogic.openOf(n)
                    },
                    onBack = { false },
                    onMore = {},
                    onPreview = {},
                    onRetry = {},
                )
            }
        }
        rule.onNodeWithText("Meine Dateien").assertExists()
        rule.onNodeWithText("Familie").assertExists()
        // the remote starts on the first area; ▶ OK opens "Familie"
        rule.onNodeWithContentDescription("Meine Dateien").assertIsFocused()
        rule.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        rule.onNodeWithContentDescription("Familie").assertIsFocused()
        rule.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        rule.waitForIdle()
        rule.onNodeWithText("› Familie").assertExists()
        rule.onNodeWithContentDescription("Urlaub").assertIsFocused()
        rule.onRoot().performKeyInput {
            pressKey(Key.DirectionRight)
            pressKey(Key.DirectionRight)
            pressKey(Key.DirectionCenter)
        }
        rule.waitForIdle()
        assertEquals(listOf(4L), pressed)
        // a ZIP: the TV says it cannot open it
        rule.onNodeWithText("Cannot be opened on the TV").assertExists()
    }

    @Test
    fun `a folder without anything and a problem`() {
        var ui by mutableStateOf(TvDriveUi(stack = TvDriveStack().open(100, "Familie")))
        rule.setContent {
            GateControlTvTheme {
                TvDrivePage(ui, emptyMap(), {}, {}, { TvDriveOpen.NONE }, { false }, {}, {}, {})
            }
        }
        rule.onNodeWithText("This folder is empty.").assertExists()
        ui = ui.copy(problem = com.gatecontrol.android.tv.drive.TvDriveProblem.OFFLINE)
        rule.waitForIdle()
        rule.onNodeWithText("No connection to the server.").assertExists()
        rule.onNodeWithText("Try again").assertExists()
    }
}
