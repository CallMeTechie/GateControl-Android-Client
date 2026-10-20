package com.gatecontrol.android.familie.school

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.TemporalAdjusters

/** What a child's day looks like on the Familie card (pure, unit-tested). */
data class SchoolDaySummary(
    val date: String,
    val status: Status,
    val holiday: String? = null,
    val cancelled: Int = 0,
    val changed: Int = 0,
    val extra: Int = 0,
    /**
     * The next changed lessons of the day the card lists (today only those
     * not over); together with the [agenda] line at most
     * [SchoolLogic.CARD_LESSONS] lines, see [SchoolLogic.summary].
     */
    val next: List<SchoolLesson> = emptyList(),
    /** The exams of the day. */
    val exams: List<SchoolExam> = emptyList(),
    /** Homework due on the day and not completed. */
    val homework: Int = 0,
    /** The card shows the line "Morgen: Mathe-Arbeit · 2 Hausaufgaben" ([SchoolLogic.agendaText]). */
    val agenda: Boolean = false,
) {
    enum class Status {
        /** Holidays (no lessons): [holiday] names them. */
        HOLIDAY,

        /** No lessons, no holidays. */
        FREE,

        /** Lessons, nothing changed. */
        PLANNED,

        /** Cancellations, substitutions or extra lessons. */
        CHANGES,

        /** The day is not in the answer. */
        UNKNOWN,
    }
}

/** Texts of a summary line, from the app's strings (an interface, so the wording is unit-tested). */
interface SchoolSummaryTexts {
    fun planned(): String
    fun free(): String
    fun unknown(): String
    fun cancelled(n: Int): String
    fun changed(n: Int): String
    fun extra(n: Int): String
}

/** Texts of the card's exam / homework line (an interface, so the wording is unit-tested). */
interface SchoolAgendaTexts {
    /** One exam: "Mathematik-Arbeit". */
    fun exam(subject: String): String
    /** Several exams: "2 Arbeiten". */
    fun exams(n: Int): String
    /** Open homework: "2 Hausaufgaben". */
    fun homework(n: Int): String
}

/**
 * Which days the timetable shows and how a day reads (pure, unit-tested).
 * Times are the device's local time, which is taken to be the school's.
 */
object SchoolLogic {

    /** From this hour on, the card and the screen show the next school day. */
    const val SWITCH_HOUR = 15

    /** Lines the card lists under a child: changed lessons and the exam / homework line. */
    const val CARD_LESSONS = 2

    /** Days loaded at once: this week and next (Monday … Sunday of next week). */
    const val WINDOW_DAYS = 14

