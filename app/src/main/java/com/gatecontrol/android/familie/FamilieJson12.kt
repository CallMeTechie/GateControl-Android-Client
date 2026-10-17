package com.gatecontrol.android.familie

import com.gatecontrol.android.familie.FamilieJson.arr
import com.gatecontrol.android.familie.FamilieJson.bool
import com.gatecontrol.android.familie.FamilieJson.clean
import com.gatecontrol.android.familie.FamilieJson.date
import com.gatecontrol.android.familie.FamilieJson.dbl
import com.gatecontrol.android.familie.FamilieJson.int
import com.gatecontrol.android.familie.FamilieJson.ints
import com.gatecontrol.android.familie.FamilieJson.obj
import com.gatecontrol.android.familie.FamilieJson.objects
import com.gatecontrol.android.familie.FamilieJson.stamp
import com.gatecontrol.android.familie.FamilieJson.str
import com.gatecontrol.android.familie.board.BoardNote
import com.gatecontrol.android.familie.board.BoardPayload
import com.gatecontrol.android.familie.board.Checklist
import com.gatecontrol.android.familie.board.ChecklistItem
import com.gatecontrol.android.familie.board.ChecklistTemplate
import com.gatecontrol.android.familie.board.NoteDraft
import com.gatecontrol.android.familie.board.OverviewBoard
import com.gatecontrol.android.familie.meals.Meal
import com.gatecontrol.android.familie.meals.MealDay
import com.gatecontrol.android.familie.meals.MealWeek
import com.gatecontrol.android.familie.meals.OverviewMeals
import com.gatecontrol.android.familie.money.AllowanceAccount
import com.gatecontrol.android.familie.money.AllowanceBooking
import com.gatecontrol.android.familie.money.AllowancePayload
import com.gatecontrol.android.familie.money.AllowanceRule
import com.gatecontrol.android.familie.money.AllowanceRuleDraft
import com.gatecontrol.android.familie.money.ConvertRequest
import com.gatecontrol.android.familie.money.Expense
import com.gatecontrol.android.familie.money.ExpenseDraft
import com.gatecontrol.android.familie.money.ExpenseMonth
import com.gatecontrol.android.familie.money.OverviewAllowance
import com.gatecontrol.android.familie.money.StarRate
import com.gatecontrol.android.familie.money.Transfer
import com.gatecontrol.android.familie.money.WishDraft
import com.gatecontrol.android.familie.money.WishItem
import com.gatecontrol.android.familie.places.RuleFence
import com.gatecontrol.android.familie.places.RuleWatch
import com.gatecontrol.android.familie.presence.PresenceMember
import com.gatecontrol.android.familie.presence.PresencePayload
import com.gatecontrol.android.familie.presence.PresenceState
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Parsers and request bodies of the Familie 1.2 modules (allowance, wishes,
 * expenses, board, meals, presence) — the same hand-written Gson tree
 * style as [FamilieJson] (no reflection, so R8 cannot break it): lenient
 * where SQLite leaks through, strict about ids and required fields, texts
 * plain and capped.
 */
object FamilieJson12 {

    private val MONTH_RE = Regex("^\\d{4}-(0[1-9]|1[0-2])$")

    /** An int field, also accepted as one of [keys] (first that is there). */
    private fun JsonObject.intOf(vararg keys: String): Int? = keys.firstNotNullOfOrNull { int(it) }

    /** A count: the number itself or the size of an array. */
    private fun JsonObject.count(vararg keys: String): Int? = keys.firstNotNullOfOrNull { k ->
        int(k) ?: arr(k)?.size()
    }?.coerceAtLeast(0)

    private fun nullable(value: String?): JsonElement = value?.let { JsonPrimitive(it) } ?: JsonNull.INSTANCE

    private fun nullable(value: Int?): JsonElement = value?.let { JsonPrimitive(it) } ?: JsonNull.INSTANCE

    // ─── Taschengeld ─────────────────────────────────────────────────

