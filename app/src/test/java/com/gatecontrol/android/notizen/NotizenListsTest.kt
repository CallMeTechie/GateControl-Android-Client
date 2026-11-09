package com.gatecontrol.android.notizen

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Plugin 1.2.0 (docs: notizen-1.2 API contract): lists with icon, days, roles; the list-day cards of „Mein Tag“;
 * usage, delete and restore; the new prefs — and the side menu, the list's menu and the filter built from them,
 * with every new ability gated by `me.features` (a 1.1.x server keeps working).
 */
class NotizenListsTest {

    private fun json(s: String): JsonObject = JsonParser.parseString(s).asJsonObject

    private fun me(features: String, sharing: String = "all", readOnly: Boolean = false) = NotizenJson.me(
        json("""{"user":{"id":1,"name":"Chris Weber"},"lang":"de","today":"2026-11-14","features":[$features],"sharing":"$sharing","readOnly":$readOnly,
            "listIcons":["list","cart","home","tool","gift","plane","heart","work","Bad Icon!"]}"""),
    )!!

    private val v11 = "\"tasks\",\"notes\",\"offlineEdits\",\"pushTargets\""
    private val v12 = "$v11,\"listDays\",\"shareRoles\",\"groupEdit\",\"deleteKeep\",\"listIcons\""

    private val listJson = """{"id":12,"name":"Einkaufsliste","area":"private","color":"green","icon":"cart","isDefault":false,
        "owner":{"id":1,"name":"Chris Weber"},"role":"owner","canEdit":true,"groupId":null,"position":2,"shared":true,
        "members":[{"id":2,"name":"Anna Weber","role":"write","since":1730600000000},{"id":3,"name":"Tom Weber","role":"read","since":null}],
        "open":7,"days":[6,6,9,"x",1],"remind":"09:00","mydayOff":false,"dayToday":true,"ext":null}"""

    private fun list(s: String = listJson) = NotizenJson.list(json(s))!!

    @Test
    fun `a list of 1_2 - icon, roles, days, reminder, my own switch`() {
        val l = list()
        assertEquals("cart", l.icon)
        assertTrue(l.canEdit)
        assertTrue(l.isOwner)
        assertEquals(listOf(1, 6), l.days, "1..7 only, each once, in order")
        assertEquals("09:00", l.remind)
        assertFalse(l.mydayOff)
        assertTrue(l.dayToday)
        assertEquals(listOf("write", "read"), l.members.map { it.role })
        assertEquals(1730600000000L, l.members[0].since)
        assertNull(l.members[1].since)
    }

    @Test
    fun `a list of 1_1 - no new fields, everyone may change, owner or write`() {
        val l = list("""{"id":4,"name":"Haushalt","area":"private","isDefault":false,"owner":{"id":1,"name":"C"},"role":"write","shared":true,"members":[{"id":1,"name":"C"}],"open":2}""")
        assertNull(l.icon)
        assertTrue(l.canEdit)
        assertFalse(l.isOwner)
        assertEquals(emptyList<Int>(), l.days)
        assertNull(l.remind)
        assertNull(l.members[0].role)
        val read = list("""{"id":5,"name":"Fest","role":"read","owner":{"id":9,"name":"Anna"}}""")
        assertFalse(read.canEdit, "a read member without canEdit: the role says it")
    }

    @Test
    fun `me - the list icons, bad ones dropped`() {
        assertEquals(listOf("list", "cart", "home", "tool", "gift", "plane", "heart", "work"), me(v12).listIcons)
        assertEquals(emptyList<String>(), NotizenJson.me(json("""{"user":{"id":1},"today":"2026-11-14"}"""))!!.listIcons)
    }

    @Test
    fun `prefs of 1_2 - my day, start area, notify - defaults without them`() {
        val p = NotizenJson.prefs(json("""{"area":"work","myday":{"listdays":false},"startArea":"private","notify":{"shared":false,"listday":false}}"""))
        assertEquals(NMyDayPrefs(listdays = false, showDone = true), p.myday)
        assertEquals("private", p.startArea)
        assertEquals(NNotifyPrefs(reminders = true, shared = false, listday = false), p.notify)
        val old = NotizenJson.prefs(json("""{"area":"work"}"""))
        assertEquals(NMyDayPrefs(), old.myday)
        assertEquals("last", old.startArea)
        assertEquals("last", NotizenJson.prefs(json("""{"startArea":"garden"}""")).startArea)
        val body = NotizenJson.prefsBody(times = mapOf("morning" to "07:30", "night" to "late"), myday = mapOf("showDone" to false), notify = mapOf("listday" to true), startArea = "work")
        assertEquals("""{"startArea":"work","times":{"morning":"07:30"},"myday":{"showDone":false},"notify":{"listday":true}}""", body.toString())
    }

