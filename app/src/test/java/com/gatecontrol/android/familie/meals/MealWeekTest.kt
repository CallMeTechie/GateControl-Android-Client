package com.gatecontrol.android.familie.meals

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

class MealWeekTest {

    @Test
    fun `iso weeks across year ends`() {
        assertEquals("2026-W42", MealWeek.keyOf(LocalDate.of(2026, 10, 14)))
        assertEquals("2026-W53", MealWeek.keyOf(LocalDate.of(2027, 1, 1)), "1 Jan 2027 is in the last week of 2026")
        assertEquals("2025-W01", MealWeek.keyOf(LocalDate.of(2024, 12, 30)))
        assertEquals(LocalDate.of(2026, 10, 12), MealWeek.monday("2026-W42"))
        assertEquals(LocalDate.of(2024, 12, 30), MealWeek.monday("2025-W01"))
        assertEquals(LocalDate.of(2026, 12, 28), MealWeek.monday("2026-W53"))
    }

    @Test
    fun `invalid week keys`() {
        listOf(null, "", "2026-42", "2026-W00", "2025-W53", "2026-W54", "26-W01", "2026-W1").forEach {
            assertNull(MealWeek.monday(it), it.toString())
            assertFalse(MealWeek.isKey(it), it.toString())
        }
        assertTrue(MealWeek.isKey("2026-W53"))
    }

    @Test
    fun `shifting weeks`() {
        assertEquals("2026-W43", MealWeek.shift("2026-W42", 1))
        assertEquals("2027-W01", MealWeek.shift("2026-W53", 1))
        assertEquals("2026-W53", MealWeek.shift("2027-W01", -1))
    }

    @Test
    fun `missing days are filled in`() {
        val days = MealWeek.complete("2026-W42", listOf(MealDay("2026-10-14", Meal("Nudeln"), null), MealDay("2026-11-01", Meal("x"), null)))
        assertEquals((12..18).map { "2026-10-$it" }, days.map { it.date })
        assertEquals("Nudeln", days[2].lunch?.title)
        assertEquals("Nudeln", days[2].meal(MealWeek.LUNCH)?.title)
        assertNull(days[2].meal(MealWeek.DINNER))
    }
}