    private fun rule(o: JsonObject?): AllowanceRule? {
        if (o == null) return null
        val amount = o.intOf("amountCents", "cents")?.takeIf { it in 0..AllowanceRule.MAX_CENTS } ?: return null
        val interval = if (o.str("interval") == AllowanceRule.MONTHLY) AllowanceRule.MONTHLY else AllowanceRule.WEEKLY
        return AllowanceRule(
            amountCents = amount,
            interval = interval,
            weekday = o.int("weekday")?.takeIf { it in 1..7 },
            dayOfMonth = o.int("dayOfMonth")?.takeIf { it in 1..28 },
            active = o.bool("active") != false,
        )
    }

    private fun starRate(o: JsonObject?): StarRate? {
        val stars = o?.int("stars")?.takeIf { it > 0 } ?: return null
        val cents = o.int("cents")?.takeIf { it > 0 } ?: return null
        return StarRate(stars, cents)
    }

    fun booking(o: JsonObject, childId: Int? = null): AllowanceBooking? {
        val id = o.int("id") ?: return null
        val child = o.int("childId") ?: childId ?: return null
        val cents = o.int("cents") ?: return null
        return AllowanceBooking(
            id = id,
            childId = child,
            cents = cents,
            kind = o.str("kind")?.takeIf { it in AllowanceBooking.KINDS } ?: AllowanceBooking.KIND_MANUAL,
            note = clean(o.str("note"), AllowanceBooking.MAX_NOTE),
            at = stamp(o.str("at") ?: o.str("createdAt")),
            by = (o.obj("by")?.int("userId")) ?: o.int("by"),
        )
    }

    fun convertRequest(o: JsonObject, childId: Int? = null): ConvertRequest? {
        val id = o.int("id") ?: o.int("requestId") ?: return null
        val child = o.int("childId") ?: childId ?: return null
        val stars = o.int("stars")?.takeIf { it > 0 } ?: return null
        return ConvertRequest(
            id = id,
            childId = child,
            stars = stars,
            cents = o.int("cents")?.takeIf { it >= 0 },
            status = clean(o.str("status"), 20).ifBlank { ConvertRequest.PENDING },
            createdAt = stamp(o.str("createdAt") ?: o.str("at")),
        )
    }

    private fun account(o: JsonObject): AllowanceAccount? {
        val child = o.int("childId") ?: o.int("userId") ?: return null
        val rate = starRate(o.obj("starRate"))
        return AllowanceAccount(
            childId = child,
            balanceCents = o.intOf("balanceCents", "balance") ?: 0,
            rule = rule(o.obj("rule")),
            starRate = rate,
            nextPayout = (o.obj("nextPayout")?.let { it.str("date") ?: it.str("at") } ?: o.str("nextPayout"))
                ?.let { date(it) ?: stamp(it) },
            convertibleStars = o.intOf("convertibleStars", "starsConvertible", "starBalance")
                ?: o.obj("stars")?.intOf("convertible", "available", "balance")
                ?: o.int("stars"),
            bookings = o.arr("bookings")?.objects()?.mapNotNull { booking(it, child) }.orEmpty().take(50),
            requests = (o.arr("requests") ?: o.arr("convertRequests"))?.objects()?.mapNotNull { convertRequest(it, child) }.orEmpty(),
        )
    }

    /**
     * `GET allowance` → accounts (key `accounts`, or a single `account`), each
     * with its bookings; convert requests per account or in a top-level
     * `requests` list (both are merged).
     */
    fun allowance(o: JsonObject): AllowancePayload {
        val list = o.arr("accounts")?.objects()?.mapNotNull(::account)
            ?: o.obj("account")?.let(::account)?.let(::listOf)
            ?: emptyList()
        val loose = o.arr("requests")?.objects()?.mapNotNull { convertRequest(it) }.orEmpty()
        val accounts = list.distinctBy { it.childId }.map { a ->
            val extra = loose.filter { r -> r.childId == a.childId && a.requests.none { it.id == r.id } }
            if (extra.isEmpty()) a else a.copy(requests = a.requests + extra)
        }
        return AllowancePayload(accounts)
    }

    /** `POST allowance/<childId>/bookings`: [cents] signed (spend is negative). */
    fun bookingBody(cents: Int, note: String, kind: String): JsonObject = JsonObject().apply {
        addProperty("cents", cents)
        addProperty("note", note.trim().take(AllowanceBooking.MAX_NOTE))
        addProperty("kind", if (kind == AllowanceBooking.KIND_SPEND) AllowanceBooking.KIND_SPEND else AllowanceBooking.KIND_MANUAL)
    }

