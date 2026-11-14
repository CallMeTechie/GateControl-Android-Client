package com.gatecontrol.android.notizen

import com.gatecontrol.android.familie.FamilieJson
import com.gatecontrol.android.familie.FamiliePlace
import com.gatecontrol.android.familie.places.GeofenceBudget
import com.gatecontrol.android.familie.places.GeofenceRegistrar
import com.gatecontrol.android.familie.places.PlacesHome
import com.gatecontrol.android.notify.model.PushMessage
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * „Aufgaben und Erinnerungen“, stage 4 (plugin 1.6.0 / Familie 1.24.0, the apps' contract): the words in `features`,
 * `me.familyTasks`, whose turn it is, stars, the family's list and what a child may do there, ticking off for a child,
 * who may agree to a place, the quick entry — and after the move: where the family's tasks open and which place
 * reminders this phone still watches for the Familie plugin. With an older plugin all stays as it was.
 */
class NotizenStage4Test {
    private fun json(s: String): JsonObject = JsonParser.parseString(s).asJsonObject

    private val all = """"tasks","places","taskTurns","taskStars","familyTasks","doneFeedback","placeMine""""

    private fun family(parent: Boolean = true, bound: Boolean = true, stars: Boolean = true, moved: String = "null") =
        ""","familyTasks":{"available":true,"bound":$bound,"listId":${if (bound) "12" else "null"},"parent":$parent,"stars":$stars,"starsMax":5,"moved":$moved}"""

    private fun me(features: String = all, extra: String = family(), id: Int = 2, prefs: String = """{"area":"private"}""", readOnly: Boolean = false) =
        NotizenJson.me(json("""{"ok":true,"user":{"id":$id,"name":"Papa"},"lang":"de","today":"2026-11-14","time":"10:14","features":[$features],
            "prefs":$prefs,"sharing":"all","readOnly":$readOnly$extra}"""))!!

    private val lena = """{"id":4,"name":"Lena"}"""
    private val tim = """{"id":5,"name":"Tim"}"""

    private fun task(extra: String = "", id: Int = 77, done: Boolean = false) =
        NotizenJson.task(json("""{"id":$id,"listId":12,"title":"Spülmaschine ausräumen","done":$done,"position":1,"updatedAt":5$extra}"""))!!

    private val rotate = ""","assignee":$lena,"turn":"rotate","people":[$lena,$tim],"stars":2,"family":true"""
    private val first = ""","assignee":null,"turn":"first","people":[$lena,$tim],"family":true"""

    private fun list(family: Boolean = true, role: String = "owner", members: String = "[$lena,$tim]") =
        NotizenJson.list(json("""{"id":12,"name":"Familie","area":"private","role":"$role","shared":true,"owner":{"id":2,"name":"Papa"},"members":$members,"open":3,"family":$family}"""))!!

    // ── recognising the plugins ─────────────────────────────

    @Test
    fun `the abilities are the words in features, never a version`() {
        val old = me(""""tasks","places","shoppingLists"""", "")
        assertFalse(old.taskTurns)
        assertFalse(old.taskStars)
        assertNull(old.familyList)
        assertFalse(old.familyParent)
        assertFalse(old.doneFeedback)
        assertFalse(old.placeMine)
        assertNull(old.familyTasks)
        val new = me()
        assertTrue(new.taskTurns && new.taskStars && new.familyParent && new.doneFeedback && new.placeMine)
        assertEquals(12L, new.familyList)
        assertEquals(5, NotizenTurns.starsMax(new))
        // each word by itself: turns in every shared list without the family, …
        val only = me(""""taskTurns"""", "")
        assertTrue(only.taskTurns)
        assertFalse(only.taskStars)
        assertNull(only.familyList)
        // … `familyTasks` in the answer without the word is not the family's list
        assertNull(me(""""taskTurns","taskStars"""").familyList)
        assertFalse(me(""""taskTurns","taskStars"""").taskStars)
        // not bound (no move, or not of the family): no list, no stars, no parent — whatever else it says
        val unbound = me(extra = family(bound = false))
        assertNull(unbound.familyList)
        assertFalse(unbound.taskStars || unbound.familyParent || unbound.doneFeedback)
        // the Familie plugin does not count stars: none show, none are set
        assertFalse(me(extra = family(stars = false)).taskStars)
        // a child: the list and the stars, not what the parents do
        val child = me(extra = family(parent = false))
        assertTrue(child.taskStars)
        assertFalse(child.familyParent || child.doneFeedback)
    }

    @Test
    fun `me familyTasks - the move and whether its note was read`() {
        val m = me(extra = family(moved = """{"at":"2026-11-14T10:00:00Z","tasks":12,"places":3,"seen":false}""")).familyTasks!!
        assertEquals(NFamilyMoved("2026-11-14T10:00:00Z", 12, 3, false), m.moved)
        assertNull(me().familyTasks!!.moved)
        // `bound` without a list number is not bound
        assertFalse(NotizenJson.familyTasks(json("""{"available":true,"bound":true,"listId":null}"""))!!.bound)
        assertEquals(10, NotizenJson.familyTasks(json("""{"bound":true,"listId":3,"starsMax":99}"""))!!.starsMax)
    }

    @Test
    fun `prefs feedback - all by default, only its three values are sent`() {
        assertEquals("all", me().prefs.feedback)
        assertEquals("stars", me(prefs = """{"feedback":"stars"}""").prefs.feedback)
        assertEquals("all", me(prefs = """{"feedback":"loud"}""").prefs.feedback)
        assertEquals("""{"feedback":"off"}""", NotizenJson.prefsBody(feedback = "off").toString())
        assertEquals("{}", NotizenJson.prefsBody(feedback = "sometimes").toString())
    }

    // ── the task's new fields ───────────────────────────────

    @Test
    fun `a task - turn, people, stars, doneBy, family, and none of it from an older plugin`() {
        val old = task()
        assertNull(old.turn)
        assertEquals(emptyList<NPerson>(), old.people)
        assertEquals(0, old.stars)
        assertNull(old.doneBy)
        assertFalse(old.family)
        val r = task(rotate)
        assertEquals(TurnWords.ROTATE, r.turn)
        assertEquals(listOf(4L, 5L), r.people.map { it.id })
        assertEquals(2, r.stars)
        assertTrue(r.family)
        assertEquals(4L, r.assignee?.id)
        val f = task(first)
        assertEquals(TurnWords.FIRST, f.turn)
        assertNull(f.assignee)
        // fewer than two people, or a word the app does not know: an ordinary task (people only belong to a turn)
        assertNull(task(""","turn":"rotate","people":[$lena]""").turn)
        assertEquals(emptyList<NPerson>(), task(""","turn":"rotate","people":[$lena]""").people)
        assertNull(task(""","turn":"everyone","people":[$lena,$tim]""").turn)
        assertEquals(10, task(""","stars":25""").stars)
        assertEquals(0, task(""","stars":-3""").stars)
        val d = task(""","doneBy":$lena,"stars":2,"family":true""", done = true)
        assertEquals("Lena", d.doneBy?.name)
        assertTrue(NotizenJson.list(json("""{"id":12,"name":"Familie","family":true}"""))!!.family)
        assertFalse(NotizenJson.list(json("""{"id":12,"name":"Familie"}"""))!!.family)
    }

    @Test
    fun `the row - whose turn it is`() {
        assertEquals(NotizenTurns.Line.Rotate("Lena"), NotizenTurns.line(task(rotate), 2))
        assertEquals(NotizenTurns.Line.RotateMe, NotizenTurns.line(task(rotate), 4))
        assertEquals(NotizenTurns.Line.First, NotizenTurns.line(task(first), 4))
        assertNull(NotizenTurns.line(task(), 4))
        // done: „Erledigt von …“ instead
        assertNull(NotizenTurns.line(task(rotate, done = true), 4))
        assertEquals(NotizenTurns.ONE, NotizenTurns.mode(null))
        assertEquals(NotizenTurns.ONE, NotizenTurns.mode("everyone"))
        assertEquals(TurnWords.FIRST, NotizenTurns.mode("first"))
    }

    @Test
    fun `writing - turn, people, stars, and the base of a change`() {
        assertEquals(listOf("turn" to "rotate"), NotizenJson.turnFields("rotate", listOf(4, 5)).filter { it.first == "turn" })
        assertEquals("""{"turn":"rotate","people":[4,5]}""", NotizenJson.patch(*NotizenJson.turnFields("rotate", listOf(4, 5, 4)).toTypedArray()).toString())
        assertEquals("""{"turn":"first","people":[4,5],"stars":2}""", NotizenJson.patch(*NotizenJson.turnFields("first", listOf(4, 5), 2).toTypedArray()).toString())
        // „Eine Person“, a word not known, or fewer than two people: `turn: null`, no people
        for (f in listOf(NotizenJson.turnFields(null, listOf(4, 5)), NotizenJson.turnFields("all", listOf(4, 5)), NotizenJson.turnFields("rotate", listOf(4)))) {
            assertEquals(listOf<Pair<String, Any?>>("turn" to null), f)
        }
        assertEquals("""{"done":true,"userId":4}""", NotizenJson.completeBody(true, 4).toString())
        assertEquals("""{"done":false}""", NotizenJson.completeBody(false, null).toString())
        assertEquals("""{"text":"x","area":"private","listId":12}""", NotizenJson.parseBody("x", "private", 12).toString())
        assertEquals("""{"text":"x","area":"private"}""", NotizenJson.parseBody("x", "private").toString())
        val detail = NTaskDetail(task(rotate), null, emptyList(), "Familie", "private", emptyList(), null)
        assertEquals("""{"turn":"rotate","people":[4,5],"stars":2}""", NotizenOutbox.taskBase(detail, listOf("turn", "people", "stars")).toString())
        assertEquals("""{"turn":null,"people":[],"stars":0}""", com.google.gson.GsonBuilder().serializeNulls().create().toJson(NotizenOutbox.taskBase(detail.copy(task = task()), listOf("turn", "people", "stars"))))
    }

    // ── who may what ────────────────────────────────────────

    @Test
    fun `Wer ist dran is offered with the word and two people to choose from`() {
        assertTrue(NotizenTurns.offered(me(), list()))
        assertEquals(listOf(2L, 4L, 5L), NotizenTurns.candidates(list()).map { it.id })
        // an older plugin; a list of my own; only me in it
        assertFalse(NotizenTurns.offered(me(""""tasks"""", ""), list()))
        assertFalse(NotizenTurns.offered(me(), NotizenJson.list(json("""{"id":3,"name":"Meine","shared":false}"""))))
        assertFalse(NotizenTurns.offered(me(), null))
        // a turn that is there can always be taken away
        assertTrue(NotizenTurns.offered(me(), null, task(rotate)))
        assertFalse(NotizenTurns.offered(me(""""tasks"""", ""), null, task(rotate)))
    }

    @Test
    fun `stars - shown in the family's list, given by the parents only`() {
        val parent = me()
        val child = me(extra = family(parent = false), id = 4)
        assertTrue(NotizenTurns.starsShown(parent, task(rotate)))
        assertTrue(NotizenTurns.starsShown(child, task(rotate)))
        assertTrue(NotizenTurns.mayGiveStars(parent, 12))
        assertFalse(NotizenTurns.mayGiveStars(child, 12))
        // another list, no list, the Familie plugin not counting, an older plugin
        assertFalse(NotizenTurns.mayGiveStars(parent, 3))
        assertFalse(NotizenTurns.mayGiveStars(parent, null))
        assertFalse(NotizenTurns.mayGiveStars(me(extra = family(stars = false)), 12))
        assertFalse(NotizenTurns.starsShown(me(extra = family(stars = false)), task(rotate)))
        assertFalse(NotizenTurns.starsShown(me(""""tasks","taskTurns"""", ""), task(rotate)))
        assertFalse(NotizenTurns.starsShown(parent, NotizenJson.task(json("""{"id":1,"listId":3,"title":"x"}"""))!!))
    }

    @Test
    fun `a parent ticks off for a child - whom, and when it is not offered`() {
        val parent = me()
        val child = me(extra = family(parent = false), id = 4)
        // „wer zuerst“: one of its people; nobody assigned: one of the family
        assertEquals(listOf(4L, 5L), NotizenTurns.tickFor(parent, task(first), list()).map { it.id })
        assertEquals(listOf(2L, 4L, 5L), NotizenTurns.tickFor(parent, task(""","family":true"""), list()).map { it.id })
        // assigned to someone: the server ticks for that person by itself
        assertEquals(emptyList<NPerson>(), NotizenTurns.tickFor(parent, task(rotate), list()))
        // a child, a task done, another list, read only, may not complete
        assertEquals(emptyList<NPerson>(), NotizenTurns.tickFor(child, task(first), list()))
        assertEquals(emptyList<NPerson>(), NotizenTurns.tickFor(parent, task(first, done = true), list()))
        assertEquals(emptyList<NPerson>(), NotizenTurns.tickFor(parent, NotizenJson.task(json("""{"id":1,"listId":3,"title":"x"}"""))!!, list(family = false)))
        assertEquals(emptyList<NPerson>(), NotizenTurns.tickFor(me(readOnly = true), task(first), list()))
        assertEquals(emptyList<NPerson>(), NotizenTurns.tickFor(parent, task("$first,\"canComplete\":false"), list()))
    }

    @Test
    fun `Noch nicht fertig - a parent, for what was ticked off for someone else`() {
        val done = task(""","doneBy":$lena,"stars":2,"family":true""", done = true)
        assertTrue(NotizenTurns.mayReject(me(), done))
        assertFalse(NotizenTurns.mayReject(me(extra = family(parent = false), id = 5), done))
        // my own tick is taken back with the tick; an open task has nothing to take back
        assertFalse(NotizenTurns.mayReject(me(id = 4), done))
        assertFalse(NotizenTurns.mayReject(me(), task(rotate)))
        assertFalse(NotizenTurns.mayReject(me(), task(""","doneBy":$lena""", done = true).copy(listId = 3)))
        assertFalse(NotizenTurns.mayReject(me(readOnly = true), done))
    }

    @Test
    fun `the family's list - no sharing, leaving or deleting`() {
        val owner = NotizenLists.actions(list(), me())
        assertTrue(NotizenLists.Action.EDIT in owner && NotizenLists.Action.MOVE in owner)
        assertFalse(NotizenLists.Action.SHARE in owner || NotizenLists.Action.DELETE in owner)
        val member = NotizenLists.actions(list(role = "write"), me(extra = family(parent = false), id = 4))
        assertFalse(NotizenLists.Action.LEAVE in member)
        assertFalse(NotizenLists.mayLeave(list(role = "write"), me(id = 4)))
        assertFalse(NotizenLists.mayDelete(list(), me()))
        // after „Zurückholen“ it is an ordinary shared list again
        assertTrue(NotizenLists.mayDelete(list(family = false), me()))
        assertTrue(NotizenLists.mayLeave(list(family = false, role = "write"), me(id = 4)))
        assertTrue(NotizenLists.Action.SHARE in NotizenLists.actions(list(family = false), me()))
    }

    @Test
    fun `a child - canEdit and canComplete of the task say what it may do`() {
        val theirs = task(""","family":true,"canEdit":false,"canComplete":true""")
        assertFalse(theirs.canEdit)
        assertTrue(NotizenLists.canTick(theirs, list(role = "write"), 4))
        assertFalse(NotizenLists.canTick(task(""","family":true,"canEdit":false,"canComplete":false"""), list(role = "write"), 4))
    }

    // ── the place: who may agree ────────────────────────────

    private fun place(extra: String, consent: String = "pending", by: Int = 2) = NotizenPlacesJson.place(
        json("""{"mode":"place","id":1,"name":"Schule","lat":52.5,"lon":13.4,"radius":150,"trigger":"enter","by":$by,"consent":"$consent","watch":false$extra}"""),
    )!!

    @Test
    fun `Annehmen and Ablehnen show only for the person who may consent`() {
        val meLena = me(id = 4)
        assertEquals(true, place(""","mine":true,"canConsent":true""").canConsent)
        assertEquals(true, place(""","mine":true,"canConsent":true""").mine)
        assertTrue(NotizenTurns.canConsent(place(""","mine":true,"canConsent":true"""), meLena))
        // a third person of the shared list: someone else set it, but it is not theirs to agree to
        assertFalse(NotizenTurns.canConsent(place(""","mine":false,"canConsent":false"""), me(id = 5)))
        assertTrue(NotizenTurns.consentKnown(place(""","mine":false,"canConsent":false"""), me(id = 5)))
        // declined before: the person asked may still agree; accepted: nothing to decide
        assertTrue(NotizenTurns.canConsent(place(""","canConsent":true""", consent = "declined"), meLena))
        assertFalse(NotizenTurns.canConsent(place(""","canConsent":true""", consent = "accepted"), meLena))
        assertFalse(NotizenTurns.canConsent(place(""","canConsent":true"""), me(id = 4, readOnly = true)))
    }

    @Test
    fun `without placeMine the app guesses as before`() {
        val old = me(""""tasks","places"""", "", id = 4)
        val p = place("")
        assertNull(p.canConsent)
        assertNull(p.mine)
        assertTrue(NotizenTurns.canConsent(p, old))
        assertFalse(NotizenTurns.canConsent(place("", by = 4), old))
        assertFalse(NotizenTurns.canConsent(place("", consent = "declined"), old))
        assertFalse(NotizenTurns.consentKnown(p, old))
        // the field without the word, or the word without the field: still the guess
        assertTrue(NotizenTurns.canConsent(place(""","canConsent":false"""), old))
        assertTrue(NotizenTurns.canConsent(p, me(id = 4)))
    }

    // ── the quick entry ─────────────────────────────────────

    private val people = listOf(QuickParse.Ref(4, "Lena"), QuickParse.Ref(5, "Tim"), QuickParse.Ref(2, "Papa"))
    private val qctx = QuickParse.Ctx("2026-11-14", now = "10:00", lists = listOf(QuickParse.Ref(12, "Familie")), people = people)

    private fun read(text: String, me: NMe = me(), listId: Long? = 12) = NotizenTurns.read(QuickParse.parse(text, qctx), people, me, listId)

    @Test
    fun `the quick entry - several people, the turn, the stars`() {
        val r = NotizenTurns.effective(read("@Lena @Tim Spülmaschine ausräumen täglich 18 Uhr reihum 2 Sterne"), null, emptySet())!!
        assertEquals(NotizenTurns.Quick("Spülmaschine ausräumen", listOf(people[0], people[1]), TurnWords.ROTATE, 2), r)
        assertEquals(4L, NotizenTurns.assignee(r))
        // „wer zuerst“: nobody is assigned
        val f = NotizenTurns.effective(read("@Lena @Tim Blumen gießen wer zuerst"), null, emptySet())!!
        assertEquals(TurnWords.FIRST, f.turn)
        assertNull(NotizenTurns.assignee(f))
        // two people without a word: one person, the first
        val one = NotizenTurns.effective(read("@Lena @Tim Zimmer aufräumen"), null, emptySet())!!
        assertNull(one.turn)
        assertEquals(4L, NotizenTurns.assignee(one))
    }

    @Test
    fun `the quick entry - what the person chose instead of the line`() {
        val line = read("@Lena @Tim Müll reihum 1 Stern")
        assertNull(NotizenTurns.effective(line, NotizenTurns.ONE, emptySet())!!.turn)
        assertEquals(TurnWords.FIRST, NotizenTurns.effective(line, TurnWords.FIRST, emptySet())!!.turn)
        assertEquals(TurnWords.ROTATE, NotizenTurns.effective(read("@Lena @Tim Müll"), TurnWords.ROTATE, emptySet())!!.turn)
        // the chips taken away: no people (so no turn), no stars — the title stays the cleaned one
        val off = NotizenTurns.effective(line, null, setOf("person", "stars"))!!
        assertEquals(NotizenTurns.Quick("Müll", emptyList(), null, null), off)
        // a turn needs two people
        assertNull(NotizenTurns.effective(read("@Lena Müll"), TurnWords.ROTATE, emptySet())!!.turn)
        // a further person who does not belong to the list falls away silently, as on the server — the first one stays
        val members = NotizenTurns.members(list(members = "[$lena]"))
        assertEquals(setOf(2L, 4L), members)
        assertEquals(NotizenTurns.Quick("Müll", listOf(people[0]), null, 1), NotizenTurns.effective(line, null, emptySet(), members))
        assertEquals(listOf(people[1]), NotizenTurns.effective(read("@Tim @Lena Müll reihum"), null, emptySet(), emptySet())!!.people)
        assertNull(NotizenTurns.members(null))
        assertEquals(emptySet<Long>(), NotizenTurns.members(NotizenJson.list(json("""{"id":3,"name":"Meine","shared":false}"""))))
    }

    @Test
    fun `the quick entry - stars only where I may give them, nothing with an older plugin`() {
        // another list, a child, the Familie plugin not counting: „Hotel 4 Sterne buchen“ stays whole
        assertEquals("Hotel 4 Sterne buchen", read("Hotel 4 Sterne buchen", listId = 3)!!.title)
        assertNull(read("Hotel 4 Sterne buchen", listId = null)!!.stars)
        assertNull(read("@Lena Zimmer 2 Sterne", me(extra = family(parent = false), id = 4))!!.stars)
        assertNull(read("@Lena Zimmer 2 Sterne", me(extra = family(stars = false)))!!.stars)
        assertEquals(4, read("Hotel 4 Sterne buchen")!!.stars)
        // without `taskTurns` the line is not read a second time at all
        assertNull(read("@Lena @Tim Müll reihum", me(""""tasks"""", "")))
        assertNull(NotizenTurns.effective(null, TurnWords.ROTATE, emptySet()))
    }

    // ── pushes ──────────────────────────────────────────────

    private fun push(topic: String, data: String) = PushMessage(
        seq = 1, id = 7, eventId = "e", topic = topic, priority = "normal", title = "t", body = "",
        createdAt = null, expiresAt = null, collapseKey = null, silent = false, route = null, actions = emptyList(), dataJson = data,
    )

    @Test
    fun `done_feedback and reminder_done open the task`() {
        val fb = push(NotizenPush.TOPIC_SHARED, """{"notizen":{"kind":"done_feedback","taskId":77,"listId":12,"area":"private","task":77,"userId":4,"stars":2},
            "actions":[{"type":"open_app_route","id":"open","label":"Ansehen","target":"plg-gatecontrol-notizen"},{"type":"callback","id":"not_done","label":"Noch nicht fertig"}]}""")
        assertEquals("task:77@private", NotizenPush.destination(fb))
        assertNull(NotizenPush.doneElsewhere(fb))
        val rd = push(NotizenPush.TOPIC_REMINDERS, """{"notizen":{"kind":"reminder_done","taskId":77,"listId":12,"area":"private","userId":4}}""")
        assertEquals("task:77@private", NotizenPush.destination(rd))
        assertEquals(77L, NotizenPush.doneElsewhere(rd))
        // it is not a reminder itself: no alarm's twin, nothing to hide
        assertNull(NotizenPush.reminderTag(rd))
        assertNull(NotizenPush.doneElsewhere(push(NotizenPush.TOPIC_REMINDERS, """{"notizen":{"kind":"reminder","taskId":77,"tag":"task:77:1"}}""")))
    }

    // ── after the move: Familie or the hub ──────────────────

    @Test
    fun `Familie says where its tasks live - home of GET tasks and of the overview`() {
        assertEquals("notizen", FamilieJson.tasks(json("""{"ok":true,"tasks":[],"home":{"home":"notizen","since":"2026-11-14T10:00:00Z"}}""")).home)
        assertEquals("familie", FamilieJson.tasks(json("""{"ok":true,"tasks":[],"home":{"home":"familie"}}""")).home)
        assertNull(FamilieJson.tasks(json("""{"ok":true,"tasks":[]}""")).home)
        assertNull(FamilieJson.tasks(json("""{"ok":true,"tasks":[],"home":{"home":"elsewhere"}}""")).home)
        assertEquals("notizen", FamilieJson.overview(json("""{"ok":true,"tasks":{"mine":[],"open":2,"home":{"home":"notizen"}}}""")).tasksHome)
        assertNull(FamilieJson.overview(json("""{"ok":true,"tasks":{"mine":[],"open":2}}""")).tasksHome)
        // the stars stay where they were: Familie's board
        val t = FamilieJson.tasks(json("""{"ok":true,"tasks":[],"home":{"home":"notizen"},"stars":{"week":{"4":2,"5":0},"goal":{"4":10}}}"""))
        assertEquals(mapOf(4 to 2, 5 to 0), t.stars!!.week)
    }

    @Test
    fun `the family's tasks open in the hub only after the move and with the family's list there`() {
        assertEquals(TasksHome.Hub(12), TasksHome.target("notizen", me()))
        assertEquals("list:12", TasksHome.dest(TasksHome.Hub(12)))
        assertEquals(NotizenPush.ListPage(12), NotizenPush.decode(TasksHome.dest(TasksHome.Hub(12))))
        // no move, an older Familie plugin
        assertEquals(TasksHome.Familie, TasksHome.target("familie", me()))
        assertEquals(TasksHome.Familie, TasksHome.target(null, me()))
        // moved, but this app's hub has no family list for me: no hub, an older hub, not bound
        assertEquals(TasksHome.Familie, TasksHome.target("notizen", null))
        assertEquals(TasksHome.Familie, TasksHome.target("notizen", me(""""tasks","taskTurns"""", "")))
        assertEquals(TasksHome.Familie, TasksHome.target("notizen", me(extra = family(bound = false))))
        assertTrue(TasksHome.moved("notizen"))
        assertFalse(TasksHome.moved("familie") || TasksHome.moved(null))
    }

    // ── place reminders after the move ──────────────────────

    private fun fplace(id: Int, extra: String = "", target: Int = 4) = FamilieJson.place(
        json("""{"id":$id,"ownerId":2,"targetUserId":$target,"title":"Ort $id","lat":52.5,"lon":13.4,"radius":150,"trigger":"enter","action":"note","repeat":"always","consent":"accepted"$extra}"""),
    )!!

    @Test
    fun `a place reminder that moved is no geofence of the Familie plugin any more`() {
        val moved = fplace(3, ""","active":false,"home":"notizen","moved":true""")
        val tell = fplace(5, ""","action":"tell","tellUserIds":[2],"active":true,"home":"familie","moved":false""")
        val before = fplace(6)
        assertTrue(moved.inHub)
        assertFalse(tell.inHub || before.inHub)
        assertNull(before.home)
        assertEquals(listOf(5, 6), GeofenceRegistrar.select(listOf(moved, tell, before), 4).map { it.id })
        // never twice: even if it were still marked active, a reminder of the hub is the hub's to watch
        assertEquals(emptyList<FamiliePlace>(), GeofenceRegistrar.select(listOf(fplace(3, ""","active":true,"home":"notizen","moved":true"""), fplace(4, ""","moved":true""")), 4))
        // a phone that never asks the hub gets all of them from the Familie plugin, as before
        val all = listOf(fplace(3, ""","active":true,"home":"familie","moved":false"""), tell)
        assertEquals(listOf(3, 5), GeofenceRegistrar.select(all, 4).map { it.id })
        // without the move nothing changes: mine, active, accepted
        val plain = listOf(fplace(1), fplace(2, target = 9), fplace(7, ""","active":false"""), fplace(8).copy(consent = FamiliePlace.CONSENT_PENDING))
        assertEquals(listOf(1), GeofenceRegistrar.select(plain, 4).map { it.id })
    }

    @Test
    fun `the budget - what moved makes room for the hub, the sum stays`() {
        val before = GeofenceRegistrar.select((1..10).map { fplace(it) }, 4)
        val after = GeofenceRegistrar.select((1..10).map { fplace(it, if (it <= 7) ""","active":false,"home":"notizen","moved":true""" else "") }, 4)
        assertEquals(10, before.size)
        assertEquals(3, after.size)
        assertEquals(7, GeofenceBudget.notizen(after.size) - GeofenceBudget.notizen(before.size).coerceAtMost(GeofenceBudget.NOTIZEN_MAX - 7))
        assertTrue(after.size + GeofenceBudget.notizen(after.size) + GeofenceBudget.RESERVE + GeofenceBudget.MOVE_FENCE <= GeofenceBudget.ANDROID_MAX)
    }

    @Test
    fun `Familie's page lists only what still belongs to it - Bescheid sagen stays`() {
        val moved = fplace(3, ""","active":false,"home":"notizen","moved":true""")
        val tell = fplace(5, ""","action":"tell","tellUserIds":[2],"home":"familie","moved":false""")
        assertEquals(listOf(5), GeofenceRegistrar.shown(listOf(moved, tell)).map { it.id })
        assertEquals(PlacesHome(moved = true, left = 1), GeofenceRegistrar.placesHome(listOf(moved, tell)))
        assertEquals(PlacesHome(moved = true, left = 0), GeofenceRegistrar.placesHome(listOf(moved)))
        assertEquals(PlacesHome(moved = false, left = 2), GeofenceRegistrar.placesHome(listOf(fplace(1), fplace(2))))
        assertEquals(PlacesHome(moved = false, left = 0), GeofenceRegistrar.placesHome(emptyList()))
    }

    @Test
    fun `first the hub is asked, then the Familie plugin - once per start and per move`() {
        val m = me(extra = family(moved = """{"at":"2026-11-14T10:00:00Z","tasks":12,"places":3,"seen":true}"""))
        val key = TasksHome.askFamilyAgain(m, null)
        assertEquals("12:2026-11-14T10:00:00Z", key)
        assertNull(TasksHome.askFamilyAgain(m, key))
        // moved again (after „Zurückholen“): asked again
        val again = me(extra = family(moved = """{"at":"2026-12-01T08:00:00Z","tasks":1,"places":1,"seen":false}"""))
        assertEquals("12:2026-12-01T08:00:00Z", TasksHome.askFamilyAgain(again, key))
        // no move, an older plugin, no hub: the Familie plugin is not asked on the hub's account
        assertNull(TasksHome.askFamilyAgain(me(extra = family(bound = false)), null))
        assertNull(TasksHome.askFamilyAgain(me(""""tasks","places"""", ""), null))
        assertNull(TasksHome.askFamilyAgain(null, null))
    }
}
