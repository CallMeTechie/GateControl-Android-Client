package com.gatecontrol.android.ui.start

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.layout.LayoutAccess
import com.gatecontrol.android.layout.StartPages
import com.gatecontrol.android.ui.components.GcBanner
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcChevron
import com.gatecontrol.android.ui.components.GcIconSquare
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcListRow
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcRowDivider
import com.gatecontrol.android.ui.components.GcSectionLabel
import com.gatecontrol.android.ui.components.GcSegmented
import com.gatecontrol.android.ui.components.GcSubpageBar
import com.gatecontrol.android.ui.components.GcSwitchRow
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.theme.GateControlTheme
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/** What pinning and taking off pages from a screen of their own needs: the snackbar texts and "Rückgängig". */
private class PageActions(
    private val viewModel: StartPagesViewModel,
    private val resources: android.content.res.Resources,
    private val snack: (String, (() -> Unit)?) -> Unit,
) {
    fun pin(id: String) = viewModel.pin(id) { undo ->
        snack(resources.getString(R.string.pages_snack_pinned, startPageLabel(resources, id)), undo)
    }

    fun unpin(id: String) {
        val message = if (StartPages.isWidgets(id)) {
            resources.getString(R.string.pages_snack_removed)
        } else {
            resources.getString(R.string.pages_snack_unpinned, startPageLabel(resources, id))
        }
        viewModel.unpin(id) { undo -> snack(message, undo) }
    }

    fun addWidgets() = viewModel.addWidgets { id, undo ->
        snack(resources.getString(R.string.pages_snack_pinned, startPageLabel(resources, id)), undo)
    }
}

@Composable
private fun rememberPageActions(viewModel: StartPagesViewModel): PageActions {
    val resources = LocalResources.current
    val snack = LocalPageSnack.current
    return remember(viewModel, resources, snack) { PageActions(viewModel, resources, snack) }
}

