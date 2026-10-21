package com.gatecontrol.android.ui.notifications

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.push.routes.NotifyDest
import com.gatecontrol.android.push.routes.NotifyRouteGroup
import com.gatecontrol.android.push.routes.NotifyRouteTopic
import com.gatecontrol.android.push.routes.NotifyRoutes
import com.gatecontrol.android.push.routes.NotifyRoutesRepository
import com.gatecontrol.android.ui.components.GcBanner
import com.gatecontrol.android.ui.components.GcChevron
import com.gatecontrol.android.ui.components.GcChip
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcListRow
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcSubpageBar
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.theme.GateControlTheme

/**
 * "Wo Mitteilungen erscheinen" (core 1.191, Settings › Benachrichtigungen
 * and the chat settings): per source its kinds of notification, each with
 * where it appears now; a tap chooses GateControl, the family chat or an
 * own chat of the source (bottom sheet). Locked kinds always stay in
 * GateControl; without the messenger everything does.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotifyRoutesScreen(
    onBack: () -> Unit,
    viewModel: NotifyRoutesViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val extra = GateControlTheme.extraColors

    LaunchedEffect(viewModel) {
        viewModel.notice.collect { n ->
            Toast.makeText(context, resources.getString(refusalText(n.code)), Toast.LENGTH_LONG).show()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState()),
    ) {
        GcSubpageBar(
            title = stringResource(R.string.push_routes_title),
            onBack = onBack,
            backDescription = stringResource(R.string.common_back),
        )
        Column(
            Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val routes = ui.routes
            when {
                ui.unsupported -> Hint(stringResource(R.string.push_routes_unsupported))
                routes == null && ui.loading -> Hint(stringResource(R.string.push_routes_loading))
                routes == null -> {
                    Hint(stringResource(R.string.push_routes_load_failed))
                    GcOutlineButton(text = stringResource(R.string.push_routes_retry), onClick = viewModel::refresh, icon = GcIcons.Refresh, minHeight = 48.dp)
                }
                else -> {
                    Hint(stringResource(R.string.push_routes_intro) + if (routes.canFamily) " " + stringResource(R.string.push_routes_intro_family) else "")
                    if (!routes.sink) {
                        GcBanner(tone = GcTone.Info, icon = GcIcons.Alert) {
                            Text(
                                stringResource(R.string.push_routes_no_sink),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    if (routes.groups.isEmpty()) Hint(stringResource(R.string.push_routes_empty))
                    routes.groups.forEach { g ->
                        Group(g.label) {
                            g.topics.forEach { t -> TopicRow(routes, g, t, saving = ui.saving == t.id, onClick = { viewModel.pick(t.id) }) }
                        }
                    }
                    Text(stringResource(R.string.push_routes_footer), style = MaterialTheme.typography.bodySmall, color = extra.muted, modifier = Modifier.padding(horizontal = 4.dp))
                }
            }
        }
    }

    val picking = ui.picking
    val routes = ui.routes
    if (picking != null && routes != null) {
        ModalBottomSheet(onDismissRequest = viewModel::dismiss, containerColor = MaterialTheme.colorScheme.surface) {
            DestSheet(routes, picking, onChoose = viewModel::choose)
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = GateControlTheme.extraColors.muted, modifier = Modifier.padding(horizontal = 4.dp))
}

/** One kind of notification with where it appears (chip), or the lock of one that always stays in GateControl. */
@Composable
private fun TopicRow(routes: NotifyRoutes, group: NotifyRouteGroup, t: NotifyRouteTopic, saving: Boolean, onClick: () -> Unit) {
    val extra = GateControlTheme.extraColors
    if (t.locked) {
        val a11y = t.label + ", " + stringResource(R.string.push_routes_locked_a11y)
        GcListRow(
            title = t.label,
            modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = a11y },
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(GcIcons.Lock, contentDescription = null, tint = extra.muted, modifier = Modifier.size(16.dp))
                    Text(stringResource(R.string.push_routes_locked), style = MaterialTheme.typography.bodySmall, color = extra.muted)
                }
            },
        )
        return
    }
    val dest = routes.effective(t)
    GcListRow(
        title = t.label,
        description = familyLine(t).takeIf { dest == NotifyDest.FAMILY },
        // Without the messenger there is nothing to choose: everything stays in GateControl.
        onClick = if (routes.sink) onClick else null,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (saving) {
                    val savingText = stringResource(R.string.push_routes_saving)
                    CircularProgressIndicator(Modifier.size(18.dp).semantics { contentDescription = savingText }, strokeWidth = 2.dp)
                }
                GcChip(destChip(dest, group.label), tone = destTone(dest), small = true)
                if (routes.sink) GcChevron()
            }
        },
    )
}