    /** `PUT allowance/<childId>/rule` (with the star rate, null: no converting). */
    fun ruleBody(d: AllowanceRuleDraft): JsonObject = JsonObject().apply {
        addProperty("amountCents", d.amountCents.coerceIn(0, AllowanceRule.MAX_CENTS))
        addProperty("interval", d.interval)
        if (d.interval == AllowanceRule.MONTHLY) addProperty("dayOfMonth", d.dayOfMonth.coerceIn(1, 28))
        else addProperty("weekday", d.weekday.coerceIn(1, 7))
        addProperty("active", d.active)
        add(
            "starRate",
            d.starRate?.takeIf { it.stars > 0 && it.cents > 0 }?.let { r ->
                JsonObject().apply {
                    addProperty("stars", r.stars)
                    addProperty("cents", r.cents)
                }
            } ?: JsonNull.INSTANCE,
        )
    }

    fun overviewAllowance(o: JsonObject?): OverviewAllowance? {
        if (o == null) return null
        val cents = o.intOf("balanceCents", "sumCents", "totalCents", "cents", "balance") ?: 0
        return OverviewAllowance(
            cents = cents,
            pending = o.count("pending", "pendingRequests", "requests") ?: 0,
            nextPayout = date(o.str("nextPayout")),
            amountCents = o.int("amountCents")?.coerceAtLeast(0) ?: 0,
        )
    }

    // ─── Wunschlisten ────────────────────────────────────────────────

    /** https links only (contract), at most 300 characters. */
    fun httpsUrl(value: String?): String? = value?.trim()?.takeIf {
        it.length <= WishItem.MAX_URL && it.startsWith("https://", ignoreCase = true) && it.length > 8 &&
            it.none { c -> c.isWhitespace() || c.code < 32 }
    }

    fun wish(o: JsonObject, ownerId: Int? = null): WishItem? {
        val id = o.int("id") ?: return null
        val owner = o.int("ownerId") ?: ownerId ?: return null
        val title = clean(o.str("title"), WishItem.MAX_TITLE)
        if (title.isBlank()) return null
        val reservedBy = o.obj("reservedBy")?.int("userId") ?: o.int("reservedBy")
        return WishItem(
            id = id,
            ownerId = owner,
            title = title,
            url = httpsUrl(o.str("url")),
            priceCents = o.int("priceCents")?.takeIf { it >= 0 },
            note = clean(o.str("note"), WishItem.MAX_NOTE),
            occasion = o.str("occasion")?.takeIf { it in WishItem.OCCASIONS },
            sort = o.int("sort") ?: 0,
            createdAt = stamp(o.str("createdAt")),
            reservedBy = reservedBy,
            reservedAt = if (reservedBy != null) stamp(o.str("reservedAt")) else null,
            gotIt = o.bool("gotIt") == true,
        )
    }

    /**
     * `GET wishes` → every member's items: a flat `items` (or `wishes`)
     * list, or `lists: [{userId|ownerId, items}]`. A reservation on one of
     * [myUserId]'s own items is dropped here too, whatever the server sends.
     */
    fun wishes(o: JsonObject, myUserId: Int? = null): List<WishItem> {
        val flat = (o.arr("items") ?: o.arr("wishes"))?.objects()?.mapNotNull { wish(it) }.orEmpty()
        val grouped = (o.arr("lists") ?: o.arr("members"))?.objects()?.flatMap { l ->
            val owner = l.int("userId") ?: l.int("ownerId")
            l.arr("items")?.objects()?.mapNotNull { wish(it, owner) }.orEmpty()
        }.orEmpty()
        return (flat + grouped).distinctBy { it.id }
            .map { if (it.ownerId == myUserId && it.reserved) it.copy(reservedBy = null, reservedAt = null) else it }
            .sortedWith(compareBy({ it.ownerId }, { it.sort }, { it.id }))
    }

    fun wishBody(d: WishDraft): JsonObject = JsonObject().apply {
        addProperty("title", d.title.trim().take(WishItem.MAX_TITLE))
        add("url", nullable(httpsUrl(d.url)))
        add("priceCents", nullable(d.priceCents?.takeIf { it >= 0 }))
        addProperty("note", d.note.trim().take(WishItem.MAX_NOTE))
        add("occasion", nullable(d.occasion?.takeIf { it in WishItem.OCCASIONS }))
        d.ownerId?.let { addProperty("ownerId", it) }
    }

