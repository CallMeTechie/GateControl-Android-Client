package com.gatecontrol.android.ui.dnsprotect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.R
import com.gatecontrol.android.dnsprotect.DnsDuration
import com.gatecontrol.android.dnsprotect.DnsMay
import com.gatecontrol.android.dnsprotect.DnsPerson
import com.gatecontrol.android.dnsprotect.DnsTarget
import com.gatecontrol.android.ui.components.GcChevron
import com.gatecontrol.android.ui.components.GcFilterChip
import com.gatecontrol.android.ui.components.GcListRow
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcRowDivider
import com.gatecontrol.android.ui.components.GcSecondaryButton
import com.gatecontrol.android.ui.theme.GateControlTheme

/**
 * "Für andere unterbrechen" (administrators and parents, `status.manage`):
 * the people of `GET people` with their devices, an administrator also
 * "Alle VPN-Geräte". A tap on a row opens its durations — only those the
 * server allows for that target — or offers to switch back on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DnsOthersSheet(ui: DnsUiState, viewModel: DnsProtectViewModel, onDismiss: () -> Unit) {
    /** The row whose durations are open: `all`, `u<id>` or `p<id>`. */
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(Modifier.padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
            DnsOthersList(
                ui = ui,
                open = open,
                onOpen = { open = it },
                onPause = { duration, target -> viewModel.pause(duration, target) },
                onResume = { viewModel.resume(it) },
                onRetry = { viewModel.loadPeople() },
            )
        }
    }
}

/** The sheet's content: the heading, a refusal, the rows. */
@Composable
internal fun DnsOthersList(
    ui: DnsUiState,
    open: String?,
    onOpen: (String?) -> Unit,
    onPause: (DnsDuration, DnsTarget) -> Unit,
    onResume: (DnsTarget) -> Unit,
    onRetry: () -> Unit,
) {
    val extra = GateControlTheme.extraColors
    Text(
        stringResource(R.string.dns_others_title),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
    )
    (ui.notice as? DnsNotice.Refused)?.let { refused ->
        Text(
            refused.text ?: stringResource(R.string.dns_notice_refused),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    val people = ui.people
    when {
        people == null && ui.peopleFailed -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.dns_others_failed), style = MaterialTheme.typography.bodyMedium, color = extra.muted)
            GcOutlineButton(text = stringResource(R.string.dns_retry), onClick = onRetry, fillWidth = false)
        }
        people == null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
        people.all == null && people.people.isEmpty() -> Text(
            stringResource(R.string.dns_others_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = extra.muted,
            modifier = Modifier.padding(16.dp),
        )
        else -> {
            people.all?.let { all ->
                TargetRow(
                    key = "all",
                    open = open,
                    onOpen = onOpen,
                    title = stringResource(R.string.dns_others_all),
                    state = all.pause?.let { dnsPauseLine(it) } ?: dnsReasonText(all.may.reason),
                    may = all.may,
                    busy = ui.working,
                    target = DnsTarget.All,
                    onPause = onPause,
                    onResume = onResume,
                )
                GcRowDivider(Modifier.padding(horizontal = 16.dp))
            }
            people.people.forEachIndexed { index, person ->
                if (index > 0) GcRowDivider(Modifier.padding(horizontal = 16.dp))
                PersonRows(person, open, onOpen, ui.working, onPause, onResume)
            }
        }
    }
}

@Composable
private fun PersonRows(
    person: DnsPerson,
    open: String?,
    onOpen: (String?) -> Unit,
    busy: Boolean,
    onPause: (DnsDuration, DnsTarget) -> Unit,
    onResume: (DnsTarget) -> Unit,
) {
    val suspended = person.devices.count { it.suspended }
    val state = when {
        suspended > 0 && suspended == person.devices.size ->
            person.devices.firstNotNullOfOrNull { it.pause }?.let { dnsPauseLine(it) } ?: stringResource(R.string.dns_others_paused_open)
        suspended > 0 -> stringResource(R.string.dns_others_paused_some)
        else -> dnsReasonText(person.may.reason)
            ?: pluralStringResource(R.plurals.dns_others_devices, person.devices.size, person.devices.size)
    }
    TargetRow(
        key = "u${person.userId}",
        open = open,
        onOpen = onOpen,
        title = if (person.self) stringResource(R.string.dns_others_self, person.name) else person.name,
        state = state,
        may = person.may,
        busy = busy,
        target = DnsTarget.User(person.userId),
        onPause = onPause,
        onResume = onResume,
    )
    // One device: the person's row says it all.
    if (person.devices.size > 1) {
        person.devices.forEach { device ->
            TargetRow(
                key = "p${device.peerId}",
                open = open,
                onOpen = onOpen,
                title = device.name,
                state = device.pause?.let { dnsPauseLine(it) } ?: dnsReasonText(device.may.reason),
                may = device.may,
                busy = busy,
                target = DnsTarget.Peer(device.peerId),
                onPause = onPause,
                onResume = onResume,
                indent = true,
            )
        }
    }
}

@Composable
private fun TargetRow(
    key: String,
    open: String?,
    onOpen: (String?) -> Unit,
    title: String,
    state: String?,
    may: DnsMay,
    busy: Boolean,
    target: DnsTarget,
    onPause: (DnsDuration, DnsTarget) -> Unit,
    onResume: (DnsTarget) -> Unit,
    indent: Boolean = false,
) {
    val start = if (indent) 16.dp else 0.dp
    GcListRow(
        title = title,
        description = state,
        modifier = Modifier.padding(start = start),
        onClick = if (may.pause) ({ onOpen(if (open == key) null else key) }) else null,
        titleColor = if (may.pause || may.resume) MaterialTheme.colorScheme.onSurface else GateControlTheme.extraColors.muted,
        trailing = {
            when {
                may.resume -> GcSecondaryButton(
                    text = stringResource(R.string.dns_tile_resume),
                    onClick = { onResume(target) },
                    enabled = !busy,
                    fillWidth = false,
                )
                may.pause -> GcChevron()
            }
        },
    )
    if (open == key && may.pause) {
        Column(
            Modifier.padding(start = 16.dp + start, end = 16.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                may.durations.forEach { minutes ->
                    GcFilterChip(
                        text = dnsChipLabel(minutes),
                        selected = false,
                        enabled = !busy,
                        onClick = {
                            onPause(DnsDuration.Minutes(minutes), target)
                            onOpen(null)
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            if (may.forever) {
                GcFilterChip(
                    text = stringResource(R.string.dns_others_forever),
                    selected = false,
                    enabled = !busy,
                    onClick = {
                        onPause(DnsDuration.Forever, target)
                        onOpen(null)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
