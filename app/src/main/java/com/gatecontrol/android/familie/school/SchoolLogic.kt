package com.gatecontrol.android.familie.school

import com.gatecontrol.android.profile.AvatarPalette
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
    /**
     * New messages the card names in a line of its own ("1 neue Nachricht");
     * 0 when there are none or no line is left for them (lowest priority).
     */
    val newMessages: Int = 0,
) {
    /** Lines the card shows under the child (exam / homework, changed lessons, new messages). */
    val lines: Int get() = (if (agenda) 1 else 0) + next.size + (if (newMessages > 0) 1 else 0)

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

/** What a school attachment is, for its symbol. */
enum class SchoolFileKind { PDF, IMAGE, DOCUMENT, SHEET, MEDIA, ARCHIVE, OTHER }

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
     * only shows when a line is still free; (4) the line with [newMessages]
     * (plugin 1.4+) only when one is still free after all that. Counts in
     * the summary text are never cut.
     */
    fun summary(student: SchoolStudent, date: LocalDate, now: LocalDateTime? = null, newMessages: Int = 0): SchoolDaySummary =
        dayOnly(student, date, now).let { s ->
            if (newMessages > 0 && s.lines < CARD_LESSONS) s.copy(newMessages = newMessages) else s
        }

    private fun dayOnly(student: SchoolStudent, date: LocalDate, now: LocalDateTime?): SchoolDaySummary {
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

    // ─── Messages and absences (plugin 1.4+) ─────────────────────────

    /** [studentId]'s messages (its id in `studentIds`), newest first (without a time last). */
    fun messagesFor(messages: List<SchoolMessage>?, studentId: Int): List<SchoolMessage> =
        messages.orEmpty().filter { it.isFor(studentId) }.sortedByDescending { it.sentAt ?: Long.MIN_VALUE }

    /** New (unread) messages of [studentId]; a message to two children counts for each. */
    fun newMessages(messages: List<SchoolMessage>?, studentId: Int): Int =
        messages.orEmpty().count { it.isNew && it.isFor(studentId) }

    /** [m] as read on this device: no longer new (the "neu" chip and bold text go at once). */
    fun markedRead(m: SchoolMessage): SchoolMessage = if (!m.isNew && m.read) m else m.copy(isNew = false, read = true)

    /** [messages] with those in [readIds] (opened on this device) marked read. */
    fun withLocalRead(messages: List<SchoolMessage>?, readIds: Set<String>): List<SchoolMessage>? =
        if (messages == null || readIds.isEmpty()) messages else messages.map { if (it.id in readIds) markedRead(it) else it }

    /**
     * The ids opened on this device that still need the local mark after a
     * fresh list: those the server still calls new (its `read` did not get
     * through yet, or an older plugin that cannot keep it). Messages gone
     * from the list, or that the server knows as read, drop out.
     */
    fun pendingLocalRead(readIds: Set<String>, fresh: List<SchoolMessage>?): Set<String> {
        if (fresh == null) return readIds
        val newIds = fresh.filter { it.isNew }.mapTo(HashSet()) { it.id }
        return readIds.filterTo(LinkedHashSet()) { it in newIds }
    }

    /** Of [pending], those to send `read` again: the plugin keeps the read state ([SchoolMessage.extended]); at most [max]. */
    fun readToRetry(pending: Set<String>, fresh: List<SchoolMessage>?, max: Int = 5): List<String> =
        fresh.orEmpty().filter { it.id in pending && it.extended && it.isNew }.map { it.id }.take(max)

    /** Kind of file for its symbol, from the type or else the name's extension. */
    fun fileKind(mime: String?, name: String?): SchoolFileKind {
        val m = mime.orEmpty().lowercase()
        val ext = name?.substringAfterLast('.', "")?.lowercase().orEmpty()
        return when {
            m == "application/pdf" || ext == "pdf" -> SchoolFileKind.PDF
            m.startsWith("image/") || ext in IMAGE_EXT -> SchoolFileKind.IMAGE
            m.startsWith("audio/") || m.startsWith("video/") || ext in MEDIA_EXT -> SchoolFileKind.MEDIA
            m.contains("spreadsheet") || m.contains("excel") || m == "text/csv" || ext in SHEET_EXT -> SchoolFileKind.SHEET
            m.contains("word") || m.contains("document") || m.startsWith("text/") || m.contains("presentation") ||
                m.contains("powerpoint") || ext in DOC_EXT -> SchoolFileKind.DOCUMENT
            m.contains("zip") || ext in ARCHIVE_EXT -> SchoolFileKind.ARCHIVE
            else -> SchoolFileKind.OTHER
        }
    }

    private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "bmp")
    private val MEDIA_EXT = setOf("mp3", "m4a", "wav", "ogg", "mp4", "mov", "webm")
    private val SHEET_EXT = setOf("xls", "xlsx", "ods", "csv")
    private val DOC_EXT = setOf("doc", "docx", "odt", "rtf", "txt", "ppt", "pptx", "odp")
    private val ARCHIVE_EXT = setOf("zip", "7z", "rar")

    private val NAME_BAD_RE = Regex("[\\u0000-\\u001F\\u007F/\\\\:*?\"<>|]")

    /**
     * A file name safe to save under: reserved characters replaced, no
     * leading dot, ≤ 120 characters (the extension kept); blank → `anhang.<[ext]>`.
     */
    fun safeFileName(name: String?, ext: String): String {
        val cleaned = name?.let { NAME_BAD_RE.replace(it, "_").trim().trimStart('.').trim() }?.takeIf { it.isNotBlank() }
            ?: return "anhang.$ext"
        if (cleaned.length <= 120) return cleaned
        val e = cleaned.substringAfterLast('.', "").take(10)
        return if (e.isNotEmpty()) cleaned.take(119 - e.length) + "." + e else cleaned.take(120)
    }

    /** The cache folder of one attachment (`cache/school/<this>/<name>`): message and attachment id, safe for a path. */
    fun attachmentFolder(messageId: String, attachmentId: String): String =
        (messageId + "_" + attachmentId).replace(Regex("[^A-Za-z0-9_.-]"), "_").trimStart('.').take(160).ifEmpty { "_" }

    /** [studentId]'s absences, latest first. */
    fun absencesFor(absences: List<SchoolAbsence>?, studentId: Int): List<SchoolAbsence> =
        absences.orEmpty().filter { it.studentId == studentId }
            .sortedWith(compareByDescending<SchoolAbsence> { it.date }.thenByDescending { it.start ?: "" })

    /**
     * The child to show a message with: [current] when the message is for
     * it, else the first of its children the timetable knows; null keeps
     * the current child.
     */
    fun studentForMessage(message: SchoolMessage, current: Int?, timetable: SchoolTimetable?): Int? = when {
        current != null && message.isFor(current) -> current
        else -> message.studentIds.firstOrNull { id -> timetable?.student(id) != null }
    }

    /** The student to show: [wanted] when it is there, else the first one. */
    fun pickStudent(timetable: SchoolTimetable?, wanted: Int?): SchoolStudent? =
        timetable?.student(wanted) ?: timetable?.students?.firstOrNull()

    /**
     * Whether text on [hex] (`#RRGGBB`) should be dark: the WCAG relative
     * luminance is above the point where white and black contrast equally
     * (≈ 0.179), so the darker ink reads better. False for an invalid colour.
     */
    fun darkInkOn(hex: String?): Boolean {
        val h = AvatarPalette.validHex(hex) ?: return false
        fun channel(i: Int): Double {
            val c = h.substring(i, i + 2).toInt(16) / 255.0
            return if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        val l = 0.2126 * channel(1) + 0.7152 * channel(3) + 0.0722 * channel(5)
        return l > 0.179
    }
}