    @Test
    fun `my day - the list-day cards on top, absent on 1_1`() {
        val v = NotizenJson.view(json("""{"view":"myday","title":"Mein Tag","date":"2026-11-14","sections":[{"id":"today","title":"Heute","items":[]}],
            "listdays":[{"list":$listJson,"title":"Heute ist Einkaufstag","open":7,"assignedToMe":2,
              "items":[{"type":"task","id":31,"listId":12,"title":"Milch (2 l)","assignee":{"id":2,"name":"Anna Weber"},"canComplete":true,"canEdit":true},
                       {"type":"task","id":32,"listId":12,"title":"Brot","canComplete":false,"canEdit":false}],"more":4},
              {"title":"kaputt"}]}"""))!!
        assertEquals(1, v.listdays.size, "a card without its list is left out")
        val c = v.listdays[0]
        assertEquals("Heute ist Einkaufstag", c.title)
        assertEquals(7, c.open)
        assertEquals(2, c.assignedToMe)
        assertEquals(4, c.more)
        assertEquals(listOf("Milch (2 l)", "Brot"), c.items.map { it.title })
        assertFalse(c.items[1].canComplete)
        assertFalse(c.items[1].canEdit)
        assertTrue(NotizenJson.view(json("""{"view":"myday","sections":[]}"""))!!.listdays.isEmpty())
        assertTrue(NotizenJson.task(json("""{"id":1,"title":"x"}"""))!!.canComplete, "1.1: every task may be ticked")
    }

    @Test
    fun `usage, delete answers, groups with open`() {
        val u = NotizenJson.listUsage(json("""{"ok":true,"open":7,"done":23,"members":[{"id":2,"name":"Anna","role":"write"}],"isDefault":false,"canDelete":true,"target":{"id":1,"name":"Aufgaben"}}"""))!!
        assertEquals(7, u.open)
        assertEquals(23, u.done)
        assertEquals("Aufgaben", u.target?.name)
        assertTrue(u.canDelete)
        val n = NotizenJson.notebookUsage(json("""{"notes":9,"withImages":2,"withFiles":0,"members":[],"isDefault":false,"canDelete":true,"vault":true,"target":null}"""))!!
        assertEquals(9, n.notes)
        assertTrue(n.vault)
        assertNull(n.target)
        assertNull(NotizenJson.listUsage(json("""{"ok":true}""")))
        val d = NotizenJson.deleted(json("""{"ok":true,"deleted":true,"moved":7,"removed":23,"target":{"id":1,"name":"Aufgaben"},"restoreUntil":1733000000000}"""))
        assertEquals(NDeleted(false, 7, 23, NRef(1, "Aufgaben"), 1733000000000L), d)
        assertNull(NotizenJson.deleted(json("""{"ok":true}""")).restoreUntil, "1.1: no undo")
        assertTrue(NotizenJson.deleted(json("""{"ok":true,"left":true}""")).left)
        assertEquals(5, NotizenJson.group(json("""{"id":3,"name":"Haushalt","area":"private","position":0,"open":5}"""))!!.open)
        assertNull(NotizenJson.group(json("""{"id":3,"name":"Haushalt"}"""))!!.open)
    }

    @Test
    fun `bodies - members with or without roles, a list's changes, a group with lists`() {
        assertEquals("""{"members":[{"id":2,"role":"write"},{"id":3,"role":"read"}]}""", NotizenJson.membersBody(listOf(2L to "write", 3L to "read"), roles = true).toString())
        assertEquals("""{"members":[2,3]}""", NotizenJson.membersBody(listOf(2L to "write", 3L to "read"), roles = false).toString())
        assertEquals(
            """{"name":"Einkauf","color":null,"icon":"cart","days":[1,6],"remind":"09:00","groupId":null}""",
            com.google.gson.GsonBuilder().serializeNulls().create().toJson(
                NotizenJson.listPatch(name = " Einkauf ", clearColor = true, icon = "cart", days = listOf(6, 1, 6, 8), remind = "09:00", clearGroup = true),
            ),
        )
        assertEquals("""{"mydayOff":true}""", NotizenJson.listPatch(mydayOff = true).toString())
        assertEquals("""{"name":"Haushalt","area":"private","lists":[4,5]}""", NotizenJson.groupBody("Haushalt", "private", listOf(4, 5)).toString())
        assertEquals("""{"name":"Haushalt","area":"work"}""", NotizenJson.groupBody("Haushalt", "work", emptyList()).toString())
    }

    // ── the menu of a list: feature gating ───────────────────

