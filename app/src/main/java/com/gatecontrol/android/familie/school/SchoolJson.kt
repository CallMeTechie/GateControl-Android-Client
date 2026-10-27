package com.gatecontrol.android.familie.school

import com.gatecontrol.android.familie.FamilieJson
import com.gatecontrol.android.familie.FamilieJson.arr
import com.gatecontrol.android.familie.FamilieJson.bool
import com.gatecontrol.android.familie.FamilieJson.dbl
import com.gatecontrol.android.familie.FamilieJson.int
import com.gatecontrol.android.familie.FamilieJson.ints
import com.gatecontrol.android.familie.FamilieJson.obj
import com.gatecontrol.android.familie.FamilieJson.objects
import com.gatecontrol.android.familie.FamilieJson.str
import com.gatecontrol.android.profile.AvatarPalette
import com.gatecontrol.android.profile.ProfileJson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * Turns the Schule plugin's JSON into [SchoolModels] — read from the Gson
 * tree by hand like [FamilieJson] (no reflection, nothing for R8 to strip).
 * Lenient where it does not matter (unknown `state` → normal, missing
 * optional fields), strict where it does: a day without a valid date, a
 * lesson without valid times and a student without an id are dropped.
 * `homework` / `exams` of a day (plugin 1.2+) are optional: absent or
 * broken lists read as none, entries without a text (homework) or without
 * name and subject (exam) are dropped. A student's `color` / `avatarUrl`
 * (plugin 1.3+) are optional too: a colour that is not `#RRGGBB` and an
 * avatar address that is not a user avatar ([ProfileJson.avatarUrl]) read
 * as none. Messages and absences (plugin 1.4+) follow the same rules;
 * message texts may come as HTML and are turned into plain text ([plain]).
 * The fields of plugin 1.5 (`fullText`, `attachments`, `read`,
 * `confirmation`) are optional: missing they read as preview only, none,
 * unread and no confirmation asked.
 */
object SchoolJson {

    /** Days per request the plugin answers (`from` … `to` inclusive). */
    const val MAX_DAYS = 14

    private const val MAX_STUDENTS = 20
    private const val MAX_LESSONS = 30
    private const val MAX_CHANGES = 200
    private const val MAX_HOMEWORK = 20
    private const val MAX_EXAMS = 10
    private const val MAX_MESSAGES = 200
    private const val MAX_ABSENCES = 300
    private const val MAX_ID = 80
    private const val MAX_BODY = 20_000
    private const val MAX_ATTACHMENTS = 30

    /** An attachment id goes into a request path: one plain segment. */
    private val ATTACHMENT_ID_RE = Regex("^[A-Za-z0-9][A-Za-z0-9_.-]{0,79}$")
    /** A media id of the core's plugin media store. */
    private val MEDIA_ID_RE = Regex("^[A-Za-z0-9][A-Za-z0-9_-]{0,79}$")
    private val MIME_RE = Regex("^[a-z0-9][a-z0-9.+-]{0,40}/[a-z0-9][a-z0-9.+-]{0,80}$")

