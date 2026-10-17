package com.gatecontrol.android.ui.quickactions

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.quickactions.QuickActionActivity
import com.gatecontrol.android.quickactions.QuickActionDeviceGroup
import com.gatecontrol.android.quickactions.QuickActionIcons
import com.gatecontrol.android.quickactions.QuickActionItem
import com.gatecontrol.android.quickactions.QuickActionLongPress
import com.gatecontrol.android.quickactions.QuickActionsLoad
import com.gatecontrol.android.quickactions.StartWidgetPin
import com.gatecontrol.android.ui.components.GcBanner
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcChevron
import com.gatecontrol.android.ui.components.GcIconSquare
import com.gatecontrol.android.ui.components.GcRowDivider
import com.gatecontrol.android.ui.components.GcSectionLabel
import com.gatecontrol.android.ui.components.GcSubpageBar
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.theme.GateControlTheme
import kotlinx.coroutines.delay

/**
 * "Schnellaktionen und Widgets": the plugins' devices for this device's
 * user, one row each, grouped by plugin. Nothing is switched here: a tap
 * opens the sheet to put the device on the start tab as buttons or as a
 * widget ([PinSheet]); the chips tell how it is pinned. A long press on a
 * door's row opens the door screen ([onOpenDoor]).
 */
@Composable
fun QuickActionsScreen(onBack: () -> Unit, onOpenDoor: (() -> Unit)? = null, viewModel: QuickActionsViewModel = hiltViewModel()) {
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val pins by viewModel.pins.collectAsStateWithLifecycle()
    val widgets by viewModel.widgets.collectAsStateWithLifecycle()
    val load by viewModel.load.collectAsStateWithLifecycle()
    val extra = GateControlTheme.extraColors
    var sheetFor by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { viewModel.refresh() }

    val pinnedKeys = pins.map { it.key }.toSet()
    val widgetKeys = widgets.mapNotNull { pin -> pin.resolve(devices)?.key }.toSet()

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            GcSubpageBar(
                title = stringResource(R.string.start_edit_actions),
                onBack = onBack,
                backDescription = stringResource(R.string.common_back),
            )
        }
        item {
            Text(
                stringResource(R.string.quick_actions_list_hint),
                style = MaterialTheme.typography.bodySmall,
                color = extra.muted,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        val notice = when (load) {
            QuickActionsLoad.Unsupported -> R.string.quick_actions_unsupported
            QuickActionsLoad.NotConfigured -> R.string.quick_actions_not_configured
            is QuickActionsLoad.Failed -> R.string.quick_actions_failed
            QuickActionsLoad.Loaded -> if (devices.isEmpty()) R.string.quick_actions_none else null
            else -> null
        }
        if (load == QuickActionsLoad.Loading && devices.isEmpty()) {
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
        devices.groupBy { it.pluginName }.forEach { (plugin, group) ->
            item(key = "h:$plugin") {
                GcSectionLabel(plugin, Modifier.padding(start = 20.dp, top = 4.dp))
            }
            item(key = "c:$plugin") {
                GcCard(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    contentPadding = PaddingValues(0.dp),
                    verticalArrangement = Arrangement.Top,
                ) {
                    group.forEachIndexed { index, device ->
                        if (index > 0) GcRowDivider(Modifier.padding(horizontal = 14.dp))
                        DeviceRow(
                            device = device,
                            buttons = device.actions.count { it.key in pinnedKeys },
                            widget = device.key in widgetKeys,
                            onClick = { sheetFor = device.key },
                            onLongPress = device.actions.firstNotNullOfOrNull { longPress(it, onOpenDoor) },
                        )
                    }
                }
            }
        }
    }

    val open = sheetFor?.let { key -> devices.firstOrNull { it.key == key } }
    if (open != null) {
        PinSheet(
            device = open,
            pinnedActionIds = open.actions.filter { it.key in pinnedKeys }.map { it.id }.toSet(),
            widgetPin = widgets.firstOrNull { it.resolve(devices)?.key == open.key },
            onSaveButtons = { ids -> viewModel.setButtons(open, ids); sheetFor = null },
            onSaveWidget = { parts, wide -> viewModel.setWidget(open, parts, wide); sheetFor = null },
            onRemoveWidget = { viewModel.removeWidget(open.key); sheetFor = null },
            onDismiss = { sheetFor = null },
        )
    } else if (sheetFor != null && devices.isNotEmpty()) {
        sheetFor = null
    }
}

/** One device of the list: icon, name, what it is, chips for how it is pinned; never switches anything. */
@Composable
private fun DeviceRow(
    device: QuickActionDeviceGroup,
    buttons: Int,
    widget: Boolean,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)?,
) {
    val extra = GateControlTheme.extraColors
    val pinned = buttons > 0 || widget
    val icon = remember(device.icon) { QuickActionIcons.vector(device.icon) }
    val description = device.subtitle
        ?: if (device.actions.size > 1) pluralStringResource(R.plurals.device_actions_count, device.actions.size, device.actions.size)
        else device.actions.firstOrNull()?.subtitle
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .then(if (pinned) Modifier.background(extra.accentBg.copy(alpha = 0.5f)) else Modifier)
            .combinedClickable(
                role = Role.Button,
                onLongClickLabel = onLongPress?.let { stringResource(R.string.door_open_screen) },
                onLongClick = onLongPress,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        GcIconSquare(size = 40.dp, background = if (pinned) extra.accentBg else extra.panel2) {
            Icon(icon, contentDescription = null, tint = if (pinned) extra.accentText else extra.muted, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(device.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (description != null) {
                Text(description, style = MaterialTheme.typography.bodySmall, color = extra.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (widget) PinChip(stringResource(R.string.quick_actions_as_widget), extra.accentText, extra.accentBg)
            if (buttons > 0) PinChip(pluralStringResource(R.plurals.quick_actions_chip_buttons, buttons, buttons), extra.blue, extra.blueBg)
        }
        GcChevron()
    }
}

@Composable
private fun PinChip(text: String, color: Color, background: Color) {
    Box(
        Modifier.clip(RoundedCornerShape(7.dp)).background(background).padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold), color = color, maxLines = 1)
    }
}

/** How often the start tab asks for the actions (widget values) while it is visible. */
private const val REFRESH_MS = 60_000L

/**
 * Start-tab section of the plugins: the pinned actions as icon buttons,
 * five to a row with the title below (a switch filled while on, or in its
 * own look per state, e.g. a green closed / red open lock), then the
 * devices pinned as widgets ([QuickActionWidgetCard]), half or full width.
 * With nothing pinned but actions available only a quiet hint that a long
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
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val widgetPins by viewModel.widgets.collectAsStateWithLifecycle()
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
    val runOrAsk: (QuickActionItem) -> Unit = { item -> if (item.needsConfirm) confirming = item else viewModel.run(item) }
    val widgets = widgetPins.mapNotNull { pin -> pin.resolve(devices)?.let { pin to it } }

    when (val row = QuickActionsRow.of(pins, actions, widgets.isNotEmpty())) {
        QuickActionsRow.Hidden -> Unit
        QuickActionsRow.Choose -> Text(
            stringResource(R.string.start_edit_hint),
            style = MaterialTheme.typography.bodySmall,
            color = extra.faint,
            textAlign = TextAlign.Center,
            modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        )
        is QuickActionsRow.Tiles -> BoxWithConstraints(modifier.fillMaxWidth()) {
            // In whole pixels, rounded down: the row lays the gaps out in whole
            // pixels, and a width rounded up pushes the last tile into the next row.
            val density = LocalDensity.current
            val tile = with(density) { ((constraints.maxWidth - TILE_GAP.roundToPx() * (TILES_PER_ROW - 1)) / TILES_PER_ROW).toDp() }
            val half = with(density) { ((constraints.maxWidth - WIDGET_GAP.roundToPx()) / 2).toDp() }
            val full = maxWidth
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (row.items.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(TILE_GAP),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        maxItemsInEachRow = TILES_PER_ROW,
                    ) {
                        row.items.forEach { item ->
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
                if (widgets.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(WIDGET_GAP),
                        verticalArrangement = Arrangement.spacedBy(WIDGET_GAP),
                    ) {
                        widgets.forEach { (pin, device) ->
                            QuickActionWidgetCard(
                                device = device,
                                parts = pin.shownParts(device),
                                running = running,
                                adjusting = adjusting,
                                onRun = runOrAsk,
                                onLongPress = device.actions.firstNotNullOfOrNull { longPress(it, onOpenDoor) },
                                onControl = { carrier, control, value -> viewModel.control(carrier, control, value) },
                                modifier = Modifier.width(if (pin.wide) full else half),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** What the start screen shows of the quick action buttons (pure, unit-tested). */
sealed interface QuickActionsRow {
    /** Nothing pinned and the server has no actions (or none known yet). */
    data object Hidden : QuickActionsRow

    /** Actions available, nothing pinned: only the hint how to set the tab up. */
    data object Choose : QuickActionsRow

    /** The pinned buttons with the server's current title, icon and state where known (may be empty beside widgets). */
    data class Tiles(val items: List<QuickActionItem>) : QuickActionsRow

    companion object {
        fun of(pins: List<QuickActionItem>, actions: List<QuickActionItem>, hasWidgets: Boolean = false): QuickActionsRow {
            if (pins.isEmpty() && !hasWidgets) return if (actions.isEmpty()) Hidden else Choose
            val byKey = actions.associateBy { it.key }
            return Tiles(pins.map { pin -> byKey[pin.key] ?: pin })
        }
    }
}

private const val TILES_PER_ROW = 5
private val TILE_GAP = 10.dp
private val WIDGET_GAP = 12.dp
private val TileShape = RoundedCornerShape(18.dp)

/** Colour and background of a look's tone (good green, warn amber, crit red, neutral grey). */
@Composable
internal fun toneColors(tone: String?): Pair<Color, Color>? {
    val extra = GateControlTheme.extraColors
    return when (tone) {
        "good" -> extra.accentText to extra.accentBg
        "warn" -> extra.warn to extra.warnBg
        "crit" -> MaterialTheme.colorScheme.error to extra.errorBg
        "neutral" -> extra.muted to extra.panel2
        else -> null
    }
}

/**
 * One pinned action: icon tile and its title below; [width] of the column.
 * A switch is filled while on — or, with its own look, tinted in the
 * state's tone with the state's icon; progress while it runs.
 */
@Composable
private fun QuickActionTile(item: QuickActionItem, width: Dp, running: Boolean, onClick: () -> Unit, onLongPress: (() -> Unit)?) {
    val extra = GateControlTheme.extraColors
    val scheme = MaterialTheme.colorScheme
    val on = item.isOn
    val toned = toneColors(item.currentLook?.tone)
    val stateText = if (item.toggle) stringResource(if (on) R.string.quick_action_on else R.string.quick_action_off) else null
    val icon = remember(item.displayIcon) { QuickActionIcons.vector(item.displayIcon) }
    val glyph = when {
        toned != null -> toned.first
        on -> scheme.onPrimary
        item.toggle -> extra.muted
        else -> scheme.onSurface
    }
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
            .semantics(mergeDescendants = true) { if (stateText != null && toned == null) stateDescription = stateText },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .size(width)
                .clip(TileShape)
                .then(
                    when {
                        toned != null -> Modifier.background(toned.second).border(1.dp, toned.first.copy(alpha = 0.4f), TileShape)
                        on -> Modifier.background(scheme.primary)
                        else -> Modifier.background(scheme.surface).border(1.dp, extra.border2, TileShape)
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (running) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(22.dp), color = glyph)
            } else {
                Icon(icon, contentDescription = null, tint = glyph, modifier = Modifier.size(28.dp))
            }
        }
        // Two lines, long words hyphenated, the size stepping down before
        // anything is cut: „Haustür öffnen“, „Scheibenheizung“ fit a fifth of a row.
        val labelStyle = MaterialTheme.typography.labelSmall
        val labelColor = toned?.first ?: if (on) extra.accentText else scheme.onSurface
        BasicText(
            item.title,
            style = labelStyle.copy(
                color = labelColor,
                textAlign = TextAlign.Center,
                hyphens = Hyphens.Auto,
                lineBreak = LineBreak.Paragraph,
                lineHeight = labelStyle.fontSize * 1.15f,
            ),
            maxLines = 2,
            minLines = 2,
            overflow = TextOverflow.Ellipsis,
            autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = labelStyle.fontSize, stepSize = 0.5.sp),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Long press of [item] (QuickActionLongPress): a door action opens the door screen, others have none. */
internal fun longPress(item: QuickActionItem, onOpenDoor: (() -> Unit)?): (() -> Unit)? =
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
        text = { Text(item.subtitle ?: item.deviceTitle ?: item.pluginName) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.quick_action_confirm_run)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}
