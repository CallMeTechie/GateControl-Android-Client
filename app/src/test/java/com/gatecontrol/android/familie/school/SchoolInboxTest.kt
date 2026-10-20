package com.gatecontrol.android.familie.school

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime

/** Messages and absences (plugin 1.4+): parsing, which child they belong to, the card line. */
class SchoolInboxTest {

    private fun obj(json: String) = JsonParser.parseString(json).asJsonObject

    private val messagesAnswer = """
        {"ok":true,"messages":[
          {"id":"m1","accountId":2,"studentIds":[3],"subject":"Elternabend","preview":"Am Dienstag …","body":"Liebe Eltern,\r\n\r\n\r\n\r\nam Dienstag\tum 19 Uhr.","sender":"Frau Klein","sentAt":"2026-10-19T07:15:00Z","hasAttachments":true,"isNew":true},
          {"id":42,"studentIds":[3,"4",0,-1,3,"x"],"subject":"Wandertag","preview":"","sender":"Sekretariat","sentAt":"2026-10-20T08:00:00+02:00","hasAttachments":0,"isNew":"1"},
          {"id":"m3","studentIds":"3","subject":null,"body":"<p>Hallo<br>Welt &amp; mehr</p>","sentAt":"gestern"},
          {"subject":"no id"},
          {"id":"   ","subject":"blank id"},
          {"id":"m1","subject":"duplicate"},
          "not an object"
        ]}
    """.trimIndent()

    @Test
    fun `messages are read defensively, newest first`() {
        val list = SchoolJson.messages(obj(messagesAnswer))!!
        assertEquals(listOf("42", "m1", "m3"), list.map { it.id }, "by time; without a valid time last; no id or a duplicate: dropped")

        val m1 = list.first { it.id == "m1" }
        assertEquals(2, m1.accountId)
        assertEquals(listOf(3), m1.studentIds)
        assertEquals("Elternabend", m1.subject)
        assertEquals("Liebe Eltern,\n\nam Dienstag um 19 Uhr.", m1.body, "line breaks kept, at most one blank line, no control characters")
        assertEquals("Frau Klein", m1.sender)
        assertEquals(1792394100000L, m1.sentAt)
        assertTrue(m1.hasAttachments)
        assertTrue(m1.isNew)

        val m42 = list.first { it.id == "42" }
        assertEquals(listOf(3, 4), m42.studentIds, "invalid and repeated ids go")
        assertEquals("", m42.preview)
        assertNull(m42.body)
        assertEquals(1792476000000L, m42.sentAt, "an offset is honoured")
        assertFalse(m42.hasAttachments)
        assertTrue(m42.isNew)

        val m3 = list.first { it.id == "m3" }
        assertTrue(m3.studentIds.isEmpty(), "studentIds not a list: no child")
        assertEquals("", m3.subject)
        assertEquals("Hallo\nWelt & mehr", m3.body, "HTML becomes plain text")
        assertEquals("Hallo Welt & mehr", m3.preview, "no preview: the start of the text")
        assertNull(m3.sentAt)
        assertFalse(m3.isNew)
    }

    @Test
    fun `message texts are capped`() {
        val long = "x".repeat(30_000)
        val m = SchoolJson.messages(obj("""{"messages":[{"id":"1","subject":"$long","preview":"$long","body":"$long","sender":"$long"}]}"""))!!.single()
        assertEquals(200, m.subject.length)
        assertEquals(300, m.preview.length)
        assertEquals(20_000, m.body!!.length)
        assertEquals(100, m.sender.length)
        assertEquals(80, SchoolJson.messages(obj("""{"messages":[{"id":"${"a".repeat(200)}"}]}"""))!!.single().id.length)
    }

