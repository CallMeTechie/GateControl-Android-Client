package com.gatecontrol.android.familie.places

import com.gatecontrol.android.familie.FamiliePlace
import com.gatecontrol.android.notizen.NFence
import com.gatecontrol.android.notizen.NotizenGeofences
import com.gatecontrol.android.notizen.PlaceState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The geofences of Familie and Notizen as the Ortsnachrichten do it (PR #255): replaced instead of removed first,
 * `INITIAL_TRIGGER_ENTER | _EXIT`, the side of each fence kept, at least 150 m. Made-up places only.
 */
class FenceSidesTest {

    private fun reminder(id: Int, lat: Double = 50.0, radius: Int = 100, trigger: String = FamiliePlace.TRIGGER_ENTER) =
        StoredFence(id, "Brot holen", "", lat, 8.0, radius, trigger, FamiliePlace.ACTION_NOTE, null, "always")

    @Test
    fun `the watched radius is at least 150 m, the saved one stays`() {
        assertEquals(150, FenceSides.radius(50))
        assertEquals(150, FenceSides.radius(150))
        assertEquals(300, FenceSides.radius(300))
        // 100 m and 140 m are the same fence on the phone
        assertEquals(FenceSides.signature(50.0, 8.0, 100), FenceSides.signature(50.0, 8.0, 140))
        assertTrue(FenceSides.signature(50.0, 8.0, 100) != FenceSides.signature(50.0, 8.0, 200))
    }

    @Test
    fun `adding again keeps the side of the same fence, a moved one learns anew`() {
        val prev = mapOf(
            "a" to FenceSide(FenceSides.signature(50.0, 8.0, 150), FenceSides.IN, 1),
            "b" to FenceSide(FenceSides.signature(50.1, 8.0, 150), FenceSides.OUT, 1),
            "gone" to FenceSide("x", FenceSides.OUT, 1),
        )
        val next = FenceSides.afterRegister(
            prev,
            mapOf("a" to FenceSides.signature(50.0, 8.0, 150), "b" to FenceSides.signature(50.2, 8.0, 150), "new" to FenceSides.signature(50.3, 8.0, 150)),
            nowMs = 9,
        )
        assertEquals(setOf("a", "b", "new"), next.keys)
        assertEquals(FenceSides.IN, next["a"]?.side)
        assertNull(next["b"]?.side) // the place moved: only its side is learned
        assertEquals(9L, next["b"]?.sinceMs)
        assertNull(next["new"]?.side)
    }

    @Test
    fun `the report on adding is an arrival only when the phone was outside before`() {
        val sig = FenceSides.signature(50.0, 8.0, 150)
        // new fence: learns its side, no reminder (being there while saving sends nothing)
        val (learned, moved1) = FenceSides.observe(FenceSide(sig, null, 0), FenceSides.IN, 5)
        assertFalse(moved1)
        assertEquals(FenceSides.IN, learned?.side)
        // the app started at the place: inside before, inside now — nothing twice
        assertFalse(FenceSides.observe(learned, FenceSides.IN, 6).second)
        // outside before, inside on adding again: the arrival the old fence had not reported is not lost
        val (arrived, moved2) = FenceSides.observe(FenceSide(sig, FenceSides.OUT, 0), FenceSides.IN, 7)
        assertTrue(moved2)
        assertEquals(7L, arrived?.sinceMs)
        // a fence of an older version of the app (no side kept): a transition, as it was then
        assertEquals(null to true, FenceSides.observe(null, FenceSides.OUT, 8))
    }

    @Test
    fun `what goes before adding - only the ones no longer wanted, all once for an older list`() {
        assertEquals(setOf("b"), FenceSides.toRemove(listOf("a", "b"), listOf("a", "c")))
        assertEquals(emptySet<String>(), FenceSides.toRemove(listOf("a"), listOf("a")))
        assertNull(FenceSides.toRemove(null, listOf("a")))
    }

    @Test
    fun `the sides survive the storage`() {
        val m = mapOf("p:1" to FenceSide("s1", FenceSides.IN, 3), "cell" to FenceSide("s2", null, 4))
        assertEquals(m, FenceSides.read(FenceSides.write(m)))
        assertTrue(FenceSides.read(null).isEmpty())
    }

    @Test
    fun `Familie - reminders and rule places as one list of signatures`() {
        val rules = listOf(RuleFence(placeId = 3, name = "Schule", lat = 50.2, lon = 8.2, radius = 80, enter = true, exit = false))
        val sigs = GeofenceRegistrar.signatures(listOf(reminder(1), reminder(2, trigger = FamiliePlace.TRIGGER_EXIT)), rules)
        assertEquals(listOf("familie-place-1", "familie-place-2", "familie-rule-3"), sigs.keys.toList())
        // the reminder's trigger is part of it: switched to „Beim Verlassen“ it is a new fence
        assertTrue(sigs["familie-place-1"] != GeofenceRegistrar.signatures(listOf(reminder(1, trigger = FamiliePlace.TRIGGER_EXIT)), emptyList())["familie-place-1"])
        assertTrue(sigs.getValue("familie-rule-3").contains("|150|"))
        // a rule place nobody waits at is not watched
        assertTrue(GeofenceRegistrar.signatures(emptyList(), listOf(rules[0].copy(enter = false))).isEmpty())
    }

    @Test
    fun `Notizen - the sides of the reported fences, only real transitions act`() {
        val f = NFence("p:12", "profile:12", 50.0, 8.0, 100, "Arbeit", null, enter = true, exit = false, tasks = emptyList())
        val sigs = NotizenGeofences.signatures(listOf(f))
        assertEquals(setOf("p:12"), sigs.keys)
        val start = FenceSides.afterRegister(mapOf("p:12" to FenceSide(sigs.getValue("p:12"), FenceSides.OUT, 0)), sigs + ("cell" to "c"), 1)
        val (sides, moved) = NotizenGeofences.observeAll(start, listOf("p:12", "cell"), entered = true, nowMs = 2)
        assertEquals(listOf("p:12"), moved) // the cell learned its side only
        assertEquals(FenceSides.IN, sides["p:12"]?.side)
        assertEquals(FenceSides.IN, sides["cell"]?.side)
        assertTrue(NotizenGeofences.observeAll(sides, listOf("p:12"), entered = true, nowMs = 3).second.isEmpty())
    }

    @Test
    fun `Notizen - the sides are kept with the place state`() {
        val s = PlaceState(registered = listOf("p:12"), sides = mapOf("p:12" to FenceSide("s", FenceSides.IN, 5), "cell" to FenceSide("c", null, 6), "bad id" to FenceSide("x", null, 0)))
        val back = PlaceState.fromJson(s.toJson())
        assertEquals(setOf("p:12", "cell"), back.sides.keys)
        assertEquals(FenceSides.IN, back.sides["p:12"]?.side)
    }
}