/** The admin fixes the order: pinning, taking off and arranging are off. */
@Composable
private fun LockedHint(access: LayoutAccess, modifier: Modifier = Modifier) {
    if (!access.locked) return
    GcBanner(tone = GcTone.Info, icon = GcIcons.Lock, modifier = modifier) {
        Text(stringResource(R.string.arrange_locked), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

/**
 * "Seiten anordnen" (mockup 5): Start fixed at the top, the pages beside
 * it to drag into order (screen readers: "Nach oben/unten"), each with
 * "Als Tab" or, a widget page, "Entfernen"; below the functions still in
 * the bar to pin, and "Neue Widget-Seite". [available]: the functions this
 * login has as tabs or pages (MainTabs.features).
 */
@Composable
fun StartPagesArrangeScreen(
    available: List<String>,
    onBack: () -> Unit,
    viewModel: StartPagesViewModel = hiltViewModel(),
) {
    val extra = GateControlTheme.extraColors
    val haptic = LocalHapticFeedback.current
    val all by viewModel.pages.collectAsStateWithLifecycle()
    val access by viewModel.access.collectAsStateWithLifecycle()
    val blocks by viewModel.blocks.collectAsStateWithLifecycle()
    val actions = rememberPageActions(viewModel)
    val locked = access.locked
    val visible = StartPages.visible(all, available)
    val inBar = available.filter { it !in all }
    // The order while dragging (saved when the drag ends); follows the stored one otherwise.
    var working by remember { mutableStateOf<List<String>?>(null) }
    val shown = working?.takeIf { it.toSet() == visible.toSet() } ?: visible
    var removing by rememberSaveable { mutableStateOf<String?>(null) }
    val remove: (String) -> Unit = { id ->
        if (StartPages.isWidgets(id) && blocks[id].orEmpty().isNotEmpty()) removing = id else actions.unpin(id)
    }

    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        val next = StartPages.move(shown, from.key as? String ?: return@rememberReorderableLazyListState, to.key as? String ?: return@rememberReorderableLazyListState)
        if (next !== shown) {
            working = next
            haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
        }
    }
    val upLabel = stringResource(R.string.arrange_move_up)
    val downLabel = stringResource(R.string.arrange_move_down)
    fun moveActions(id: String): List<CustomAccessibilityAction> = if (locked) emptyList() else buildList {
        if (StartPages.canMove(shown, id, -1)) add(CustomAccessibilityAction(upLabel) { viewModel.reorder(StartPages.moveBy(shown, id, -1)); true })
        if (StartPages.canMove(shown, id, 1)) add(CustomAccessibilityAction(downLabel) { viewModel.reorder(StartPages.moveBy(shown, id, 1)); true })
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "bar") {
            GcSubpageBar(title = stringResource(R.string.pages_arrange), onBack = onBack, backDescription = stringResource(R.string.common_back))
        }
        item(key = "hint") {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.pages_arrange_hint), style = MaterialTheme.typography.bodySmall, color = extra.muted)
                LockedHint(access)
            }
        }
        item(key = "h:pages") {
            GcSectionLabel(stringResource(R.string.pages_beside_start), Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp))
        }
        item(key = "start") {
            PageRow(
                icon = startPageIcon(START_PAGE),
                title = stringResource(R.string.nav_start),
                description = stringResource(R.string.pages_start_first),
                accent = true,
                dragging = false,
                trailing = {
                    Icon(GcIcons.Lock, contentDescription = stringResource(R.string.pages_fixed), tint = extra.faint, modifier = Modifier.padding(end = 10.dp).size(18.dp))
                },
            )
        }
        items(shown, key = { it }) { id ->
            ReorderableItem(reorderState, key = id, enabled = !locked) { dragging ->
                val label = startPageLabel(id)
                PageRow(
                    icon = startPageIcon(id),
                    title = label,
                    description = if (dragging) stringResource(R.string.pages_moving) else null,
                    accent = true,
                    dragging = dragging,
                    grip = !locked,
                    trailing = {
                        GcOutlineButton(
                            text = stringResource(if (StartPages.isWidgets(id)) R.string.pages_remove_short else R.string.pages_as_tab_short),
                            onClick = { remove(id) },
                            enabled = !locked,
                            fillWidth = false,
                            minHeight = 36.dp,
                        )
                    },
                    modifier = Modifier
                        .then(
                            if (locked) {
                                Modifier
                            } else {
                                Modifier.longPressDraggableHandle(
                                    onDragStarted = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                                    onDragStopped = { working?.let { viewModel.reorder(it) } },
                                )
                            },
                        )
                        .semantics { customActions = moveActions(id) },
                )
            }
        }
        if (inBar.isNotEmpty()) {
            item(key = "h:bar") {
                GcSectionLabel(stringResource(R.string.pages_in_bar), Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp))
            }
            items(inBar, key = { "bar:$it" }) { id ->
                PageRow(
                    icon = startPageIcon(id),
                    title = startPageLabel(id),
                    description = null,
                    accent = false,
                    dragging = false,
                    trailing = {
                        GcOutlineButton(
                            text = stringResource(R.string.pages_pin_short),
                            onClick = { actions.pin(id) },
                            enabled = !locked,
                            fillWidth = false,
                            minHeight = 36.dp,
                        )
                    },
                )
            }
        }
        item(key = "new") {
            GcOutlineButton(
                text = stringResource(R.string.pages_new_widgets),
                onClick = { actions.addWidgets() },
                enabled = !locked,
                icon = GcIcons.Plus,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
            )
        }
    }
    removing?.let { id ->
        RemovePageDialog(
            id = id,
            count = blocks[id].orEmpty().size,
            onConfirm = { removing = null; actions.unpin(id) },
            onDismiss = { removing = null },
        )
    }
}

