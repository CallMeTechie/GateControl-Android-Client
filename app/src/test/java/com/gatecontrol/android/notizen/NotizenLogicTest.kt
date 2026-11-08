package com.gatecontrol.android.notizen

import com.gatecontrol.android.familie.PluginAvailability
import com.gatecontrol.android.layout.StartPages
import com.gatecontrol.android.navigation.MainTabs
import com.gatecontrol.android.navigation.NotizenRoutes
import com.gatecontrol.android.navigation.Screen
import com.gatecontrol.android.network.ClientPlugin
import com.gatecontrol.android.ui.notizen.NotizenShare
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The tab, the offline outbox, the dates of the rows and the share target. */
class NotizenLogicTest {

    @Test
    fun `the tab comes after Mail, only when the server lists the plugin, and can be a page`() {
        assertTrue(PluginAvailability.from(listOf(ClientPlugin(id = NOTIZEN_PLUGIN))).notizen)
        assertFalse(PluginAvailability.from(emptyList()).notizen)
        val all = PluginAvailability(messengerTab = true, mail = true, notizen = true, drive = true)
        assertEquals(listOf(Screen.Vpn.route, Screen.Chats.route, Screen.Mail.route, NotizenRoutes.TAB, Screen.Drive.route), MainTabs.routes(network = false, plugins = all))
        assertFalse(NotizenRoutes.TAB in MainTabs.routes(network = false, plugins = all.copy(notizen = false)))
        assertTrue(NotizenRoutes.TAB in MainTabs.BAR_ROUTES)
        assertEquals(StartPages.NOTIZEN, MainTabs.pageOf(NotizenRoutes.TAB))
        assertEquals(NotizenRoutes.TAB, MainTabs.routeOf(StartPages.NOTIZEN))
        assertEquals(StartPages.NOTIZEN, MainTabs.pageFor(NotizenRoutes.TAB))
        assertNull(MainTabs.pageFor(NotizenRoutes.task(5)), "a task lies on top of the tab")
        assertTrue(StartPages.isFeature("notizen"))
        assertEquals("notizen/view/list%3A12", NotizenRoutes.view("list:12"))
        assertEquals("notizen/quick?mode=note", NotizenRoutes.quick(true))
        assertEquals("notizen/note/0?notebook=4", NotizenRoutes.note(0, 4))
    }

    @Test
    fun `ticks made offline wait once per thing and show over the server's copy`() {
        var ops = emptyList<NotizenOutbox.Op>()
        ops = NotizenOutbox.add(ops, NotizenOutbox.Op("task", 5, true, 1))
        ops = NotizenOutbox.add(ops, NotizenOutbox.Op("item", 5, true, 2))
        ops = NotizenOutbox.add(ops, NotizenOutbox.Op("task", 5, false, 3))
        assertEquals(listOf(NotizenOutbox.Op("item", 5, true, 2), NotizenOutbox.Op("task", 5, false, 3)), ops)
        assertEquals(false, NotizenOutbox.pending(ops, "t:5"))
        assertEquals(true, NotizenOutbox.pending(ops, "s:5"))
        assertNull(NotizenOutbox.pending(ops, "t:6"))
        assertEquals(ops, ops.map { NotizenOutbox.fromJson(NotizenOutbox.toJson(it))!! })
        val task = NTask(5, 1, null, "A", false, false, true, null, null, false, null, null, null, null, false, null, 0, null, null, 0, 0)
        val view = NView("myday", "Mein Tag", listOf(NSection("today", "Heute", 1, listOf(task), false)))
        assertFalse((NotizenOutbox.overlay(view, ops).sections[0].items[0] as NTask).done)
        assertEquals(NotizenOutbox.MAX, (1..600).fold(emptyList<NotizenOutbox.Op>()) { l, i -> NotizenOutbox.add(l, NotizenOutbox.Op("task", i.toLong(), true, 0)) }.size)
    }

    @Test
    fun `dates of the rows`() {
        val w = Triple("heute", "morgen", "gestern")
        assertEquals("heute 9:00", NotizenFormat.due(NDue("2026-11-08", "09:00"), "2026-11-08", "de", w))
        assertEquals("morgen", NotizenFormat.due(NDue("2026-11-09"), "2026-11-08", "de", w))
        assertEquals("gestern", NotizenFormat.due(NDue("2026-11-07"), "2026-11-08", "de", w))
        assertEquals("Fr. 13. Nov.", NotizenFormat.due(NDue("2026-11-13"), "2026-11-08", "de", w))
        assertEquals("Fri, Nov 13", NotizenFormat.due(NDue("2026-11-13"), "2026-11-08", "en", Triple("today", "tomorrow", "yesterday")))
        assertEquals("Sonntag, 8. November", NotizenFormat.dayTitle("2026-11-08", "de"))
        assertEquals("Sunday, November 8", NotizenFormat.dayTitle("2026-11-08", "en"))
        assertEquals(NDue("2026-11-09", "14:00"), NotizenFormat.remindParts("2026-11-09T14:00:00+01:00"))
        assertEquals("1,4 GB", NotizenFormat.bytes(1_500_000_000L, "de"))
        assertEquals("512 B", NotizenFormat.bytes(512, "en"))
    }

    @Test
    fun `a share becomes a title and a text`() {
        assertEquals("Rezept" to "https://example.com/r", NotizenShare.split("Rezept", "https://example.com/r"))
        assertEquals("Einkauf" to "Milch\nBrot", NotizenShare.split(null, "Einkauf\nMilch\nBrot"))
        assertEquals("https://example.com" to "", NotizenShare.split(" ", "https://example.com"))
    }
}
