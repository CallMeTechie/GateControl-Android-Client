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
}