    // ─── Haushaltsbuch ───────────────────────────────────────────────

    fun isMonth(value: String?): Boolean = value != null && MONTH_RE.matches(value)

    fun expense(o: JsonObject): Expense? {
        val id = o.int("id") ?: return null
        val cents = o.int("cents")?.takeIf { it > 0 } ?: return null
        val paidBy = o.obj("paidBy")?.int("userId") ?: o.int("paidBy") ?: return null
        val date = date(o.str("date")) ?: return null
        return Expense(
            id = id,
            cents = cents,
            category = o.str("category")?.takeIf { it in Expense.CATEGORIES } ?: Expense.OTHER,
            paidBy = paidBy,
            date = date,
            note = clean(o.str("note"), Expense.MAX_NOTE),
            shared = o.bool("shared") != false,
            createdBy = o.obj("createdBy")?.int("userId") ?: o.int("createdBy"),
            listId = o.str("listId")?.takeIf { LIST_ID_RE.matches(it) },
        )
    }

    private val LIST_ID_RE = Regex("^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$")

    private fun transfer(o: JsonObject): Transfer? {
        val from = o.obj("from")?.int("userId") ?: o.int("from") ?: return null
        val to = o.obj("to")?.int("userId") ?: o.int("to") ?: return null
        val cents = o.int("cents")?.takeIf { it > 0 } ?: return null
        return if (from == to) null else Transfer(from, to, cents)
    }

    /**
     * `GET expenses?month=` → expenses, `totals {all, byCategory}`,
     * `settlement {from,to,cents} | null` (or with 3+ parents
     * `transfers: [...]`, top level or inside the settlement), `settled`.
     */
    fun expenses(o: JsonObject, month: String): ExpenseMonth {
        val totals = o.obj("totals")
        val list = o.arr("expenses")?.objects()?.mapNotNull(::expense).orEmpty()
            .sortedWith(compareByDescending<Expense> { it.date }.thenByDescending { it.id })
        val byCategory = totals?.obj("byCategory")?.entrySet()?.mapNotNull { (k, _) ->
            if (k !in Expense.CATEGORIES) return@mapNotNull null
            totals.obj("byCategory")?.int(k)?.takeIf { it >= 0 }?.let { k to it }
        }?.toMap() ?: list.groupBy { it.category }.mapValues { e -> e.value.sumOf { it.cents } }
        val settlement = o.obj("settlement")
        val transfers = (o.arr("transfers") ?: settlement?.arr("transfers"))?.objects()?.mapNotNull(::transfer)
            ?: settlement?.let(::transfer)?.let(::listOf)
            ?: emptyList()
        return ExpenseMonth(
            month = o.str("month")?.takeIf { isMonth(it) } ?: month,
            expenses = list,
            totalCents = totals?.int("all") ?: list.sumOf { it.cents },
            sharedCents = totals?.int("shared") ?: list.filter { it.shared }.sumOf { it.cents },
            byCategory = byCategory,
            transfers = transfers,
            settled = o.bool("settled") == true || settlement?.bool("settled") == true,
        )
    }

    fun expenseBody(d: ExpenseDraft): JsonObject = JsonObject().apply {
        addProperty("cents", d.cents.coerceIn(1, Expense.MAX_CENTS))
        addProperty("category", d.category.takeIf { it in Expense.CATEGORIES } ?: Expense.OTHER)
        addProperty("paidBy", d.paidBy)
        addProperty("date", d.date)
        addProperty("note", d.note.trim().take(Expense.MAX_NOTE))
        addProperty("shared", d.shared)
        d.listId?.takeIf { LIST_ID_RE.matches(it) }?.let { addProperty("listId", it) }
    }

    fun overviewExpenses(o: JsonObject?): com.gatecontrol.android.familie.money.OverviewExpenses? {
        if (o == null) return null
        val total = o.intOf("totalCents", "all") ?: 0
        return com.gatecontrol.android.familie.money.OverviewExpenses(
            month = o.str("month")?.takeIf { isMonth(it) },
            totalCents = total,
            sharedCents = o.int("sharedCents") ?: total,
            settled = o.bool("settled") == true,
        )
    }

