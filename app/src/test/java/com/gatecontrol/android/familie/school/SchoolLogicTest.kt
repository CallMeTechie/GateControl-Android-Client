package com.gatecontrol.android.familie.school

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime

class SchoolLogicTest {

    private fun at(date: String, time: String) = LocalDateTime.parse("${date}T$time")
    private fun d(date: String) = LocalDate.parse(date)

    private fun lesson(start: String, end: String, state: LessonState = LessonState.NORMAL, subject: String = "M") =
        SchoolLesson(start = start, end = end, subject = subject, state = state)

    // 2026-10-19 is a Monday.

    @Test
    fun `the default day is today until 15 o'clock, then the next school day`() {
        assertEquals(d("2026-10-20"), SchoolLogic.defaultDay(at("2026-10-20", "07:00")))
        assertEquals(d("2026-10-20"), SchoolLogic.defaultDay(at("2026-10-20", "14:59")))
        assertEquals(d("2026-10-21"), SchoolLogic.defaultDay(at("2026-10-20", "15:00")))
        // Friday afternoon, Saturday and Sunday: Monday.
        assertEquals(d("2026-10-26"), SchoolLogic.defaultDay(at("2026-10-23", "16:30")))
        assertEquals(d("2026-10-26"), SchoolLogic.defaultDay(at("2026-10-24", "09:00")))
        assertEquals(d("2026-10-26"), SchoolLogic.defaultDay(at("2026-10-25", "18:00")))
        assertEquals(d("2026-10-23"), SchoolLogic.defaultDay(at("2026-10-23", "08:00")))
    }

    @Test
    fun `a saturday with lessons is a school day`() {
        val saturday = d("2026-10-24")
        val hasLessons: (LocalDate) -> Boolean = { it == saturday }
        assertEquals(saturday, SchoolLogic.defaultDay(at("2026-10-24", "08:00"), hasLessons))
        assertEquals(saturday, SchoolLogic.defaultDay(at("2026-10-23", "15:30"), hasLessons))
        assertEquals(d("2026-10-26"), SchoolLogic.defaultDay(at("2026-10-24", "15:00"), hasLessons))
        assertEquals(
            listOf("2026-10-19", "2026-10-20", "2026-10-21", "2026-10-22", "2026-10-23", "2026-10-24"),
            SchoolLogic.weekDays(d("2026-10-19"), hasLessons).map { it.toString() },
        )
        assertEquals(5, SchoolLogic.weekDays(d("2026-10-26"), hasLessons).size)
    }

    @Test
    fun `the loaded range is this week and next, or the week of a later day`() {
        val today = d("2026-10-22")
        assertEquals(d("2026-10-19"), SchoolLogic.windowStart(today))
        assertEquals(d("2026-11-01"), SchoolLogic.windowEnd(d("2026-10-19")))
        assertEquals(14, java.time.temporal.ChronoUnit.DAYS.between(d("2026-10-19"), SchoolLogic.windowEnd(d("2026-10-19"))) + 1)
        assertEquals(d("2026-10-19"), SchoolLogic.windowStart(d("2026-10-25")), "Sunday belongs to its week")
        assertEquals(d("2026-10-19"), SchoolLogic.windowFor(d("2026-10-30"), today))
        assertEquals(d("2026-11-02"), SchoolLogic.windowFor(d("2026-11-04"), today))
        assertEquals(d("2026-10-12"), SchoolLogic.windowFor(d("2026-10-16"), today))
        // The default day always lies in the usual range.
        listOf("2026-10-23T16:00", "2026-10-24T10:00", "2026-10-25T20:00").forEach {
            val now = LocalDateTime.parse(it)
            val day = SchoolLogic.defaultDay(now)
            val start = SchoolLogic.windowStart(now.toLocalDate())
            assertTrue(day >= start && day <= SchoolLogic.windowEnd(start), it)
        }
    }

    private val texts = object : SchoolSummaryTexts {
        override fun planned() = "Planmäßig"
        override fun free() = "Frei"
        override fun unknown() = "?"
        override fun cancelled(n: Int) = if (n == 1) "1 Ausfall" else "$n Ausfälle"
        override fun changed(n: Int) = if (n == 1) "1 Vertretung" else "$n Vertretungen"
        override fun extra(n: Int) = if (n == 1) "1 Zusatzstunde" else "$n Zusatzstunden"
    }

    private fun student(vararg days: SchoolDay) = SchoolStudent(id = 3, name = "Lena", days = days.toList())

