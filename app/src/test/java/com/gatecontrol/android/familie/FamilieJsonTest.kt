package com.gatecontrol.android.familie

import com.gatecontrol.android.familie.shopping.ShoppingOp
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FamilieJsonTest {

    private fun json(s: String): JsonObject = JsonParser.parseString(s).asJsonObject

    @Test
    fun `me of a parent with modules and members as in the contract`() {
        val p = FamilieJson.profile(
            json(
                """
                { "ok": true,
                  "me": { "userId": 3, "name": "Anna", "role": "parent", "age": null, "locateMode": null },
                  "modules": { "shopping": true, "tasks": true, "stars": 1, "dates": true,
                               "locate": true, "arrival_sos": true, "places": false },
                  "members": [ { "userId": 5, "name": "Tim", "role": "child", "age": 9,
                                 "color": "#5B21B6", "locateMode": "auto", "locatable": true },
                               { "userId": 7, "name": "Bad", "role": "uncle" },
                               { "name": "no id", "role": "child" } ],
                  "serverTime": "2026-10-14T12:00:00Z" }
                """,
            ),
        )!!
        assertEquals(3, p.me.userId)
        assertTrue(p.me.isParent)
        assertNull(p.me.locateMode)
        assertTrue(p.modules.stars, "SQLite 0/1 booleans are accepted")
        assertTrue(p.modules.arrivalSos)
        assertFalse(p.modules.places)
        assertEquals(listOf(5), p.members.map { it.userId })
        val tim = p.members.single()
        assertEquals(LocateMode.AUTO, tim.locateMode)
        assertEquals(true, tim.locatable)
        assertEquals("#5B21B6", tim.color)
        assertEquals(listOf(5), p.children.map { it.userId })
        assertEquals(listOf(3, 5), p.everyone.map { it.userId })
        assertEquals("2026-10-14T12:00:00Z", p.serverTime)
    }

    @Test
    fun `a child sees its own mode and members without locate fields`() {
        val p = FamilieJson.profile(
            json("""{"ok":true,"me":{"userId":5,"name":"Tim","role":"child","age":9,"locateMode":"confirm"},"modules":{},"members":[{"userId":3,"name":"Anna","role":"parent"}]}"""),
        )!!
        assertTrue(p.me.isChild)
        assertEquals(LocateMode.CONFIRM, p.me.locateMode)
        assertFalse(p.modules.any)
        assertNull(p.members.single().locateMode)
        assertEquals("Anna", p.nameOf(3))
    }

    @Test
    fun `me without a valid user is rejected`() {
        assertNull(FamilieJson.profile(json("""{"ok":true}""")))
        assertNull(FamilieJson.profile(json("""{"ok":true,"me":{"userId":1,"role":"admin"}}""")))
    }

    @Test
    fun `overview keeps switched-off modules as null`() {
        val o = FamilieJson.overview(
            json(
                """
                { "ok": true, "shopping": { "lists": [ { "id": "a1b2", "name": "REWE", "open": 7 }, { "id": "bad id!", "name": "x" } ] },
                  "tasks": { "mine": [ { "id": 4, "title": "Bad putzen", "mode": "rotate", "assignees": [3,5], "current": 5,
                                         "repeat": "weekly", "weekdays": [2, 9], "dueTime": "18:00", "dueDate": "2026-10-14",
                                         "stars": 12, "reminder": true, "doneToday": false, "lastDone": null } ], "open": 3 },
                  "stars": null,
                  "dates": { "next": [ { "id": "member:5", "title": "Tim", "date": "2026-10-20", "kind": "birthday", "repeat": "yearly", "allDay": true } ] },
                  "locate": null }
                """,
            ),
        )
        assertEquals(listOf("a1b2"), o.shoppingLists!!.map { it.id })
        assertEquals(7, o.shoppingLists.single().open)
        val task = o.myTasks!!.single()
        assertEquals(listOf(5), task.responsible())
        assertEquals(listOf(2), task.weekdays)
        assertEquals(10, task.stars, "stars are capped to 0..10")
        assertEquals(3, o.openTasks)
        assertNull(o.stars)
        assertNull(o.lastLocates)
        val birthday = o.nextDates!!.single()
        assertTrue(birthday.readOnly)
        assertTrue(birthday.isBirthday)
    }

    @Test
    fun `server 1_164 extras - colours, going, arrivals, request ids`() {
        val p = FamilieJson.profile(json("""{"ok":true,"me":{"userId":3,"name":"Anna","role":"parent","color":"#0B7A55"},"modules":{"shopping":true},"members":[],"unknownField":{"x":1}}"""))!!
        assertEquals("#0B7A55", p.me.color)
        assertEquals("#0B7A55", p.everyone.first().color)
        val o = FamilieJson.overview(
            json(
                """{"ok":true,"shopping":{"lists":[{"id":"list_0001-abc","name":"REWE","open":2}],"going":[{"userId":4,"listId":"list_0001-abc","at":"2026-10-14T16:31:00Z"}]},
                   "arrivals":[{"userId":6,"label":"Schule","at":"2026-10-14T13:10:00Z"}],
                   "locate":{"last":[{"childId":5,"status":"answered","at":"2026-10-14T13:10:00Z","requestId":44}]}}""",
            ),
        )
        assertEquals(listOf("list_0001-abc"), o.shoppingLists!!.map { it.id }, "server ids may contain underscores")
        assertEquals(OverviewGoing(4, "list_0001-abc", "2026-10-14T16:31:00Z"), o.going.single())
        assertEquals(OverviewArrival(6, "Schule", "2026-10-14T13:10:00Z"), o.arrivals!!.single())
        assertEquals(44, o.lastLocates!!.single().requestId)
        assertNull(FamilieJson.overview(json("""{"ok":true}""")).arrivals, "arrivals null when the module is off")

        val r = FamilieJson.locateRequest(json("""{"id":1,"childId":5,"status":"declined","reason":"busy","requestedByName":"Anna","childName":"Tim","purged":true}"""))!!
        assertEquals("Anna", r.requestedByName)
        assertEquals("busy", r.code)
        assertEquals("12", FamilieJson.event(json("""{"id":"12","title":"x","date":"2026-10-14","baseDate":"2026-10-14","years":3,"readOnly":false}"""))!!.id)
        assertEquals(30, FamilieJson.retryAfter(json("""{"ok":false,"code":"too_soon","retryAfter":30}""")))
        assertEquals(17, FamilieJson.refusedRequestId(json("""{"ok":false,"code":"too_soon","requestId":17}""")))
    }

    @Test
    fun `shopping answer with tombstones and lenient fields`() {
        val s = FamilieJson.shopping(
            json(
                """
                { "ok": true,
                  "lists": [ { "id": "l1", "name": "Supermarkt", "icon": null, "sort": 1, "updatedAt": "2026-10-14T10:00:00Z", "deleted": false },
                             { "id": "l2", "name": "Alt", "deleted": 1 } ],
                  "items": [ { "id": "i1", "listId": "l1", "text": "Milch", "qty": "2×", "checked": false, "checkedBy": null },
                             { "id": "i2", "listId": "l1", "text": "Brot", "checked": true, "checkedBy": 5 },
                             { "id": "i3", "listId": "l1", "text": "", "deleted": true },
                             { "id": "i4", "listId": "l1", "text": "   " } ],
                  "serverTime": "2026-10-14T12:00:00Z" }
                """,
            ),
        )
        assertEquals(listOf(false, true), s.lists.map { it.deleted })
        assertEquals(listOf("i1", "i2", "i3"), s.items.map { it.id })
        assertEquals("2×", s.items[0].qty)
        assertEquals("5", s.items[1].checkedBy)
        assertTrue(s.items[2].deleted)
        assertEquals("2026-10-14T12:00:00Z", s.serverTime)
    }

    @Test
    fun `ops body leaves out null fields`() {
        val body = FamilieJson.opsBody(
            listOf(
                ShoppingOp(ShoppingOp.ITEM_ADD, id = "u1", listId = "l1", text = "Milch"),
                ShoppingOp(ShoppingOp.ITEM_CHECK, id = "u1", checked = true),
                ShoppingOp(ShoppingOp.CLEAR_CHECKED, listId = "l1"),
            ),
        )
        assertEquals(
            """{"ops":[{"op":"item_add","id":"u1","listId":"l1","text":"Milch"},{"op":"item_check","id":"u1","checked":true},{"op":"clear_checked","listId":"l1"}]}""",
            body.toString(),
        )
        val r = FamilieJson.opsResult(json("""{"ok":true,"applied":2,"rejected":[{"index":1,"code":"not_found"}],"serverTime":"2026-10-14T12:00:01Z"}"""))
        assertEquals(2, r.applied)
        assertEquals(listOf(1 to "not_found"), r.rejected)
    }

    @Test
    fun `tasks with stars per user`() {
        val t = FamilieJson.tasks(
            json("""{"ok":true,"tasks":[{"id":1,"title":"Müll","mode":"first","assignees":[5,6],"repeat":"daily","stars":2,"reminder":true,"doneToday":true,"lastDone":{"by":5,"at":"2026-10-14T07:00:00Z"}}],"stars":{"week":{"5":9,"6":"12"},"goal":{"5":20,"x":3}}}"""),
        )
        val task = t.tasks.single()
        assertEquals(FamilieTask.MODE_FIRST, task.mode)
        assertTrue(task.doneToday)
        assertEquals(5, task.lastDone?.by)
        assertEquals(mapOf(5 to 9, 6 to 12), t.stars!!.week)
        assertEquals(mapOf(5 to 20), t.stars.goal)
    }

    @Test
    fun `task body carries every field of the contract`() {
        val b = FamilieJson.taskBody(TaskDraft(title = " Zimmer ", mode = "rotate", assignees = listOf(5, 6), repeat = "weekly", weekdays = listOf(1, 3), dueTime = "18:00", dueDate = "2026-10-15", stars = 3))
        assertEquals("Zimmer", b.get("title").asString)
        assertEquals("[5,6]", b.get("assignees").toString())
        assertEquals("[1,3]", b.get("weekdays").toString())
        assertEquals("18:00", b.get("dueTime").asString)
        assertEquals(3, b.get("stars").asInt)
        assertTrue(b.get("reminder").asBoolean)
    }

    @Test
    fun `dates and alltag events`() {
        val events = FamilieJson.events(
            json("""{"ok":true,"dates":[{"id":12,"title":"Elternabend","date":"2026-10-14","time":"19:00","allDay":false,"repeat":"none","memberIds":[3],"kind":"event","remindDays":1,"note":"4b"},{"id":13,"title":"kaputt","date":"14.10.2026"}]}"""),
        )
        assertEquals(listOf("12"), events.map { it.id })
        assertEquals("19:00", events.single().time)
        assertEquals(1, events.single().remindDays)

        val alltag = FamilieJson.alltag(
            json("""{"ok":true,"events":[{"id":"a7","title":"Zahnarzt","date":"2026-10-16","time":"08:30","endTime":"09:00","allDay":false,"calendar":{"id":1,"name":"Familie","color":"#0B7A55"}}],"waste":[{"date":"2026-10-15","kinds":["Gelbe Tonne"],"color":"#F5C400"},{"date":"2026-10-17","kinds":[]}]}"""),
        )
        assertEquals("Familie", alltag.events.single().calendarName)
        assertEquals("#0B7A55", alltag.events.single().calendarColor)
        assertEquals(listOf("Gelbe Tonne"), alltag.waste.single().kinds)
    }

    @Test
    fun `locate requests with and without coordinates`() {
        val list = FamilieJson.locateRequests(
            json(
                """{"ok":true,"requests":[
                   {"id":21,"childId":5,"requestedBy":3,"mode":"auto","status":"answered","createdAt":"2026-10-14T14:42:05Z","expiresAt":"2026-10-14T14:52:05Z","lat":52.52,"lon":13.405,"accuracy":12.5,"at":"2026-10-14T14:42:09Z","battery":54},
                   {"id":20,"childId":5,"requestedBy":3,"mode":"confirm","status":"declined","code":"busy"},
                   {"id":19,"childId":5,"status":"answered","lat":95,"lon":13}]}""",
            ),
        )
        assertEquals(3, list.size)
        assertTrue(list[0].hasPosition)
        assertEquals(54, list[0].battery)
        assertEquals("busy", list[1].code)
        assertFalse(list[2].hasPosition, "an out-of-range latitude drops the position")

        assertEquals(30, FamilieJson.requestIn(json("""{"ok":true,"request":{"id":30,"childId":5,"status":"pending"}}"""))?.id)
        assertEquals(31, FamilieJson.requestIn(json("""{"ok":true,"id":31,"childId":5,"status":"pending"}"""))?.id)
        assertNull(FamilieJson.requestIn(json("""{"ok":true}""")))
        assertEquals(8, FamilieJson.sosId(json("""{"ok":true,"sosId":8}""")))
    }

    @Test
    fun `places are validated and bodies are built per action`() {
        val places = FamilieJson.places(
            json(
                """{"ok":true,"places":[
                   {"id":1,"ownerId":3,"targetUserId":5,"title":"Schule","note":"","lat":52.5,"lon":13.4,"radius":5000,"trigger":"exit","action":"tell","listId":null,"tellUserIds":[3],"repeat":"always","active":true,"consent":"pending"},
                   {"id":2,"ownerId":3,"title":"ohne Koordinaten"}]}""",
            ),
        )
        val p = places.single()
        assertEquals(FamiliePlace.MAX_RADIUS, p.radius)
        assertEquals(FamiliePlace.TRIGGER_EXIT, p.trigger)
        assertEquals(FamiliePlace.CONSENT_PENDING, p.consent)

        val body = FamilieJson.placeBody(PlaceDraft(title = "REWE", lat = 52.5, lon = 13.4, action = FamiliePlace.ACTION_NOTE, listId = "l1", tellUserIds = listOf(3)))
        assertTrue(body.get("listId").isJsonNull, "listId only for action list")
        assertEquals("[]", body.get("tellUserIds").toString(), "tellUserIds only for action tell")
        assertFalse(body.has("targetUserId"), "target defaults to me on the server")
    }

    @Test
    fun `error code of an error answer`() {
        assertEquals("module_off", FamilieJson.errorCode(json("""{"ok":false,"code":"module_off","error":"Modul aus"}""")))
        assertNull(FamilieJson.errorCode(json("""{"ok":false,"code":"<script>"}""")))
        assertNotNull(FamilieJson.clean("a\u0000b", 10))
        assertEquals("a b", FamilieJson.clean("a\nb", 10))
    }

    // ─── 1.1: rides ──────────────────────────────────────────────────

    @Test
    fun `rides as in the 1_1 contract, broken ones dropped`() {
        val rides = FamilieJson.rides(
            json(
                """
                { "ok": true, "rides": [
                  { "id": "3:2026-10-16", "seriesId": 3, "date": "2026-10-16", "time": "17:00", "title": "Tim → Schwimmen",
                    "place": "Hallenbad", "childIds": [5], "status": "taken", "driver": { "label": "Familie Weber" },
                    "requestedBy": { "userId": 3, "name": "Anna" }, "declined": [], "askedAt": null, "remindMinutes": 20, "note": "" },
                  { "id": "12", "seriesId": null, "date": "2026-10-14", "time": "16:00:00", "title": "Tim vom Training abholen",
                    "place": "Sporthalle Nord", "lat": 52.5, "lon": 13.4, "childIds": [5, "5"], "status": "open", "driver": null,
                    "requestedBy": { "userId": 3, "name": "Anna" }, "declined": [ { "userId": 4, "name": "Marc" } ],
                    "askedAt": "2026-10-14T13:40:00Z", "remindMinutes": 500, "note": "Tor 2" },
                  { "id": "13", "date": "2026-10-15", "time": "08:00", "title": "Lena", "status": "weird",
                    "driver": { "userId": 3, "name": "Anna" }, "lat": 52.5 },
                  { "id": "../x", "date": "2026-10-14", "time": "16:00", "title": "bad id" },
                  { "id": "14", "date": "14.10.2026", "time": "16:00", "title": "bad date" },
                  { "id": "15", "date": "2026-10-14", "time": "16:00", "title": "  " }
                ] }
                """,
            ),
        )
        assertEquals(listOf("12", "13", "3:2026-10-16"), rides.map { it.id }, "sorted by date and time")
        val open = rides[0]
        assertTrue(open.isOpen)
        assertEquals("16:00", open.time)
        assertEquals(listOf(5), open.childIds)
        assertEquals(52.5, open.lat)
        assertEquals(listOf(4), open.declined.map { it.userId })
        assertEquals(120, open.remindMinutes, "capped at 120")
        assertNull(open.driver)
        assertEquals("Tor 2", open.note)

        val member = rides[1]
        assertEquals(com.gatecontrol.android.familie.rides.Ride.STATUS_OPEN, member.status, "unknown status → open")
        assertEquals(3, member.driverId)
        assertNull(member.lat, "a half position is dropped")
        assertEquals(20, member.remindMinutes, "default reminder")

        val series = rides[2]
        assertEquals(3, series.seriesId)
        assertTrue(series.isSeries)
        assertEquals(com.gatecontrol.android.familie.rides.RideDriver.Label("Familie Weber"), series.driver)
    }

    @Test
    fun `ride bodies for create, change and the whole series`() {
        val d = com.gatecontrol.android.familie.rides.RideDraft(
            date = "2026-10-14", time = "16:00", title = "  Tim abholen ", place = "Halle", lat = 52.5, lon = 13.4,
            childIds = listOf(5, 5), driver = com.gatecontrol.android.familie.rides.DriverChoice.Label("Bus"),
            remindMinutes = 30, note = "", repeat = com.gatecontrol.android.familie.rides.RideRepeat(listOf(3, 1, 9), null), ask = false,
        )
        val create = FamilieJson.rideBody(d, create = true)
        assertEquals("Tim abholen", create.get("title").asString)
        assertEquals("[5]", create.get("childIds").toString())
        assertEquals("""{"label":"Bus"}""", create.get("driver").toString())
        assertEquals("""{"weekdays":[1,3],"endDate":null}""", create.get("repeat").toString())
        assertFalse(create.get("ask").asBoolean)

        val change = FamilieJson.rideBody(d.copy(lat = null, lon = null, repeat = null), create = false)
        assertFalse(change.has("driver"), "the driver has its own call")
        assertFalse(change.has("ask"))
        assertTrue(change.get("lat").isJsonNull, "a removed map point is cleared")
        assertEquals("2026-10-14", change.get("date").asString)

        val series = FamilieJson.rideBody(d.copy(repeat = null), create = false, series = true)
        assertFalse(series.has("date"), "a series change keeps the series' dates")

        assertEquals("""{"userId":3}""", FamilieJson.driverBody(com.gatecontrol.android.familie.rides.DriverChoice.Member(3)).toString())
    }

    @Test
    fun `modules and overview of 1_1`() {
        val p = FamilieJson.profile(
            json("""{ "me": { "userId": 3, "name": "Anna", "role": "parent" }, "modules": { "rides": true, "emergency": 1, "wall": true }, "members": [] }"""),
        )!!
        assertTrue(p.modules.rides)
        assertTrue(p.modules.emergency)
        assertTrue(p.modules.wall)
        assertTrue(p.modules.any)
        assertFalse(FamilieModules(wall = true).any, "the wall alone has no app screen")

        val o = FamilieJson.overview(
            json("""{ "rides": { "next": [ { "id": "12", "date": "2026-10-14", "time": "16:00", "title": "Tim abholen", "status": "open" } ], "open": 2 } }"""),
        )
        assertEquals(2, o.rides?.open)
        assertEquals(listOf("12"), o.rides?.next?.map { it.id })
        assertNull(FamilieJson.overview(json("""{ "rides": null }""")).rides, "module off")
    }

    // ─── 1.1: emergency card ─────────────────────────────────────────

    @Test
    fun `emergency card as in the 1_1 contract`() {
        val card = FamilieJson.emergency(
            json(
                """
                { "ok": true,
                  "members": [
                    { "userId": 5, "name": "Tim", "birthdate": "2016-11-09", "age": 9, "bloodType": "O+", "weightKg": 32,
                      "allergies": "Erdnüsse (schwer)", "medications": "Notfall-Pen", "conditions": "", "notes": "",
                      "insurance": { "name": "AOK", "number": "A123454821" },
                      "contacts": [ { "label": "Oma", "phone": "+49 221 123-456", "note": "" }, { "label": "bad", "phone": "call me" } ],
                      "updatedAt": "2026-10-14T12:00:00Z", "updatedBy": { "userId": 3, "name": "Anna" } },
                    { "userId": 3, "name": "Anna", "birthdate": null, "age": null, "bloodType": "unknown", "weightKg": null,
                      "allergies": "", "medications": "", "conditions": "", "notes": "", "insurance": { "name": "", "number": "" },
                      "contacts": [], "updatedAt": null, "updatedBy": 3 },
                    { "name": "no id" }
                  ],
                  "contacts": [ { "label": "Kinderarzt Dr. Berg", "phone": "0221 123 456", "note": "Mo–Fr 8–18" } ],
                  "updatedAt": "2026-10-14T12:00:00Z" }
                """,
            ),
        )
        assertEquals(listOf(5, 3), card.members.map { it.userId })
        val tim = card.member(5)!!
        assertEquals("0+", tim.bloodType, "O and 0 are the same")
        assertEquals(32.0, tim.weightKg)
        assertEquals("A123454821", tim.insurance?.number)
        assertEquals(listOf("Oma"), tim.contacts.map { it.label }, "a number with letters is dropped")
        assertEquals(3, tim.updatedBy)
        assertEquals("Anna", tim.updatedByName)
        assertFalse(tim.isEmpty)

        val anna = card.member(3)!!
        assertNull(anna.bloodType)
        assertNull(anna.insurance, "an empty insurance is none")
        assertEquals(3, anna.updatedBy)
        assertTrue(anna.isEmpty)
        assertEquals("Kinderarzt Dr. Berg", card.contacts.single().label)
    }

    @Test
    fun `emergency bodies are capped and clean`() {
        val body = FamilieJson.emergencyMemberBody(
            com.gatecontrol.android.familie.emergency.EmergencyMemberDraft(
                bloodType = "ab-", weightKg = 0.0, allergies = "x".repeat(400), medications = "", conditions = "", notes = "",
                insurance = com.gatecontrol.android.familie.emergency.EmergencyInsurance("", ""),
                contacts = listOf(
                    com.gatecontrol.android.familie.emergency.EmergencyContact("Kinderarzt", "0221 / 123-45 (Praxis)", ""),
                    com.gatecontrol.android.familie.emergency.EmergencyContact("leer", "", ""),
                ),
            ),
        )
        assertEquals("AB-", body.get("bloodType").asString)
        assertTrue(body.get("weightKg").isJsonNull)
        assertEquals(300, body.get("allergies").asString.length)
        assertTrue(body.get("insurance").isJsonNull)
        assertEquals("""[{"label":"Kinderarzt","phone":"0221 / 123-45 ()","note":""}]""", body.get("contacts").toString())
        assertEquals(
            20,
            FamilieJson.emergencyContactsBody(List(25) { com.gatecontrol.android.familie.emergency.EmergencyContact("L$it", "1$it", "") })
                .getAsJsonArray("contacts").size(),
            "at most 20 family numbers",
        )
    }
}