    @Test
    fun `the owner's menu - with 1_2 the list day, without it not`() {
        val l = list()
        assertEquals(
            listOf(NotizenLists.Action.EDIT, NotizenLists.Action.SHARE, NotizenLists.Action.MOVE, NotizenLists.Action.DAYS, NotizenLists.Action.DELETE),
            NotizenLists.actions(l, me(v12)),
        )
        assertEquals(listOf(NotizenLists.Action.EDIT, NotizenLists.Action.SHARE, NotizenLists.Action.MOVE, NotizenLists.Action.DELETE), NotizenLists.actions(l, me(v11)))
        assertEquals(listOf(NotizenLists.Action.EDIT, NotizenLists.Action.MOVE, NotizenLists.Action.DAYS, NotizenLists.Action.DELETE), NotizenLists.actions(l, me(v12, sharing = "off")), "no sharing allowed")
        val default = l.copy(isDefault = true)
        assertEquals(listOf(NotizenLists.Action.EDIT, NotizenLists.Action.DAYS), NotizenLists.actions(default, me(v12)), "the standard list: rename only, not delete")
        assertEquals(emptyList<NotizenLists.Action>(), NotizenLists.actions(l, me(v12, readOnly = true)))
    }

    @Test
    fun `a member's menu - move, my day (1_2), leave`() {
        val l = list().copy(role = "write", owner = NPerson(9, "Anna"))
        assertEquals(listOf(NotizenLists.Action.MOVE, NotizenLists.Action.MYDAY, NotizenLists.Action.LEAVE), NotizenLists.actions(l, me(v12)))
        assertEquals(listOf(NotizenLists.Action.MOVE, NotizenLists.Action.LEAVE), NotizenLists.actions(l, me(v11)))
    }

    @Test
    fun `read only - no adding, ticking only what is assigned to me`() {
        val ro = list().copy(role = "read", canEdit = false, owner = NPerson(9, "Anna"))
        val meNow = me(v12)
        assertFalse(NotizenLists.canAdd(ro, meNow))
        assertTrue(NotizenLists.canAdd(list(), meNow))
        assertTrue(NotizenLists.canAdd(null, meNow), "a view that is no list (Wichtig …)")
        assertFalse(NotizenLists.canAdd(list(), me(v12, readOnly = true)))
        val mine = task(1, NPerson(1, "Chris"))
        val anna = task(2, NPerson(9, "Anna"))
        assertTrue(NotizenLists.canTick(mine, ro, 1))
        assertFalse(NotizenLists.canTick(anna, ro, 1))
        assertFalse(NotizenLists.canTick(anna.copy(canComplete = false), list(), 1), "the server says no")
        assertTrue(NotizenLists.canTick(anna, list(), 1))
    }

    private fun task(id: Long, who: NPerson?) = NotizenJson.task(json("""{"id":$id,"title":"t$id"}"""))!!.copy(assignee = who)

    // ── the side menu ─────────────────────────────────────────

    private fun nav(): NNav = NotizenJson.nav(
        json("""{"area":"private","smart":{"myday":5,"important":2,"planned":4,"assigned":3},"sources":[],
            "lists":[{"id":1,"name":"Aufgaben","isDefault":true,"role":"owner","open":6},
                     {"id":12,"name":"Einkaufsliste","role":"owner","open":7,"position":1},
                     {"id":13,"name":"Baumarkt","role":"owner","open":3,"groupId":3,"position":1},
                     {"id":14,"name":"Putzplan","role":"owner","open":2,"groupId":3,"position":2},
                     {"id":15,"name":"Lost","role":"owner","open":1,"groupId":99,"position":5}],
            "groups":[{"id":3,"name":"Haushalt","area":"private","position":0}],
            "notes":{"total":38,"notebooks":[],"tags":[]}}"""),
    )!!

    @Test
    fun `my lists - without the standard list, groups as folders that open and close`() {
        val n = nav()
        assertEquals(1L, NotizenLists.defaultList(n)?.id)
        val open = NotizenLists.rows(n, emptySet())
        assertEquals(listOf("l12", "l15", "g3", "l13", "l14"), open.map { it.key }, "a list of an unknown group stands without one")
        val g = open[2] as NotizenLists.Row.Group
        assertEquals(5, g.open, "1.1: the sum of its lists")
        assertEquals(2, g.lists)
        assertEquals(1, (open[3] as NotizenLists.Row.ListRow).depth)
        assertEquals(listOf("l12", "l15", "g3"), NotizenLists.rows(n, setOf(3L)).map { it.key })
        assertEquals(9, NotizenLists.groupOpen(n.groups[0].copy(open = 9), n.lists), "1.2: the server's count")
        assertTrue(NotizenLists.rows(null, emptySet()).isEmpty())
    }

    @Test
    fun `shared marks and the people of a list`() {
        val l = list()
        assertEquals(NotizenLists.Shared.BY_ME, NotizenLists.shared(l))
        assertEquals(NotizenLists.Shared.WITH_ME, NotizenLists.shared(l.copy(role = "read")))
        assertEquals(NotizenLists.Shared.NO, NotizenLists.shared(l.copy(shared = false)))
        assertEquals(listOf(1L, 2L, 3L), NotizenLists.people(l).map { it.id })
        assertEquals(listOf("Anna Weber", "Tom Weber"), NotizenLists.others(l, 1))
    }

