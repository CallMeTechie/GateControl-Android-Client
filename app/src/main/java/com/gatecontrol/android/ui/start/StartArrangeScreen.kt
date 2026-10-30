package com.gatecontrol.android.ui.start

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.layout.LayoutAccess
import com.gatecontrol.android.layout.LayoutScope
import com.gatecontrol.android.layout.StartEntry
import com.gatecontrol.android.layout.StartLayout
import com.gatecontrol.android.layout.StartLayout.isHalf
import com.gatecontrol.android.quickactions.QuickActionIcons
import com.gatecontrol.android.ui.components.GcIconSquare
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.components.GcSectionLabel
import com.gatecontrol.android.ui.quickactions.QuickActionsViewModel
import com.gatecontrol.android.ui.theme.GateControlTheme
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState

/** Grid columns: a tile takes 2 (three in a row), a half widget 3, a full-width block all 6. */
private const val COLUMNS = 6

/**
 * "Start-Tab anordnen" (mockup StartArrange): the tiles (their order among
 * each other; "−" takes one off, "+ Kachel" leads to the list of actions),
 * then widgets, Info card and usage card in one common order. Hold a block
 * and drag it; a double tap on a widget switches it between half and full
 * width; screen readers get "Nach oben/unten" and "Breite ändern". "Fertig"
 * (or back) saves the order ([QuickActionsViewModel.saveArrangement]).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StartArrangeScreen(
    onDone: () -> Unit,
    onAddTile: () -> Unit,
    modifier: Modifier = Modifier,
    /** A widget page beside Start to arrange instead of the start tab, with its [title]. */
    page: String? = null,
    title: String? = null,
    viewModel: QuickActionsViewModel = hiltViewModel(),
) {
    val extra = GateControlTheme.extraColors
    val haptic = LocalHapticFeedback.current
    val pages by viewModel.pages.collectAsStateWithLifecycle()
    val entries = pages[page].orEmpty()
    val access by viewModel.layoutAccess.collectAsStateWithLifecycle()
    val pins by viewModel.pins.collectAsStateWithLifecycle()
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val widgetPins by viewModel.widgets.collectAsStateWithLifecycle()
    // The order being arranged; null until the first change (then it no longer follows the stored one).
    var working by remember { mutableStateOf<List<StartEntry>?>(null) }
    val list = working ?: entries
    val finish = {
        working?.let { viewModel.saveArrangement(it, page) }
        onDone()
    }
    BackHandler(onBack = finish)

    val gridState = rememberLazyGridState()
    val reorderState = rememberReorderableLazyGridState(gridState) { from, to ->
        val next = StartLayout.move(list, from.key as? String ?: return@rememberReorderableLazyGridState, to.key as? String ?: return@rememberReorderableLazyGridState)
        if (next !== list) {
            working = next
            haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
        }
    }
    val upLabel = stringResource(R.string.arrange_move_up)
    val downLabel = stringResource(R.string.arrange_move_down)
    val widthLabel = stringResource(R.string.arrange_toggle_width)
    val halfText = stringResource(R.string.arrange_width_half)
    val fullText = stringResource(R.string.arrange_width_full)

    fun moveActions(key: String): List<CustomAccessibilityAction> = buildList {
        if (StartLayout.canMove(list, key, -1)) add(CustomAccessibilityAction(upLabel) { working = StartLayout.moveBy(list, key, -1); true })
        if (StartLayout.canMove(list, key, 1)) add(CustomAccessibilityAction(downLabel) { working = StartLayout.moveBy(list, key, 1); true })
    }

    val tiles = list.filterIsInstance<StartEntry.Tile>()
    val cards = list.filter { it !is StartEntry.Tile }
    val resolved = widgetPins.mapNotNull { pin -> pin.resolve(devices)?.let { pin.device to it } }.toMap()

    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        ArrangeBar(
            title = title ?: stringResource(R.string.start_arrange),
            hint = stringResource(R.string.start_arrange_hint),
            scope = access,
            onDone = finish,
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(COLUMNS),
            state = gridState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "h:tiles", span = { GridItemSpan(COLUMNS) }) {
                GcSectionLabel(stringResource(R.string.arrange_tiles), Modifier.padding(start = 4.dp))
            }
            items(tiles, key = { it.key }, span = { GridItemSpan(2) }) { tile ->
                val item = pins.firstOrNull { it.key == tile.action }
                val title = item?.title ?: tile.action
                ReorderableItem(reorderState, key = tile.key) { dragging ->
                    TileBlock(
                        title = title,
                        icon = remember(item?.displayIcon) { QuickActionIcons.vector(item?.displayIcon) },
                        dragging = dragging,
                        onRemove = {
                            working = list.filter { it.key != tile.key }
                            viewModel.unpinTile(tile.action)
                        },
                        removeLabel = stringResource(R.string.arrange_remove, title),
                        modifier = Modifier
                            .longPressDraggableHandle(onDragStarted = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) })
                            .semantics { customActions = moveActions(tile.key) },
                    )
                }
            }
            item(key = "add", span = { GridItemSpan(2) }) {
                Box(
                    Modifier
                        .height(88.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .border(2.dp, extra.border2, RoundedCornerShape(20.dp))
                        .clickable(role = Role.Button, onClick = { finish(); onAddTile() }),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(R.string.arrange_add_tile), style = MaterialTheme.typography.labelLarge, color = extra.muted)
                }
            }
            if (page == null || cards.isNotEmpty()) {
                item(key = "h:cards", span = { GridItemSpan(COLUMNS) }) {
                    GcSectionLabel(stringResource(R.string.arrange_cards), Modifier.padding(start = 4.dp, top = 8.dp))
                }
            }
            items(cards, key = { it.key }, span = { GridItemSpan(if (it.isHalf) COLUMNS / 2 else COLUMNS) }) { card ->
                val (title, icon, accent) = when (card) {
                    is StartEntry.Widget -> {
                        val device = resolved[card.device]
                        Triple(device?.title ?: card.device, remember(device?.icon) { QuickActionIcons.vector(device?.icon) }, true)
                    }
                    StartEntry.Info -> Triple(stringResource(R.string.arrange_info), GcIcons.Sliders, false)
                    else -> Triple(stringResource(R.string.arrange_usage), GcIcons.Activity, false)
                }
                ReorderableItem(reorderState, key = card.key) { dragging ->
                    val widget = card as? StartEntry.Widget
                    CardBlock(
                        title = title,
                        icon = icon,
                        accent = accent,
                        dragging = dragging,
                        modifier = Modifier
                            .longPressDraggableHandle(onDragStarted = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) })
                            .then(
                                if (widget != null) {
                                    Modifier.combinedClickable(
                                        onClick = {},
                                        onDoubleClick = { working = StartLayout.toggleWide(list, widget.device) },
                                        onClickLabel = null,
                                    )
                                } else {
                                    Modifier
                                },
                            )
                            .semantics(mergeDescendants = true) {
                                contentDescription = title
                                if (widget != null) stateDescription = if (widget.wide) fullText else halfText
                                customActions = moveActions(card.key) + listOfNotNull(
                                    widget?.let { w -> CustomAccessibilityAction(widthLabel) { working = StartLayout.toggleWide(list, w.device); true } },
                                )
                            },
                    )
                }
            }
            item(key = "footer", span = { GridItemSpan(COLUMNS) }) {
                Text(
                    stringResource(R.string.arrange_footer),
                    style = MaterialTheme.typography.bodySmall,
                    color = extra.muted,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/** A tile while arranging: icon and name, "−" in the corner takes it off the start tab. */
@Composable
private fun TileBlock(title: String, icon: ImageVector, dragging: Boolean, onRemove: () -> Unit, removeLabel: String, modifier: Modifier = Modifier) {
    val extra = GateControlTheme.extraColors
    Box(modifier.height(88.dp)) {
        Surface(
            modifier = Modifier.fillMaxSize().graphicsLayer { rotationZ = if (dragging) -3f else 0f },
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, if (dragging) MaterialTheme.colorScheme.primary else extra.border),
            shadowElevation = if (dragging) 8.dp else 0.dp,
        ) {
            Column(
                Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box(
            Modifier
                .align(Alignment.TopStart)
                .size(32.dp)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClickLabel = removeLabel, onClick = onRemove)
                .semantics { contentDescription = removeLabel },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(22.dp).clip(CircleShape).background(extra.muted), contentAlignment = Alignment.Center) {
                Icon(GcIcons.Minus, contentDescription = null, tint = MaterialTheme.colorScheme.surface, modifier = Modifier.size(12.dp))
            }
        }
    }
}

/** A widget or card while arranging: icon, name and the grip. */
@Composable
private fun CardBlock(title: String, icon: ImageVector, accent: Boolean, dragging: Boolean, modifier: Modifier = Modifier) {
    val extra = GateControlTheme.extraColors
    Surface(
        modifier = modifier.fillMaxWidth().height(64.dp).graphicsLayer { rotationZ = if (dragging) -3f else 0f },
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (dragging) MaterialTheme.colorScheme.primary else extra.border),
        shadowElevation = if (dragging) 8.dp else 0.dp,
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            GcIconSquare(size = 34.dp, background = if (accent) extra.accentBg else extra.panel2) {
                Icon(icon, contentDescription = null, tint = if (accent) extra.accentText else MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp))
            }
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(StartIcons.Grip, contentDescription = null, tint = extra.faint, modifier = Modifier.size(18.dp))
        }
    }
}

/** Title of an arranging screen, what dragging (and double tap) do, where the order applies, and "Fertig". */
@Composable
internal fun ArrangeBar(title: String, hint: String, scope: LayoutAccess, onDone: () -> Unit) {
    val extra = GateControlTheme.extraColors
    Surface(color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, extra.border)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                Text(hint, style = MaterialTheme.typography.bodySmall, color = extra.muted)
                if (scope.shared) {
                    Text(
                        stringResource(if (scope.scope == LayoutScope.GLOBAL) R.string.arrange_scope_global else R.string.arrange_scope_user),
                        style = MaterialTheme.typography.bodySmall,
                        color = extra.accentText,
                    )
                }
            }
            GcPrimaryButton(text = stringResource(R.string.common_done), onClick = onDone, fillWidth = false, minHeight = 42.dp)
        }
    }
}
