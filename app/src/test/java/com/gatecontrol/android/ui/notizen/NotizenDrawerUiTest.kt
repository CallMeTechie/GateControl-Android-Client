package com.gatecontrol.android.ui.notizen

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.gatecontrol.android.R
import com.gatecontrol.android.notizen.NMe
import com.gatecontrol.android.notizen.NNav
import com.gatecontrol.android.notizen.NView
import com.gatecontrol.android.notizen.NotizenJson
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
 * The side menu, the menu of a list and the card „Heute ist Einkaufstag“ as drawn: what shows and what a 1.1 server
 * hides. With `NOTIZEN_SHOTS=<dir>` the drawn pictures are saved there (for a look, not part of CI).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp")
class NotizenDrawerUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val ctx: Application get() = ApplicationProvider.getApplicationContext()
    private fun s(id: Int, vararg args: Any) = ctx.getString(id, *args)

    private fun json(s: String) = JsonParser.parseString(s).asJsonObject

    private fun me(v12: Boolean): NMe = NotizenJson.me(
        json(
            """{"user":{"id":1,"name":"Chris Weber"},"lang":"de","today":"2026-11-14","sharing":"all","vaults":true,
            "features":["tasks","notes","vault"${if (v12) ",\"listDays\",\"shareRoles\",\"groupEdit\",\"deleteKeep\",\"listIcons\"" else ""}],
            "listIcons":["list","cart","home","tool","gift","plane","heart","work"]}""",
        ),
    )!!

    private val einkauf = """{"id":12,"name":"Einkaufsliste","area":"private","color":"green","icon":"cart","owner":{"id":1,"name":"Chris Weber"},"role":"owner",
        "canEdit":true,"shared":true,"members":[{"id":2,"name":"Anna Weber","role":"write"},{"id":3,"name":"Tom Weber","role":"read"}],"open":7,"days":[6],"dayToday":true}"""

    private val nav: NNav = NotizenJson.nav(
        json(
            """{"area":"private","smart":{"myday":5,"important":2,"planned":4,"assigned":3},
            "sources":[{"plugin":"gatecontrol-familie","label":"Familie","open":4},{"plugin":"gatecontrol-mail","label":"Mail","open":2}],
            "lists":[{"id":1,"name":"Aufgaben","isDefault":true,"role":"owner","open":6},$einkauf,
                     {"id":13,"name":"Baumarkt","role":"owner","open":3,"groupId":3,"color":"orange"},
                     {"id":14,"name":"Putzplan","role":"owner","open":2,"groupId":3,"color":"blue"},
                     {"id":20,"name":"Vereinsfest","role":"write","open":5,"shared":true,"owner":{"id":2,"name":"Anna Weber"}}],
            "groups":[{"id":3,"name":"Haushalt","area":"private","open":5}],
            "notes":{"total":38,"notebooks":[{"id":7,"name":"Notizen","isDefault":true,"role":"owner","count":21},{"id":8,"name":"Rezepte","role":"owner","count":9,"shared":true,"members":[{"id":2,"name":"Anna"}]}],"tags":[]}}""",
        ),
    )!!

    private val noActions = NotizenDrawerActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})

    private fun shot(name: String) {
        val dir = System.getenv("NOTIZEN_SHOTS") ?: return
        val bmp = rule.onNodeWithTag("shot").captureToImage().asAndroidBitmap()
        File(dir).mkdirs()
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun `the side menu - fixed views, my lists with a group, other plugins, notebooks, the gear`() {
        var settings = 0
        var listMenu: Long? = null
        rule.setContent {
            GateControlTheme(darkTheme = false) {
                Box(Modifier.width(320.dp).background(MaterialTheme.colorScheme.background).testTag("shot")) {
                    NotizenDrawer(
                        me(true), nav, "private", "myday", emptySet(), { it.name },
                        NotizenDrawerActions({}, {}, {}, {}, {}, { listMenu = it.id }, {}, {}, {}, {}, {}, { settings++ }),
                    )
                }
            }
        }
        rule.onNodeWithText("Einkaufsliste").assertIsDisplayed()
        rule.onNodeWithText("Haushalt").assertIsDisplayed()
        rule.onNodeWithText("Baumarkt").assertIsDisplayed()
        rule.onNodeWithText("Aufgaben").assertIsDisplayed()
        rule.onNodeWithText(s(R.string.notizen_my_lists).uppercase()).assertIsDisplayed()
        shot("drawer")
        rule.onNodeWithContentDescription(s(R.string.notizen_settings)).performClick()
        assertEquals(1, settings)
        assertEquals(null, listMenu)
    }

    @Test
    fun `the menu of a list - the list day only with plugin 1_2`() {
        val l = nav.lists.first { it.id == 12L }
        rule.setContent {
            GateControlTheme(darkTheme = false) {
                ListSheet(l, me(true), {}, {}, {}, {}, {}, {}, {})
            }
        }
        rule.onNodeWithText(s(R.string.notizen_list_day)).assertIsDisplayed()
        rule.onNodeWithText(s(R.string.notizen_list_delete_dots)).assertIsDisplayed()
        rule.onNodeWithText(s(R.string.notizen_share_dots)).assertIsDisplayed()
    }

    @Test
    fun `the menu of a list against plugin 1_1 - no list day`() {
        val l = nav.lists.first { it.id == 12L }
        rule.setContent {
            GateControlTheme(darkTheme = false) {
                ListSheet(l, me(false), {}, {}, {}, {}, {}, {}, {})
            }
        }
        rule.onNodeWithText(s(R.string.notizen_list_delete_dots)).assertIsDisplayed()
        assertTrue(rule.onAllNodesWithTextCount(s(R.string.notizen_list_day)) == 0)
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodesWithTextCount(t: String) =
        onAllNodes(androidx.compose.ui.test.hasText(t)).fetchSemanticsNodes().size

    @Test
    fun `my day - the card of the shopping day with its first entries`() {
        val day: NView = NotizenJson.view(
            json(
                """{"view":"myday","sections":[],"listdays":[{"list":$einkauf,"title":"Heute ist Einkaufstag","open":7,"assignedToMe":2,
                "items":[{"type":"task","id":31,"listId":12,"title":"Milch (2 l)","assignee":{"id":2,"name":"Anna Weber"}},
                         {"type":"task","id":32,"listId":12,"title":"Brot vom Bäcker","assignee":{"id":1,"name":"Chris Weber"}},
                         {"type":"task","id":33,"listId":12,"title":"Waschmittel"}],"more":4}]}""",
            ),
        )!!
        val nav = NotizenNav({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, { false }, {})
        rule.setContent {
            GateControlTheme(darkTheme = false) {
                Box(Modifier.width(411.dp).background(MaterialTheme.colorScheme.background).testTag("shot")) {
                    ListDayCard(day.listdays[0], me(true), {}, nav)
                }
            }
        }
        rule.onNodeWithText("Heute ist Einkaufstag").assertIsDisplayed()
        rule.onNodeWithText("Milch (2 l)").assertIsDisplayed()
        rule.onNodeWithText(s(R.string.notizen_listday_more, 4)).assertIsDisplayed()
        rule.onNodeWithText(s(R.string.notizen_listday_open_all)).assertIsDisplayed()
        shot("listday")
    }
}
