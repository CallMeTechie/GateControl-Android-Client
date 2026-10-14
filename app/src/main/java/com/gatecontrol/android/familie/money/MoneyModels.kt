package com.gatecontrol.android.familie.money

/*
 * Familie 1.2 money modules: Taschengeld (`allowance`), Wunschlisten
 * (`wishes`), Haushaltsbuch (`expenses`). Money is bookkeeping only —
 * nothing is ever paid or transferred. Amounts are integer cents (EUR).
 * Parsed by hand in [com.gatecontrol.android.familie.FamilieJson12].
 */

// ─── Taschengeld ──────────────────────────────────────────────────────

/** When the allowance is booked: weekly on [weekday] (1 = Monday) or monthly on [dayOfMonth] (1…28). */
data class AllowanceRule(
    val amountCents: Int,
    /** weekly | monthly */
    val interval: String,
    val weekday: Int?,
    val dayOfMonth: Int?,
    val active: Boolean,
) {
    companion object {
        const val WEEKLY = "weekly"
        const val MONTHLY = "monthly"
        const val MAX_CENTS = 100_000
    }
}

/** [stars] stars are worth [cents]. */
data class StarRate(val stars: Int, val cents: Int)

data class AllowanceBooking(
    val id: Int,
    val childId: Int,
    /** Positive: money in; negative: money out. */
    val cents: Int,
    /** allowance | manual | stars | spend */
    val kind: String,
    val note: String,
    val at: String?,
    val by: Int?,
) {
    companion object {
        const val KIND_ALLOWANCE = "allowance"
        const val KIND_MANUAL = "manual"
        const val KIND_STARS = "stars"
        const val KIND_SPEND = "spend"
        val KINDS = listOf(KIND_ALLOWANCE, KIND_MANUAL, KIND_STARS, KIND_SPEND)
        const val MAX_NOTE = 80
    }
}

/** A child asked to turn stars into money; parents approve or decline. */
data class ConvertRequest(
    val id: Int,
    val childId: Int,
    val stars: Int,
    /** What it is worth, when the server says. */
    val cents: Int?,
    /** pending | approved | declined */
    val status: String,
    val createdAt: String?,
) {
    val isPending: Boolean get() = status == PENDING

    companion object {
        const val PENDING = "pending"
    }
}

data class AllowanceAccount(
    val childId: Int,
    val balanceCents: Int,
    val rule: AllowanceRule?,
    val starRate: StarRate?,
    /** Next payout (`YYYY-MM-DD` or an ISO time), null without an active rule. */
    val nextPayout: String?,
    /** Stars that can still be turned into money (earned − converted), null when unknown. */
    val convertibleStars: Int?,
    /** Newest first, at most 50. */
    val bookings: List<AllowanceBooking>,
    val requests: List<ConvertRequest>,
) {
    val pending: List<ConvertRequest> get() = requests.filter { it.isPending }
}

/** `GET allowance`: parents all children, a child only its own account. */
data class AllowancePayload(val accounts: List<AllowanceAccount>) {
    fun account(childId: Int?): AllowanceAccount? = accounts.firstOrNull { it.childId == childId }
    val pending: List<ConvertRequest> get() = accounts.flatMap { it.pending }
}

/** `PUT allowance/<childId>/rule`. */
data class AllowanceRuleDraft(
    val amountCents: Int,
    val interval: String = AllowanceRule.WEEKLY,
    val weekday: Int = 6,
    val dayOfMonth: Int = 1,
    val active: Boolean = true,
    /** null: stars cannot be converted. */
    val starRate: StarRate? = null,
)

/** `overview.allowance`: a child's balance, or for parents the sum of all children. */
data class OverviewAllowance(val cents: Int, val pending: Int = 0)

// ─── Wunschlisten ─────────────────────────────────────────────────────

data class WishItem(
    val id: Int,
    val ownerId: Int,
    val title: String,
    /** https only. */
    val url: String?,
    val priceCents: Int?,
    val note: String,
    /** birthday | christmas | other | null */
    val occasion: String?,
    val sort: Int,
    val createdAt: String?,
    /** Never set on the caller's own items (the surprise stays). */
    val reservedBy: Int?,
    val reservedAt: String?,
    val gotIt: Boolean,
) {
    val reserved: Boolean get() = reservedBy != null

    companion object {
        const val BIRTHDAY = "birthday"
        const val CHRISTMAS = "christmas"
        const val OTHER = "other"
        val OCCASIONS = listOf(BIRTHDAY, CHRISTMAS, OTHER)
        const val MAX_TITLE = 80
        const val MAX_URL = 300
        const val MAX_NOTE = 200
    }
}

/** `POST wishes` / `PATCH wishes/<id>`. */
data class WishDraft(
    val title: String,
    val url: String? = null,
    val priceCents: Int? = null,
    val note: String = "",
    val occasion: String? = null,
    /** Parents adding to a child's list; null = the caller. */
    val ownerId: Int? = null,
)

// ─── Haushaltsbuch ────────────────────────────────────────────────────

data class Expense(
    val id: Int,
    val cents: Int,
    /** food | kids | household | leisure | mobility | other */
    val category: String,
    val paidBy: Int,
    val date: String,
    val note: String,
    val shared: Boolean,
    val createdBy: Int?,
) {
    companion object {
        const val FOOD = "food"
        const val KIDS = "kids"
        const val HOUSEHOLD = "household"
        const val LEISURE = "leisure"
        const val MOBILITY = "mobility"
        const val OTHER = "other"
        val CATEGORIES = listOf(FOOD, KIDS, HOUSEHOLD, LEISURE, MOBILITY, OTHER)
        const val MAX_NOTE = 80
        const val MAX_CENTS = 10_000_000
    }
}

/** One parent owes another [cents] to even out the month (record only). */
data class Transfer(val from: Int, val to: Int, val cents: Int)

/** `GET expenses?month=YYYY-MM`. */
data class ExpenseMonth(
    val month: String,
    /** Newest first. */
    val expenses: List<Expense>,
    val totalCents: Int,
    val byCategory: Map<String, Int>,
    /** Empty: nothing to even out (or fewer than two parents). */
    val transfers: List<Transfer>,
    val settled: Boolean,
)

/** `POST expenses` / `PATCH expenses/<id>`. */
data class ExpenseDraft(
    val cents: Int,
    val category: String = Expense.FOOD,
    val paidBy: Int,
    val date: String,
    val note: String = "",
    val shared: Boolean = true,
)
