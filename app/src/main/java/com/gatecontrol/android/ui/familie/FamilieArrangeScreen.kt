package com.gatecontrol.android.ui.familie

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.R
import com.gatecontrol.android.familie.FamilieProfile
import com.gatecontrol.android.layout.LayoutAccess
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIconSquare
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcSectionLabel
import com.gatecontrol.android.ui.start.ArrangeBar
import com.gatecontrol.android.ui.start.StartIcons
import com.gatecontrol.android.ui.theme.GateControlTheme
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * "Familie anordnen" (mockup FamilyArrange): the cards as rows to drag into
 * order (hold a row, then drag; screen readers get "Nach oben/unten"), the
 * eye hides a card — hidden ones gather under "Ausgeblendet", their eye
 * shows them again. "Fertig" (or back) hands [onDone] the order of all
 * cards and the hidden ones.
 */
@Composable
fun FamilieArrangeScreen(
    profile: FamilieProfile,
    order: List<String>,
    hidden: Set<String>,
    scope: LayoutAccess,
    onDone: (order: List<String>, hidden: Set<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val extra = GateControlTheme.extraColors
    val haptic = LocalHapticFeedback.current
    var cards by rememberSaveable { mutableStateOf(order) }
    var off by rememberSaveable { mutableStateOf(hidden.filter { it in order }) }
    val visible = cards.filter { it !in off }
    val finish = { onDone(cards, off.toSet()) }
    BackHandler(onBack = finish)

    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        val a = from.key as? String
        val b = to.key as? String
        val i = cards.indexOf(a)
        val j = cards.indexOf(b)
        if (i >= 0 && j >= 0 && a !in off && b !in off) {
            cards = cards.toMutableList().apply { add(j, removeAt(i)) }
            haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
        }
    }
    val upLabel = stringResource(R.string.arrange_move_up)
    val downLabel = stringResource(R.string.arrange_move_down)

    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        ArrangeBar(
            title = stringResource(R.string.familie_arrange),
            hint = stringResource(R.string.familie_arrange_hint),
            scope = scope,
            onDone = finish,
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(visible, key = { it }) { id ->
                ReorderableItem(reorderState, key = id) { dragging ->
                    val index = visible.indexOf(id)
                    val title = FamilieCards.title(id, profile)
                    ArrangeRow(
                        title = title,
                        icon = FamilieCards.icon(id),
                        hiddenNow = false,
                        dragging = dragging,
                        onEye = { off = off + id },
                        eyeLabel = stringResource(R.string.familie_arrange_hide, title),
                        modifier = Modifier
                            .longPressDraggableHandle(onDragStarted = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) })
                            .semantics {
                                customActions = buildList {
                                    if (index > 0) add(CustomAccessibilityAction(upLabel) { cards = swapVisible(cards, visible, index, -1); true })
                                    if (index < visible.lastIndex) add(CustomAccessibilityAction(downLabel) { cards = swapVisible(cards, visible, index, 1); true })
                                }
                            },
                    )
                }
            }
            val hiddenCards = cards.filter { it in off }
            if (hiddenCards.isNotEmpty()) {
                item(key = "h:hidden") {
                    GcSectionLabel(stringResource(R.string.familie_arrange_hidden), Modifier.padding(top = 8.dp, start = 4.dp))
                }
                items(hiddenCards, key = { "off:$it" }) { id ->
                    val title = FamilieCards.title(id, profile)
                    ArrangeRow(
                        title = title,
                        icon = FamilieCards.icon(id),
                        hiddenNow = true,
                        dragging = false,
                        onEye = { off = off - id },
                        eyeLabel = stringResource(R.string.familie_arrange_show, title),
                    )
                }
            }
            item(key = "footer") {
                Text(
                    stringResource(R.string.familie_arrange_footer),
                    style = MaterialTheme.typography.bodySmall,
                    color = extra.muted,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/** [cards] with the visible card at [index] of [visible] swapped with its visible neighbour in [delta]'s direction. */
internal fun swapVisible(cards: List<String>, visible: List<String>, index: Int, delta: Int): List<String> {
    val a = visible.getOrNull(index) ?: return cards
    val b = visible.getOrNull(index + delta) ?: return cards
    val i = cards.indexOf(a)
    val j = cards.indexOf(b)
    if (i < 0 || j < 0) return cards
    return cards.toMutableList().apply { this[i] = b; this[j] = a }
}

/** One card: icon, name, the eye (hide/show) and — when shown — the grip to drag it by. */
@Composable
private fun ArrangeRow(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    hiddenNow: Boolean,
    dragging: Boolean,
    onEye: () -> Unit,
    eyeLabel: String,
    modifier: Modifier = Modifier,
) {
    val extra = GateControlTheme.extraColors
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(54.dp)
            .graphicsLayer { rotationZ = if (dragging) -1.5f else 0f },
        shape = MaterialTheme.shapes.small,
        color = if (hiddenNow) extra.panel2 else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (dragging) MaterialTheme.colorScheme.primary else extra.border),
        shadowElevation = if (dragging) 8.dp else 0.dp,
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GcIconSquare(size = 32.dp, background = if (hiddenNow) extra.panel2 else extra.accentBg) {
                Icon(icon, contentDescription = null, tint = if (hiddenNow) extra.muted else extra.accentText, modifier = Modifier.size(18.dp))
            }
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = if (hiddenNow) extra.muted else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            GcIconButton(
                icon = if (hiddenNow) StartIcons.EyeOff else GcIcons.Eye,
                contentDescription = eyeLabel,
                onClick = onEye,
                size = 44.dp,
                iconSize = 20.dp,
            )
            if (!hiddenNow) Icon(StartIcons.Grip, contentDescription = null, tint = extra.faint, modifier = Modifier.padding(end = 6.dp).size(18.dp))
        }
    }
}
