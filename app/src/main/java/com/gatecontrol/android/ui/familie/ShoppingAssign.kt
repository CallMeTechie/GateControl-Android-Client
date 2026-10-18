package com.gatecontrol.android.ui.familie

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.R
import com.gatecontrol.android.familie.FamilieProfile
import com.gatecontrol.android.familie.shopping.ShoppingList
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcFilterChip
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.components.GcSectionLabel
import com.gatecontrol.android.ui.theme.GateControlTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Where the request of "<Name> bitten" goes (B3 `notify`). */
object ShoppingNotify {
    const val DIRECT = "direct"
    const val FAMILY = "family"
    const val PUSH = "push"
    val ALL = listOf(DIRECT, FAMILY, PUSH)
}

/**
 * "Wer kauft ein?" (mockup ShoppingList, Familie B3): person chips (Ich,
 * the others, Offen), where the message goes, and the two buttons
 * "<Name> bitten" / "Ich gehe einkaufen". Without other members only
 * "Ich gehe einkaufen" stays.
 */
@Composable
fun ShoppingAssignSection(
    list: ShoppingList,
    profile: FamilieProfile?,
    going: Boolean,
    assigning: Boolean,
    onGoing: () -> Unit,
    onAssign: (userId: Int?, notify: String) -> Unit,
) {
    val extra = GateControlTheme.extraColors
    val me = profile?.me?.userId
    val others = profile?.everyone.orEmpty().filter { it.userId != me }
    // Key: the list and its current assignee — a change on the server resets the choice.
    var pick by rememberSaveable(list.id, list.assigneeId) { mutableStateOf(list.assigneeId ?: PICK_OPEN) }
    var notify by rememberSaveable(list.id) { mutableStateOf(ShoppingNotify.DIRECT) }
    val picked = others.firstOrNull { it.userId == pick }
    if (others.isNotEmpty()) {
        val current = list.assigneeId?.let { id -> profile?.nameOf(id) ?: list.assigneeName }
        if (current != null) {
            Text(
                listOfNotNull(
                    stringResource(R.string.familie_shop_assigned, current),
                    when (list.assigneeState) {
                        "taken" -> stringResource(R.string.familie_shop_state_taken)
                        "declined" -> stringResource(R.string.familie_shop_state_declined)
                        "open" -> stringResource(R.string.familie_shop_state_open)
                        else -> null
                    },
                    list.dueAt?.let { dueLabel(it) }?.let { stringResource(R.string.familie_shop_due, it) },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = extra.accentText,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        GcSectionLabel(stringResource(R.string.familie_shop_assign_who), Modifier.padding(start = 4.dp, top = 4.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (me != null) GcFilterChip(stringResource(R.string.familie_shop_assign_me), pick == me, { pick = me })
            others.forEach { m -> GcFilterChip(m.name, pick == m.userId, { pick = m.userId }) }
            GcFilterChip(stringResource(R.string.familie_shop_assign_open), pick == PICK_OPEN, { pick = PICK_OPEN })
        }
        if (picked != null) {
            GcCard(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.familie_shop_assign_to), style = MaterialTheme.typography.titleSmall, color = extra.muted)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ShoppingNotify.ALL.forEach { n ->
                        GcFilterChip(
                            when (n) {
                                ShoppingNotify.DIRECT -> stringResource(R.string.familie_shop_notify_direct, picked.name)
                                ShoppingNotify.FAMILY -> stringResource(R.string.familie_shop_notify_family)
                                else -> stringResource(R.string.familie_shop_notify_push)
                            },
                            notify == n,
                            { notify = n },
                        )
                    }
                }
                Text(stringResource(R.string.familie_shop_assign_hint, picked.name), style = MaterialTheme.typography.bodySmall, color = extra.muted)
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            picked != null -> GcPrimaryButton(
                text = stringResource(R.string.familie_shop_ask, picked.name),
                onClick = { onAssign(picked.userId, notify) },
                loading = assigning,
                icon = GcIcons.Share,
            )
            pick == PICK_OPEN && list.assigneeId != null -> GcOutlineButton(
                text = stringResource(R.string.familie_shop_unassign),
                onClick = { onAssign(null, ShoppingNotify.PUSH) },
                loading = assigning,
            )
        }
        val goingButton: @Composable () -> Unit = {
            if (picked == null) {
                GcPrimaryButton(text = stringResource(R.string.familie_shopping_going), onClick = onGoing, loading = going, icon = GcIcons.Cart)
            } else {
                GcOutlineButton(text = stringResource(R.string.familie_shopping_going), onClick = onGoing, loading = going, icon = GcIcons.Cart)
            }
        }
        goingButton()
    }
}

private const val PICK_OPEN = -1

@Composable
private fun dueLabel(iso: String): String? = runCatching {
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).format(Instant.parse(iso).atZone(ZoneId.systemDefault()))
}.getOrNull()
