package com.gatecontrol.android.familie.school

import com.gatecontrol.android.familie.FamilieJson
import com.gatecontrol.android.familie.FamilieJson.arr
import com.gatecontrol.android.familie.FamilieJson.bool
import com.gatecontrol.android.familie.FamilieJson.dbl
import com.gatecontrol.android.familie.FamilieJson.int
import com.gatecontrol.android.familie.FamilieJson.objects
import com.gatecontrol.android.familie.FamilieJson.str
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.time.LocalDate

/**
 * Turns the Schule plugin's JSON into [SchoolModels] — read from the Gson
 * tree by hand like [FamilieJson] (no reflection, nothing for R8 to strip).
 * Lenient where it does not matter (unknown `state` → normal, missing
 * optional fields), strict where it does: a day without a valid date, a
 * lesson without valid times and a student without an id are dropped.
 * `homework` / `exams` of a day (plugin 1.2+) are optional: absent or
 * broken lists read as none, entries without a text (homework) or without
 * name and subject (exam) are dropped.
 */
object SchoolJson {

    /** Days per request the plugin answers (`from` … `to` inclusive). */
    const val MAX_DAYS = 14

    private const val MAX_STUDENTS = 20
    private const val MAX_LESSONS = 30
    private const val MAX_CHANGES = 200
    private const val MAX_HOMEWORK = 20
    private const val MAX_EXAMS = 10

    /** `GET app/timetable`; null without a `students` list. */
    fun timetable(o: JsonObject): SchoolTimetable? {
        val list = o.arr("students") ?: return null
        return SchoolTimetable(
            updatedAt = stampMs(o),
            students = list.objects().mapNotNull(::student).distinctBy { it.id }.take(MAX_STUDENTS),
        )
    }

    /** The stored answer read back (null when it is not one). */
    fun timetable(raw: String?): SchoolTimetable? = raw?.let {
        runCatching { JsonParser.parseString(it).takeIf { e -> e.isJsonObject }?.asJsonObject?.let(::timetable) }.getOrNull()
    }

    private fun student(o: JsonObject): SchoolStudent? {
        val id = o.int("id")?.takeIf { it > 0 } ?: return null
        return SchoolStudent(
            id = id,
            name = FamilieJson.clean(o.str("name"), 60).ifBlank { "#$id" },
            userId = o.int("userId")?.takeIf { it > 0 },
            klasse = text(o.str("klasse"), 30),
            updatedAt = stampMs(o),
            days = o.arr("days")?.objects()?.mapNotNull(::day).orEmpty().distinctBy { it.date }.sortedBy { it.date },
        )
    }

    private fun day(o: JsonObject): SchoolDay? {
        val date = date(o.str("date")) ?: return null
        return SchoolDay(
            date = date,
            holiday = text(o.str("holiday"), 60),
            lessons = o.arr("lessons")?.objects()?.mapNotNull(::lesson).orEmpty()
                .sortedWith(compareBy({ it.start }, { it.end }))
                .take(MAX_LESSONS),
            homework = o.arr("homework")?.objects()?.mapNotNull(::homework).orEmpty().distinct().take(MAX_HOMEWORK),
            // Exams without a time first, then by time.
            exams = o.arr("exams")?.objects()?.mapNotNull(::exam).orEmpty().distinct()
                .sortedWith(compareBy({ it.start ?: "" }, { it.end ?: "" }))
                .take(MAX_EXAMS),
        )
    }

    /** One homework of `days[].homework` (plugin 1.2+); without a text it is dropped. */
    internal fun homework(o: JsonObject): SchoolHomework? {
        val text = text(o.str("text"), 300) ?: return null
        return SchoolHomework(
            subject = FamilieJson.clean(o.str("subject"), 30),
            subjectLong = text(o.str("subjectLong"), 60),
            text = text,
            givenDate = date(o.str("givenDate")),
            completed = o.bool("completed") == true,
        )
    }