    /** Monday of [date]'s week. */
    fun monday(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    /** First day of the loaded range: Monday of this week. */
    fun windowStart(today: LocalDate): LocalDate = monday(today)

    fun windowEnd(start: LocalDate): LocalDate = start.plusDays(WINDOW_DAYS - 1L)

    /** The range to load for [date]: the usual one when it lies within, else from Monday of its week. */
    fun windowFor(date: LocalDate, today: LocalDate): LocalDate {
        val usual = windowStart(today)
        return if (date >= usual && date <= windowEnd(usual)) usual else monday(date)
    }

    /** Monday to Friday, and Saturday when there are lessons on it. */
    fun isSchoolDay(date: LocalDate, hasLessons: (LocalDate) -> Boolean): Boolean = when (date.dayOfWeek) {
        DayOfWeek.SATURDAY -> hasLessons(date)
        DayOfWeek.SUNDAY -> false
        else -> true
    }

    /**
     * The day the card and the screen open on: today when it is a school
     * day and before [SWITCH_HOUR]; otherwise the next school day (after
     * 15:00, on Friday evening and at weekends: Monday). Holidays are not
     * skipped — the card shows their name.
     */
    fun defaultDay(now: LocalDateTime, hasLessons: (LocalDate) -> Boolean = { false }): LocalDate {
        val today = now.toLocalDate()
        if (now.hour < SWITCH_HOUR && isSchoolDay(today, hasLessons)) return today
        var d = today.plusDays(1)
        repeat(7) {
            if (isSchoolDay(d, hasLessons)) return d
            d = d.plusDays(1)
        }
        return d
    }

    /** True when one of [students] (all when [studentId] is null, else that one) has lessons on [date]. */
    fun hasLessons(timetable: SchoolTimetable?, studentId: Int? = null): (LocalDate) -> Boolean = { date ->
        val key = date.toString()
        timetable?.students.orEmpty()
            .filter { studentId == null || it.id == studentId }
            .any { s -> s.day(key)?.lessons?.isNotEmpty() == true }
    }

    /** The days of the week starting [weekStart] the strip offers: Mon–Fri, Sat when there are lessons. */
    fun weekDays(weekStart: LocalDate, hasLessons: (LocalDate) -> Boolean): List<LocalDate> =
        (0L until 6L).map { weekStart.plusDays(it) }.filter { isSchoolDay(it, hasLessons) }

    /**
     * How [student]'s [date] reads; with [now] on that very day only the
     * changed lessons that are not over yet count as "next".
     *
     * The card has room for [CARD_LESSONS] lines per child. Priority when
     * space is short: (1) the exam line — with exams it always shows (open
     * homework rides along on it) and takes the first line; (2) the changed
     * lessons fill the remaining lines; (3) a line with open homework alone
     * only shows when a line is still free. Counts in the summary text are
     * never cut.
     */
    fun summary(student: SchoolStudent, date: LocalDate, now: LocalDateTime? = null): SchoolDaySummary {
        val key = date.toString()
        val day = student.day(key) ?: return SchoolDaySummary(key, SchoolDaySummary.Status.UNKNOWN)
        val exams = day.exams
        val homework = day.homework.count { !it.completed }
        if (day.lessons.isEmpty()) {
            val agenda = exams.isNotEmpty() || homework > 0
            return if (day.holiday != null) {
                SchoolDaySummary(key, SchoolDaySummary.Status.HOLIDAY, holiday = day.holiday, exams = exams, homework = homework, agenda = agenda)
            } else {
                SchoolDaySummary(key, SchoolDaySummary.Status.FREE, exams = exams, homework = homework, agenda = agenda)
            }
        }
        val cancelled = day.lessons.count { it.state == LessonState.CANCELLED }
        val changed = day.lessons.count { it.state == LessonState.CHANGED }
        val extra = day.lessons.count { it.state == LessonState.EXTRA }
        val nowTime = now?.takeIf { it.toLocalDate() == date }?.toLocalTime()?.toString()?.take(5)
        val examLine = exams.isNotEmpty()
        val next = day.lessons
            .filter { it.isSpecial && (nowTime == null || it.end > nowTime) }
            .take(if (examLine) CARD_LESSONS - 1 else CARD_LESSONS)
        val agenda = examLine || (homework > 0 && next.size < CARD_LESSONS)
        val status = if (cancelled + changed + extra == 0) SchoolDaySummary.Status.PLANNED else SchoolDaySummary.Status.CHANGES
        return SchoolDaySummary(key, status, day.holiday, cancelled, changed, extra, next, exams, homework, agenda)
    }

    /**
     * The card's exam / homework line without the day: "Mathematik-Arbeit",
     * "2 Arbeiten", "Mathematik-Arbeit · 2 Hausaufgaben", "1 Hausaufgabe";
     * null when the summary has no such line. An exam without a subject is
     * named by its name.
     */
    fun agendaText(s: SchoolDaySummary, texts: SchoolAgendaTexts): String? {
        if (!s.agenda) return null
        val exams = when (s.exams.size) {
            0 -> null
            1 -> s.exams[0].let { e -> e.title.ifBlank { null }?.let(texts::exam) ?: e.name }
            else -> texts.exams(s.exams.size)
        }
        val homework = s.homework.takeIf { it > 0 }?.let(texts::homework)
        return listOfNotNull(exams, homework).joinToString(" · ").ifBlank { null }
    }

    /**
     * The exam written in [lesson], if any: same subject (short or long
     * name, ignoring case) and the times overlap. Exams without a start
     * time and cancelled lessons never match (better no badge than a wrong
     * one); an exam without an end counts as starting in the lesson.
     */
    fun examFor(lesson: SchoolLesson, exams: List<SchoolExam>): SchoolExam? {
        if (lesson.state == LessonState.CANCELLED) return null
        return exams.firstOrNull { e ->
            val start = e.start ?: return@firstOrNull false
            val sameSubject = (e.subject.isNotBlank() && e.subject.equals(lesson.subject, ignoreCase = true)) ||
                (e.subjectLong != null && lesson.subjectLong != null && e.subjectLong.equals(lesson.subjectLong, ignoreCase = true))
            val overlaps = if (e.end != null) start < lesson.end && lesson.start < e.end else start >= lesson.start && start < lesson.end
            sameSubject && overlaps
        }
    }

    /** "Planmäßig", "2 Ausfälle · 1 Vertretung", "Frei", the holidays' name. */
    fun summaryText(s: SchoolDaySummary, texts: SchoolSummaryTexts): String = when (s.status) {
        SchoolDaySummary.Status.HOLIDAY -> s.holiday ?: texts.free()
        SchoolDaySummary.Status.FREE -> texts.free()
        SchoolDaySummary.Status.PLANNED -> texts.planned()
        SchoolDaySummary.Status.UNKNOWN -> texts.unknown()
        SchoolDaySummary.Status.CHANGES -> listOfNotNull(
            s.cancelled.takeIf { it > 0 }?.let(texts::cancelled),
            s.changed.takeIf { it > 0 }?.let(texts::changed),
            s.extra.takeIf { it > 0 }?.let(texts::extra),
        ).joinToString(" · ")
    }

    /**
     * The change log grouped by the lesson's day, latest day first, each
     * day by time; only [studentId]'s changes when given.
     */
    fun changesByDay(changes: List<SchoolChange>, studentId: Int? = null): List<Pair<String, List<SchoolChange>>> =
        changes
            .filter { studentId == null || it.studentId == studentId }
            .groupBy { it.date }
            .toSortedMap(compareByDescending { it })
            .map { (date, list) -> date to list.sortedWith(compareBy({ it.start ?: "" }, { -(it.detectedAt ?: 0L) })) }

    /** The student to show: [wanted] when it is there, else the first one. */
    fun pickStudent(timetable: SchoolTimetable?, wanted: Int?): SchoolStudent? =
        timetable?.student(wanted) ?: timetable?.students?.firstOrNull()
}