    fun overviewWishes(o: JsonObject?): com.gatecontrol.android.familie.money.OverviewWishes? =
        o?.let { com.gatecontrol.android.familie.money.OverviewWishes((it.int("mine") ?: 0).coerceAtLeast(0), (it.int("reservedByMe") ?: 0).coerceAtLeast(0)) }

    /** `POST meals/…/to-shopping` → `{added, skipped}`. */
    fun shoppingAdded(o: JsonObject): com.gatecontrol.android.familie.money.ShoppingAdded =
        com.gatecontrol.android.familie.money.ShoppingAdded((o.int("added") ?: 0).coerceAtLeast(0), (o.int("skipped") ?: 0).coerceAtLeast(0))

    // ─── Pinnwand & Packlisten ───────────────────────────────────────

    fun note(o: JsonObject): BoardNote? {
        val id = o.int("id") ?: return null
        val text = clean(o.str("text"), BoardNote.MAX_TEXT)
        if (text.isBlank()) return null
        return BoardNote(
            id = id,
            text = text,
            color = o.str("color")?.takeIf { it in BoardNote.COLORS } ?: BoardNote.YELLOW,
            pinned = o.bool("pinned") == true,
            authorId = o.obj("author")?.int("userId") ?: o.int("authorId"),
            createdAt = stamp(o.str("createdAt")),
            expiresAt = stamp(o.str("expiresAt")),
        )
    }

    private fun checklistItem(o: JsonObject): ChecklistItem? {
        val id = o.int("id") ?: return null
        val text = clean(o.str("text"), ChecklistItem.MAX_TEXT)
        if (text.isBlank()) return null
        return ChecklistItem(id, text, o.bool("done") == true, o.obj("doneBy")?.int("userId") ?: o.int("doneBy"))
    }

    fun checklist(o: JsonObject): Checklist? {
        val id = o.int("id") ?: return null
        val title = clean(o.str("title"), Checklist.MAX_TITLE)
        if (title.isBlank()) return null
        return Checklist(
            id = id,
            title = title,
            items = o.arr("items")?.objects()?.mapNotNull(::checklistItem).orEmpty().distinctBy { it.id },
            fromTemplateId = o.int("fromTemplateId"),
            createdAt = stamp(o.str("createdAt")),
        )
    }

    private fun template(o: JsonObject): ChecklistTemplate? {
        val id = o.int("id") ?: return null
        val title = clean(o.str("title"), Checklist.MAX_TITLE)
        if (title.isBlank()) return null
        val items = o.arr("items")?.mapNotNull { e ->
            when {
                e.isJsonPrimitive -> clean(e.asJsonPrimitive.takeIf { it.isString }?.asString, ChecklistItem.MAX_TEXT)
                e.isJsonObject -> clean(e.asJsonObject.str("text"), ChecklistItem.MAX_TEXT)
                else -> null
            }?.takeIf { it.isNotBlank() }
        }.orEmpty()
        return ChecklistTemplate(id, title, items)
    }

    /** `GET board` → notes (pinned first, newest first), checklists, templates. */
    fun board(o: JsonObject): BoardPayload = BoardPayload(
        notes = o.arr("notes")?.objects()?.mapNotNull(::note).orEmpty().distinctBy { it.id }
            .sortedWith(compareByDescending<BoardNote> { it.pinned }.thenByDescending { it.createdAt.orEmpty() }.thenByDescending { it.id }),
        checklists = o.arr("checklists")?.objects()?.mapNotNull(::checklist).orEmpty().distinctBy { it.id },
        templates = o.arr("templates")?.objects()?.mapNotNull(::template).orEmpty().distinctBy { it.id },
    )

    /** End of [date] in [zone] as an ISO UTC time (`expiresAt`), null for none. */
    fun endOfDay(date: String?, zone: ZoneId): String? {
        val d = date?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
        val instant = d.plusDays(1).atStartOfDay(zone).toInstant().minusSeconds(1)
        return DateTimeFormatter.ISO_INSTANT.format(instant.atOffset(ZoneOffset.UTC).withNano(0))
    }

