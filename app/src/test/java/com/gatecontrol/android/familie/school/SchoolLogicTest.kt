package com.gatecontrol.android.familie.school

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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

    private val agendaTexts = object : SchoolAgendaTexts {
        override fun exam(subject: String) = "$subject-Arbeit"
        override fun exams(n: Int) = if (n == 1) "1 Arbeit" else "$n Arbeiten"
        override fun homework(n: Int) = if (n == 1) "1 Hausaufgabe" else "$n Hausaufgaben"
    }

    private fun exam(subject: String, start: String? = null, end: String? = null, long: String? = null, name: String = "Arbeit") =
        SchoolExam(subject = subject, subjectLong = long, name = name, start = start, end = end)

    private fun homework(done: Boolean = false) = SchoolHomework(subject = "M", text = "S. 42", completed = done)

    private val threeChanges = listOf(
        lesson("08:00", "08:45", LessonState.CANCELLED, "D"),
        lesson("08:50", "09:35", LessonState.CHANGED, "E"),
        lesson("09:50", "10:35", LessonState.EXTRA, "Mu"),
    )

    @Test
    fun `the card line names one exam, counts several and adds open homework`() {
        fun line(vararg days: SchoolDay) = SchoolLogic.agendaText(SchoolLogic.summary(student(*days), d("2026-10-21")), agendaTexts)
        val lessons = listOf(lesson("08:00", "08:45"))

        assertEquals("Mathe-Arbeit", line(SchoolDay("2026-10-21", lessons = lessons, exams = listOf(exam("M", long = "Mathe")))))
        assertEquals("M-Arbeit", line(SchoolDay("2026-10-21", lessons = lessons, exams = listOf(exam("M")))), "no long name: the short one")
        assertEquals("Vokabeltest", line(SchoolDay("2026-10-21", lessons = lessons, exams = listOf(exam("", name = "Vokabeltest")))), "no subject: the name")
        assertEquals("2 Arbeiten", line(SchoolDay("2026-10-21", lessons = lessons, exams = listOf(exam("M"), exam("E")))))
        assertEquals(
            "Mathe-Arbeit · 2 Hausaufgaben",
            line(SchoolDay("2026-10-21", lessons = lessons, exams = listOf(exam("M", long = "Mathe")), homework = listOf(homework(), homework(), homework(done = true)))),
            "completed homework does not count",
        )
        assertEquals("1 Hausaufgabe", line(SchoolDay("2026-10-21", lessons = lessons, homework = listOf(homework()))))
        assertNull(line(SchoolDay("2026-10-21", lessons = lessons, homework = listOf(homework(done = true)))), "all done: no line")
        assertNull(line(SchoolDay("2026-10-21", lessons = lessons)))
        assertNull(line(), "unknown day")
        // Also on a day without lessons.
        assertEquals("E-Arbeit", line(SchoolDay("2026-10-21", exams = listOf(exam("E")))))
    }

    @Test
    fun `exams take the first card line, changed lessons the rest, homework alone only a free one`() {
        // Rule: at most CARD_LESSONS lines per child.
        assertEquals(2, SchoolLogic.CARD_LESSONS)
        fun summary(day: SchoolDay, now: LocalDateTime? = null) = SchoolLogic.summary(student(day), d(day.date), now)
        fun lines(s: SchoolDaySummary) = s.next.size + if (s.agenda) 1 else 0

        // No exam: the changed lessons use both lines.
        val plain = summary(SchoolDay("2026-10-21", lessons = threeChanges))
        assertEquals(listOf("D", "E"), plain.next.map { it.subject })
        assertEquals(false, plain.agenda)

        // An exam: its line first, one changed lesson; the counts stay complete.
        val withExam = summary(SchoolDay("2026-10-21", lessons = threeChanges, exams = listOf(exam("M"))))
        assertTrue(withExam.agenda)
        assertEquals(listOf("D"), withExam.next.map { it.subject })
        assertEquals(2, lines(withExam))
        assertEquals(1, withExam.cancelled)
        assertEquals(1, withExam.changed)
        assertEquals(1, withExam.extra)

        // Homework alone: below the changed lessons, only when a line is left.
        val full = summary(SchoolDay("2026-10-21", lessons = threeChanges, homework = listOf(homework())))
        assertEquals(false, full.agenda)
        assertEquals(2, full.next.size)
        assertEquals(1, full.homework, "still counted")
        val oneChange = summary(SchoolDay("2026-10-21", lessons = threeChanges.take(1), homework = listOf(homework())))
        assertTrue(oneChange.agenda)
        assertEquals(1, oneChange.next.size)
        // Later on the day fewer changed lessons are left: room for homework again.
        val later = summary(SchoolDay("2026-10-21", lessons = threeChanges, homework = listOf(homework())), at("2026-10-21", "09:00"))
        assertEquals(listOf("E", "Mu"), later.next.map { it.subject })
        assertEquals(false, later.agenda)
        val evening = summary(SchoolDay("2026-10-21", lessons = threeChanges, homework = listOf(homework())), at("2026-10-21", "10:00"))
        assertTrue(evening.agenda)

        // Exams and homework: one line, never more than CARD_LESSONS in all.
        val both = summary(SchoolDay("2026-10-21", lessons = threeChanges, exams = listOf(exam("M"), exam("E")), homework = listOf(homework(), homework())))
        assertTrue(both.agenda)
        assertEquals(1, both.next.size)
        assertEquals(2, lines(both))
        assertEquals("2 Arbeiten · 2 Hausaufgaben", SchoolLogic.agendaText(both, agendaTexts))
    }

    @Test
    fun `an exam marks the lesson of its subject and time`() {
        val m = SchoolLesson(start = "08:00", end = "08:45", subject = "M", subjectLong = "Mathematik")
        val m2 = SchoolLesson(start = "08:50", end = "09:35", subject = "M", subjectLong = "Mathematik")
        val e = SchoolLesson(start = "09:50", end = "10:35", subject = "E")
        val ka = exam("M", "08:00", "09:35")
        assertEquals(ka, SchoolLogic.examFor(m, listOf(ka)))
        assertEquals(ka, SchoolLogic.examFor(m2, listOf(ka)), "a double-period exam marks both")
        assertNull(SchoolLogic.examFor(e, listOf(ka)), "other subject")
        assertNull(SchoolLogic.examFor(m, listOf(exam("M", "09:50", "10:35"))), "other time")
        assertNull(SchoolLogic.examFor(m, listOf(exam("M"))), "no time: no badge")
        assertNull(SchoolLogic.examFor(m, listOf(exam("M", "08:45", "09:30"))), "touching is not overlapping")
        // Only a start: it has to fall into the lesson.
        assertEquals("08:10", SchoolLogic.examFor(m, listOf(exam("M", "08:10")))?.start)
        assertNull(SchoolLogic.examFor(m2, listOf(exam("M", "08:10"))))
        // The long name matches too, ignoring case; a cancelled lesson is never marked.
        assertEquals("09:00", SchoolLogic.examFor(m2, listOf(exam("Ma", "09:00", "09:30", long = "mathematik")))?.start)
        assertNull(SchoolLogic.examFor(m.copy(state = LessonState.CANCELLED), listOf(ka)))
        assertNull(SchoolLogic.examFor(m, emptyList()))
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

    @Test
    fun `dark ink on light child colours, white on dark ones`() {
        assertTrue(SchoolLogic.darkInkOn("#EAB308"), "yellow")
        assertTrue(SchoolLogic.darkInkOn("#FFFFFF"))
        assertTrue(SchoolLogic.darkInkOn("#84CC16"), "lime")
        assertFalse(SchoolLogic.darkInkOn("#000000"))
        assertFalse(SchoolLogic.darkInkOn("#7C3AED"), "violet")
        assertFalse(SchoolLogic.darkInkOn("#64748B"), "slate")
        assertFalse(SchoolLogic.darkInkOn("nope"))
        assertFalse(SchoolLogic.darkInkOn(null))
    }
}
