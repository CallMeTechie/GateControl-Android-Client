package com.gatecontrol.android.drive.work

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.ZoneId

class DriveWorkClassifierTest {

    private val zone = ZoneId.of("Europe/Berlin")

    private fun ex(key: String, work: Boolean, vararg f: Pair<Int, Float>, source: WorkExample.Source = WorkExample.Source.ASSIGN, at: Long = 0) =
        WorkExample(key, work, source, f.toMap(), at)

    // label indices as the image labeling might find them: 10 whiteboard, 11 desk, 12 computer; 20 dog, 21 beach, 22 food
    private val examples = buildList {
        repeat(10) { i -> add(ex("w$i", true, 10 to 0.9f, 11 to 0.7f, DriveWorkFeatures.WORK_HOURS to 1f)) }
        repeat(5) { i -> add(ex("w2$i", true, 12 to 0.8f)) }
        repeat(10) { i -> add(ex("p$i", false, 20 to 0.9f, 21 to 0.6f, DriveWorkFeatures.WEEKEND to 1f)) }
        repeat(5) { i -> add(ex("p2$i", false, 22 to 0.8f)) }
    }

    @Test
    fun `learns work and private apart`() {
        val m = DriveWorkModel.train(examples)!!
        assertTrue(m.predict(mapOf(10 to 0.95f, DriveWorkFeatures.WORK_HOURS to 1f)) > 0.85)
        assertTrue(m.predict(mapOf(20 to 0.9f, DriveWorkFeatures.WEEKEND to 1f)) < 0.15)
        val unknown = m.predict(emptyMap())
        assertTrue(unknown in 0.3..0.7, "nothing known: unsure ($unknown)")
        // both kinds weigh the same whatever their numbers
        val skew = DriveWorkModel.train(examples + List(40) { ex("x$it", false, 22 to 0.8f) })!!
        assertTrue(skew.predict(mapOf(10 to 0.9f, 11 to 0.7f)) > 0.7)
    }

    @Test
    fun `no model without both kinds`() {
        assertNull(DriveWorkModel.train(emptyList()))
        assertNull(DriveWorkModel.train(listOf(ex("a", true, 1 to 1f))))
    }

    @Test
    fun `features are the sure labels and when it was taken`() {
        val weekday = LocalDateTime.of(2026, 10, 29, 9, 12).atZone(zone).toInstant().toEpochMilli()
        val evening = LocalDateTime.of(2026, 10, 29, 21, 0).atZone(zone).toInstant().toEpochMilli()
        val saturday = LocalDateTime.of(2026, 10, 31, 11, 0).atZone(zone).toInstant().toEpochMilli()
        val f = DriveWorkFeatures.of(mapOf(1 to 0.9f, 2 to 0.3f), weekday, zone)
        assertEquals(mapOf(1 to 0.9f, DriveWorkFeatures.WORK_HOURS to 1f), f)
        assertEquals(mapOf(1 to 0.9f), DriveWorkFeatures.of(mapOf(1 to 0.9f), evening, zone))
        assertEquals(mapOf(DriveWorkFeatures.WEEKEND to 1f), DriveWorkFeatures.of(emptyMap(), saturday, zone))
        assertEquals(emptyMap<Int, Float>(), DriveWorkFeatures.of(mapOf(DriveWorkFeatures.WEEKEND to 1f), null, zone))
    }

    @Test
    fun `one example per photo, the newest answer wins, the oldest go`() {
        var list = DriveWorkFeatures.add(emptyList(), ex("n:1", true, at = 1))
        list = DriveWorkFeatures.add(list, ex("n:1", false, at = 2))
        assertEquals(listOf(false), list.map { it.work })
        val full = (0 until DriveWorkFeatures.MAX_EXAMPLES).map { ex("k$it", it % 2 == 0, at = it.toLong() + 10) }
        val more = DriveWorkFeatures.add(full, ex("new", true, at = 99_999))
        assertEquals(DriveWorkFeatures.MAX_EXAMPLES, more.size)
        assertTrue(more.none { it.key == "k0" })
        assertTrue(more.any { it.key == "new" })
    }

    @Test
    fun `what was learned`() {
        val l = DriveWorkFeatures.learned(
            listOf(
                ex("a", true, source = WorkExample.Source.WIFI),
                ex("b", true),
                ex("c", false, source = WorkExample.Source.WIFI),
                ex("d", false, source = WorkExample.Source.CLEANUP),
            ),
        )
        assertEquals(DriveWorkLogic.Learned(total = 4, work = 2, private = 2, workFromWifi = 1, privateFromWifi = 1), l)
    }

    @Test
    fun `kept as JSON on the phone`() {
        val list = listOf(ex("n:5", true, 3 to 0.75f, source = WorkExample.Source.MOVE, at = 42), ex("n:6", false))
        assertEquals(list, DriveWorkFeatures.decode(DriveWorkFeatures.encode(list)))
        assertTrue(DriveWorkFeatures.decode("nonsense").isEmpty())
        assertTrue(DriveWorkFeatures.decode(null).isEmpty())
        // an unknown source or a broken entry is left out
        val mixed = DriveWorkFeatures.decode("""{"v":1,"e":[{"k":"a","w":true,"s":"x","f":{}},{"k":"b","w":false,"s":"wifi","f":{"1":0.5,"y":2}},{"w":true}]}""")
        assertEquals(listOf("b"), mixed.map { it.key })
        assertEquals(mapOf(1 to 0.5f), mixed.single().features)
    }
}
