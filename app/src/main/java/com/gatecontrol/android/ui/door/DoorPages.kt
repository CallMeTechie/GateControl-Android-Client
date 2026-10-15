package com.gatecontrol.android.ui.door

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.door.DoorDest
import com.gatecontrol.android.ui.adaptive.GcTwoPane

/** One door screen for [dest] (phones: its own route on top of the door screen). */
@Composable
fun DoorPage(dest: DoorDest, onBack: () -> Unit, onOpen: (DoorDest) -> Unit) {
    when (dest) {
        DoorDest.Home -> DoorHomeScreen(onOpen = onOpen, onBack = onBack)
        DoorDest.History -> DoorHistoryScreen(onBack = onBack, onOpen = onOpen)
        is DoorDest.Event -> DoorEventScreen(eventId = dest.eventId, onBack = onBack)
    }
}

/**
 * The door screen "Tür & Kamera" on wide windows: locks and cameras as the
 * list, the history or a ring's detail beside it ([initial] from a push
 * message). [onBack] leaves it (back to the start tab).
 */
@Composable
fun DoorTwoPane(initial: DoorDest?, onBack: () -> Unit) {
    var destKey by rememberSaveable(initial?.key) { mutableStateOf(initial?.takeIf { it != DoorDest.Home }?.key) }
    val selected = DoorDest.parse(destKey)
    GcTwoPane(
        listWidth = 420.dp,
        list = { DoorHomeScreen(onOpen = { destKey = it.key }, onBack = onBack, selected = selected) },
        detail = {
            key(destKey) {
                when (selected) {
                    null, DoorDest.Home -> DoorPanePlaceholder()
                    DoorDest.History -> DoorHistoryScreen(onBack = { destKey = null }, onOpen = { destKey = it.key }, selected = selected)
                    is DoorDest.Event -> DoorEventScreen(eventId = selected.eventId, onBack = { destKey = DoorDest.History.key })
                }
            }
        },
    )
}