    fun noteBody(d: NoteDraft, zone: ZoneId = ZoneId.systemDefault()): JsonObject = JsonObject().apply {
        addProperty("text", d.text.trim().take(BoardNote.MAX_TEXT))
        addProperty("color", d.color.takeIf { it in BoardNote.COLORS } ?: BoardNote.YELLOW)
        addProperty("pinned", d.pinned)
        add("expiresAt", nullable(endOfDay(d.expiresAt, zone)))
    }

    /** `POST board/checklists`: from a template, or a new empty list. */
    fun checklistBody(templateId: Int?, title: String?): JsonObject = JsonObject().apply {
        if (templateId != null) addProperty("templateId", templateId)
        title?.trim()?.take(Checklist.MAX_TITLE)?.takeIf { it.isNotBlank() }?.let { addProperty("title", it) }
    }

    fun overviewBoard(o: JsonObject?): OverviewBoard? {
        if (o == null) return null
        val pinnedNotes = (o.arr("pinned") ?: o.arr("notes"))?.objects()?.mapNotNull(::note).orEmpty()
        return OverviewBoard(
            pinned = o.count("pinned", "pinnedNotes", "pinnedCount") ?: pinnedNotes.size,
            openChecklists = o.count("openChecklists", "checklists", "open") ?: 0,
            pinnedTexts = pinnedNotes.map { it.text.take(80) }.take(3),
        )
    }

    // ─── Essensplan ──────────────────────────────────────────────────

    fun meal(o: JsonObject?): Meal? {
        if (o == null) return null
        val title = clean(o.str("title"), Meal.MAX_TITLE)
        if (title.isBlank()) return null
        return Meal(
            title = title,
            note = clean(o.str("note"), Meal.MAX_NOTE),
            cook = o.obj("cook")?.int("userId") ?: o.int("cook"),
            ingredients = o.arr("ingredients")?.mapNotNull { e ->
                e.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.let { clean(it, Meal.MAX_INGREDIENT) }?.takeIf { it.isNotBlank() }
            }.orEmpty().take(Meal.MAX_INGREDIENTS),
        )
    }

    /** `GET meals?week=` → the week, always seven days (missing ones empty). */
    fun meals(o: JsonObject, week: String): MealWeek {
        val key = o.str("week")?.takeIf { MealWeek.isKey(it) } ?: week
        val days = o.arr("days")?.objects()?.mapNotNull { d ->
            val date = date(d.str("date")) ?: return@mapNotNull null
            MealDay(date, meal(d.obj("lunch")), meal(d.obj("dinner")))
        }.orEmpty()
        return MealWeek(key, MealWeek.complete(key, days))
    }

    fun mealBody(m: Meal): JsonObject = JsonObject().apply {
        addProperty("title", m.title.trim().take(Meal.MAX_TITLE))
        addProperty("note", m.note.trim().take(Meal.MAX_NOTE))
        add("cook", nullable(m.cook))
        add("ingredients", JsonArray().apply {
            m.ingredients.map { it.trim().take(Meal.MAX_INGREDIENT) }.filter { it.isNotBlank() }.distinctBy { it.lowercase() }
                .take(Meal.MAX_INGREDIENTS).forEach { add(it) }
        })
    }

