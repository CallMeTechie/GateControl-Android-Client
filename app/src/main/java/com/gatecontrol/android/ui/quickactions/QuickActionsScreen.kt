package com.gatecontrol.android.ui.quickactions

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.gatecontrol.android.ui.components.GcFilterChip
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.gatecontrol.android.ui.components.GcIconSquare
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.quickactions.QuickActionActivity
import com.gatecontrol.android.quickactions.QuickActionIcons
import com.gatecontrol.android.quickactions.QuickActionItem
import com.gatecontrol.android.quickactions.QuickActionLongPress
import com.gatecontrol.android.quickactions.QuickActionRepository
import com.gatecontrol.android.quickactions.QuickActionsLoad
import com.gatecontrol.android.ui.components.GcBanner
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcChip
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcListRow
import com.gatecontrol.android.ui.components.GcSectionLabel
import com.gatecontrol.android.ui.components.GcSubpageBar
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.theme.GateControlTheme

/**
 * All quick actions of the plugins for this device's user, grouped by
 * plugin: a tap runs one, the pin adds it to the start screen, the app
 * shortcuts, the Quick Settings tiles and the widget. A long press on a
 * door action opens the door screen ([onOpenDoor], null: the door plugin
 * is not listed — QuickActionLongPress).
 */
@Composable
fun QuickActionsScreen(onBack: () -> Unit, onOpenDoor: (() -> Unit)? = null, viewModel: QuickActionsViewModel = hiltViewModel()) {
    val actions by viewModel.actions.collectAsStateWithLifecycle()
    val pins by viewModel.pins.collectAsStateWithLifecycle()
    val load by viewModel.load.collectAsStateWithLifecycle()
    val running by viewModel.running.collectAsStateWithLifecycle()
    val extra = GateControlTheme.extraColors
    var confirming by remember { mutableStateOf<QuickActionItem?>(null) }
    val pinned = pins.map { it.key }.toSet()

    LaunchedEffect(Unit) { viewModel.refresh() }
    ResultToasts(viewModel)

    confirming?.let { item ->
        ConfirmDialog(item, onConfirm = { confirming = null; viewModel.run(item) }, onDismiss = { confirming = null })
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            GcSubpageBar(
                title = stringResource(R.string.quick_actions_title),
                onBack = onBack,
                backDescription = stringResource(R.string.common_back),
            )
        }
        item {
            Text(
                stringResource(R.string.quick_actions_hint, QuickActionRepository.MAX_PINS),
                style = MaterialTheme.typography.bodySmall,
                color = extra.muted,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        val notice = when (load) {
            QuickActionsLoad.Unsupported -> R.string.quick_actions_unsupported
            QuickActionsLoad.NotConfigured -> R.string.quick_actions_not_configured
            is QuickActionsLoad.Failed -> R.string.quick_actions_failed
            QuickActionsLoad.Loaded -> if (actions.isEmpty()) R.string.quick_actions_none else null
            else -> null
        }
        if (load == QuickActionsLoad.Loading && actions.isEmpty()) {
            item {
                Row(Modifier.fillMaxWidth().padding(32.dp), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        if (notice != null) {
            item {
                GcBanner(
                    tone = if (load is QuickActionsLoad.Failed) GcTone.Warn else GcTone.Info,
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    Text(stringResource(notice), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        actions.groupBy { it.pluginName }.forEach { (plugin, items) ->
            item(key = "h:$plugin") {
                GcSectionLabel(plugin, Modifier.padding(start = 20.dp, top = 4.dp))
            }
            item(key = "c:$plugin") {
                GcCard(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    contentPadding = PaddingValues(vertical = 6.dp),
                    verticalArrangement = Arrangement.Top,
                ) {
                    items.forEach { action ->
                        ActionRow(
                            item = action,
                            pinned = action.key in pinned,
                            asWidget = pins.firstOrNull { it.key == action.key }?.asWidget == true,
                            running = running == action.key,
                            onRun = { if (action.confirm) confirming = action else viewModel.run(action) },
                            onTogglePin = { viewModel.togglePin(action) },
                            onSetWidget = { viewModel.setWidget(action, it) },
                            onLongPress = longPress(action, onOpenDoor),
                        )
                    }
                }
            }
        }
    }
}

/** One action of the list; a pinned one with a widget also gets the "Widget" chip (card instead of button on the start tab). */
@Composable
private fun ActionRow(
    item: QuickActionItem,
    pinned: Boolean,
    asWidget: Boolean,
    running: Boolean,
    onRun: () -> Unit,
    onTogglePin: () -> Unit,
    onSetWidget: (Boolean) -> Unit,
    onLongPress: (() -> Unit)?,
) {
    val extra = GateControlTheme.extraColors
    val icon = remember(item.icon) { QuickActionIcons.vector(item.icon) }
    GcListRow(
        title = item.title,
        description = item.subtitle,
        onClick = onRun,
        onLongClick = onLongPress,
        onLongClickLabel = onLongPress?.let { stringResource(R.string.door_open_screen) },
        leading = {
            GcIconSquare(size = 40.dp, background = if (item.isOn) extra.accentBg else extra.panel2) {
                Icon(icon, contentDescription = null, tint = if (item.isOn) extra.accentText else MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(22.dp))
            }
        },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                when {
                    running -> CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp), color = extra.faint)
                    pinned && item.widget != null -> GcFilterChip(
                        text = stringResource(R.string.quick_actions_as_widget),
                        selected = asWidget,
                        onClick = { onSetWidget(!asWidget) },
                    )
                    item.toggle -> GcChip(
                        stringResource(if (item.isOn) R.string.quick_action_on else R.string.quick_action_off),
                        tone = if (item.isOn) GcTone.Ok else GcTone.Neutral,
                        small = true,
                    )
                }
                GcIconButton(
                    icon = if (pinned) GcIcons.StarFilled else GcIcons.Star,
                    contentDescription = stringResource(if (pinned) R.string.quick_actions_unpin else R.string.quick_actions_pin),
                    onClick = onTogglePin,
                    tint = if (pinned) extra.warn else extra.faint,
                    size = 40.dp,
                    iconSize = 20.dp,
                )
            }
        },
    )
}

/** How often the start tab asks for the actions (widget readings) while it is visible. */
private const val REFRESH_MS = 60_000L

/**
 * Start-tab section of the pinned quick actions (no card, no heading): icon
 * tiles five to a row with the title below (filled while a switch is on),
 * then the pins shown as widgets two to a row ([QuickActionWidgetCard]).
 * With none pinned but actions available only a quiet hint that a long
 * press on the tab sets it up; hidden without actions. Refreshed when the
 * tab becomes visible and every 60 s while it is.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuickActionsCard(
    modifier: Modifier = Modifier,
    /** Long press on a door action: the door screen (null: door plugin not listed). */
    onOpenDoor: (() -> Unit)? = null,
    viewModel: QuickActionsViewModel = hiltViewModel(),
) {
    val pins by viewModel.pins.collectAsStateWithLifecycle()
    val actions by viewModel.actions.collectAsStateWithLifecycle()
    val running by viewModel.running.collectAsStateWithLifecycle()
    val adjusting by viewModel.adjusting.collectAsStateWithLifecycle()
    val extra = GateControlTheme.extraColors
    var confirming by remember { mutableStateOf<QuickActionItem?>(null) }

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                viewModel.refresh()
                delay(REFRESH_MS)
            }
        }
    }
    ResultToasts(viewModel)
    confirming?.let { item ->
        ConfirmDialog(item, onConfirm = { confirming = null; viewModel.run(item) }, onDismiss = { confirming = null })
    }
    val runOrAsk: (QuickActionItem) -> Unit = { item -> if (item.confirm) confirming = item else viewModel.run(item) }

    when (val row = QuickActionsRow.of(pins, actions)) {
        QuickActionsRow.Hidden -> Unit
        QuickActionsRow.Choose -> Text(
            stringResource(R.string.start_edit_hint),
            style = MaterialTheme.typography.bodySmall,
            color = extra.faint,
            textAlign = TextAlign.Center,
            modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        )
        is QuickActionsRow.Tiles -> BoxWithConstraints(modifier.fillMaxWidth()) {
            val tile = (maxWidth - TILE_GAP * (TILES_PER_ROW - 1)) / TILES_PER_ROW
            val card = (maxWidth - WIDGET_GAP) / 2
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (row.buttons.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(TILE_GAP),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        maxItemsInEachRow = TILES_PER_ROW,
                    ) {
                        row.buttons.forEach { item ->
                            QuickActionTile(
                                item = item,
                                width = tile,
                                running = running == item.key,
                                onClick = { runOrAsk(item) },
                                onLongPress = longPress(item, onOpenDoor),
                            )
                        }
                    }
                }
                if (row.widgets.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(WIDGET_GAP),
                        verticalArrangement = Arrangement.spacedBy(WIDGET_GAP),
                        maxItemsInEachRow = 2,
                    ) {
                        row.widgets.forEach { item ->
                            QuickActionWidgetCard(
                                item = item,
                                running = running == item.key,
                                adjusting = adjusting,
                                onRun = { runOrAsk(item) },
                                onLongPress = longPress(item, onOpenDoor),
                                onControl = { control, value -> viewModel.control(item, control, value) },
                                modifier = Modifier.width(card),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** What the start screen shows of the quick actions (pure, unit-tested). */
sealed interface QuickActionsRow {
    /** No pins and the server has no actions (or none known yet). */
    data object Hidden : QuickActionsRow

    /** Actions available, none pinned: only the hint how to set the tab up. */
    data object Choose : QuickActionsRow

    /**
     * The pinned actions, with the server's current title, icon, state and
     * widget where known and the pin's own display choice: [buttons] as
     * icon tiles, [widgets] as widget cards.
     */
    data class Tiles(val items: List<QuickActionItem>) : QuickActionsRow {
        val buttons: List<QuickActionItem> get() = items.filter { !it.showsWidget }
        val widgets: List<QuickActionItem> get() = items.filter { it.showsWidget }
    }

    companion object {
        fun of(pins: List<QuickActionItem>, actions: List<QuickActionItem>): QuickActionsRow {
            if (pins.isEmpty()) return if (actions.isEmpty()) Hidden else Choose
            val byKey = actions.associateBy { it.key }
            return Tiles(pins.map { pin -> byKey[pin.key]?.copy(asWidget = pin.asWidget) ?: pin })
        }
    }
}

private const val TILES_PER_ROW = 5
private val TILE_GAP = 10.dp
private val WIDGET_GAP = 12.dp
private val TileShape = RoundedCornerShape(18.dp)

/** One pinned action: icon tile (filled while a switch is on, progress while it runs) and its title; [width] of the column. */
@Composable
private fun QuickActionTile(item: QuickActionItem, width: Dp, running: Boolean, onClick: () -> Unit, onLongPress: (() -> Unit)?) {
    val extra = GateControlTheme.extraColors
    val scheme = MaterialTheme.colorScheme
    val on = item.isOn
    val stateText = if (item.toggle) stringResource(if (on) R.string.quick_action_on else R.string.quick_action_off) else null
    val icon = remember(item.icon) { QuickActionIcons.vector(item.icon) }
    Column(
        Modifier
            .width(width)
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(
                enabled = !running,
                role = if (item.toggle) Role.Switch else Role.Button,
                onLongClickLabel = onLongPress?.let { stringResource(R.string.door_open_screen) },
                onLongClick = onLongPress,
                onClick = onClick,
            )
            .semantics(mergeDescendants = true) { if (stateText != null) stateDescription = stateText },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .size(width)
                .clip(TileShape)
                .then(
                    if (on) Modifier.background(scheme.primary)
                    else Modifier.background(scheme.surface).border(1.dp, extra.border2, TileShape),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (running) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(22.dp),
                    color = if (on) scheme.onPrimary else extra.accentText,
                )
            } else {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = when {
                        on -> scheme.onPrimary
                        item.toggle -> extra.muted
                        else -> scheme.onSurface
                    },
                    modifier = Modifier.size(28.dp),
                )
            }
        }
        Text(
            item.title,
            style = MaterialTheme.typography.labelSmall,
            color = if (on) extra.accentText else scheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Long press of [item] (QuickActionLongPress): a door action opens the door screen, others have none. */
private fun longPress(item: QuickActionItem, onOpenDoor: (() -> Unit)?): (() -> Unit)? =
    when (QuickActionLongPress.target(item, doorListed = onOpenDoor != null)) {
        QuickActionLongPress.Target.DOOR -> onOpenDoor
        null -> null
    }

@Composable
private fun ResultToasts(viewModel: QuickActionsViewModel) {
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.results.collect { (item, result) -> QuickActionActivity.showResult(context, item, result) }
    }
}

@Composable
private fun ConfirmDialog(item: QuickActionItem, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.quick_action_confirm_title, item.title)) },
        text = { Text(item.subtitle ?: item.pluginName) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.quick_action_confirm_run)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}