    /**
     * One exam of `days[].exams` (plugin 1.2+); without a name and a
     * subject it is dropped. An end time needs a start before it.
     */
    internal fun exam(o: JsonObject): SchoolExam? {
        val subject = FamilieJson.clean(o.str("subject"), 30)
        val subjectLong = text(o.str("subjectLong"), 60)
        val name = FamilieJson.clean(o.str("name"), 100)
        if (name.isBlank() && subject.isBlank() && subjectLong == null) return null
        val start = FamilieJson.time(o.str("start"))
        return SchoolExam(
            subject = subject,
            subjectLong = subjectLong,
            name = name,
            start = start,
            end = FamilieJson.time(o.str("end"))?.takeIf { start != null && it > start },
            type = text(o.str("type"), 40),
            text = text(o.str("text"), 300),
        )
    }

    internal fun lesson(o: JsonObject): SchoolLesson? {
        val start = FamilieJson.time(o.str("start")) ?: return null
        val end = FamilieJson.time(o.str("end")) ?: return null
        val period = period(o.int("period"))
        return SchoolLesson(
            start = start,
            end = end,
            period = period,
            periodEnd = period(o.int("periodEnd"))?.takeIf { period != null && it > period },
            subject = FamilieJson.clean(o.str("subject"), 30),
            subjectLong = text(o.str("subjectLong"), 60),
            subjectOld = text(o.str("subjectOld"), 30),
            teacher = text(o.str("teacher"), 40),
            teacherOld = text(o.str("teacherOld"), 40),
            room = text(o.str("room"), 30),
            roomOld = text(o.str("roomOld"), 30),
            state = LessonState.fromWire(o.str("state")),
            info = text(o.str("info"), 300),
            substText = text(o.str("substText"), 300),
            text = text(o.str("text"), 300),
        )
    }

    /** `GET app/changes` (newest first, as the plugin sends them); entries without id, student or date are dropped. */
    fun changes(o: JsonObject): List<SchoolChange>? {
        val list = o.arr("changes") ?: return null
        return list.objects().mapNotNull { c ->
            val id = c.int("id") ?: return@mapNotNull null
            val studentId = c.int("studentId")?.takeIf { it > 0 } ?: return@mapNotNull null
            val date = date(c.str("date")) ?: return@mapNotNull null
            SchoolChange(
                id = id,
                studentId = studentId,
                student = text(c.str("student"), 60),
                date = date,
                start = FamilieJson.time(c.str("start")),
                end = FamilieJson.time(c.str("end")),
                period = period(c.int("period")),
                kind = ChangeKind.fromWire(c.str("kind")),
                subject = FamilieJson.clean(c.str("subject"), 30),
                subjectLong = text(c.str("subjectLong"), 60),
                subjectOld = text(c.str("subjectOld"), 30),
                teacher = text(c.str("teacher"), 40),
                teacherOld = text(c.str("teacherOld"), 40),
                room = text(c.str("room"), 30),
                roomOld = text(c.str("roomOld"), 30),
                substText = text(c.str("substText"), 300),
                info = text(c.str("info"), 300),
                detectedAt = c.dbl("detectedAt")?.takeIf { it > 0 }?.toLong(),
                pushed = c.bool("pushed") == true,
            )
        }.distinctBy { it.id }.take(MAX_CHANGES)
    }

    /** A real calendar date (`2026-02-30` is not). */
    internal fun date(value: String?): String? =
        FamilieJson.date(value)?.takeIf { runCatching { LocalDate.parse(it) }.isSuccess }

    private fun period(value: Int?): Int? = value?.takeIf { it in 1..30 }

    private fun stampMs(o: JsonObject): Long? = o.dbl("updatedAt")?.takeIf { it > 0 }?.toLong()

    private fun text(value: String?, max: Int): String? = FamilieJson.clean(value, max).takeIf { it.isNotBlank() }
}
