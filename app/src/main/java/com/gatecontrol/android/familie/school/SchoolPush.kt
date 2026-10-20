package com.gatecontrol.android.familie.school

import com.gatecontrol.android.familie.FamilieDest
import com.gatecontrol.android.familie.FamilieJson.int
import com.gatecontrol.android.familie.FamilieJson.obj
import com.gatecontrol.android.familie.FamilieJson.str
import com.gatecontrol.android.notify.model.PushMessage
import com.google.gson.JsonParser

/**
 * Pushes of the Schule plugin (every topic `plugin:gatecontrol-schule:…`:
 * `changes`, and since 1.2 the evening-before reminders `exams` and
 * `homework`; all with `data.schule = { studentId, date }`): a tap opens
 * the timetable on that child and day (a screen of the Familie tab).
 * Since 1.4 `messages` (`data.schule = { messageId, studentId? }`) opens
 * that message in the tab "Nachrichten", `absences` (`{ studentId, date }`)
 * the tab "Fehlzeiten" of that child.
 * Pure, unit-tested.
 */
object SchoolPush {

    private const val TOPIC_PREFIX = "plugin:$SCHULE_PLUGIN:"
    const val ROUTE = "plg-$SCHULE_PLUGIN"
    const val TOPIC_MESSAGES = "${TOPIC_PREFIX}messages"
    const val TOPIC_ABSENCES = "${TOPIC_PREFIX}absences"

    fun isSchool(message: PushMessage): Boolean = message.topic.startsWith(TOPIC_PREFIX) || message.route == ROUTE

    /** Where a tap on a Schule message leads; null for other senders. Missing or broken context: the view as it opens. */
    fun destination(message: PushMessage): FamilieDest.School? {
        if (!isSchool(message)) return null
        val view = when (message.topic) {
            TOPIC_MESSAGES -> SchoolView.MESSAGES
            TOPIC_ABSENCES -> SchoolView.ABSENCES
            else -> SchoolView.PLAN
        }
        val schule = message.dataJson?.let { raw ->
            runCatching { JsonParser.parseString(raw).takeIf { it.isJsonObject }?.asJsonObject?.obj("schule") }.getOrNull()
        } ?: return FamilieDest.School(view = view)
        val studentId = schule.int("studentId")?.takeIf { it > 0 }
        return when (view) {
            SchoolView.MESSAGES -> FamilieDest.School(
                studentId = studentId,
                view = view,
                messageId = schule.str("messageId")?.trim()?.takeIf { FamilieDest.School.MESSAGE_ID_RE.matches(it) },
            )
            SchoolView.ABSENCES -> FamilieDest.School(studentId = studentId, view = view)
            SchoolView.PLAN -> FamilieDest.School(studentId = studentId, date = SchoolJson.date(schule.str("date")))
        }
    }
}
