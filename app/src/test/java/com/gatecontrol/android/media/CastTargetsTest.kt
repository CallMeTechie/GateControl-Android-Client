package com.gatecontrol.android.media

import com.gatecontrol.android.gatedrop.GdDevice
import com.gatecontrol.android.gatedrop.GdOwner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CastTargetsTest {

    @Test
    fun `only own TVs and PCs with GateControl, online first, then by name`() {
        val devices = listOf(
            GdDevice("p1", "Pixel 8", "android", online = true),
            GdDevice("w2", "Laptop", "windows", online = false),
            GdDevice("t1", "Wohnzimmer-TV", "android-tv", online = true),
            GdDevice("w1", "Büro-PC", "Windows", online = true),
            GdDevice("x1", "Marcs TV", "android-tv", online = true, owner = GdOwner("7", "Marc")),
            GdDevice("i1", "iPad", "ios", online = true),
            GdDevice("n1", "Ohne Plattform", null, online = true),
        )
        assertEquals(
            listOf(
                CastTarget("w1", "Büro-PC", CastTarget.Kind.PC, true),
                CastTarget("t1", "Wohnzimmer-TV", CastTarget.Kind.TV, true),
                CastTarget("w2", "Laptop", CastTarget.Kind.PC, false),
            ),
            CastTargets.of(devices),
        )
    }

    @Test
    fun `none without devices`() {
        assertEquals(emptyList<CastTarget>(), CastTargets.of(emptyList()))
    }
}
