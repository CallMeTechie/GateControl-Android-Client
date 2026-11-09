package com.gatecontrol.android.notizen

import com.gatecontrol.android.navigation.NotizenRoutes
import com.gatecontrol.android.notify.model.PushMessage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** A tap on a push of Notizen & Aufgaben: what it names (plugin 1.1.0 and 1.0) and the way to its page. */
class NotizenPushTest {
    private val vaultId = "AAAAAAAAAAAAAAAAAAAAAA"

    private fun msg(topic: String, data: String?) = PushMessage(
        seq = 1, id = 1, eventId = "e", topic = topic, priority = "normal", title = "t", body = "",
        createdAt = null, expiresAt = null, collapseKey = null, silent = false, route = null, actions = emptyList(), dataJson = data,
    )

    private fun dest(topic: String, notizen: String) = NotizenPush.destination(msg("plugin:gatecontrol-notizen:$topic", """{"notizen":$notizen}"""))

    @Test
    fun `plugin 1_1 - kind and the ids`() {
        assertEquals("task:12@work", dest("reminders", """{"kind":"reminder","taskId":12,"listId":3,"area":"work","task":12}"""))
        assertEquals("task:12@private", dest("shared", """{"kind":"assigned","taskId":12,"listId":3,"area":"private","task":12}"""))
        assertEquals("list:3@work", dest("shared", """{"kind":"shared_list","listId":3,"area":"work","list":3}"""))
        assertEquals("notebook:7@private", dest("shared", """{"kind":"shared_notebook","notebookId":7,"area":"private","notebook":7}"""))
        assertEquals("tresor:7:$vaultId@private", dest("shared", """{"kind":"shared_notebook","notebookId":7,"vaultId":"$vaultId","area":"private","notebook":7}"""))
        assertEquals("vault:$vaultId@work", dest("vault", """{"kind":"vault_request","vaultId":"$vaultId","notebookId":7,"deviceId":"d","area":"work","vault":"$vaultId","notebook":7,"event":"request"}"""))
    }

    @Test
    fun `plugin 1_0 - the topic and the key say it`() {
        assertEquals("task:12", dest("reminders", """{"task":12}"""))
        assertEquals("task:12", dest("shared", """{"task":12}"""))
        assertEquals("list:3", dest("shared", """{"list":3}"""))
        assertEquals("notebook:7", dest("shared", """{"notebook":7}"""))
        assertEquals("vault:$vaultId", dest("vault", """{"vault":"$vaultId","notebook":7,"event":"request"}"""))
    }

    @Test
    fun `nothing for other plugins, broken ids, a kind of a later plugin`() {
        assertNull(NotizenPush.destination(msg("plugin:gatecontrol-drive:vault", """{"notizen":{"task":12}}""")))
        assertNull(NotizenPush.destination(msg("plugin:gatecontrol-notizen:reminders", null)))
        assertNull(NotizenPush.destination(msg("plugin:gatecontrol-notizen:reminders", "not json")))
        assertNull(dest("reminders", """{"kind":"reminder","taskId":-4}"""))
        assertNull(dest("reminders", """{"kind":"reminder","taskId":"12; drop"}"""))
        assertNull(dest("vault", """{"kind":"vault_request","vaultId":"../x"}"""))
        assertEquals("task:12", dest("shared", """{"kind":"later","taskId":12}"""), "a kind of a later plugin: read by the topic as in 1.0")
        assertNull(dest("reminders", """{"kind":"later","listId":3}"""))
        assertEquals("task:12", dest("reminders", """{"kind":"reminder","taskId":"12","area":"elsewhere"}"""), "ids as text are fine, an unknown area is left out")
    }

    @Test
    fun `destinations go into the intent and come back`() {
        val all = listOf(
            NotizenPush.Task(12, "work"), NotizenPush.ListPage(3), NotizenPush.Notebook(7, "private"),
            NotizenPush.Tresor(7, vaultId, "work"), NotizenPush.VaultRequest(vaultId),
        )
        all.forEach { assertEquals(it, NotizenPush.decode(NotizenPush.encode(it))) }
        assertNull(NotizenPush.decode("task:0"))
        assertNull(NotizenPush.decode("task:12@home"))
        assertNull(NotizenPush.decode("tresor:7:short"))
        assertNull(NotizenPush.decode("notizen/task/12"))
        assertNull(NotizenPush.decode(null))
    }

    @Test
    fun `the way - the tab, then the page, without the plugin only the tab (it says why)`() {
        val tab = NotizenPush.Tab
        assertEquals(listOf(tab, NotizenPush.Route(NotizenRoutes.task(12))), NotizenPush.steps("task:12@work", listed = true))
        assertEquals(listOf(tab, NotizenPush.Route(NotizenRoutes.view("list:3"))), NotizenPush.steps("list:3", listed = true))
        assertEquals(listOf(tab), NotizenPush.steps("notebook:7", listed = true), "the tab shows the notebook's notes")
        assertEquals(listOf(tab, NotizenPush.Route("notizen/vault/$vaultId")), NotizenPush.steps("tresor:7:$vaultId", listed = true), "the Tresor unlocks first")
        assertEquals(listOf(tab, NotizenPush.Route("notizen/vault/$vaultId?requests=1")), NotizenPush.steps("vault:$vaultId@work", listed = true))
        assertEquals(listOf(tab), NotizenPush.steps("task:12", listed = false), "plugin gone or not licensed")
        assertEquals(listOf(tab), NotizenPush.steps("bogus", listed = true))
    }

    @Test
    fun `the tab is handed the area and the notebook, and a hint once`() {
        NotizenDeepLink.request(NotizenPush.decode("notebook:7@work"))
        assertEquals(NotizenDeepLink.Open("work", 7), NotizenDeepLink.take())
        assertNull(NotizenDeepLink.take())
        NotizenDeepLink.request(NotizenPush.decode("tresor:9:$vaultId"))
        assertEquals(NotizenDeepLink.Open(null, 9), NotizenDeepLink.take())
        NotizenDeepLink.request(NotizenPush.decode("task:12@private"))
        assertEquals(NotizenDeepLink.Open("private", null), NotizenDeepLink.take())
        NotizenDeepLink.hint("gone")
        assertEquals("gone", NotizenDeepLink.takeHint())
        assertNull(NotizenDeepLink.takeHint())
    }
}