/** One page in "Seiten anordnen": icon, name, what to do with it and — when it can be dragged — the grip. */
@Composable
private fun PageRow(
    icon: ImageVector,
    title: String,
    description: String?,
    accent: Boolean,
    dragging: Boolean,
    modifier: Modifier = Modifier,
    grip: Boolean = false,
    trailing: @Composable () -> Unit,
) {
    val extra = GateControlTheme.extraColors
    Surface(
        modifier = modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .heightIn(min = 58.dp)
            .graphicsLayer { rotationZ = if (dragging) -1.5f else 0f },
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (dragging) MaterialTheme.colorScheme.primary else extra.border),
        shadowElevation = if (dragging) 8.dp else 0.dp,
    ) {
        Row(
            Modifier.padding(start = if (grip) 6.dp else 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (grip) Icon(StartIcons.Grip, contentDescription = null, tint = extra.faint, modifier = Modifier.size(18.dp))
            GcIconSquare(size = 32.dp, background = if (accent) extra.accentBg else extra.panel2) {
                Icon(icon, contentDescription = null, tint = if (accent) extra.accentText else extra.muted, modifier = Modifier.size(18.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (description != null) Text(description, style = MaterialTheme.typography.bodySmall, color = extra.muted, maxLines = 1)
            }
            trailing()
        }
    }
}

/**
 * Einstellungen → "Leiste und Seiten" (mockup 7): a preview of the bar and
 * of the pages, each function as "Tab" or "Seite", and "Leiste ausblenden,
 * wenn nur Start bleibt" (this device's choice, on by default).
 * [available]: the functions this login has (MainTabs.features).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StartPagesSettingsScreen(
    available: List<String>,
    onBack: () -> Unit,
    onArrangePages: () -> Unit,
    viewModel: StartPagesViewModel = hiltViewModel(),
) {
    val extra = GateControlTheme.extraColors
    val all by viewModel.pages.collectAsStateWithLifecycle()
    val access by viewModel.access.collectAsStateWithLifecycle()
    val hideBar by viewModel.hideBar.collectAsStateWithLifecycle()
    val actions = rememberPageActions(viewModel)
    val locked = access.locked
    val pinned = StartPages.visible(all, available)
    val tabs = listOf(START_PAGE) + available.filter { it !in all }
    val tabLabel = stringResource(R.string.pages_tab)
    val pageLabel = stringResource(R.string.pages_page)

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState()),
    ) {
        GcSubpageBar(title = stringResource(R.string.pages_settings_title), onBack = onBack, backDescription = stringResource(R.string.common_back))
        Column(
            Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            LockedHint(access)
            GcSectionLabel(stringResource(R.string.pages_preview), Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
            GcCard {
                val names = pinned.map { startPageLabel(it) }
                Text(
                    if (names.isEmpty()) {
                        stringResource(R.string.pages_preview_none)
                    } else {
                        stringResource(R.string.pages_preview_line, (listOf(stringResource(R.string.nav_start)) + names).joinToString(" → "))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = extra.muted,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    tabs.forEachIndexed { index, id ->
                        val first = index == 0
                        Row(
                            Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (first) extra.accentBg else extra.panel2)
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(startPageIcon(id), contentDescription = null, tint = if (first) extra.accentText else extra.muted, modifier = Modifier.size(14.dp))
                            Text(startPageLabel(id), style = MaterialTheme.typography.labelMedium, color = if (first) extra.accentText else extra.muted)
                        }
                    }
                }
            }
            GcSectionLabel(stringResource(R.string.pages_each_as), Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 4.dp))
            GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.Top) {
                GcListRow(
                    title = stringResource(R.string.nav_start),
                    description = stringResource(R.string.pages_start_always),
                    leading = { Icon(startPageIcon(START_PAGE), contentDescription = null, tint = extra.accentText, modifier = Modifier.size(22.dp)) },
                )
                if (available.isEmpty()) {
                    Text(
                        stringResource(R.string.pages_settings_no_features),
                        style = MaterialTheme.typography.bodySmall,
                        color = extra.muted,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
                available.forEach { id ->
                    GcRowDivider(Modifier.padding(horizontal = 16.dp))
                    val label = startPageLabel(id)
                    val asPage = id in all
                    val groupLabel = stringResource(R.string.pages_segment_desc, label)
                    Row(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(startPageIcon(id), contentDescription = null, tint = extra.muted, modifier = Modifier.size(22.dp))
                        Text(label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                        Box(Modifier.width(168.dp).semantics { contentDescription = groupLabel }) {
                            GcSegmented(
                                options = listOf(false to tabLabel, true to pageLabel),
                                selected = asPage,
                                onSelect = { page ->
                                    if (!locked && page != asPage) {
                                        if (page) actions.pin(id) else actions.unpin(id)
                                    }
                                },
                            )
                        }
                    }
                }
            }
            Text(
                stringResource(R.string.pages_settings_footer),
                style = MaterialTheme.typography.bodySmall,
                color = extra.muted,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.Top) {
                GcSwitchRow(
                    label = stringResource(R.string.pages_hide_bar),
                    description = stringResource(R.string.pages_hide_bar_desc),
                    checked = hideBar,
                    onCheckedChange = viewModel::setHideBar,
                )
                GcRowDivider(Modifier.padding(horizontal = 16.dp))
                GcListRow(
                    title = stringResource(R.string.pages_arrange),
                    onClick = onArrangePages,
                    leading = { Icon(StartIcons.Arrange, contentDescription = null, tint = extra.muted, modifier = Modifier.size(22.dp)) },
                    trailing = { GcChevron() },
                )
            }
        }
    }
}
