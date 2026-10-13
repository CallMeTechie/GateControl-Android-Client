package com.gatecontrol.android.ui.quickactions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import com.gatecontrol.android.quickactions.QuickActionItem
import com.gatecontrol.android.quickactions.QuickActionRepository
import com.gatecontrol.android.quickactions.QuickActionsLoad
import com.gatecontrol.android.ui.components.GcBanner
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcChip
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcListRow
import com.gatecontrol.android.ui.components.GcOutlineButton
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
    GcListRow(
        title = item.title,
        description = item.subtitle,
        onClick = onRun,
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
 * Start-screen card with the pinned quick actions; with none pinned but
 * actions available it invites to choose some. Hidden otherwise.
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
    if (pins.isEmpty() && actions.isEmpty()) return

    // Fresh state from the server where known, the stored pin otherwise.
    val byKey = actions.associateBy { it.key }
    val shown = pins.map { byKey[it.key] ?: it }

    GcCard(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.quick_actions_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onOpenAll) {
                Text(stringResource(if (shown.isEmpty()) R.string.quick_actions_choose else R.string.quick_actions_all))
            }
        }
        if (shown.isEmpty()) {
            Text(stringResource(R.string.quick_actions_empty_pins), style = MaterialTheme.typography.bodySmall, color = extra.muted)
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                shown.forEach { item ->
                    GcOutlineButton(
                        text = if (item.toggle) {
                            item.title + " · " + stringResource(if (item.isOn) R.string.quick_action_on else R.string.quick_action_off)
                        } else {
                            item.title
                        },
                        onClick = { if (item.confirm) confirming = item else viewModel.run(item) },
                        icon = if (running == item.key) null else GcIcons.Bolt,
                        fillWidth = false,
                        minHeight = 40.dp,
                    )
                }
            }
        }
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