    // ── the filter of a shared list ───────────────────────────

    @Test
    fun `filter chips - all, mine, each person with tasks, nobody`() {
        val l = list()
        val tasks = listOf(task(1, NPerson(1, "Chris")), task(2, NPerson(2, "Anna")), task(3, NPerson(2, "Anna")), task(4, null), task(5, null).copy(done = true))
        val f = NotizenLists.filters(l, tasks, 1)
        assertEquals(
            listOf(NotizenLists.Filter.All to 4, NotizenLists.Filter.Mine to 1, NotizenLists.Filter.Person(2, "Anna Weber") to 2, NotizenLists.Filter.Nobody to 1),
            f,
            "Tom has none: no chip of his own; done tasks are not counted",
        )
        assertTrue(NotizenLists.matches(tasks[3], NotizenLists.Filter.Nobody, 1))
        assertFalse(NotizenLists.matches(tasks[1], NotizenLists.Filter.Mine, 1))
        assertTrue(NotizenLists.filters(l.copy(shared = false), tasks, 1).isEmpty())
        assertTrue(NotizenLists.filters(null, tasks, 1).isEmpty())
    }

    // ── sharing ──────────────────────────────────────────────

    @Test
    fun `share - candidates and the members after a change`() {
        val people = listOf(NPersonPick(1, "Chris Weber", null, null, true), NPersonPick(2, "Anna Weber", null, null, false), NPersonPick(4, "Lisa Weber", null, null, false), NPersonPick(5, "Oma Gerda", null, null, false))
        val members = list().members
        assertEquals(listOf(4L, 5L), NotizenLists.candidates(people, members, 1, "").map { it.id })
        assertEquals(listOf(4L), NotizenLists.candidates(people, members, 1, "  li ").map { it.id })
        assertEquals(listOf(2L to "write", 3L to "read", 4L to "write"), NotizenLists.membersAfter(members, add = 4))
        assertEquals(listOf(2L to "write"), NotizenLists.membersAfter(members, remove = 3))
        assertEquals(listOf(2L to "read", 3L to "read"), NotizenLists.membersAfter(members, role = 2L to "read"))
        assertEquals(listOf(2L to "write"), NotizenLists.membersAfter(listOf(NPerson(2, "Anna")), add = 2), "1.1 members (no role) are writers; adding twice adds once")
    }

    // ── notebooks ────────────────────────────────────────────

    @Test
    fun `notebook menu and the Tresor's name check`() {
        val nb = NotizenJson.notebook(json("""{"id":7,"name":"Rezepte","role":"owner","shared":true,"members":[{"id":2,"name":"Anna","role":"read"}],"count":9}"""))!!
        assertEquals(listOf(NotizenLists.NotebookAction.EDIT, NotizenLists.NotebookAction.SHARE, NotizenLists.NotebookAction.DELETE), NotizenLists.notebookActions(nb, me(v12)))
        assertEquals(listOf(NotizenLists.NotebookAction.EDIT), NotizenLists.notebookActions(nb.copy(isDefault = true), me(v12, sharing = "off")))
        assertEquals(listOf(NotizenLists.NotebookAction.LEAVE), NotizenLists.notebookActions(nb.copy(role = "read", canEdit = false), me(v12)))
        assertEquals(listOf(NotizenLists.NotebookAction.EDIT), NotizenLists.notebookActions(nb.copy(bound = NBound("gatecontrol-familie", "k", null, null)), me(v12)))
        assertFalse(NotizenJson.notebook(json("""{"id":8,"role":"read"}"""))!!.canEdit)
        assertTrue(NotizenLists.nameConfirmed(" versicherungen ", "Versicherungen"))
        assertFalse(NotizenLists.nameConfirmed("Versicherung", "Versicherungen"))
        assertFalse(NotizenLists.nameConfirmed("", null), "a locked Tresor: its name cannot be read")
    }

    @Test
    fun `the features of 1_2 are read from me`() {
        val m = me(v12)
        listOf(NFeature.LIST_DAYS, NFeature.SHARE_ROLES, NFeature.GROUP_EDIT, NFeature.DELETE_KEEP, NFeature.LIST_ICONS).forEach { assertTrue(m.has(it), it) }
        val old = me(v11)
        listOf(NFeature.LIST_DAYS, NFeature.SHARE_ROLES, NFeature.GROUP_EDIT, NFeature.DELETE_KEEP, NFeature.LIST_ICONS).forEach { assertFalse(old.has(it), it) }
        assertNotNull(old.prefs)
    }
}
