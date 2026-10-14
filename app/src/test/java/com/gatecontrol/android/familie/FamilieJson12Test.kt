package com.gatecontrol.android.familie

import com.gatecontrol.android.familie.board.NoteDraft
import com.gatecontrol.android.familie.meals.Meal
import com.gatecontrol.android.familie.money.AllowanceBooking
import com.gatecontrol.android.familie.money.AllowanceRule
import com.gatecontrol.android.familie.money.AllowanceRuleDraft
import com.gatecontrol.android.familie.money.Expense
import com.gatecontrol.android.familie.money.ExpenseDraft
import com.gatecontrol.android.familie.money.StarRate
import com.gatecontrol.android.familie.money.Transfer
import com.gatecontrol.android.familie.money.WishDraft
import com.gatecontrol.android.familie.presence.PresenceState
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.ZoneId

/** Parsers and bodies of the Familie 1.2 modules (contract B1–B7). */
class FamilieJson12Test {

    private fun json(s: String): JsonObject = JsonParser.parseString(s).asJsonObject

    // ─── me / overview ───────────────────────────────────────────────

    @Test
    fun `the new module switches come from me`() {
        val p = FamilieJson.profile(
            json(
                """{"ok":true,"me":{"userId":3,"name":"Anna","role":"parent"},
                   "modules":{"allowance":true,"wishes":true,"expenses":1,"board":true,"meals":false,"presence":"true"},"members":[]}""",
            ),
        )!!
        assertTrue(p.modules.allowance)
        assertTrue(p.modules.wishes)
        assertTrue(p.modules.expenses)
        assertTrue(p.modules.board)
        assertFalse(p.modules.meals)
        assertTrue(p.modules.presence)
        assertTrue(p.modules.any, "a 1.2 module alone shows the tab content")
    }

    @Test
    fun `overview cards of the new modules, null when off`() {
        val o = FamilieJson.overview(
            json(
                """{"ok":true,"shopping":null,
                   "allowance":{"balanceCents":2350},
                   "meals":{"date":"2026-10-14","lunch":null,"dinner":{"title":"Pfannkuchen","cook":3,"ingredients":["Mehl","Eier"]}},
                   "board":{"pinned":[{"id":1,"text":"Zahnarzt Do 15 Uhr","pinned":true}],"openChecklists":2},
                   "presence":{"home":[3,{"userId":5,"since":"2026-10-14T13:05:00Z"}],"away":[4]}}""",
            ),
        )
        assertEquals(2350, o.allowance!!.cents)
        assertEquals("Pfannkuchen", o.meals!!.dinner!!.title)
        assertNull(o.meals!!.lunch)
        assertEquals(1, o.board!!.pinned)
        assertEquals(2, o.board!!.openChecklists)
        assertEquals(listOf("Zahnarzt Do 15 Uhr"), o.board!!.pinnedTexts)
        val presence = o.presence!!
        assertEquals(listOf(3, 5, 4), presence.map { it.userId })
        assertEquals(PresenceState.AWAY, presence.last().state)
        assertEquals("2026-10-14T13:05:00Z", presence[1].since)

        val off = FamilieJson.overview(json("""{"ok":true,"allowance":null,"meals":null,"board":null,"presence":null}"""))
        assertNull(off.allowance)
        assertNull(off.meals)
        assertNull(off.board)
        assertNull(off.presence)
    }

    @Test
    fun `overview of a parent sums the allowance and counts open requests`() {
        val a = FamilieJson12.overviewAllowance(json("""{"sumCents":4200,"pending":[{"id":1}]}"""))!!
        assertEquals(4200, a.cents)
        assertEquals(1, a.pending)
    }

    // ─── allowance ───────────────────────────────────────────────────

