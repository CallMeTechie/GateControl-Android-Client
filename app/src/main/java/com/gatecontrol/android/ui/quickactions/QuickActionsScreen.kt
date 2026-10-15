package com.gatecontrol.android.ui.quickactions

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
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
 * shortcuts, the Quick Settings tiles and the widget.
 */
@Composable
fun QuickActionsScreen(onBack: () -> Unit, viewModel: QuickActionsViewModel = hiltViewModel()) {
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
                            running = running == action.key,
                            onRun = { if (action.confirm) confirming = action else viewModel.run(action) },
                            onTogglePin = { viewModel.togglePin(action) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionRow(
    item: QuickActionItem,
    pinned: Boolean,
    running: Boolean,
    onRun: () -> Unit,
    onTogglePin: () -> Unit,
) {
    val extra = GateControlTheme.extraColors
    val icon = remember(item.icon) { QuickActionIcons.vector(item.icon) }
    GcListRow(
        title = item.title,
        description = item.subtitle,
        onClick = onRun,
        leading = {
            GcIconSquare(size = 40.dp, background = if (item.isOn) extra.accentBg else extra.panel2) {
                Icon(icon, contentDescription = null, tint = if (item.isOn) extra.accentText else MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(22.dp))
            }
        },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                when {
                    running -> CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp), color = extra.faint)
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

/**
 * Start-screen row of the pinned quick actions (no card, no heading): one
 * icon tile per action with its title below, a switch filled while on;
 * a "+" on the right opens the list. With none pinned but actions
 * available only "Schnellaktionen auswählen". Hidden without actions.
 */
@Composable
fun QuickActionsCard(onOpenAll: () -> Unit, modifier: Modifier = Modifier, viewModel: QuickActionsViewModel = hiltViewModel()) {
    val pins by viewModel.pins.collectAsStateWithLifecycle()
    val actions by viewModel.actions.collectAsStateWithLifecycle()
    val running by viewModel.running.collectAsStateWithLifecycle()
    val extra = GateControlTheme.extraColors
    var confirming by remember { mutableStateOf<QuickActionItem?>(null) }

    LaunchedEffect(Unit) { viewModel.refresh() }
    ResultToasts(viewModel)
    confirming?.let { item ->
        ConfirmDialog(item, onConfirm = { confirming = null; viewModel.run(item) }, onDismiss = { confirming = null })
    }

    when (val row = QuickActionsRow.of(pins, actions)) {
        QuickActionsRow.Hidden -> Unit
        QuickActionsRow.Choose -> TextButton(onClick = onOpenAll, modifier = modifier) {
            Icon(GcIcons.Plus, contentDescription = null, tint = extra.accentText, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.quick_actions_choose_long), color = extra.accentText, style = MaterialTheme.typography.labelLarge)
        }
        is QuickActionsRow.Tiles -> Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                row.items.forEach { item ->
                    QuickActionTile(
                        item = item,
                        running = running == item.key,
                        onClick = { if (item.confirm) confirming = item else viewModel.run(item) },
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            // "+" without a label: choose more.
            val chooseDescription = stringResource(R.string.quick_actions_choose_long)
            Box(
                Modifier
                    .size(TILE_SIZE)
                    .clip(TileShape)
                    .border(1.dp, extra.border2, TileShape)
                    .clickable(role = Role.Button, onClick = onOpenAll)
                    .semantics { contentDescription = chooseDescription },
                contentAlignment = Alignment.Center,
            ) {
                Icon(GcIcons.Plus, contentDescription = null, tint = extra.muted, modifier = Modifier.size(24.dp))
            }
        }
    }
}

/** What the start screen shows of the quick actions (pure, unit-tested). */
sealed interface QuickActionsRow {
    /** No pins and the server has no actions (or none known yet). */
    data object Hidden : QuickActionsRow

    /** Actions available, none pinned: only "Schnellaktionen auswählen". */
    data object Choose : QuickActionsRow

    /** The pinned actions, with the server's current title, icon and state where known. */
    data class Tiles(val items: List<QuickActionItem>) : QuickActionsRow

    companion object {
        fun of(pins: List<QuickActionItem>, actions: List<QuickActionItem>): QuickActionsRow {
            if (pins.isEmpty()) return if (actions.isEmpty()) Hidden else Choose
            val byKey = actions.associateBy { it.key }
            return Tiles(pins.map { byKey[it.key] ?: it })
        }
    }
}

private val TILE_SIZE = 56.dp
private val TILE_LABEL_WIDTH = 68.dp
private val TileShape = RoundedCornerShape(18.dp)

/** One pinned action: icon tile (filled while a switch is on, progress while it runs) and its title. */
@Composable
private fun QuickActionTile(item: QuickActionItem, running: Boolean, onClick: () -> Unit) {
    val extra = GateControlTheme.extraColors
    val scheme = MaterialTheme.colorScheme
    val on = item.isOn
    val stateText = if (item.toggle) stringResource(if (on) R.string.quick_action_on else R.string.quick_action_off) else null
    val icon = remember(item.icon) { QuickActionIcons.vector(item.icon) }
    Column(
        Modifier
            .width(TILE_LABEL_WIDTH)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = !running, role = if (item.toggle) Role.Switch else Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { if (stateText != null) stateDescription = stateText },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .size(TILE_SIZE)
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
                    modifier = Modifier.size(26.dp),
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