    /** Ingredients typed one per line (or separated by commas). */
    fun ingredientLines(text: String): List<String> =
        text.split('\n', ',', ';').map { it.trim().take(Meal.MAX_INGREDIENT) }.filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }.take(Meal.MAX_INGREDIENTS)

    /** `overview.meals`: `{date, lunch, dinner}` or `{today: {…}}`. */
    fun overviewMeals(o: JsonObject?): OverviewMeals? {
        if (o == null) return null
        val today = o.obj("today") ?: o
        return OverviewMeals(date(today.str("date")), meal(today.obj("lunch")), meal(today.obj("dinner")))
    }

    // ─── Anwesenheit ─────────────────────────────────────────────────

    private fun presenceMember(o: JsonObject): PresenceMember? {
        val id = o.int("userId") ?: return null
        val shares = o.bool("share") ?: o.bool("shares") ?: o.bool("consent") ?: o.bool("shared")
        val home = o.bool("home")
        val state = PresenceState.fromWire(o.str("state") ?: o.str("presence"))
            ?: when {
                shares == false -> PresenceState.NOT_SHARED
                home == true -> PresenceState.HOME
                home == false -> PresenceState.AWAY
                else -> PresenceState.UNKNOWN
            }
        return PresenceMember(
            userId = id,
            state = state,
            since = stamp(o.str("since")),
            shares = shares ?: (state != PresenceState.NOT_SHARED),
        )
    }

    /** `GET presence` → members with state and consent (also accepts `states`). */
    fun presence(o: JsonObject): PresencePayload = PresencePayload(
        (o.arr("members") ?: o.arr("states") ?: o.arr("presence"))?.objects()?.mapNotNull(::presenceMember).orEmpty().distinctBy { it.userId },
    )

    /** My own consent in a `GET presence` / `PUT presence/consent` answer, else null. */
    fun myConsent(o: JsonObject, myUserId: Int): Boolean? =
        o.bool("share") ?: o.obj("consent")?.bool("share") ?: o.bool("consent")
            ?: o.obj("me")?.let { it.bool("share") ?: it.bool("consent") }
            ?: presence(o).of(myUserId)?.shares

    /**
     * `overview.presence` (who is home): `members` like `GET presence`, or
     * `home: [userId | {userId, since}]`.
     */
    fun overviewPresence(o: JsonElement?): List<PresenceMember>? {
        if (o == null || o.isJsonNull) return null
        if (o.isJsonArray) return o.asJsonArray.objects().mapNotNull(::presenceMember)
        if (!o.isJsonObject) return null
        val p = o.asJsonObject
        p.arr("members")?.let { return presence(p).members }
        val home = p.arr("home")?.mapNotNull { e ->
            when {
                e.isJsonObject -> e.asJsonObject.int("userId")?.let { PresenceMember(it, PresenceState.HOME, stamp(e.asJsonObject.str("since")), true) }
                e.isJsonPrimitive -> e.asJsonPrimitive.takeIf { it.isNumber }?.asDouble?.takeIf { it % 1.0 == 0.0 }?.toInt()
                    ?.let { PresenceMember(it, PresenceState.HOME, null, true) }
                else -> null
            }
        }.orEmpty()
        val away = p.ints("away").map { PresenceMember(it, PresenceState.AWAY, null, true) }
        return (home + away).distinctBy { it.userId }
    }

    /** My consent to place events for rules (1.5) in a `GET presence` answer, else null (older server). */
    fun myPlacesConsent(o: JsonObject): Boolean? = o.obj("me")?.bool("places") ?: o.obj("consent")?.bool("places")

    /** The version of my rule-place watch list in a presence answer (1.5), else null. */
    fun watchVersion(o: JsonObject): String? = o.str("watchVersion")?.let { clean(it, 64) }?.takeIf { it.isNotEmpty() }

    /** `GET presence/watch` (1.5): the rule places this phone watches; unusable entries are dropped. */
    fun ruleWatch(o: JsonObject): RuleWatch = RuleWatch(
        places = o.arr("places")?.objects()?.mapNotNull(::ruleFence).orEmpty().distinctBy { it.placeId },
        version = o.str("version")?.let { clean(it, 64) }?.takeIf { it.isNotEmpty() },
    )

    private fun ruleFence(o: JsonObject): RuleFence? {
        val id = o.int("id")?.takeIf { it > 0 } ?: return null
        val lat = o.dbl("lat")?.takeIf { it in -90.0..90.0 } ?: return null
        val lon = o.dbl("lon")?.takeIf { it in -180.0..180.0 } ?: return null
        val radius = o.int("radius")?.coerceIn(RuleFence.MIN_RADIUS, RuleFence.MAX_RADIUS) ?: return null
        val transitions = o.arr("transitions")?.mapNotNull { e -> e.takeIf { it.isJsonPrimitive }?.asString }.orEmpty()
        val enter = RuleFence.ENTER in transitions
        val exit = RuleFence.EXIT in transitions
        if (!enter && !exit) return null
        return RuleFence(id, clean(o.str("name"), 40), lat, lon, radius, enter, exit)
    }

    fun presenceBody(home: Boolean, at: String): JsonObject = JsonObject().apply {
        addProperty("home", home)
        addProperty("at", at)
    }
}
