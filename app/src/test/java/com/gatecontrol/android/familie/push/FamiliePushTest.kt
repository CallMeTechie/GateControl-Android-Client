package com.gatecontrol.android.familie.push

import com.gatecontrol.android.familie.FamilieDest
import com.gatecontrol.android.familie.LocateMode
import com.gatecontrol.android.notify.PushEventParser
import com.gatecontrol.android.notify.PushEvent
import com.gatecontrol.android.notify.model.PushMessage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class FamiliePushTest {

    /** A message as the stream delivers it (through the real parser). */
    private fun message(topicKey: String, familie: String?, route: String? = "plg-gatecontrol-familie", createdAt: String = "2026-10-14T14:42:05Z"): PushMessage {
        val data = buildList {
            if (route != null) add("\"route\":\"$route\"")
            if (familie != null) add("\"familie\":$familie")
        }.joinToString(",")
        val json = """{"seq":5,"id":6,"topic":"plugin:gatecontrol-familie:$topicKey","title":"t","created_at":"$createdAt","data":{$data}}"""
        return (PushEventParser.parse("notification", "5", json) as PushEvent.Notification).message
    }

    @Test
    fun `locate requests in both modes`() {
        val auto = FamiliePush.parse(message("locate", """{"type":"locate_request","requestId":12,"mode":"auto","from":"Anna"}"""))
        assertEquals(FamiliePushEvent.LocateRequest(12, LocateMode.AUTO, "Anna"), auto)
        assertTrue(FamiliePush.actions(auto!!).isEmpty(), "auto: the server's notification only")
        assertEquals(FamilieDest.Child, FamiliePush.destination(auto))

        val confirm = FamiliePush.parse(message("locate", """{"type":"locate_request","requestId":13,"mode":"confirm","from":"Anna"}"""))!!
        assertEquals(listOf(FamiliePush.Action.SEND_LOCATION, FamiliePush.Action.DECLINE_LOCATION), FamiliePush.actions(confirm))
    }

    @Test
    fun `every familie type routes to its screen`() {
        fun dest(topic: String, f: String) = FamiliePush.destination(FamiliePush.parse(message(topic, f))!!)
        assertEquals(FamilieDest.Locate(5, 21), dest("locate", """{"type":"locate_result","requestId":21,"childId":5}"""))
        assertEquals(FamilieDest.Tasks, dest("tasks", """{"type":"task_due","taskId":4,"occurrence":"2026-10-14"}"""))
        assertEquals(FamilieDest.Shopping("l1"), dest("shopping", """{"type":"shopping_going","listId":"l1"}"""))
        assertEquals(FamilieDest.Dates, dest("dates", """{"type":"date","eventId":"12","date":"2026-10-20"}"""))
        assertEquals(FamilieDest.Home, dest("arrival", """{"type":"arrival","userId":5,"label":"Schule"}"""))
        assertEquals(FamilieDest.Places, dest("places", """{"type":"place_consent","placeId":3}"""))
        assertEquals(FamilieDest.Places, dest("places", """{"type":"place_tell","placeId":3,"userId":5}"""))
        assertEquals(
            FamilieDest.Sos(8, 5, 52.52, 13.405, 12.0),
            dest("sos", """{"type":"sos","sosId":8,"userId":5,"lat":52.52,"lon":13.405,"accuracy":12}"""),
        )
        // The parent's acknowledgement on the child's phone (no own type in the contract).
        assertEquals(FamilieDest.Child, dest("sos", """{"type":"sos_ack","sosId":8}"""))
        // A locate update of another name still carries the request.
        assertEquals(FamilieDest.Locate(5, 22), dest("locate", """{"type":"locate_whatever","requestId":22,"childId":5}"""))
    }

    @Test
    fun `task_done and the other locate pushes of the plugin`() {
        val done = FamiliePush.parse(message("tasks", """{"type":"task_done","taskId":4,"occurrence":"2026-10-14"}"""))!!
        assertEquals(FamiliePushEvent.TaskDone(4, "2026-10-14"), done)
        assertEquals(FamilieDest.Tasks, FamiliePush.destination(done))
        assertEquals("task:4:2026-10-14", FamiliePush.reminderCollapseKey(4, "2026-10-14"))
        assertNull(FamiliePush.reminderCollapseKey(4, null))
        listOf("locate_declined", "locate_failed", "locate_expired").forEach { t ->
            assertEquals(FamiliePushEvent.LocateUpdate(t, 21, 5), FamiliePush.parse(message("locate", """{"type":"$t","requestId":21,"childId":5}""")))
        }
        val ack = FamiliePush.parse(message("sos", """{"type":"sos_ack","sosId":8}"""))!!
        assertTrue(FamiliePush.isSosAck(ack, "sos"))
    }

    @Test
    fun `buttons replace the generic ones where the contract asks for them`() {
        val task = FamiliePush.parse(message("tasks", """{"type":"task_due","taskId":4,"occurrence":"2026-10-14"}"""))!!
        assertEquals(listOf(FamiliePush.Action.TASK_DONE, FamiliePush.Action.TASK_SNOOZE), FamiliePush.actions(task))
        val consent = FamiliePush.parse(message("places", """{"type":"place_consent","placeId":3}"""))!!
        assertEquals(listOf(FamiliePush.Action.PLACE_ACCEPT, FamiliePush.Action.PLACE_DECLINE), FamiliePush.actions(consent))
        val sos = FamiliePush.parse(message("sos", """{"type":"sos","sosId":8,"userId":5}"""))!!
        assertEquals(listOf(FamiliePush.Action.SOS_SEEN), FamiliePush.actions(sos))
        val sosEvent = assertInstanceOf(FamiliePushEvent.Sos::class.java, sos)
        assertNull(sosEvent.lat)
        val going = FamiliePush.parse(message("shopping", """{"type":"shopping_going","listId":"l1"}"""))!!
        assertTrue(FamiliePush.actions(going).isEmpty())
    }

    @Test
    fun `place messages re-sync the geofences, sos acks are recognised`() {
        val consent = FamiliePush.parse(message("places", """{"type":"place_consent","placeId":3}"""))!!
        assertTrue(FamiliePush.touchesPlaces(consent, "places"))
        val task = FamiliePush.parse(message("tasks", """{"type":"task_due","taskId":4}"""))!!
        assertFalse(FamiliePush.touchesPlaces(task, "tasks"))
        val ack = FamiliePush.parse(message("sos", null))!!
        assertTrue(FamiliePush.isSosAck(ack, "sos"))
        val sos = FamiliePush.parse(message("sos", """{"type":"sos","sosId":8,"userId":5}"""))!!
        assertFalse(FamiliePush.isSosAck(sos, "sos"))
    }

    @Test
    fun `messages of other senders and broken context`() {
        val other = (PushEventParser.parse("notification", "1", """{"seq":1,"id":1,"topic":"devices","title":"x","data":{"familie":{"type":"sos","sosId":1,"userId":2}}}""") as PushEvent.Notification).message
        assertNull(FamiliePush.parse(other), "only Familie topics or the Familie route count")
        val broken = FamiliePush.parse(message("tasks", """{"type":"task_due"}"""))
        assertEquals(FamiliePushEvent.Other("task_due", "tasks"), broken)
        assertEquals(FamilieDest.Tasks, FamiliePush.destination(broken!!))
        val noData = FamiliePush.parse(message("shopping", null, route = null))
        assertEquals(FamiliePushEvent.Other(null, "shopping"), noData)
    }

    @Test
    fun `an automatic answer only for fresh requests`() {
        val m = message("locate", """{"type":"locate_request","requestId":12,"mode":"auto"}""", createdAt = "2026-10-14T14:42:05Z")
        val created = Instant.parse("2026-10-14T14:42:05Z").toEpochMilli()
        assertTrue(FamiliePushHandler.isFresh(m, created + 60_000))
        assertFalse(FamiliePushHandler.isFresh(m, created + 11 * 60_000))
    }

    // ─── 1.1 ─────────────────────────────────────────────────────────

    @Test
    fun `ride messages open the rides screen, an ask brings the two buttons`() {
        val ask = FamiliePush.parse(message("rides", """{"type":"ride_ask","rideId":"3:2026-10-14","date":"2026-10-14"}"""))!!
        assertEquals(FamiliePushEvent.Ride("ride_ask", "3:2026-10-14", "2026-10-14"), ask)
        assertEquals(listOf(FamiliePush.Action.RIDE_TAKE, FamiliePush.Action.RIDE_DECLINE), FamiliePush.actions(ask))
        assertEquals(FamilieDest.Rides("3:2026-10-14"), FamiliePush.destination(ask))

        listOf("ride_reminder", "ride_open", "ride_taken", "ride_released", "ride_unassigned").forEach { t ->
            val e = FamiliePush.parse(message("rides", """{"type":"$t","rideId":"12","date":"2026-10-14"}"""))!!
            assertEquals(FamiliePushEvent.Ride(t, "12", "2026-10-14"), e)
            assertTrue(FamiliePush.actions(e).isEmpty(), t)
            assertEquals(FamilieDest.Rides("12"), FamiliePush.destination(e), t)
        }

        // A ride id that is not one: no buttons, the list still opens.
        val bad = FamiliePush.parse(message("rides", """{"type":"ride_ask","rideId":"12/../x"}"""))!!
        assertEquals(FamiliePushEvent.Ride("ride_ask", null, null), bad)
        assertTrue(FamiliePush.actions(bad).isEmpty())
        assertEquals(FamilieDest.Rides(), FamiliePush.destination(bad))
        assertEquals(FamilieDest.Rides(), FamiliePush.destination(FamiliePush.parse(message("rides", null))!!))
    }

    @Test
    fun `the age hint opens the child's locate screen for the parent`() {
        val hint = FamiliePush.parse(message("locate", """{"type":"locate_age_hint","childId":5}"""))!!
        assertEquals(FamiliePushEvent.LocateAgeHint(5), hint)
        assertEquals(FamilieDest.Locate(5), FamiliePush.destination(hint))
        assertTrue(FamiliePush.actions(hint).isEmpty())
    }
}
