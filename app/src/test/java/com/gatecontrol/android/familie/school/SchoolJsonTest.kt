package com.gatecontrol.android.familie.school

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SchoolJsonTest {

    private fun obj(json: String) = JsonParser.parseString(json).asJsonObject

    private val answer = """
        {"ok":true,"updatedAt":1792483200000,"students":[
          {"id":3,"name":"Lena","userId":7,"klasse":"5a","updatedAt":1792483100000,"days":[
            {"date":"2026-10-20","lessons":[
              {"start":"09:50","end":"10:35","period":3,"periodEnd":4,"subject":"M","subjectLong":"Mathematik","teacher":"SCH","teacherOld":"MUE","room":"104","roomOld":"201","state":"changed","substText":"Vertretung"},
              {"start":"08:00","end":"08:45","period":1,"subject":"D","teacher":"KLE","room":"104","state":"cancelled","info":"Lehrkraft krank"},
              {"start":"11:00","end":"11:45","subject":"Mu","state":"teleported"},
              {"start":"25:00","end":"11:45","subject":"X","state":"normal"},
              {"end":"11:45","subject":"Y"}
            ]},
            {"date":"2026-10-21","holiday":"Herbstferien","lessons":[]},
            {"date":"2026-02-30","lessons":[]},
            {"date":"tomorrow","lessons":[]},
            {"date":"2026-10-22"}
          ]},
          {"name":"no id"},
          {"id":"4","name":"  ","days":[]}
        ]}
    """.trimIndent()

    @Test
    fun `a timetable answer becomes students, days and lessons`() {
        val tt = SchoolJson.timetable(obj(answer))!!
        assertEquals(1792483200000L, tt.updatedAt)
        assertEquals(listOf(3, 4), tt.students.map { it.id })
        val lena = tt.students[0]
        assertEquals("Lena", lena.name)
        assertEquals(7, lena.userId)
        assertEquals("5a", lena.klasse)
        assertEquals(1792483100000L, lena.updatedAt)
        assertEquals("#4", tt.students[1].name, "a blank name falls back to the id")

        // Bad dates are skipped, a day without lessons is kept.
        assertEquals(listOf("2026-10-20", "2026-10-21", "2026-10-22"), lena.days.map { it.date })
        assertEquals("Herbstferien", lena.day("2026-10-21")!!.holiday)
        assertTrue(lena.day("2026-10-22")!!.lessons.isEmpty())

        // Lessons by time; without valid times they are dropped.
        val lessons = lena.day("2026-10-20")!!.lessons
        assertEquals(listOf("08:00", "09:50", "11:00"), lessons.map { it.start })
        val d = lessons[0]
        assertEquals(LessonState.CANCELLED, d.state)
        assertEquals("D", d.title, "no long name: the short one")
        assertEquals("Lehrkraft krank", d.info)
        assertNull(d.periodEnd)
        val m = lessons[1]
        assertEquals(LessonState.CHANGED, m.state)
        assertEquals("Mathematik", m.title)
        assertEquals(3, m.period)
        assertEquals(4, m.periodEnd)
        assertEquals("MUE", m.teacherOld)
        assertEquals("201", m.roomOld)
        assertEquals("Vertretung", m.substText)
        // Unknown state: normal; missing optionals: null.
        val mu = lessons[2]
        assertEquals(LessonState.NORMAL, mu.state)
        assertNull(mu.teacher)
        assertNull(mu.room)
        assertNull(mu.period)
    }

    @Test
    fun `without a student list the answer is not a timetable`() {
        assertNull(SchoolJson.timetable(obj("""{"ok":true}""")))
        val empty = SchoolJson.timetable(obj("""{"ok":true,"updatedAt":null,"students":[]}"""))!!
        assertTrue(empty.students.isEmpty())
        assertNull(empty.updatedAt)
    }

    @Test
    fun `the stored text reads back the same`() {
        val o = obj(answer)
        assertEquals(SchoolJson.timetable(o), SchoolJson.timetable(o.toString()))
        assertNull(SchoolJson.timetable("not json"))
        assertNull(SchoolJson.timetable("[1,2]"))
        assertNull(SchoolJson.timetable(null as String?))
    }

    private val withAgenda = """
        {"ok":true,"updatedAt":1792483200000,"students":[
          {"id":3,"name":"Lena","days":[
            {"date":"2026-10-21","lessons":[],
             "homework":[
               {"subject":"M","subjectLong":"Mathematik","text":"S. 42 Nr. 3\u0007","givenDate":"2026-10-19","completed":false},
               {"subject":"D","text":"Gedicht lernen","completed":true},
               {"subject":"E","text":"Vokabeln","givenDate":"19.10.2026","completed":"yes"},
               {"subject":"Bio","text":"   "},
               {"subject":"Ku"},
               "nonsense",
               {"subject":"M","subjectLong":"Mathematik","text":"S. 42 Nr. 3","givenDate":"2026-10-19","completed":false}
             ],
             "exams":[
               {"subject":"E","subjectLong":"Englisch","name":"Vokabeltest","start":"11:00"},
               {"subject":"M","subjectLong":"Mathematik","name":"Klassenarbeit 2","start":"08:00","end":"09:30","type":"Schulaufgabe","text":"Kapitel 3 und 4"},
               {"subject":"D","name":"Diktat","start":"10:00","end":"09:00"},
               {"subject":"Ch","name":"Test","start":"later"},
               {"name":"  ","text":"no subject, no name"},
               {"subject":"Ph","name":"${"x".repeat(150)}"}
             ]},
            {"date":"2026-10-22","homework":"none","exams":{"subject":"M"}}
          ]}
        ]}
    """.trimIndent()

    @Test
    fun `homework and exams of a day are parsed and broken entries dropped`() {
        val tt = SchoolJson.timetable(obj(withAgenda))!!
        val day = tt.students[0].day("2026-10-21")!!

        // Homework: cleaned text, entries without a text dropped, duplicates once.
        assertEquals(listOf("S. 42 Nr. 3", "Gedicht lernen", "Vokabeln"), day.homework.map { it.text })
        val m = day.homework[0]
        assertEquals("Mathematik", m.title)
        assertEquals("2026-10-19", m.givenDate)
        assertFalse(m.completed)
        assertTrue(day.homework[1].completed)
        assertNull(day.homework[2].givenDate, "a date not in ISO form is dropped")
        assertFalse(day.homework[2].completed, "only true / 1 count as completed")

        // Exams: by time (without a time first), without name and subject dropped.
        assertEquals(listOf("Ch", "Ph", "M", "D", "E"), day.exams.map { it.subject })
        assertNull(day.exams[0].start, "a time that is no time is dropped")
        val ph = day.exams[1]
        assertEquals(100, ph.name.length, "the name is capped")
        assertNull(ph.start)
        val ma = day.exams[2]
        assertEquals("Mathematik", ma.title)
        assertEquals("Klassenarbeit 2", ma.name)
        assertEquals("08:00", ma.start)
        assertEquals("09:30", ma.end)
        assertEquals("Schulaufgabe", ma.type)
        assertEquals("Kapitel 3 und 4", ma.text)
        val d = day.exams[3]
        assertEquals("10:00", d.start)
        assertNull(d.end, "an end before the start is dropped")
        assertNull(day.exams[4].end)

        // Lists of the wrong type read as none.
        val other = tt.students[0].day("2026-10-22")!!
        assertTrue(other.homework.isEmpty())
        assertTrue(other.exams.isEmpty())
    }

    @Test
    fun `older answers without homework and exams still parse, and the stored text keeps them`() {
        val old = SchoolJson.timetable(obj(answer))!!
        old.students.flatMap { it.days }.forEach {
            assertTrue(it.homework.isEmpty())
            assertTrue(it.exams.isEmpty())
        }
        // The offline copy is the raw text: read back, homework and exams are still there.
        val o = obj(withAgenda)
        val stored = SchoolJson.timetable(o.toString())!!
        assertEquals(SchoolJson.timetable(o), stored)
        assertEquals(3, stored.students[0].day("2026-10-21")!!.homework.size)
        assertEquals(5, stored.students[0].day("2026-10-21")!!.exams.size)
    }

    @Test
    fun `the change log keeps valid entries in the server's order`() {
        val changes = SchoolJson.changes(
            obj(
                """{"ok":true,"changes":[
                  {"id":9,"studentId":3,"student":"Lena","date":"2026-10-20","start":"09:50","end":"10:35","period":3,"kind":"cancelled","subject":"E","subjectLong":"Englisch","detectedAt":1792480000000,"pushed":true},
                  {"id":8,"studentId":3,"student":"Lena","date":"2026-10-19","start":"08:00","end":"08:45","kind":"brand-new","subject":"M","teacher":"SCH","teacherOld":"MUE","pushed":0},
                  {"id":7,"studentId":3,"date":"2026-10-19","kind":"restored","subject":"D"},
                  {"id":6,"date":"2026-10-19","kind":"extra","subject":"D"},
                  {"id":5,"studentId":3,"date":"19.10.2026","kind":"extra","subject":"D"},
                  {"studentId":3,"date":"2026-10-19","kind":"extra","subject":"D"}
                ]}""",
            ),
        )!!
        assertEquals(listOf(9, 8, 7), changes.map { it.id })
        assertEquals(ChangeKind.CANCELLED, changes[0].kind)
        assertEquals("Englisch", changes[0].title)
        assertEquals(1792480000000L, changes[0].detectedAt)
        assertTrue(changes[0].pushed)
        assertEquals(ChangeKind.CHANGED, changes[1].kind, "unknown kind")
        assertFalse(changes[1].pushed)
        assertEquals("MUE", changes[1].teacherOld)
        assertEquals(ChangeKind.RESTORED, changes[2].kind)
        assertNull(changes[2].student)
        assertNull(changes[2].start)
        assertNull(SchoolJson.changes(obj("""{"ok":true}""")))
    }

    // ─── colour and avatar per student (plugin 1.3+) ────────────────

    private fun students(vararg fields: String) = SchoolJson.timetable(
        obj("""{"students":[""" + fields.mapIndexed { i, f -> """{"id":${i + 1},"name":"Kind ${i + 1}"$f}""" }.joinToString(",") + "]}"),
    )!!.students

    @Test
    fun `a student's colour and avatar are read`() {
        val s = students(""","color":"#3b82f6","avatarUrl":"/avatars/AbCdEfGhIjKlMnOpQrStUv12.webp"""")[0]
        assertEquals("#3B82F6", s.colorHex, "normalised to upper case")
        assertEquals("/avatars/AbCdEfGhIjKlMnOpQrStUv12.webp", s.avatarUrl)
        assertTrue(s.hasFace)
    }

    @Test
    fun `answers without colour and avatar keep working`() {
        val s = students("")[0]
        assertNull(s.colorHex)
        assertNull(s.avatarUrl)
        assertFalse(s.hasFace)
        // The stored answer of an older plugin reads the same.
        val raw = """{"updatedAt":1,"students":[{"id":2,"name":"Lena","days":[]}]}"""
        assertNull(SchoolJson.timetable(raw)!!.students[0].colorHex)
    }

    @Test
    fun `a colour that is not #RRGGBB reads as none`() {
        listOf("\"red\"", "\"#FFF\"", "\"3B82F6\"", "\"#3B82F6FF\"", "\"#GGGGGG\"", "\"\"", "null", "12", "{}", "[]").forEach { v ->
            assertNull(students(""","color":$v""")[0].colorHex, v)
        }
        assertEquals("#14B8A6", SchoolJson.color(" #14b8a6 "))
    }

    @Test
    fun `only http(s) and server-relative avatar addresses are kept`() {
        assertEquals("/avatars/AbCdEfGhIjKlMnOpQrStUv12.png", SchoolJson.avatarUrl("/avatars/AbCdEfGhIjKlMnOpQrStUv12.png"))
        assertEquals(
            "https://gc.example.com/avatars/AbCdEfGhIjKlMnOpQrStUv12.jpg",
            SchoolJson.avatarUrl("https://gc.example.com/avatars/AbCdEfGhIjKlMnOpQrStUv12.jpg"),
        )
        assertEquals(
            "http://192.168.1.2:3000/avatars/AbCdEfGhIjKlMnOpQrStUv12.webp",
            SchoolJson.avatarUrl("http://192.168.1.2:3000/avatars/AbCdEfGhIjKlMnOpQrStUv12.webp"),
        )
        listOf(
            null,
            "",
            "javascript:alert(1)",
            "file:///sdcard/avatars/AbCdEfGhIjKlMnOpQrStUv12.png",
            "ftp://gc.example.com/avatars/AbCdEfGhIjKlMnOpQrStUv12.png",
            "content://media/avatars/AbCdEfGhIjKlMnOpQrStUv12.png",
            "data:image/png;base64,AAAA",
            "avatars/AbCdEfGhIjKlMnOpQrStUv12.png",
            "/avatars/../etc/passwd",
            "/avatars/AbCdEfGhIjKlMnOpQrStUv12.png?x=1",
            "https://gc.example.com/avatars/AbCdEfGhIjKlMnOpQrStUv12.png#f",
            "https:///avatars/AbCdEfGhIjKlMnOpQrStUv12.png",
        ).forEach { assertNull(SchoolJson.avatarUrl(it), it) }
        // Not a string: none, the student is still read.
        val s = students(""","avatarUrl":{"url":"/avatars/AbCdEfGhIjKlMnOpQrStUv12.png"},"color":"#22C55E"""")[0]
        assertNull(s.avatarUrl)
        assertEquals("#22C55E", s.colorHex)
    }
}
