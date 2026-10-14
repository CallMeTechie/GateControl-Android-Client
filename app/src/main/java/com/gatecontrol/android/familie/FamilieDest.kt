package com.gatecontrol.android.familie

/**
 * A screen of the Familie tab, as navigation argument (`familie?dest=…`,
 * `familie/page?dest=…`) and as target of a push message. [key] is plain
 * text without `?`, `&` or `#`; [parse] rejects anything else.
 */
sealed interface FamilieDest {
    val key: String

    data object Home : FamilieDest {
        override val key = "home"
    }

    data class Shopping(val listId: String? = null) : FamilieDest {
        override val key get() = if (listId == null) "shopping" else "shopping/$listId"
    }

    data object Tasks : FamilieDest {
        override val key = "tasks"
    }

    data object Dates : FamilieDest {
        override val key = "dates"
    }

    /** Parent: locate one child; [requestId] shows that request. */
    data class Locate(val childId: Int, val requestId: Int? = null) : FamilieDest {
        override val key get() = if (requestId == null) "locate/$childId" else "locate/$childId/$requestId"
    }

    /** Parent: an SOS of [userId] with the position it carried. */
    data class Sos(
        val sosId: Int,
        val userId: Int,
        val lat: Double? = null,
        val lon: Double? = null,
        val accuracy: Double? = null,
    ) : FamilieDest {
        override val key get() = "sos/$sosId/$userId/${lat ?: "-"}/${lon ?: "-"}/${accuracy ?: "-"}"
    }

    /** Child: "Kinder-Handy" (arrival, SOS, who located me). */
    data object Child : FamilieDest {
        override val key = "child"
    }

    data object Places : FamilieDest {
        override val key = "places"
    }

    /** Create ([placeId] null) or edit a place reminder. */
    data class PlaceEdit(val placeId: Int? = null) : FamilieDest {
        override val key get() = if (placeId == null) "place/new" else "place/$placeId"
    }

    /** Abholen & Fahrten; [rideId] (`12`, `3:2026-10-14`) is the ride a message was about. */
    data class Rides(val rideId: String? = null) : FamilieDest {
        override val key get() = if (rideId == null) "rides" else "rides/$rideId"
    }

    /** Notfall-Karte, opened at [userId]'s card (else the caller's own). */
    data class Emergency(val userId: Int? = null) : FamilieDest {
        override val key get() = if (userId == null) "emergency" else "emergency/$userId"
    }

    /** 1.2 Taschengeld of [childId] (parents; a child sees its own). */
    data class Allowance(val childId: Int? = null) : FamilieDest {
        override val key get() = if (childId == null) "allowance" else "allowance/$childId"
    }

    /** 1.2 Wunschlisten, opened at [userId]'s list (else the caller's own). */
    data class Wishes(val userId: Int? = null) : FamilieDest {
        override val key get() = if (userId == null) "wishes" else "wishes/$userId"
    }

    /**
     * 1.2 Haushaltsbuch (parents); [fromListId] opens a new expense for
     * that shopping list ("Einkauf erfassen?").
     */
    data class Expenses(val fromListId: String? = null) : FamilieDest {
        override val key get() = if (fromListId == null) "expenses" else "expenses/list/$fromListId"
    }

    /** 1.2 Pinnwand & Packlisten. */
    data object Board : FamilieDest {
        override val key = "board"
    }

    /** 1.2 Essensplan, at ISO week [week] (`2026-W42`, else this week). */
    data class Meals(val week: String? = null) : FamilieDest {
        override val key get() = if (week == null) "meals" else "meals/$week"
    }

    /** 1.2 Anwesenheit: who is home, sharing on / off. */
    data object Presence : FamilieDest {
        override val key = "presence"
    }

    companion object {
        private val UUID_RE = Regex("^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$")

        fun parse(key: String?): FamilieDest? {
            if (key.isNullOrBlank() || key.length > 200) return null
            val p = key.split('/')
            fun int(i: Int) = p.getOrNull(i)?.toIntOrNull()
            fun dbl(i: Int) = p.getOrNull(i)?.takeIf { it != "-" }?.toDoubleOrNull()?.takeIf { it.isFinite() }
            return when (p[0]) {
                "home" -> Home
                "shopping" -> when (p.size) {
                    1 -> Shopping()
                    2 -> p[1].takeIf { UUID_RE.matches(it) }?.let { Shopping(it) }
                    else -> null
                }
                "tasks" -> Tasks
                "dates" -> Dates
                "locate" -> int(1)?.let { Locate(it, int(2)) }
                "sos" -> {
                    val sos = int(1) ?: return null
                    val user = int(2) ?: return null
                    val lat = dbl(3)?.takeIf { it in -90.0..90.0 }
                    val lon = dbl(4)?.takeIf { it in -180.0..180.0 }
                    Sos(sos, user, lat.takeIf { lon != null }, lon.takeIf { lat != null }, dbl(5)?.takeIf { it >= 0 })
                }
                "child" -> Child
                "places" -> Places
                "place" -> when (p.getOrNull(1)) {
                    "new" -> PlaceEdit(null)
                    null -> null
                    else -> int(1)?.let { PlaceEdit(it) }
                }
                "rides" -> when (p.size) {
                    1 -> Rides()
                    2 -> p[1].takeIf { FamilieJson.RIDE_ID_RE.matches(it) }?.let { Rides(it) }
                    else -> null
                }
                "emergency" -> when (p.size) {
                    1 -> Emergency()
                    2 -> int(1)?.takeIf { it > 0 }?.let { Emergency(it) }
                    else -> null
                }
                "allowance" -> when (p.size) {
                    1 -> Allowance()
                    2 -> int(1)?.takeIf { it > 0 }?.let { Allowance(it) }
                    else -> null
                }
                "wishes" -> when (p.size) {
                    1 -> Wishes()
                    2 -> int(1)?.takeIf { it > 0 }?.let { Wishes(it) }
                    else -> null
                }
                "expenses" -> when {
                    p.size == 1 -> Expenses()
                    p.size == 3 && p[1] == "list" && UUID_RE.matches(p[2]) -> Expenses(p[2])
                    else -> null
                }
                "board" -> if (p.size == 1) Board else null
                "meals" -> when (p.size) {
                    1 -> Meals()
                    2 -> p[1].takeIf { com.gatecontrol.android.familie.meals.MealWeek.isKey(it) }?.let { Meals(it) }
                    else -> null
                }
                "presence" -> if (p.size == 1) Presence else null
                else -> null
            }
        }
    }
}
