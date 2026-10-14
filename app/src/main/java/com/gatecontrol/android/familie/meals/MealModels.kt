package com.gatecontrol.android.familie.meals

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.IsoFields
import java.time.temporal.TemporalAdjusters

/*
 * Familie 1.2 Essensplan (`meals`): lunch and dinner per day, a week at a
 * time (`YYYY-Www`, ISO weeks). Parsed in FamilieJson12.
 */

data class Meal(
    val title: String,
    val note: String = "",
    /** Who cooks (member id) or null. */
    val cook: Int? = null,
    val ingredients: List<String> = emptyList(),
) {
    companion object {
        const val MAX_TITLE = 60
        const val MAX_NOTE = 200
        const val MAX_INGREDIENT = 60
        const val MAX_INGREDIENTS = 50
    }
}

data class MealDay(val date: String, val lunch: Meal?, val dinner: Meal?) {
    fun meal(slot: String): Meal? = if (slot == MealWeek.LUNCH) lunch else dinner
}

/** `GET meals?week=YYYY-Www`: always seven days, Monday first. */
data class MealWeek(val week: String, val days: List<MealDay>) {
    companion object {
        const val LUNCH = "lunch"
        const val DINNER = "dinner"
        val SLOTS = listOf(LUNCH, DINNER)
        private val WEEK_RE = Regex("^(\\d{4})-W(\\d{2})$")

        /** ISO week of [date]: `2026-W42`. */
        fun keyOf(date: LocalDate): String =
            String.format(java.util.Locale.ROOT, "%04d-W%02d", date.get(IsoFields.WEEK_BASED_YEAR), date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR))

        /** Monday of the ISO week [key], null when [key] is no valid week. */
        fun monday(key: String?): LocalDate? {
            val m = key?.let { WEEK_RE.matchEntire(it) } ?: return null
            val year = m.groupValues[1].toInt()
            val week = m.groupValues[2].toInt()
            // 4 January is always in week 1.
            val jan4 = LocalDate.of(year, 1, 4)
            val weeks = jan4.range(IsoFields.WEEK_OF_WEEK_BASED_YEAR).maximum
            if (week < 1 || week > weeks) return null
            return jan4.with(IsoFields.WEEK_OF_WEEK_BASED_YEAR, week.toLong()).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        }

        fun isKey(key: String?): Boolean = monday(key) != null

        /** The week [n] weeks after [key] (negative: before). */
        fun shift(key: String, n: Long): String = keyOf((monday(key) ?: LocalDate.now()).plusWeeks(n))

        /** Seven empty days of [key] (when the server sends fewer). */
        fun complete(key: String, days: List<MealDay>): List<MealDay> {
            val monday = monday(key) ?: return days
            val byDate = days.associateBy { it.date }
            return (0L..6L).map { i ->
                val d = monday.plusDays(i).toString()
                byDate[d] ?: MealDay(d, null, null)
            }
        }
    }
}

/** `overview.meals`: today's meals (null slot: nothing planned). */
data class OverviewMeals(val date: String?, val lunch: Meal?, val dinner: Meal?)
