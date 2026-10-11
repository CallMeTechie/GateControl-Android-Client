package com.gatecontrol.android.notify

import com.gatecontrol.android.notify.model.PushMessage
import com.gatecontrol.android.notify.model.PushTopic
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PushChannelsTest {

    private fun msg(priority: String, topic: String = "devices", silent: Boolean = false, id: Long = 1, ck: String? = null) =
        PushMessage(
            seq = id, id = id, eventId = "e", topic = topic, priority = priority, title = "t", body = "",
            createdAt = null, expiresAt = null, collapseKey = ck, silent = silent, route = null, actions = emptyList(),
        )

    @Test
    fun `priority maps to channel and importance`() {
        assertEquals(PushChannels.CRITICAL, PushChannels.channelFor(msg("critical")).id)
        assertEquals(ChannelImportance.HIGH, PushChannels.channelFor(msg("critical")).importance)
        assertEquals(PushChannels.INFO, PushChannels.channelFor(msg("info")).id)
        // Info: no sound, but not a "silent" (low) channel — those are hidden on the lock screen.
        assertEquals(ChannelImportance.DEFAULT, PushChannels.channelFor(msg("info")).importance)
        assertEquals(false, PushChannels.channelFor(msg("info")).sound)
        assertEquals(true, PushChannels.channelFor(msg("normal")).sound)
        assertEquals("push_info_quiet", PushChannels.INFO)
        assertEquals("push_topic_devices", PushChannels.channelFor(msg("normal")).id)
        assertEquals("push_topic_devices", PushChannels.channelFor(msg("high")).id)
        assertEquals(ChannelImportance.DEFAULT, PushChannels.channelFor(msg("high")).importance)
        assertEquals(ChannelImportance.HIGH, PushChannels.channelFor(msg("normal", topic = "security")).importance)
    }

    @Test
    fun `silent beats priority`() {
        val c = PushChannels.channelFor(msg("critical", silent = true))
        assertEquals(PushChannels.SILENT, c.id)
        assertEquals(ChannelImportance.LOW, c.importance)
        assertEquals(false, c.sound)
    }

    @Test
    fun `plugin topics get their own channel with the server label`() {
        val specs = PushChannels.forTopics(listOf(PushTopic("plugin:skoda:charging", "Fahrzeug · Laden")))
        assertEquals("push_topic_plugin:skoda:charging", specs.single().id)
        assertEquals("Fahrzeug · Laden", specs.single().label)
        assertNull(specs.single().key)
        assertEquals(ChannelImportance.DEFAULT, specs.single().importance)
    }

    @Test
    fun `fixed channels cover every core topic and the connection channel at min importance`() {
        val ids = PushChannels.fixed().associateBy { it.id }
        PushTopic.CORE.forEach { assertTrue(PushChannels.topicChannelId(it) in ids, it) }
        assertEquals(ChannelImportance.MIN, ids.getValue(PushChannels.CONNECTION).importance)
    }

    @Test
    fun `same collapse key replaces, otherwise one id per notification`() {
        assertEquals(PushChannels.notificationId(msg("high", id = 1, ck = "gateway:3")), PushChannels.notificationId(msg("high", id = 2, ck = "gateway:3")))
        assertNotEquals(PushChannels.notificationId(msg("high", id = 1)), PushChannels.notificationId(msg("high", id = 2)))
        for (i in 1L..2000L) {
            val nid = PushChannels.notificationId(i, if (i % 2 == 0L) "k$i" else null)
            assertTrue(nid >= 0x40000000, "id $nid collides with fixed service ids")
        }
    }
}