    @Test
    fun `allowance accounts with rule, star rate, bookings and requests`() {
        val payload = FamilieJson12.allowance(
            json(
                """{"ok":true,"accounts":[
                     {"childId":5,"balanceCents":2350,
                      "rule":{"amountCents":500,"interval":"weekly","weekday":6,"active":1},
                      "starRate":{"stars":10,"cents":100},"nextPayout":"2026-10-17","convertibleStars":12,
                      "bookings":[{"id":9,"cents":-850,"kind":"spend","note":"Kino mit Mia","at":"2026-10-12T15:00:00Z","by":3},
                                  {"id":8,"cents":500,"kind":"allowance","note":"","at":"2026-10-10T05:00:00Z"},
                                  {"id":7,"kind":"manual"}],
                      "requests":[{"id":4,"stars":10,"cents":100,"status":"pending","createdAt":"2026-10-14T10:00:00Z"}]},
                     {"childId":6,"balanceCents":-200,"rule":null,"starRate":null,"bookings":[]},
                     {"balanceCents":1}],
                   "requests":[{"id":11,"childId":6,"stars":20}]}""",
            ),
        )
        assertEquals(listOf(5, 6), payload.accounts.map { it.childId })
        val lena = payload.account(5)!!
        assertEquals(AllowanceRule(500, AllowanceRule.WEEKLY, 6, null, true), lena.rule)
        assertEquals(StarRate(10, 100), lena.starRate)
        assertEquals("2026-10-17", lena.nextPayout)
        assertEquals(12, lena.convertibleStars)
        assertEquals(listOf(9, 8), lena.bookings.map { it.id }, "a booking without cents is dropped")
        assertEquals(AllowanceBooking.KIND_SPEND, lena.bookings[0].kind)
        assertEquals(5, lena.bookings[0].childId, "the account's child")
        assertEquals(listOf(4), lena.pending.map { it.id })
        val tim = payload.account(6)!!
        assertEquals(-200, tim.balanceCents, "negative balances stay")
        assertNull(tim.rule)
        assertEquals(listOf(11), tim.pending.map { it.id }, "top-level requests join their account")
        assertEquals(listOf(4, 11), payload.pending.map { it.id })
    }

    @Test
    fun `allowance bodies`() {
        assertEquals(json("""{"cents":-850,"note":"Kino","kind":"spend"}"""), FamilieJson12.bookingBody(-850, " Kino ", "spend"))
        assertEquals("manual", FamilieJson12.bookingBody(100, "", "allowance").get("kind").asString, "only manual or spend")
        assertEquals(
            json("""{"amountCents":500,"interval":"weekly","weekday":6,"active":true,"starRate":{"stars":10,"cents":100}}"""),
            FamilieJson12.ruleBody(AllowanceRuleDraft(500, AllowanceRule.WEEKLY, 6, 1, true, StarRate(10, 100))),
        )
        assertEquals(
            json("""{"amountCents":2000,"interval":"monthly","dayOfMonth":28,"active":false,"starRate":null}"""),
            FamilieJson12.ruleBody(AllowanceRuleDraft(2000, AllowanceRule.MONTHLY, 6, 31, false, null)),
        )
    }

    // ─── wishes ──────────────────────────────────────────────────────

    @Test
    fun `wishes never show a reservation on my own items`() {
        val items = FamilieJson12.wishes(
            json(
                """{"ok":true,"items":[
                     {"id":1,"ownerId":5,"title":"Kopfhörer (kabellos)","priceCents":6000,"occasion":"birthday","url":"https://shop.example/k","reservedBy":3,"reservedAt":"2026-10-01T10:00:00Z"},
                     {"id":2,"ownerId":3,"title":"Buch","url":"http://plain.example","reservedBy":5,"reservedAt":"2026-10-01T10:00:00Z"},
                     {"id":3,"ownerId":3,"title":"","priceCents":5},
                     {"id":4,"ownerId":5,"title":"Gutschein","occasion":"easter","gotIt":1}]}""",
            ),
            myUserId = 3,
        )
        assertEquals(listOf(2, 1, 4), items.map { it.id }, "sorted by owner; an item without title is dropped")
        val mine = items.first { it.id == 2 }
        assertNull(mine.reservedBy, "own item: no reservation, whatever the server sends")
        assertNull(mine.reservedAt)
        assertNull(mine.url, "https only")
        val lenas = items.first { it.id == 1 }
        assertEquals(3, lenas.reservedBy)
        assertEquals("https://shop.example/k", lenas.url)
        assertEquals(6000, lenas.priceCents)
        assertNull(items.first { it.id == 4 }.occasion)
        assertTrue(items.first { it.id == 4 }.gotIt)
    }

    @Test
    fun `wishes grouped per member are read too`() {
        val items = FamilieJson12.wishes(json("""{"lists":[{"userId":5,"items":[{"id":7,"title":"Rad"}]},{"ownerId":6,"items":[{"id":8,"title":"Ball"}]}]}"""))
        assertEquals(listOf(5 to 7, 6 to 8), items.map { it.ownerId to it.id })
    }