    @Test
    fun `the summary per child reads planned, changes, free, holidays`() {
        val s = student(
            SchoolDay("2026-10-19", lessons = listOf(lesson("08:00", "08:45"), lesson("08:50", "09:35"))),
            SchoolDay(
                "2026-10-20",
                lessons = listOf(
                    lesson("08:00", "08:45", LessonState.CANCELLED, "D"),
                    lesson("08:50", "09:35", LessonState.CANCELLED, "E"),
                    lesson("09:50", "10:35", LessonState.CHANGED, "M"),
                    lesson("11:00", "11:45"),
                    lesson("12:00", "12:45", LessonState.EXTRA, "Mu"),
                ),
            ),
            SchoolDay("2026-10-21"),
            SchoolDay("2026-10-26", holiday = "Herbstferien"),
        )
        fun text(date: String, now: LocalDateTime? = null) = SchoolLogic.summaryText(SchoolLogic.summary(s, d(date), now), texts)

        assertEquals("Planmäßig", text("2026-10-19"))
        assertEquals("2 Ausfälle · 1 Vertretung · 1 Zusatzstunde", text("2026-10-20"))
        assertEquals("Frei", text("2026-10-21"))
        assertEquals("Herbstferien", text("2026-10-26"))
        assertEquals("?", text("2026-10-27"))

        val changes = SchoolLogic.summary(s, d("2026-10-20"))
        assertEquals(SchoolDaySummary.Status.CHANGES, changes.status)
        assertEquals(listOf("D", "E"), changes.next.map { it.subject }, "the first two changed lessons")
        // On the day itself only what is not over yet.
        val later = SchoolLogic.summary(s, d("2026-10-20"), at("2026-10-20", "09:00"))
        assertEquals(listOf("E", "M"), later.next.map { it.subject })
        val evening = SchoolLogic.summary(s, d("2026-10-20"), at("2026-10-20", "13:00"))
        assertTrue(evening.next.isEmpty())
        assertEquals(2, evening.cancelled, "the counts stay")
        // Another day's clock does not filter.
        assertEquals(listOf("D", "E"), SchoolLogic.summary(s, d("2026-10-20"), at("2026-10-19", "16:00")).next.map { it.subject })

        assertEquals("1 Ausfall", SchoolLogic.summaryText(SchoolDaySummary("x", SchoolDaySummary.Status.CHANGES, cancelled = 1), texts))
        assertEquals("1 Vertretung", SchoolLogic.summaryText(SchoolDaySummary("x", SchoolDaySummary.Status.CHANGES, changed = 1), texts))
    }

    @Test
    fun `lessons on a holiday still count`() {
        val s = student(SchoolDay("2026-10-20", holiday = "Brückentag", lessons = listOf(lesson("08:00", "08:45"))))
        assertEquals("Planmäßig", SchoolLogic.summaryText(SchoolLogic.summary(s, d("2026-10-20")), texts))
    }

    @Test
    fun `lessons of the selected child decide about saturdays`() {
        val tt = SchoolTimetable(
            students = listOf(
                SchoolStudent(3, "Lena", days = listOf(SchoolDay("2026-10-24", lessons = listOf(lesson("08:00", "08:45"))))),
                SchoolStudent(4, "Tim", days = listOf(SchoolDay("2026-10-24"))),
            ),
        )
        assertTrue(SchoolLogic.hasLessons(tt)(d("2026-10-24")))
        assertTrue(SchoolLogic.hasLessons(tt, 3)(d("2026-10-24")))
        assertEquals(false, SchoolLogic.hasLessons(tt, 4)(d("2026-10-24")))
        assertEquals(false, SchoolLogic.hasLessons(null)(d("2026-10-24")))
        assertEquals(4, SchoolLogic.pickStudent(tt, 4)?.id)
        assertEquals(3, SchoolLogic.pickStudent(tt, 99)?.id, "gone: the first one")
        assertNull(SchoolLogic.pickStudent(SchoolTimetable(), 3))
    }

    @Test
    fun `changes are grouped by the lesson's day, latest first`() {
        val changes = listOf(
            SchoolChange(id = 5, studentId = 3, date = "2026-10-20", start = "09:50", detectedAt = 5),
            SchoolChange(id = 4, studentId = 4, date = "2026-10-21", start = "08:00", detectedAt = 4),
            SchoolChange(id = 3, studentId = 3, date = "2026-10-20", start = "08:00", detectedAt = 3),
            SchoolChange(id = 2, studentId = 3, date = "2026-10-19", start = null, detectedAt = 2),
        )
        val all = SchoolLogic.changesByDay(changes)
        assertEquals(listOf("2026-10-21", "2026-10-20", "2026-10-19"), all.map { it.first })
        assertEquals(listOf(3, 5), all[1].second.map { it.id })
        val lena = SchoolLogic.changesByDay(changes, studentId = 3)
        assertEquals(listOf("2026-10-20", "2026-10-19"), lena.map { it.first })
        assertTrue(SchoolLogic.changesByDay(emptyList()).isEmpty())
    }
}
