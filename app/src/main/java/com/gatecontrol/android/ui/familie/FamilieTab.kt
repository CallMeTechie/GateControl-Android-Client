package com.gatecontrol.android.ui.familie

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.R
import com.gatecontrol.android.familie.FamilieDest
import com.gatecontrol.android.ui.adaptive.GcTwoPane
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.theme.GateControlTheme

/**
 * One screen of the Familie tab for [dest]. [onBack] leaves it, [onOpen]
 * goes to another one (e.g. places → edit), [onOpenProfile] to the own
 * profile (own avatar, "Anwesenheit teilen").
 */
@Composable
fun FamiliePage(dest: FamilieDest, onBack: () -> Unit, onOpen: (FamilieDest) -> Unit, onOpenProfile: () -> Unit = {}) {
    when (dest) {
        FamilieDest.Home -> FamilieHomeScreen(onOpen = onOpen, onOpenProfile = onOpenProfile)
        is FamilieDest.Shopping -> ShoppingScreen(onBack = onBack, initialListId = dest.listId, onOpen = onOpen)
        FamilieDest.Tasks -> TasksScreen(onBack = onBack)
        FamilieDest.Dates -> DatesScreen(onBack = onBack)
        is FamilieDest.Locate -> LocateParentScreen(childId = dest.childId, requestId = dest.requestId, onBack = onBack)
        is FamilieDest.Sos -> LocateParentScreen(childId = dest.userId, sos = dest, onBack = onBack)
        FamilieDest.Child -> ChildScreen(onBack = onBack)
        FamilieDest.Places -> PlacesScreen(onBack = onBack, onEdit = { id -> onOpen(FamilieDest.PlaceEdit(id)) })
        is FamilieDest.PlaceEdit -> PlaceEditScreen(placeId = dest.placeId, onDone = onBack)
        is FamilieDest.Rides -> RidesScreen(onBack = onBack, highlight = dest.rideId)
        is FamilieDest.Emergency -> EmergencyScreen(onBack = onBack, initialUserId = dest.userId)
        is FamilieDest.Allowance -> AllowanceScreen(onBack = onBack, onOpen = onOpen, initialChildId = dest.childId)
        is FamilieDest.Wishes -> WishesScreen(onBack = onBack, initialUserId = dest.userId)
        is FamilieDest.Expenses -> ExpensesScreen(onBack = onBack, fromListId = dest.fromListId)
        FamilieDest.Board -> BoardScreen(onBack = onBack)
        is FamilieDest.Meals -> MealsScreen(onBack = onBack, initialWeek = dest.week)
        FamilieDest.Presence -> PresenceScreen(onBack = onBack, onOpenProfile = onOpenProfile)
    }
}

/**
 * The Familie tab on wide windows: start screen as the list, the picked
 * screen beside it ([initial] from a link or push message). Place editing
 * returns to the places list in the same pane.
 */
@Composable
fun FamilieTwoPane(initial: FamilieDest?, onOpenProfile: () -> Unit = {}) {
    var destKey by rememberSaveable(initial?.key) { mutableStateOf(initial?.takeIf { it != FamilieDest.Home }?.key) }
    val selected = FamilieDest.parse(destKey)
    GcTwoPane(
        listWidth = 400.dp,
        list = {
            FamilieHomeScreen(onOpen = { destKey = it.key }, selected = selected, onOpenProfile = onOpenProfile)
        },
        detail = {
            key(destKey) {
                if (selected == null) {
                    FamiliePanePlaceholder()
                } else {
                    FamiliePage(
                        dest = selected,
                        onBack = {
                            destKey = if (selected is FamilieDest.PlaceEdit) FamilieDest.Places.key else null
                        },
                        onOpen = { destKey = it.key },
                        onOpenProfile = onOpenProfile,
                    )
                }
            }
        },
    )
}

@Composable
private fun FamiliePanePlaceholder() {
    val extra = GateControlTheme.extraColors
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(GcIcons.Family, contentDescription = null, tint = extra.faint, modifier = Modifier.size(40.dp))
        Text(
            stringResource(R.string.familie_pane_pick),
            style = MaterialTheme.typography.bodyLarge,
            color = extra.muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}
