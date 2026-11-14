package com.gatecontrol.android.notizen

import com.gatecontrol.android.familie.FamilieJson
import com.gatecontrol.android.familie.shopping.ShoppingCompleted
import com.gatecontrol.android.familie.shopping.ShoppingItem
import com.gatecontrol.android.familie.shopping.ShoppingList
import com.gatecontrol.android.familie.shopping.ShoppingOp
import com.gatecontrol.android.familie.shopping.ShoppingSnapshot
import com.gatecontrol.android.familie.shopping.ShoppingTaskRef
import com.gatecontrol.android.navigation.NotizenRoutes
import com.gatecontrol.android.notify.model.PushMessage
import com.gatecontrol.android.ui.notizen.QuickAttach
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * „Aufgaben und Erinnerungen“, stage 2 (plugin 1.4.0 / Familie 1.21.0, the apps' contract): the words in `features`,
 * the shopping lists of the hub, the list a task carries, the suggestion of the quick entry, „alles abgehakt“, the
 * reminder's „Liste öffnen“ and where the family's card opens — and that all stays as it was with older plugins.
 */
class NotizenStage2Test {
    private fun json(s: String): JsonObject = JsonParser.parseString(s).asJsonObject

    private fun me(features: String, extra: String = "") = NotizenJson.me(json("""{"ok":true,"user":{"id":1,"name":"Anna Berg"},"lang":"de","today":"2026-11-14","time":"10:14",
        "timezone":"Europe/Berlin","features":[$features],"prefs":{"area":"private"},"sharing":"all","vaults":false,"imports":{},"attachMB":25,"readOnly":false$extra}"""))!!

    private val uid = "6f1c2a90-1111-4c1d-9a00-000000000001"
    private val attachedJson = """{"id":12,"uid":"$uid","name":"Einkauf","icon":"cart","open":8,"total":8,"done":0,"family":true}"""
    private val attached = NAttached(12, uid, "Einkauf", "cart", 8, 8, 0, true)

    // ── recognising the plugin ──────────────────────────────

    @Test
    fun `the abilities are the words in features, never a version`() {
        val old = me(""""tasks","reminderActions","yesterdayOpen","taskCalendar"""")
        assertFalse(old.shoppingLists)
        assertFalse(old.taskList)
        assertFalse(old.familyShopping)
        assertNull(old.shoppingFamily)
        val new = me(""""tasks","shoppingLists","taskList","familyShopping"""", ""","shopping":{"family":{"available":true,"bound":true,"parent":true}}""")
        assertTrue(new.shoppingLists)
        assertTrue(new.taskList)
        assertTrue(new.familyShopping)
        assertEquals(NShoppingFamily(available = true, bound = true, parent = true), new.shoppingFamily)
        // each word by itself: a list at a task needs the lists, the family's buttons need the Familie plugin offering them
        assertTrue(me(""""shoppingLists"""").shoppingLists)
        assertFalse(me(""""shoppingLists"""").taskList)
        assertFalse(me(""""taskList"""").taskList)
        assertFalse(me(""""shoppingLists","familyShopping"""").familyShopping, "no me.shopping.family")
        assertFalse(me(""""shoppingLists","familyShopping"""", ""","shopping":{"family":{"available":false,"bound":false,"parent":false}}""").familyShopping)
        assertFalse(me(""""familyShopping"""", ""","shopping":{"family":{"available":true}}""").familyShopping)
    }

    // ── the list a task carries ─────────────────────────────

    @Test
    fun `attachedList of a task, null and what cannot be read`() {
        val t = NotizenJson.task(json("""{"id":41,"listId":3,"title":"Bei Lidl einkaufen","attachedList":$attachedJson}"""))!!
        assertEquals(attached, t.attachedList)
        assertNull(NotizenJson.task(json("""{"id":41,"title":"Bei Lidl einkaufen","attachedList":null}"""))!!.attachedList)
        assertNull(NotizenJson.task(json("""{"id":41,"title":"Bei Lidl einkaufen"}"""))!!.attachedList, "a plugin without the field")
        // without its number or its id it is none; numbers are kept in range
        assertNull(NotizenJson.attached(json("""{"uid":"$uid","name":"Einkauf","open":1,"total":1}""")))
        assertNull(NotizenJson.attached(json("""{"id":12,"uid":"kurz","name":"Einkauf"}""")))
        assertNull(NotizenJson.attached(json("""{"id":12,"uid":"../../etc/passwd","name":"Einkauf"}""")))
        val odd = NotizenJson.attached(json("""{"id":12,"uid":"$uid","name":"Einkauf","open":9,"total":8}"""))!!
        assertEquals(8, odd.open)
        assertEquals(0, odd.done)
        assertEquals(5, NotizenJson.attached(json("""{"id":12,"uid":"$uid","name":"Einkauf","open":3,"total":8}"""))!!.done, "done from open and total")
    }

    @Test
    fun `attaching and taking off - the body and the base of a change`() {
        assertEquals("""{"attachedListId":12}""", NotizenJson.patch("attachedListId" to 12L).toString())
        assertEquals("""{"attachedListId":null}""", com.google.gson.GsonBuilder().serializeNulls().create().toJson(NotizenJson.patch("attachedListId" to null)))
        val task = NotizenJson.task(json("""{"id":41,"title":"Bei Lidl einkaufen","attachedList":$attachedJson}"""))!!
        val detail = NTaskDetail(task, null, emptyList(), null, null, emptyList(), null)
        assertEquals("""{"attachedListId":12}""", NotizenOutbox.taskBase(detail, listOf("attachedListId")).toString())
        val bare = detail.copy(task = task.copy(attachedList = null))
        assertTrue(NotizenOutbox.taskBase(bare, listOf("attachedListId")).get("attachedListId").isJsonNull)
    }

    // ── the shopping lists of the hub ───────────────────────

    private val shopping = """{"ok":true,"serverTime":"2026-11-14T10:14:07+01:00",
        "lists":[{"id":"$uid","listId":12,"name":"Einkauf","icon":"cart","area":"private","sort":0,"updatedAt":"2026-11-14T09:58:00+01:00","deleted":false,
          "role":"write","canEdit":true,"canManage":false,"owner":{"id":1,"name":"Anna Berg"},"shared":true,"members":[{"id":2,"name":"Marc Berg","role":"write"}],
          "family":true,"open":8,"total":8,"assignee":null,"dueAt":null,"going":[],"tasks":[{"id":41,"title":"Bei Lidl einkaufen"}],"days":[6],"remind":"08:00","mydayOff":false,"dayToday":true},
         {"id":"0b0b0b0b-2222-4c1d-9a00-000000000002","listId":13,"name":"Baumarkt","icon":null,"area":"work","sort":1,"deleted":false,"role":"read","canEdit":false,"canManage":false,
          "owner":{"id":2,"name":"Marc Berg"},"shared":true,"members":[{"id":1,"name":"Anna Berg","role":"read"}],"family":false,"open":2,"total":2,"tasks":[]},
         {"id":"0c0c0c0c-3333-4c1d-9a00-000000000003","listId":9,"name":"Alt","icon":null,"area":"private","updatedAt":"2026-11-14T09:00:00+01:00","deleted":true}],
        "items":[{"id":"0a0b0c0d-0000-4000-8000-000000000001","listId":"$uid","text":"Milch","qty":"2 l","checked":false,"checkedBy":null,"updatedAt":"2026-11-14T09:58:00+01:00","deleted":false},
          {"id":"0a0b0c0d-0000-4000-8000-000000000002","listId":"$uid","text":"Brot","qty":null,"checked":true,"checkedBy":2,"deleted":false}]}"""

    @Test
    fun `GET shopping of the hub - who may do what, the tasks, the time with its offset`() {
        val s = FamilieJson.shopping(json(shopping))
        assertEquals("2026-11-14T10:14:07+01:00", s.serverTime, "sent back unchanged as the next since")
        assertNull(s.home, "home is Familie's word")
        val l = s.lists[0]
        assertEquals(uid, l.id)
        assertEquals(12L, l.listNo)
        assertEquals("private", l.area)
        assertTrue(l.family && l.shared)
        assertTrue(l.editable)
        assertFalse(l.manageable)
        assertEquals("Anna Berg", l.ownerName)
        assertEquals(listOf("Marc Berg"), l.memberNames)
        assertEquals(listOf(ShoppingTaskRef(41, "Bei Lidl einkaufen")), l.tasks)
        val ro = s.lists[1]
        assertFalse(ro.editable)
        assertEquals("work", ro.area)
        assertTrue(s.lists[2].deleted)
        assertEquals(listOf("Milch", "Brot"), s.items.map { it.text })
        assertEquals("2", s.items[1].checkedBy, "the hub names who by number")
    }

    @Test
    fun `a list of the Familie plugin has none of the hub's fields and may be changed as before`() {
        val s = FamilieJson.shopping(json("""{"ok":true,"serverTime":"2026-11-14T09:14:07Z","lists":[{"id":"$uid","name":"Einkauf","sort":0}],"items":[]}"""))
        val l = s.lists.single()
        assertNull(l.listNo)
        assertNull(l.canEdit)
        assertTrue(l.editable && l.manageable)
        assertFalse(l.family)
        assertNull(s.home)
        // a copy kept by an older app version (Gson leaves the new fields unset) reads the same
        val kept = com.google.gson.Gson().fromJson("""{"id":"$uid","name":"Einkauf","sort":0,"deleted":false}""", ShoppingList::class.java)
        assertTrue(kept.editable && kept.manageable)
        assertNull(kept.tasks)
        assertNull(kept.memberNames)
    }

    @Test
    fun `nav names the shopping lists of the area, an older plugin none`() {
        val nav = NotizenJson.nav(json("""{"area":"private","smart":{"myday":1},"lists":[],"groups":[],
            "shopping":[{"id":"$uid","listId":12,"name":"Einkauf","icon":"cart","open":8,"total":8,"family":true,"shared":true,"role":"write"},{"id":"x","name":"kaputt"}]}"""))!!
        assertEquals(listOf(NNavShop(uid, 12, "Einkauf", "cart", 8, 8, family = true, shared = true, role = "write")), nav.shopping)
        assertTrue(NotizenJson.nav(json("""{"area":"private","smart":{},"lists":[],"groups":[]}"""))!!.shopping.isEmpty())
    }

    @Test
    fun `the ops body carries area and family of a new list only when given`() {
        val body = FamilieJson.opsBody(
            listOf(
                ShoppingOp(ShoppingOp.LIST_ADD, id = uid, name = "Einkauf", icon = "cart", area = "private", family = true),
                ShoppingOp(ShoppingOp.ITEM_CHECK, id = "0a0b0c0d-0000-4000-8000-000000000001", checked = true),
                ShoppingOp(ShoppingOp.CLEAR_CHECKED, listId = uid),
            ),
        ).toString()
        assertEquals(
            """{"ops":[{"op":"list_add","id":"$uid","name":"Einkauf","icon":"cart","area":"private","family":true},""" +
                """{"op":"item_check","id":"0a0b0c0d-0000-4000-8000-000000000001","checked":true},{"op":"clear_checked","listId":"$uid"}]}""",
            body,
        )
    }

    @Test
    fun `the side menu - the lists of the area with what is still open on the phone`() {
        val einkauf = ShoppingList(uid, "Einkauf", area = "private", family = true)
        val buero = ShoppingList("list-buero-0001", "Büro", area = "work")
        val neu = ShoppingList("list-neu-00001", "Neu") // made by an older copy: no area → private
        val view = ShoppingSnapshot(
            listOf(einkauf, buero, neu),
            listOf(ShoppingItem("i1", uid, "Milch"), ShoppingItem("i2", uid, "Brot", checked = true), ShoppingItem("i3", "list-buero-0001", "Papier")),
        )
        assertEquals(listOf("Einkauf" to 1, "Neu" to 0), NotizenShoppingLogic.menu(view, NArea.PRIVATE).map { it.first.name to it.second })
        assertEquals(listOf("Büro" to 1), NotizenShoppingLogic.menu(view, NArea.WORK).map { it.first.name to it.second })
    }

    @Test
    fun `whom a list is shared with - the family, only me, or the names`() {
        assertNull(NotizenShoppingLogic.sharedWith(ShoppingList(uid, "Einkauf", family = true, shared = true), "Anna Berg"))
        assertEquals(emptyList<String>(), NotizenShoppingLogic.sharedWith(ShoppingList(uid, "Baumarkt"), "Anna Berg"))
        assertEquals(
            listOf("Marc Berg", "Mia"),
            NotizenShoppingLogic.sharedWith(ShoppingList(uid, "Drogerie", shared = true, ownerName = "Marc Berg", memberNames = listOf("Anna Berg", "Mia")), "Anna Berg"),
        )
    }

    // ── „alles abgehakt“ ────────────────────────────────────

    @Test
    fun `the last open entry - the server's answer is fetched at once`() {
        val view = ShoppingSnapshot(
            listOf(ShoppingList(uid, "Einkauf")),
            listOf(ShoppingItem("i1", uid, "Milch", checked = true), ShoppingItem("i2", uid, "Brot"), ShoppingItem("i9", "other-list-01", "Seife")),
        )
        val tick = { id: String, on: Boolean -> ShoppingOp(ShoppingOp.ITEM_CHECK, id = id, checked = on) }
        assertTrue(NotizenShoppingLogic.completes(view, tick("i2", true)))
        assertFalse(NotizenShoppingLogic.completes(view, tick("i2", false)), "unticking")
        assertFalse(NotizenShoppingLogic.completes(view, tick("i1", true)), "ticked already")
        assertFalse(NotizenShoppingLogic.completes(view, ShoppingOp(ShoppingOp.ITEM_DELETE, id = "i2")), "deleting the last open one completes nothing")
        assertFalse(NotizenShoppingLogic.completes(view, ShoppingOp(ShoppingOp.CLEAR_CHECKED, listId = uid)))
        val two = view.copy(items = view.items + ShoppingItem("i3", uid, "Butter"))
        assertFalse(NotizenShoppingLogic.completes(two, tick("i2", true)), "one more is open")
        assertFalse(NotizenShoppingLogic.completes(view, tick("unknown", true)))
    }

    @Test
    fun `the sentence names the tasks of the list shown, each once`() {
        val done = listOf(ShoppingCompleted(41, "Bei Lidl einkaufen", uid), ShoppingCompleted(41, "Bei Lidl einkaufen", uid), ShoppingCompleted(50, "Drogerie", "other-list-01"))
        assertEquals(listOf(41L), NotizenShoppingLogic.completedFor(done, uid).map { it.taskId })
        assertEquals(listOf(41L, 50L), NotizenShoppingLogic.completedFor(done, null).map { it.taskId })
        assertTrue(NotizenShoppingLogic.completedFor(emptyList(), uid).isEmpty())
        // „Rückgängig“ is the plain taking back of a task
        assertEquals("""{"done":false}""", NotizenJson.doneBody(false).toString())
    }

    // ── the suggestion of the quick entry ───────────────────

    @Test
    fun `attach of POST parse - one list, several, none`() {
        val one = NotizenJson.attach(json("""{"via":"word","word":"einkaufen","list":$attachedJson,"lists":[$attachedJson]}"""))!!
        assertEquals(NAttach("word", "einkaufen", attached, listOf(attached)), one)
        val drogerie = """{"id":13,"uid":"0b0b0b0b-2222-4c1d-9a00-000000000002","name":"Drogerie","icon":null,"open":3,"total":3,"done":0,"family":true}"""
        val several = NotizenJson.attach(json("""{"via":"word","word":"besorgen","list":null,"lists":[$attachedJson,$drogerie]}"""))!!
        assertNull(several.list)
        assertEquals(listOf("Einkauf", "Drogerie"), several.lists.map { it.name })
        assertNull(NotizenJson.attach(null))
        assertNull(NotizenJson.attach(json("""{"via":"magic","list":$attachedJson,"lists":[]}""")), "an unknown way")
        assertNull(NotizenJson.attach(json("""{"via":"word","list":null,"lists":[]}""")))
        assertEquals("""{"text":"bei Lidl einkaufen","area":"private"}""", NotizenJson.parseBody("bei Lidl einkaufen", "private").toString())
    }

    @Test
    fun `the chip - suggested, taken away, swapped, or the choice`() {
        val drogerie = attached.copy(id = 13, uid = "0b0b0b0b-2222-4c1d-9a00-000000000002", name = "Drogerie")
        val one = NAttach("word", "einkaufen", attached, listOf(attached))
        val several = NAttach("word", "besorgen", null, listOf(attached, drogerie))
        assertEquals(attached, QuickAttach.effective(one, null, off = false))
        assertNull(QuickAttach.effective(one, null, off = true), "the person took it away")
        assertEquals(drogerie, QuickAttach.effective(one, drogerie, off = false), "swapped")
        assertNull(QuickAttach.effective(several, null, off = false), "several fit: nothing is attached unseen")
        assertTrue(QuickAttach.asks(several, null, off = false))
        assertFalse(QuickAttach.asks(several, drogerie, off = false))
        assertFalse(QuickAttach.asks(one, null, off = false))
        assertFalse(QuickAttach.asks(null, null, off = false))
        // offline or an older plugin: no suggestion, the task is made without a list
        assertNull(QuickAttach.effective(null, null, off = false))
        assertEquals(listOf(attached, drogerie), QuickAttach.choices(several))
        assertEquals(listOf(attached), QuickAttach.choices(one))
        // `#einkauf` names a shopping list: not a new task list
        assertTrue(QuickAttach.tagIsShopping(NAttach("tag", null, attached, listOf(attached))))
        assertFalse(QuickAttach.tagIsShopping(one))
        assertFalse(QuickAttach.tagIsShopping(null))
    }

    // ── the reminder with a list ────────────────────────────

    private val reminders = """{"ok":true,"nowMs":1794729000000,"enabled":true,"reminders":[
        {"tag":"task:41:1794729600000","taskId":41,"listId":3,"area":"private","title":"Bei Lidl einkaufen",
         "body":"Fällig heute 10:00 · Aufgaben\nEinkauf · 8 Punkte: Milch, Brot, Äpfel, Butter …","atMs":1794729600000,"important":false,"collapseKey":"task:41",
         "attachedList":{"id":12,"uid":"$uid","name":"Einkauf","open":8,"total":8},
         "actions":[{"id":"open_list","label":"Liste öffnen"},{"id":"done","label":"Erledigt"},{"id":"snooze_1h","label":"In 1 Std."}]},
        {"tag":"task:42:1794733200000","taskId":42,"title":"Mülltonne","body":"","atMs":1794733200000,"collapseKey":"task:42","attachedList":null,
         "actions":[{"id":"done","label":"Erledigt"},{"id":"snooze_1h","label":"In 1 Std."},{"id":"snooze_eve","label":"Heute Abend"}]}]}"""

    @Test
    fun `GET reminders - text and buttons unchanged, Liste oeffnen only with the list`() {
        val feed = NotizenJson.reminders(json(reminders))!!
        val r = feed.reminders[0]
        assertEquals("Fällig heute 10:00 · Aufgaben\nEinkauf · 8 Punkte: Milch, Brot, Äpfel, Butter …", r.body)
        assertEquals(uid, r.attachedList?.uid)
        assertEquals(listOf("open_list" to "Liste öffnen", "done" to "Erledigt", "snooze_1h" to "In 1 Std."), ReminderPlan.buttons(r).map { it.id to it.label })
        assertNull(feed.reminders[1].attachedList)
        assertEquals(listOf("done", "snooze_1h", "snooze_eve"), ReminderPlan.buttons(feed.reminders[1]).map { it.id })
        // the button without the list it should open is left out (nothing to open)
        assertEquals(listOf("done", "snooze_1h"), ReminderPlan.buttons(r.copy(attachedList = null)).map { it.id })
        // kept on the phone (a boot, no connection) the list survives
        val kept = ReminderState.fromJson(ReminderState(reminders = feed.reminders).toJson())
        assertEquals(uid, kept.reminders[0].attachedList?.uid)
        assertEquals(3, ReminderPlan.buttons(kept.reminders[0]).size)
        assertNull(kept.reminders[1].attachedList)
    }

    private fun push(notizen: String, topic: String = NotizenPush.TOPIC_REMINDERS) = PushMessage(
        seq = 1, id = 1, eventId = "e", topic = topic, priority = "normal", title = "t", body = "",
        createdAt = null, expiresAt = null, collapseKey = null, silent = false, route = null, actions = emptyList(), dataJson = """{"notizen":$notizen}""",
    )

    @Test
    fun `the push's Liste oeffnen opens the list, everything else as before`() {
        val withList = push("""{"kind":"reminder","taskId":41,"listId":3,"area":"private","tag":"task:41:1794729600000","attachedList":{"id":12,"uid":"$uid","name":"Einkauf","open":8,"total":8}}""")
        val dest = NotizenPush.listDestination(withList)
        assertEquals("shop:$uid@private", dest)
        assertEquals(NotizenPush.Shopping(uid, "private"), NotizenPush.decode(dest))
        assertEquals(dest, NotizenPush.listButton(dest, "open_list"))
        assertNull(NotizenPush.listButton(dest, "done"))
        assertNull(NotizenPush.listButton(dest, null), "a tap on the message itself opens the task")
        assertEquals("task:41@private", NotizenPush.destination(withList))
        assertEquals(listOf(NotizenPush.Tab, NotizenPush.Route(NotizenRoutes.shopping(uid))), NotizenPush.steps(dest, listed = true))
        assertEquals(listOf(NotizenPush.Tab), NotizenPush.steps(dest, listed = false))
        // no list in the push (stage 1, or the list is not shared with me): GateControl's route stays
        assertNull(NotizenPush.listDestination(push("""{"kind":"reminder","taskId":41,"tag":"task:41:1794729600000"}""")))
        assertNull(NotizenPush.listDestination(push("""{"kind":"reminder","taskId":41,"attachedList":null}""")))
        assertNull(NotizenPush.listDestination(push("""{"kind":"reminder","taskId":41,"attachedList":{"id":12,"uid":"a/b","name":"x"}}""")))
        assertNull(NotizenPush.listDestination(push("""{"kind":"shared_list","listId":3,"attachedList":{"id":12,"uid":"$uid","name":"Einkauf"}}""", NotizenPush.TOPIC_SHARED)))
        assertNull(NotizenPush.listButton(null, "open_list"))
        assertNull(NotizenPush.listButton("task:41", "open_list"))
        assertNull(NotizenPush.decode("shop:kurz"))
        assertNotNull(NotizenPush.decode("shop:$uid"))
    }

    // ── the family's card ───────────────────────────────────

    @Test
    fun `Familie says where its lists live - home of GET shopping`() {
        fun home(extra: String) = FamilieJson.shopping(json("""{"ok":true,"lists":[],"items":[],"serverTime":"2026-11-14T09:14:07Z"$extra}""")).home
        assertEquals("notizen", home(""","home":{"home":"notizen","since":"2026-11-14T10:00:00Z"}"""))
        assertEquals("familie", home(""","home":{"home":"familie","since":null}"""))
        assertNull(home(""), "Familie 1.20")
        assertNull(home(""","home":{"home":"anderswo"}"""))
        assertNull(home(""","home":"notizen""""))
    }

    @Test
    fun `the card opens the hub's list only after the move, with the feature and the list there`() {
        val a = ShoppingList(uid, "Einkauf")
        val b = ShoppingList("0b0b0b0b-2222-4c1d-9a00-000000000002", "Drogerie")
        val onlyHub = ShoppingList("0c0c0c0c-3333-4c1d-9a00-000000000003", "Baumarkt")
        val familie = listOf(a, b)
        val hub = listOf(onlyHub, b, a)
        // no move, an older Familie, or „Notizen & Aufgaben“ gone: as before
        assertEquals(ShoppingHome.Familie, ShoppingHome.target("familie", true, hub, familie, uid))
        assertEquals(ShoppingHome.Familie, ShoppingHome.target(null, true, hub, familie, uid))
        // moved, but this app's hub has no lists (plugin 1.3, not installed, never reached): Familie's page on its copy
        assertEquals(ShoppingHome.Familie, ShoppingHome.target("notizen", false, emptyList(), familie, uid))
        // moved: the list with the same id
        assertEquals(ShoppingHome.Hub(uid), ShoppingHome.target("notizen", true, hub, familie, uid))
        assertEquals(ShoppingHome.Hub(b.id), ShoppingHome.target("notizen", true, hub, familie, b.id))
        // „Öffnen“ of the card (no list named): the first family list both know
        assertEquals(ShoppingHome.Hub(uid), ShoppingHome.target("notizen", true, hub, familie, null))
        assertEquals(ShoppingHome.Hub(b.id), ShoppingHome.target("notizen", true, listOf(onlyHub, b), familie, null))
        // the hub does not have that list (yet): Familie's page
        assertEquals(ShoppingHome.Familie, ShoppingHome.target("notizen", true, listOf(onlyHub), familie, uid))
        assertEquals(ShoppingHome.Familie, ShoppingHome.target("notizen", true, listOf(onlyHub), familie, null))
        assertEquals(ShoppingHome.Familie, ShoppingHome.target("notizen", true, hub, emptyList(), null))
        assertTrue(ShoppingHome.moved("notizen"))
        assertFalse(ShoppingHome.moved("familie"))
        assertFalse(ShoppingHome.moved(null))
    }
}