@Composable
private fun familyLine(t: NotifyRouteTopic): String =
    t.familySetBy?.let { stringResource(R.string.push_routes_family_set_by, it) } ?: stringResource(R.string.push_routes_family_set)

@Composable
private fun destChip(dest: NotifyDest, source: String): String = when (dest) {
    NotifyDest.INBOX -> stringResource(R.string.push_routes_dest_inbox)
    NotifyDest.FAMILY -> stringResource(R.string.push_routes_dest_family)
    NotifyDest.OWN -> stringResource(R.string.push_routes_dest_own, source)
}

private fun destTone(dest: NotifyDest): GcTone = when (dest) {
    NotifyDest.INBOX -> GcTone.Ok
    NotifyDest.FAMILY -> GcTone.Warn
    NotifyDest.OWN -> GcTone.Info
}

/** The choice for [t]: GateControl, the family chat (adults), an own chat of its source. */
@Composable
private fun DestSheet(routes: NotifyRoutes, t: NotifyRouteTopic, onChoose: (NotifyDest) -> Unit) {
    val extra = GateControlTheme.extraColors
    val source = routes.groupOf(t.id)?.label.orEmpty()
    val editable = routes.editable(t)
    val current = routes.effective(t)
    Column(Modifier.fillMaxWidth().padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(t.label, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(stringResource(R.string.push_routes_sheet_sub, source), style = MaterialTheme.typography.bodyMedium, color = extra.muted)
        }
        if (!editable) {
            // The family chat was set for everyone by an adult: only adults change it.
            GcBanner(tone = GcTone.Info, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text(
                    familyLine(t) + ". " + stringResource(R.string.push_routes_family_only_adults),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        routes.options(t).forEach { dest ->
            ModeRow(
                title = when (dest) {
                    NotifyDest.INBOX -> stringResource(R.string.push_routes_dest_inbox)
                    NotifyDest.FAMILY -> stringResource(R.string.push_routes_dest_family)
                    NotifyDest.OWN -> stringResource(R.string.push_routes_option_own, source)
                },
                desc = when (dest) {
                    NotifyDest.INBOX -> stringResource(R.string.push_routes_option_inbox_desc)
                    NotifyDest.FAMILY -> stringResource(R.string.push_routes_option_family_desc)
                    NotifyDest.OWN -> stringResource(R.string.push_routes_option_own_desc, source)
                },
                recommended = false,
                selected = dest == current,
                onSelect = { onChoose(dest) },
                enabled = editable,
            )
        }
    }
}

private fun refusalText(code: String): Int = when (code) {
    NotifyRoutesRepository.ERR_FAMILY -> R.string.push_routes_err_family
    NotifyRoutesRepository.ERR_LOCKED -> R.string.push_routes_err_locked
    NotifyRoutesRepository.ERR_NO_SINK -> R.string.push_routes_err_no_sink
    NotifyRoutesRepository.ERR_FORBIDDEN -> R.string.push_routes_err_forbidden
    NotifyRoutesRepository.ERR_NETWORK -> R.string.push_routes_err_network
    NotifyRoutesRepository.ERR_UNSUPPORTED -> R.string.push_routes_unsupported
    else -> R.string.push_routes_err_failed
}

/**
 * The row to "Wo Mitteilungen erscheinen" (Settings › Benachrichtigungen);
 * only shown when the server has the page.
 */
@Composable
fun NotifyRoutesEntryRow(onOpen: () -> Unit, entry: NotifyRoutesEntryViewModel = hiltViewModel()) {
    val visible by entry.visible.collectAsStateWithLifecycle()
    if (!visible) return
    GcListRow(
        title = stringResource(R.string.push_routes_title),
        description = stringResource(R.string.push_routes_entry_desc),
        onClick = onOpen,
        trailing = { GcChevron() },
    )
}
