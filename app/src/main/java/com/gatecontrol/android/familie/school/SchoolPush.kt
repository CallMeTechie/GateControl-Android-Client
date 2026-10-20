package com.gatecontrol.android.familie.school

import com.gatecontrol.android.familie.FamilieDest
import com.gatecontrol.android.familie.FamilieJson.int
import com.gatecontrol.android.familie.FamilieJson.obj
import com.gatecontrol.android.familie.FamilieJson.str
import com.gatecontrol.android.notify.model.PushMessage
import com.google.gson.JsonParser

/**
 * Pushes of the Schule plugin (topic `plugin:gatecontrol-schule:changes`,
 * `data.schule = { studentId, date }`): a tap opens the timetable on that
 * child and day (a screen of the Familie tab). Pure, unit-tested.
 */
object SchoolPush {

    private const val TOPIC_PREFIX = "plugin:$SCHULE_PLUGIN:"
    const val ROUTE = "plg-$SCHULE_PLUGIN"

    fun isSchool(message: PushMessage): Boolean = message.topic.startsWith(TOPIC_PREFIX) || message.route == ROUTE

    /** Where a tap on a Schule message leads; null for other senders. Missing or broken context: the timetable as it opens. */
    fun destination(message: PushMessage): FamilieDest.School? {
        if (!isSchool(message)) return null
        val schule = message.dataJson?.let { raw ->
            runCatching { JsonParser.parseString(raw).takeIf { it.isJsonObject }?.asJsonObject?.obj("schule") }.getOrNull()
        } ?: return FamilieDest.School()
        return FamilieDest.School(
            studentId = schule.int("studentId")?.takeIf { it > 0 },
            date = SchoolJson.date(schule.str("date")),
        )
    }
}
