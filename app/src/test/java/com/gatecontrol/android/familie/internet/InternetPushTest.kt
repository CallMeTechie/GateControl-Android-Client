package com.gatecontrol.android.familie.internet

import com.gatecontrol.android.familie.FamilieDest
import com.gatecontrol.android.familie.push.FamiliePush
import com.gatecontrol.android.familie.push.FamiliePushEvent
import com.gatecontrol.android.notify.PushEvent
import com.gatecontrol.android.notify.PushEventParser
import com.gatecontrol.android.notify.model.PushMessage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Messages of the topic `internet` (to the child): a tap opens „Internet zu Hause“, an open view reloads. */
class InternetPushTest {

    private fun message(topicKey: String, familie: String?): PushMessage {
        val data = listOfNotNull("\"route\":\"plg-gatecontrol-familie\"", familie?.let { "\"familie\":$it" }).joinToString(",")
        val json = """{"seq":5,"id":6,"topic":"plugin:gatecontrol-familie:$topicKey","title":"Internet-Pause bis 18:30","created_at":"2026-11-11T16:30:00Z","data":{$data}}"""
        return (PushEventParser.parse("notification", "5", json) as PushEvent.Notification).message
    }

    @Test
    fun `every kind opens the page, also the ones that ran out by themselves`() {
        val pause = FamiliePush.parse(message("internet", """{"type":"internet","kind":"pause","userId":5,"until":"2026-11-11T17:30:00Z","by":2,"ruleId":12}"""))!!
        assertEquals(FamiliePushEvent.Internet("pause", 5, 12), pause)
        listOf("pause", "resume", "extend", "extend_end", "pause_over", "extend_over", "something_new").forEach { kind ->
            val e = FamiliePush.parse(message("internet", """{"type":"internet","kind":"$kind","userId":5,"until":null,"by":null}"""))!!
            assertEquals(FamiliePushEvent.Internet(kind, 5, null), e)
            assertEquals(FamilieDest.Internet, FamiliePush.destination(e), kind)
            assertTrue(FamiliePush.touchesInternet(e), kind)
            assertTrue(FamiliePush.actions(e).isEmpty(), "no buttons: $kind")
        }
        assertEquals(FamilieDest.Internet, FamilieDest.parse(FamilieDest.Internet.key), "the key survives the notification intent")
    }

    @Test
    fun `a message of the topic without context, and other topics`() {
        val bare = FamiliePush.parse(message("internet", null))!!
        assertEquals(FamilieDest.Internet, FamiliePush.destination(bare))
        assertTrue(FamiliePush.touchesInternet(bare))
        val unknownType = FamiliePush.parse(message("internet", """{"type":"internet_whatever"}"""))!!
        assertEquals(FamilieDest.Internet, FamiliePush.destination(unknownType))
        val presence = FamiliePush.parse(message("presence", """{"type":"presence_rule","ruleId":3,"userId":5}"""))!!
        assertFalse(FamiliePush.touchesInternet(presence))
        assertEquals(null, FamilieDest.parse("internet/5"), "the page has no sub pages")
    }
}