    @Test
    fun `an answer without its list is not one`() {
        assertNull(SchoolJson.messages(obj("""{"ok":true}""")))
        assertNull(SchoolJson.messages(obj("""{"ok":true,"messages":{}}""")))
        assertNull(SchoolJson.absences(obj("""{"ok":true,"absences":null}""")))
        assertEquals(emptyList<SchoolMessage>(), SchoolJson.messages(obj("""{"ok":true,"messages":[]}""")))
        assertNull(SchoolJson.messages(null as String?))
        assertNull(SchoolJson.messages("not json"))
        assertNull(SchoolJson.absences("[]"))
    }

    private val absencesAnswer = """
        {"ok":true,"absences":[
          {"id":"a1","studentId":3,"date":"2026-10-14","start":"08:00","end":"09:30","reason":"Krankheit","excused":true,"text":"Attest liegt vor"},
          {"id":"a2","studentId":3,"date":"2026-10-19","start":null,"end":null,"endDate":"2026-10-21","excused":null},
          {"id":"a3","studentId":4,"date":"2026-10-20","start":"10:00","end":"09:00","endDate":"2026-10-20","excused":false},
          {"id":"a4","studentId":3,"date":"2026-10-20","start":"25:00","end":"11:00"},
          {"id":"a5","studentId":0,"date":"2026-10-20"},
          {"id":"a6","studentId":3,"date":"2026-02-30"},
          {"id":"a7","studentId":3},
          {"studentId":3,"date":"2026-10-20"}
        ]}
    """.trimIndent()

    @Test
    fun `absences are read defensively, latest first`() {
        val list = SchoolJson.absences(obj(absencesAnswer))!!
        assertEquals(listOf("a3", "a4", "a2", "a1"), list.map { it.id }, "without id, student or valid date: dropped")

        val a1 = list.first { it.id == "a1" }
        assertEquals(SchoolAbsence("a1", 3, "2026-10-14", "08:00", "09:30", null, "Krankheit", true, "Attest liegt vor"), a1)
        assertEquals(AbsenceStatus.EXCUSED, a1.status)

        val a2 = list.first { it.id == "a2" }
        assertEquals("2026-10-21", a2.endDate)
        assertNull(a2.start)
        assertEquals(AbsenceStatus.OPEN, a2.status, "excused null: not decided yet")

        val a3 = list.first { it.id == "a3" }
        assertNull(a3.endDate, "an end date not after the date is none")
        assertNull(a3.end, "an end before the start on the same day is none")
        assertEquals(AbsenceStatus.UNEXCUSED, a3.status)

        val a4 = list.first { it.id == "a4" }
        assertNull(a4.start)
        assertEquals("11:00", a4.end, "an end without a start is kept")
        assertEquals(AbsenceStatus.OPEN, a4.status, "excused missing: open")
    }

    @Test
    fun `stored answers read back the same`() {
        val m = obj(messagesAnswer)
        assertEquals(SchoolJson.messages(m), SchoolJson.messages(m.toString()))
        val a = obj(absencesAnswer)
        assertEquals(SchoolJson.absences(a), SchoolJson.absences(a.toString()))
    }

    // ─── which child, the card line ──────────────────────────────────

    private fun msg(id: String, vararg students: Int, new: Boolean = false, at: Long? = null) =
        SchoolMessage(id = id, studentIds = students.toList(), isNew = new, sentAt = at)

    @Test
    fun `a message belongs to every child in its studentIds`() {
        val messages = listOf(
            msg("a", 3, new = true, at = 1),
            msg("b", 3, 4, new = true, at = 3),
            msg("c", 4, at = 2),
            msg("d"),
        )
        assertEquals(listOf("b", "a"), SchoolLogic.messagesFor(messages, 3).map { it.id })
        assertEquals(listOf("b", "c"), SchoolLogic.messagesFor(messages, 4).map { it.id })
        assertTrue(SchoolLogic.messagesFor(messages, 5).isEmpty(), "a message without children shows with none")
        assertEquals(2, SchoolLogic.newMessages(messages, 3))
        assertEquals(1, SchoolLogic.newMessages(messages, 4), "a message to two children counts for each")
        assertEquals(0, SchoolLogic.newMessages(null, 3))
    }