    @Test
    fun `wish body`() {
        assertEquals(
            json("""{"title":"Rad","url":"https://x.example/r","priceCents":19900,"note":"blau","occasion":"christmas","ownerId":5}"""),
            FamilieJson12.wishBody(WishDraft("Rad ", "https://x.example/r", 19900, "blau", "christmas", 5)),
        )
        assertEquals(
            json("""{"title":"Rad","url":null,"priceCents":null,"note":"","occasion":null}"""),
            FamilieJson12.wishBody(WishDraft("Rad", "javascript:alert(1)", null, "", "easter")),
        )
    }

    // ─── expenses ────────────────────────────────────────────────────

    @Test
    fun `an expense month with totals and the settlement between two parents`() {
        val m = FamilieJson12.expenses(
            json(
                """{"ok":true,"expenses":[
                     {"id":1,"cents":6340,"category":"food","paidBy":4,"date":"2026-10-14","note":"REWE – Wocheneinkauf","shared":true,"createdBy":4},
                     {"id":2,"cents":4500,"category":"kids","paidBy":3,"date":"2026-10-12","note":"Schwimmkurs Tim","shared":1},
                     {"id":3,"cents":0,"category":"food","paidBy":3,"date":"2026-10-12"},
                     {"id":4,"cents":2185,"category":"weird","paidBy":3,"date":"2026-10-10","shared":false}],
                   "totals":{"all":64230,"byCategory":{"food":33400,"kids":11600,"household":10300,"leisure":8930,"bogus":5}},
                   "settlement":{"from":4,"to":3,"cents":4820},"settled":false}""",
            ),
            "2026-10",
        )
        assertEquals("2026-10", m.month)
        assertEquals(listOf(1, 2, 4), m.expenses.map { it.id }, "newest first, zero amounts dropped")
        assertEquals(Expense.OTHER, m.expenses.last().category)
        assertFalse(m.expenses.last().shared)
        assertEquals(64230, m.totalCents)
        assertEquals(setOf("food", "kids", "household", "leisure"), m.byCategory.keys)
        assertEquals(listOf(Transfer(4, 3, 4820)), m.transfers)
        assertFalse(m.settled)
    }

    @Test
    fun `three parents get a list of transfers, no settlement means nothing to even out`() {
        val three = FamilieJson12.expenses(
            json("""{"expenses":[],"totals":{"all":0,"byCategory":{}},"settlement":{"transfers":[{"from":4,"to":3,"cents":100},{"from":6,"to":3,"cents":50},{"from":3,"to":3,"cents":5}]},"settled":true}"""),
            "2026-09",
        )
        assertEquals(listOf(Transfer(4, 3, 100), Transfer(6, 3, 50)), three.transfers)
        assertTrue(three.settled)
        val none = FamilieJson12.expenses(json("""{"expenses":[{"id":1,"cents":100,"category":"food","paidBy":3,"date":"2026-10-01"}],"settlement":null}"""), "2026-10")
        assertTrue(none.transfers.isEmpty())
        assertEquals(100, none.totalCents, "totals computed when the server sends none")
        assertEquals(mapOf("food" to 100), none.byCategory)
    }

    @Test
    fun `expense body and months`() {
        assertEquals(
            json("""{"cents":6340,"category":"food","paidBy":4,"date":"2026-10-14","note":"REWE","shared":true}"""),
            FamilieJson12.expenseBody(ExpenseDraft(6340, "food", 4, "2026-10-14", " REWE ", true)),
        )
        assertTrue(FamilieJson12.isMonth("2026-10"))
        assertFalse(FamilieJson12.isMonth("2026-13"))
        assertFalse(FamilieJson12.isMonth("2026-1"))
    }

    // ─── board ───────────────────────────────────────────────────────

    @Test
    fun `board notes pinned first, checklists, templates`() {
        val b = FamilieJson12.board(
            json(
                """{"ok":true,
                   "notes":[{"id":1,"text":"Milch kaufen","color":"green","pinned":false,"authorId":3,"createdAt":"2026-10-14T08:00:00Z"},
                            {"id":2,"text":"Zahnarzt Do","color":"purple","pinned":1,"authorId":5,"createdAt":"2026-10-13T08:00:00Z","expiresAt":"2026-10-16T21:59:59Z"},
                            {"id":3,"text":"   "}],
                   "checklists":[{"id":7,"title":"Urlaub","fromTemplateId":2,"items":[{"id":1,"text":"Pässe","done":true,"doneBy":3},{"id":2,"text":"Ladekabel","done":false},{"text":"no id"}]}],
                   "templates":[{"id":2,"title":"Urlaub","items":["Pässe","Ladekabel",{"text":"Sonnencreme"},5]}]}""",
            ),
        )
        assertEquals(listOf(2, 1), b.notes.map { it.id })
        assertEquals("yellow", b.notes[0].color, "unknown colour → yellow")
        assertEquals("2026-10-16T21:59:59Z", b.notes[0].expiresAt)
        val list = b.checklists.single()
        assertEquals(1, list.open)
        assertEquals(2, list.fromTemplateId)
        assertEquals(listOf("Pässe", "Ladekabel", "Sonnencreme"), b.templates.single().items)
    }

