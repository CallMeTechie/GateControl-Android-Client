package com.gatecontrol.android.familie.school

import com.gatecontrol.android.familie.FamilieDest
import com.gatecontrol.android.notify.PushEvent
import com.gatecontrol.android.notify.PushEventParser
import com.gatecontrol.android.notify.model.PushMessage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SchoolPushTest {

    /** A message as the stream delivers it (through the real parser). */
    private fun message(topic: String, data: String?): PushMessage {
        val json = """{"seq":5,"id":6,"topic":"$topic","title":"Lena: Englisch fällt aus","created_at":"2026-10-20T06:42:05Z"${data?.let { ",\"data\":$it" } ?: ""}}"""
        return (PushEventParser.parse("notification", "5", json) as PushEvent.Notification).message
    }

    @Test
    fun `a change push opens the timetable on that child and day`() {
        val m = message("plugin:gatecontrol-schule:changes", """{"schule":{"studentId":3,"date":"2026-10-20"}}""")
        assertTrue(SchoolPush.isSchool(m))
        val dest = SchoolPush.destination(m)
        assertEquals(FamilieDest.School(3, "2026-10-20"), dest)
        assertEquals(dest, FamilieDest.parse(dest!!.key), "the key survives the notification intent")
    }

    @Test
    fun `exam and homework reminders open the timetable on that child and day too`() {
        listOf("plugin:gatecontrol-schule:exams", "plugin:gatecontrol-schule:homework").forEach { topic ->
            val m = message(topic, """{"schule":{"studentId":4,"date":"2026-10-21"}}""")
            assertTrue(SchoolPush.isSchool(m), topic)
            val dest = SchoolPush.destination(m)
            assertEquals(FamilieDest.School(4, "2026-10-21"), dest, topic)
            assertEquals(dest, FamilieDest.parse(dest!!.key), topic)
            assertEquals(FamilieDest.School(), SchoolPush.destination(message(topic, null)), topic)
        }
    }

    @Test
    fun `missing or broken context still opens the timetable`() {
        assertEquals(FamilieDest.School(), SchoolPush.destination(message("plugin:gatecontrol-schule:changes", null)))
        assertEquals(
            FamilieDest.School(null, null),
            SchoolPush.destination(message("plugin:gatecontrol-schule:changes", """{"schule":{"studentId":"x","date":"morgen"}}""")),
        )
        assertEquals(
            FamilieDest.School(3, null),
            SchoolPush.destination(message("plugin:gatecontrol-schule:changes", """{"schule":{"studentId":3,"date":"2026-13-01"}}""")),
        )
    }

    @Test
    fun `a message push opens that message in the messages view`() {
        val m = message("plugin:gatecontrol-schule:messages", """{"schule":{"messageId":"4711","studentId":3}}""")
        assertTrue(SchoolPush.isSchool(m))
        val dest = SchoolPush.destination(m)
        assertEquals(FamilieDest.School(3, null, SchoolView.MESSAGES, "4711"), dest)
        assertEquals(dest, FamilieDest.parse(dest!!.key), "the key survives the notification intent")

        // studentId is optional; a number as id is fine too.
        val noChild = SchoolPush.destination(message("plugin:gatecontrol-schule:messages", """{"schule":{"messageId":12}}"""))
        assertEquals(FamilieDest.School(null, null, SchoolView.MESSAGES, "12"), noChild)
        assertEquals(noChild, FamilieDest.parse(noChild!!.key))
        // An id the link cannot carry, no context: the messages view as it opens.
        assertEquals(
            FamilieDest.School(3, null, SchoolView.MESSAGES, null),
            SchoolPush.destination(message("plugin:gatecontrol-schule:messages", """{"schule":{"messageId":"a/b?c","studentId":3}}""")),
        )
        assertEquals(FamilieDest.School(view = SchoolView.MESSAGES), SchoolPush.destination(message("plugin:gatecontrol-schule:messages", null)))
    }

    @Test
    fun `an absence push opens the absences of that child`() {
        val m = message("plugin:gatecontrol-schule:absences", """{"schule":{"studentId":4,"date":"2026-10-20"}}""")
        assertTrue(SchoolPush.isSchool(m))
        val dest = SchoolPush.destination(m)
        assertEquals(FamilieDest.School(4, null, SchoolView.ABSENCES), dest)
        assertEquals(dest, FamilieDest.parse(dest!!.key))
        assertEquals(
            FamilieDest.School(view = SchoolView.ABSENCES),
            SchoolPush.destination(message("plugin:gatecontrol-schule:absences", """{"schule":{"studentId":-1}}""")),
        )
        assertEquals(FamilieDest.School(view = SchoolView.ABSENCES), SchoolPush.destination(message("plugin:gatecontrol-schule:absences", null)))
    }

    @Test
    fun `other senders are not school messages`() {
        val familie = message("plugin:gatecontrol-familie:tasks", """{"schule":{"studentId":3}}""")
        assertFalse(SchoolPush.isSchool(familie))
        assertNull(SchoolPush.destination(familie))
        // A plugin whose id only starts like the Schule plugin's is another sender.
        val lookalike = message("plugin:gatecontrol-schule-extra:exams", """{"schule":{"studentId":3}}""")
        assertFalse(SchoolPush.isSchool(lookalike))
        assertNull(SchoolPush.destination(lookalike))
    }
}