    @Test
    fun `an opened message shows with one of its children`() {
        val tt = SchoolTimetable(students = listOf(SchoolStudent(3, "Lena"), SchoolStudent(4, "Tom")))
        assertEquals(3, SchoolLogic.studentForMessage(msg("b", 3, 4), 3, tt), "the current child when it is one of them")
        assertEquals(4, SchoolLogic.studentForMessage(msg("c", 9, 4), 3, tt), "else the first the timetable knows")
        assertNull(SchoolLogic.studentForMessage(msg("d", 9), 3, tt), "none known: keep the current child")
    }

    @Test
    fun `absences of the selected child, latest first`() {
        val list = listOf(
            SchoolAbsence("1", 3, "2026-10-14"),
            SchoolAbsence("2", 4, "2026-10-20"),
            SchoolAbsence("3", 3, "2026-10-20", start = "08:00"),
            SchoolAbsence("4", 3, "2026-10-20", start = "10:00"),
        )
        assertEquals(listOf("4", "3", "1"), SchoolLogic.absencesFor(list, 3).map { it.id })
        assertTrue(SchoolLogic.absencesFor(null, 3).isEmpty())
    }

    @Test
    fun `new messages take the card's last free line, after exams, changes and homework`() {
        val day = "2026-10-20"
        val now = LocalDateTime.parse("2026-10-20T07:00")
        val date = LocalDate.parse(day)
        fun student(lessons: List<SchoolLesson> = emptyList(), exams: List<SchoolExam> = emptyList(), homework: List<SchoolHomework> = emptyList()) =
            SchoolStudent(3, "Lena", days = listOf(SchoolDay(day, lessons = lessons, exams = exams, homework = homework)))
        val normal = SchoolLesson("08:00", "08:45", subject = "M")
        val cancelled = SchoolLesson("09:00", "09:45", subject = "D", state = LessonState.CANCELLED)
        val changed = SchoolLesson("10:00", "10:45", subject = "E", state = LessonState.CHANGED)
        val exam = SchoolExam(subject = "M", name = "Arbeit")
        val hw = SchoolHomework(text = "S. 12")

        // Nothing else: the line shows.
        val plain = SchoolLogic.summary(student(listOf(normal)), date, now, newMessages = 2)
        assertEquals(2, plain.newMessages)
        assertEquals(1, plain.lines)
        // One changed lesson: still a line free.
        assertEquals(1, SchoolLogic.summary(student(listOf(normal, cancelled)), date, now, newMessages = 1).newMessages)
        // Exam + homework line and one change: full, no message line.
        val busy = SchoolLogic.summary(student(listOf(cancelled, changed), listOf(exam), listOf(hw)), date, now, newMessages = 1)
        assertEquals(0, busy.newMessages)
        assertTrue(busy.agenda)
        assertEquals(SchoolLogic.CARD_LESSONS, busy.lines)
        // Two changes: full.
        assertEquals(0, SchoolLogic.summary(student(listOf(cancelled, changed)), date, now, newMessages = 1).newMessages)
        // Homework alone and one change: full (homework before messages).
        val hwFirst = SchoolLogic.summary(student(listOf(changed), homework = listOf(hw)), date, now, newMessages = 1)
        assertTrue(hwFirst.agenda)
        assertEquals(0, hwFirst.newMessages)
        // A free day, an unknown day: the line shows; without new messages there is none.
        assertEquals(1, SchoolLogic.summary(student(), date, now, newMessages = 1).newMessages)
        assertEquals(1, SchoolLogic.summary(SchoolStudent(3, "Lena"), date, now, newMessages = 1).newMessages)
        assertEquals(0, SchoolLogic.summary(student(listOf(normal)), date, now).newMessages)
        // The rest of the summary does not change.
        assertEquals(
            SchoolLogic.summary(student(listOf(normal, cancelled)), date, now),
            SchoolLogic.summary(student(listOf(normal, cancelled)), date, now, newMessages = 1).copy(newMessages = 0),
        )
    }
}