    @Test
    fun `note body ends on the evening of the chosen day`() {
        val body = FamilieJson12.noteBody(NoteDraft("Zahnarzt", "blue", true, "2026-10-16"), ZoneId.of("Europe/Berlin"))
        assertEquals(json("""{"text":"Zahnarzt","color":"blue","pinned":true,"expiresAt":"2026-10-16T21:59:59Z"}"""), body)
        assertTrue(FamilieJson12.noteBody(NoteDraft("x")).get("expiresAt").isJsonNull)
        assertEquals(json("""{"templateId":2}"""), FamilieJson12.checklistBody(2, " "))
        assertEquals(json("""{"title":"Schwimmbad"}"""), FamilieJson12.checklistBody(null, "Schwimmbad"))
    }

    // ─── meals ───────────────────────────────────────────────────────

    @Test
    fun `a meal week always has seven days`() {
        val w = FamilieJson12.meals(
            json(
                """{"ok":true,"week":"2026-W42","days":[
                     {"date":"2026-10-14","lunch":{"title":"Nudeln","cook":null,"ingredients":[]},"dinner":{"title":"Pfannkuchen","note":"mit Apfelmus","cook":3,"ingredients":["Mehl","Eier",""," Milch "]}},
                     {"date":"bad"}]}""",
            ),
            "2026-W42",
        )
        assertEquals(7, w.days.size)
        assertEquals("2026-10-12", w.days.first().date)
        assertEquals("2026-10-18", w.days.last().date)
        val wed = w.days[2]
        assertEquals("Nudeln", wed.lunch!!.title)
        assertEquals(listOf("Mehl", "Eier", "Milch"), wed.dinner!!.ingredients)
        assertEquals(3, wed.dinner!!.cook)
        assertNull(w.days[0].lunch)
    }

    @Test
    fun `meal body dedups ingredients and lines are split`() {
        assertEquals(
            json("""{"title":"Pfannkuchen","note":"","cook":null,"ingredients":["Mehl","Eier"]}"""),
            FamilieJson12.mealBody(Meal("Pfannkuchen", "", null, listOf("Mehl", "Eier", "eier", " ", "mehl"))),
        )
        assertEquals(listOf("Mehl", "Eier", "Milch"), FamilieJson12.ingredientLines("Mehl\nEier, Milch;\n\nmehl"))
    }

    // ─── presence ────────────────────────────────────────────────────

    @Test
    fun `presence states and consent`() {
        val o = json(
            """{"ok":true,"members":[
                 {"userId":3,"state":"home","since":"2026-10-14T15:20:00Z","share":true},
                 {"userId":4,"home":false,"consent":true},
                 {"userId":5,"state":"not_shared","share":false},
                 {"userId":6,"share":true},
                 {"name":"x"}]}""",
        )
        val p = FamilieJson12.presence(o)
        assertEquals(listOf(3, 4, 5, 6), p.members.map { it.userId })
        assertEquals(PresenceState.HOME, p.of(3)!!.state)
        assertEquals(PresenceState.AWAY, p.of(4)!!.state)
        assertEquals(PresenceState.NOT_SHARED, p.of(5)!!.state)
        assertFalse(p.of(5)!!.shares)
        assertEquals(PresenceState.UNKNOWN, p.of(6)!!.state)
        assertEquals(listOf(3), p.home.map { it.userId })
        assertEquals(true, FamilieJson12.myConsent(o, 3))
        assertEquals(false, FamilieJson12.myConsent(o, 5))
        assertNull(FamilieJson12.myConsent(o, 99))
        assertEquals(true, FamilieJson12.myConsent(json("""{"consent":{"share":true},"members":[]}"""), 3))
        assertEquals(json("""{"home":true,"at":"2026-10-14T15:20:00Z"}"""), FamilieJson12.presenceBody(true, "2026-10-14T15:20:00Z"))
    }
}
