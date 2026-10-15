package com.gatecontrol.android.notify

import com.gatecontrol.android.notify.model.PushAction
import com.gatecontrol.android.notify.model.PushActionType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Core 1.169: pictures (`imageUrl`) and `quick_action` buttons of plugin messages. */
class PushMediaActionsTest {

    private val doorbell = """
        {"seq":501,"id":88,"event_id":"door_ding","topic":"plugin:gatecontrol-door:doorbell","priority":"high",
         "title":"Es klingelt an der Haustür","body":"Klingel Haustür","collapse_key":"ring:4",
         "imageUrl":"/notify-media/AbCdEfGhIjKlMnOpQrStUvWx.jpg",
         "data":{"route":"plg-gatecontrol-door","door":{"type":"ding","eventId":120,"cameraId":4,"lockId":1},
           "actions":[
             {"id":"1:open","label":"Tür öffnen","type":"quick_action","plugin":"gatecontrol-door","confirm":true,"auth":"device"},
             {"id":"open","label":"Ansehen","type":"open_app_route","target":"plg-gatecontrol-door"}]}}
    """.trimIndent()

    private fun parse(json: String) =
        assertInstanceOf(PushEvent.Notification::class.java, PushEventParser.parse("notification", "1", json)).message

    @Test
    fun `doorbell push carries its picture and a quick action with device auth`() {
        val m = parse(doorbell)
        assertEquals("/notify-media/AbCdEfGhIjKlMnOpQrStUvWx.jpg", m.imageUrl)
        assertEquals(listOf("1:open", "open"), m.actions.map { it.id })
        val qa = m.actions[0]
        assertEquals(PushActionType.QUICK_ACTION, qa.type)
        assertEquals("gatecontrol-door", qa.plugin)
        assertTrue(qa.confirm)
        assertEquals(PushAction.AUTH_DEVICE, qa.auth)
        assertTrue(qa.isQuickAction)
        assertTrue(qa.needsDeviceAuth)
        assertEquals(PushActionType.OPEN_APP_ROUTE, m.actions[1].type)
        assertFalse(m.actions[1].needsDeviceAuth)
    }

    @Test
    fun `quick action without auth runs without unlock`() {
        val m = parse(
            """{"seq":2,"id":2,"title":"Haustür automatisch aufgesperrt","topic":"plugin:gatecontrol-door:auto",
               "data":{"actions":[{"id":"1:lock","label":"Wieder zusperren","type":"quick_action","plugin":"gatecontrol-door","confirm":false}]}}""",
        )
        val qa = m.actions.single()
        assertTrue(qa.isQuickAction)
        assertFalse(qa.confirm)
        assertNull(qa.auth)
        assertFalse(qa.needsDeviceAuth)
    }

    @Test
    fun `malformed quick actions are dropped`() {
        val m = parse(
            """{"seq":3,"id":3,"title":"x","data":{"actions":[
                {"id":"1:open","label":"Öffnen","type":"quick_action"},
                {"id":"1:open","label":"Öffnen","type":"quick_action","plugin":"Bad Plugin"},
                {"id":"../x","label":"Öffnen","type":"quick_action","plugin":"gatecontrol-door"},
                {"id":"1:open","label":"","type":"quick_action","plugin":"gatecontrol-door"},
                {"id":"${"a".repeat(65)}","label":"Öffnen","type":"quick_action","plugin":"gatecontrol-door"},
                {"id":"1:open","label":"Öffnen","type":"quick_action","plugin":"gatecontrol-door","auth":"none"}]}}""",
        )
        val qa = m.actions.single()
        assertEquals("1:open", qa.id)
        assertNull(qa.auth)
    }

    @Test
    fun `a 64 character quick action id is kept and can be acknowledged`() {
        val id = "x".repeat(59) + ":open"
        assertEquals(64, id.length)
        val m = parse(
            """{"seq":4,"id":4,"title":"x","data":{"actions":[{"id":"$id","label":"Öffnen","type":"quick_action","plugin":"p"}]}}""",
        )
        assertEquals(id, m.actions.single().id)
        assertTrue(PushEventParser.ACK_ACTION_RE.matches(id))
        assertFalse(PushEventParser.ACTION_ID_RE.matches(id))
    }

    @Test
    fun `imageUrl must be a notify-media path`() {
        listOf(
            "https://evil.example/notify-media/AbCdEfGhIjKlMnOp.jpg",
            "//evil.example/notify-media/AbCdEfGhIjKlMnOp.jpg",
            "/avatars/AbCdEfGhIjKlMnOp.jpg",
            "/notify-media/../secret.jpg",
            "/notify-media/AbCdEfGhIjKlMnOp.gif",
            "/notify-media/short.jpg",
            "",
        ).forEach { bad ->
            val m = parse("""{"seq":5,"id":5,"title":"x","imageUrl":"$bad"}""")
            assertNull(m.imageUrl, bad)
        }
        assertNull(parse("""{"seq":6,"id":6,"title":"x","imageUrl":null}""").imageUrl)
        assertEquals("/notify-media/AbCdEfGhIjKlMnOp_-12.webp", PushEventParser.imagePath("/notify-media/AbCdEfGhIjKlMnOp_-12.webp"))
    }

    @Test
    fun `inbox items carry the picture too`() {
        val dto = com.google.gson.Gson().fromJson(doorbell, com.gatecontrol.android.network.PushNotificationDto::class.java)
        val m = PushEventParser.toMessage(dto)!!
        assertEquals("/notify-media/AbCdEfGhIjKlMnOpQrStUvWx.jpg", m.imageUrl)
    }

    @Test
    fun `the doorbell gets a high importance channel, other door topics stay default`() {
        assertEquals(ChannelImportance.HIGH, PushChannels.topicChannel("plugin:gatecontrol-door:doorbell", "Klingel").importance)
        assertEquals(ChannelImportance.DEFAULT, PushChannels.topicChannel("plugin:gatecontrol-door:motion", "Bewegung").importance)
        assertEquals(ChannelImportance.DEFAULT, PushChannels.topicChannel("plugin:gatecontrol-door:door", "Tür").importance)
        assertEquals(ChannelImportance.HIGH, PushChannels.channelFor(parse(doorbell)).importance)
    }
}