    private val HTML_RE = Regex("<(br|p|div|span|b|i|u|strong|em|a|ul|ol|li|font|table|tr|td)\\b[^>]*>|</[a-zA-Z]+>|&(nbsp|amp|lt|gt|quot|#39);", RegexOption.IGNORE_CASE)
    private val BREAK_RE = Regex("<br\\s*/?>|</(p|div|li|tr)>", RegexOption.IGNORE_CASE)
    private val TAG_RE = Regex("<[^>]*>")
    private val CONTROL_BUT_NEWLINE_RE = Regex("[\\u0000-\\u0009\\u000B-\\u001F\\u007F]")
    private val BLANK_LINES_RE = Regex("\n{3,}")

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
            colorHex = color(o.str("color")),
            avatarUrl = avatarUrl(o.str("avatarUrl")),
        )
    }

    /** A student's `color` (plugin 1.3+): `#RRGGBB` (upper case), else null. */
    internal fun color(value: String?): String? = AvatarPalette.validHex(value)

    /**
     * A student's `avatarUrl` (plugin 1.3+): a server-relative avatar path
     * or an http(s) URL of one, as for user avatars ([ProfileJson.avatarUrl]); else null.
     */
    internal fun avatarUrl(value: String?): String? = ProfileJson.avatarUrl(value)

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

    // ─── Messages and absences (plugin 1.4+) ─────────────────────────

    /**
     * `GET app/messages`, newest first (without a time last); null without
     * a `messages` list. Entries without an id are dropped; a message
     * without a preview gets the start of its text.
     */
    fun messages(o: JsonObject): List<SchoolMessage>? {
        val list = o.arr("messages") ?: return null
        return list.objects().mapNotNull(::message).distinctBy { it.id }
            .sortedByDescending { it.sentAt ?: Long.MIN_VALUE }
            .take(MAX_MESSAGES)
    }

    /** The stored answer read back (null when it is not one). */
    fun messages(raw: String?): List<SchoolMessage>? = parse(raw)?.let(::messages)

    internal fun message(o: JsonObject): SchoolMessage? {
        val id = text(o.str("id"), MAX_ID) ?: return null
        val body = multiline(o.str("body"), MAX_BODY)
        val attachments = o.arr("attachments")?.objects()?.mapNotNull(::attachment).orEmpty()
            .distinctBy { it.id }.take(MAX_ATTACHMENTS)
        val read = o.bool("read") == true
        return SchoolMessage(
            id = id,
            accountId = o.int("accountId")?.takeIf { it > 0 },
            studentIds = o.ints("studentIds").filter { it > 0 }.distinct().take(MAX_STUDENTS),
            subject = FamilieJson.clean(plain(o.str("subject")), 200),
            preview = text(plain(o.str("preview")), 300) ?: body?.let { FamilieJson.clean(it, 300) }.orEmpty(),
            body = body,
            sender = FamilieJson.clean(plain(o.str("sender")), 100),
            sentAt = instantMs(o, "sentAt"),
            hasAttachments = o.bool("hasAttachments") == true || attachments.isNotEmpty(),
            // A message this user has read is not new, whatever else the answer says.
            isNew = o.bool("isNew") == true && !read,
            fullText = o.bool("fullText") == true && body != null,
            attachments = attachments,
            read = read,
            confirmation = o.obj("confirmation")?.let(::confirmation),
            extended = o.bool("fullText") != null || o.bool("read") != null,
        )
    }

    /**
     * One of `attachments` (plugin 1.5+): without an id that can go into a
     * path it is dropped; a negative size or a type that is not `type/sub`
     * read as unknown.
     */
    internal fun attachment(o: JsonObject): SchoolAttachment? {
        val id = o.str("id")?.trim()?.takeIf { ATTACHMENT_ID_RE.matches(it) } ?: return null
        return SchoolAttachment(
            id = id,
            name = fileName(o.str("name")).orEmpty(),
            size = o.dbl("size")?.takeIf { it >= 0 }?.toLong(),
            mime = mime(o.str("mime")),
        )
    }

    /** `confirmation` (plugin 1.5+): sent when `confirmedAt` is set (also when it is not a valid time). */
    internal fun confirmation(o: JsonObject): SchoolConfirmation {
        val at = o.str("confirmedAt")?.trim()?.takeIf { it.isNotEmpty() }
        return SchoolConfirmation(
            confirmed = at != null,
            confirmedAt = if (at != null) instantMs(o, "confirmedAt") else null,
            by = if (at != null) text(plain(o.str("by")), 100) else null,
        )
    }

    /**
     * The answer of `POST app/messages/<id>/read` / `confirm`: `{ ok, message }`.
     * An answer without a (valid) message is still a success (the call is
     * idempotent); then there is nothing to update.
     */
    fun messageAnswer(o: JsonObject): SchoolMessageAnswer {
        val raw = o.obj("message")
        val message = raw?.let(::message)
        return SchoolMessageAnswer(message, raw.takeIf { message != null })
    }

    /** `POST app/messages/<id>/attachments/<attId>` → `{ ok, file: { mediaId, name, mime, bytes } }`; null without a valid media id. */
    /** `GET app/messages` → `drive`: attachments can go into Drive (Schule 1.7). */
    fun drive(raw: String?): Boolean = parse(raw)?.let { it.get("drive")?.takeIf { e -> e.isJsonPrimitive && e.asJsonPrimitive.isBoolean }?.asBoolean } == true

    /** Answer of `POST app/messages/<id>/attachments/<att>/drive` → where the file went. */
    fun driveSaved(o: JsonObject): String? = o.obj("saved")?.let { s -> s.str("where")?.take(300) ?: s.str("name")?.take(200) ?: "" }

    fun attachmentFile(o: JsonObject): SchoolAttachmentFile? {
        val f = o.obj("file") ?: return null
        val mediaId = f.str("mediaId")?.trim()?.takeIf { MEDIA_ID_RE.matches(it) } ?: return null
        return SchoolAttachmentFile(
            mediaId = mediaId,
            name = fileName(f.str("name")),
            mime = mime(f.str("mime")),
            bytes = f.dbl("bytes")?.takeIf { it >= 0 }?.toLong(),
        )
    }

    /**
     * The stored `GET app/messages` answer [raw] with the message of the same
     * id replaced by [message] (from `read` / `confirm`); [raw] unchanged
     * when the message is not in it, null when [raw] is not an answer.
     */
    fun replaceMessage(raw: String?, message: JsonObject): String? {
        val o = parse(raw) ?: return null
        val list = o.arr("messages") ?: return null
        val id = text(message.str("id"), MAX_ID) ?: return raw
        for (i in 0 until list.size()) {
            val e = list[i]
            if (e.isJsonObject && text(e.asJsonObject.str("id"), MAX_ID) == id) {
                list.set(i, message)
                return o.toString()
            }
        }
        return raw
    }

    /** A file name to show and save under: no path, no control characters, ≤ 200 characters; null when blank. */
    internal fun fileName(value: String?): String? =
        value?.substringAfterLast('/')?.substringAfterLast('\\')
            ?.let { FamilieJson.clean(it, 200) }
            ?.takeIf { it.isNotBlank() && it != "." && it != ".." }

    /** `type/subtype` in lower case without parameters; null otherwise. */
    internal fun mime(value: String?): String? =
        value?.substringBefore(';')?.trim()?.lowercase()?.takeIf { MIME_RE.matches(it) }

    /**
     * `GET app/absences`, latest first; null without an `absences` list.
     * Entries without id, student or valid date are dropped; an `endDate`
     * not after `date` and an `end` not after `start` (same day) read as none.
     */
    fun absences(o: JsonObject): List<SchoolAbsence>? {
        val list = o.arr("absences") ?: return null
        return list.objects().mapNotNull(::absence).distinctBy { it.id }
            .sortedWith(compareByDescending<SchoolAbsence> { it.date }.thenByDescending { it.start ?: "" })
            .take(MAX_ABSENCES)
    }

    /** The stored answer read back (null when it is not one). */
    fun absences(raw: String?): List<SchoolAbsence>? = parse(raw)?.let(::absences)

    internal fun absence(o: JsonObject): SchoolAbsence? {
        val id = text(o.str("id"), MAX_ID) ?: return null
        val studentId = o.int("studentId")?.takeIf { it > 0 } ?: return null
        val date = date(o.str("date")) ?: return null
        val endDate = date(o.str("endDate"))?.takeIf { it > date }
        val start = FamilieJson.time(o.str("start"))
        return SchoolAbsence(
            id = id,
            studentId = studentId,
            date = date,
            start = start,
            end = FamilieJson.time(o.str("end"))?.takeIf { start == null || endDate != null || it > start },
            endDate = endDate,
            reason = text(plain(o.str("reason")), 100),
            excused = o.bool("excused"),
            text = text(plain(o.str("text")), 300),
        )
    }

    /** ISO time (`2026-10-20T07:15:00Z`, with offset, or local) or epoch ms → epoch ms. */
    private fun instantMs(o: JsonObject, key: String): Long? {
        o.str(key)?.trim()?.let { s ->
            if (s.isEmpty()) return null
            runCatching { return OffsetDateTime.parse(s).toInstant().toEpochMilli() }
            runCatching { return LocalDateTime.parse(s).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }
            s.toDoubleOrNull()?.let { return it.takeIf { ms -> ms > 0 }?.toLong() }
            return null
        }
        return null
    }

    /**
     * A text that may come as HTML (WebUntis messages): line breaks and
     * paragraphs become new lines, other tags go, the usual entities are
     * decoded; plain text stays as it is.
     */
    internal fun plain(value: String?): String? {
        if (value == null || !HTML_RE.containsMatchIn(value)) return value
        return value
            .replace(BREAK_RE, "\n")
            .replace(TAG_RE, "")
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&amp;", "&")
    }

    /** Like [FamilieJson.clean] but keeps line breaks (at most one blank line in a row); null when blank. */
    internal fun multiline(value: String?, max: Int): String? {
        val text = plain(value) ?: return null
        return text.replace("\r\n", "\n").replace('\r', '\n')
            .replace(CONTROL_BUT_NEWLINE_RE, " ")
            .lines().joinToString("\n") { it.trimEnd() }
            .replace(BLANK_LINES_RE, "\n\n")
            .trim()
            .take(max)
            .takeIf { it.isNotBlank() }
    }

    private fun parse(raw: String?): JsonObject? = raw?.let {
        runCatching { JsonParser.parseString(it).takeIf { e -> e.isJsonObject }?.asJsonObject }.getOrNull()
    }

    /** A real calendar date (`2026-02-30` is not). */
    internal fun date(value: String?): String? =
        FamilieJson.date(value)?.takeIf { runCatching { LocalDate.parse(it) }.isSuccess }

    private fun period(value: Int?): Int? = value?.takeIf { it in 1..30 }

    private fun stampMs(o: JsonObject): Long? = o.dbl("updatedAt")?.takeIf { it > 0 }?.toLong()

    private fun text(value: String?, max: Int): String? = FamilieJson.clean(value, max).takeIf { it.isNotBlank() }
}
