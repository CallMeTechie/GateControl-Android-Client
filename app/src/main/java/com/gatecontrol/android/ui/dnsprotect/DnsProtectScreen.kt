package com.gatecontrol.android.ui.dnsprotect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.dnsprotect.DnsDuration
import com.gatecontrol.android.dnsprotect.DnsLogic
import com.gatecontrol.android.dnsprotect.DnsSnapshot
import com.gatecontrol.android.dnsprotect.DnsStatus
import com.gatecontrol.android.ui.components.GcBanner
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcChevron
import com.gatecontrol.android.ui.components.GcFilterChip
import com.gatecontrol.android.ui.components.GcIconSquare
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcListRow
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.components.GcSectionLabel
import com.gatecontrol.android.ui.components.GcStatCard
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.theme.GateControlTheme
import com.gatecontrol.android.ui.theme.MonoFontFamily
import com.gatecontrol.android.util.findComponentActivity
import kotlinx.coroutines.delay

/**
 * "DNS-Schutz" inside the Netzwerk tab (no own title; the tab provides it).
 * The segment of servers that report a DNS protection: status card,
 * counters, the card to suspend. The durations are chips; the chosen one shows the time left.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DnsProtectContent(
    modifier: Modifier = Modifier,
    viewModel: DnsProtectViewModel = hiltViewModel(LocalContext.current.findComponentActivity()),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val extra = GateControlTheme.extraColors

    // No live events: ask when the screen shows and every 45 s while it is in front.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(ui.pollMs, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.refresh()
            while (true) {
                delay(ui.pollMs)
                viewModel.refresh()
            }
        }
    }
    var others by rememberSaveable { mutableStateOf(false) }
    if (others) DnsOthersSheet(ui = ui, viewModel = viewModel, onDismiss = { others = false })

    val snapshot = ui.snapshot
    PullToRefreshBox(
        isRefreshing = ui.refreshing,
        onRefresh = { viewModel.refresh(byUser = true) },
        modifier = modifier,
    ) {
        when {
            snapshot == null && !ui.loaded -> Box(Modifier.fillMaxSize()) {
                CircularProgressIndicator(Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.primary)
            }
            // Scrolls, so pulling down to refresh works here too.
            snapshot == null -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                Text(
                    stringResource(
                        when {
                            ui.gone -> R.string.dns_gone
                            ui.notice == DnsNotice.Busy -> R.string.dns_notice_busy
                            else -> R.string.dns_empty
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = extra.muted,
                )
            }
            else -> {
                val remaining = rememberDnsRemaining(snapshot, onEnded = { viewModel.refresh() })
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    DnsProtectCards(
                        ui = ui,
                        snapshot = snapshot,
                        remaining = remaining,
                        onPause = { viewModel.pause(it) },
                        onResume = { viewModel.resume() },
                        onOthers = {
                            viewModel.loadPeople()
                            others = true
                        },
                    )
                }
            }
        }
    }
}

/** The cards of the screen for a known status, top to bottom. */
@Composable
internal fun DnsProtectCards(
    ui: DnsUiState,
    snapshot: DnsSnapshot,
    remaining: Long?,
    onPause: (DnsDuration) -> Unit,
    onResume: () -> Unit,
    onOthers: () -> Unit,
) {
    val extra = GateControlTheme.extraColors
    val status = snapshot.status
    when (ui.notice) {
        DnsNotice.Offline -> NoticeBanner(stringResource(R.string.dns_notice_offline))
        DnsNotice.Busy -> NoticeBanner(stringResource(R.string.dns_notice_busy))
        else -> Unit
    }
    StatusCard(status, remaining)
    Counters(status)
    when {
        status.state == DnsStatus.FALLBACK -> FallbackBanner()
        DnsLogic.showsPauseCard(status) -> PauseCard(ui, snapshot, remaining, onPause, onResume)
        status.state == DnsStatus.ACTIVE && status.may.reason == "profile" -> NotAllowedBanner()
    }
    if (status.today.isNotEmpty()) {
        GcCard(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            GcSectionLabel(stringResource(R.string.dns_today_title))
            status.today.forEach { entry ->
                Text(dnsScheduleLine(entry), style = MaterialTheme.typography.bodySmall, color = extra.muted)
            }
        }
    }
    val top = status.top
    if (!top.isNullOrEmpty()) TopCard(top.map { it.domain to dnsCount(it.count) })
    if (status.manage) {
        GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.Top) {
            GcListRow(
                title = stringResource(R.string.dns_others_title),
                description = stringResource(R.string.dns_others_desc),
                onClick = onOthers,
                trailing = { GcChevron() },
            )
        }
    }
}

@Composable
private fun NoticeBanner(text: String) {
    GcBanner(tone = GcTone.Warn, icon = GcIcons.Alert) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun StatusCard(status: DnsStatus, remaining: Long?) {
    val extra = GateControlTheme.extraColors
    val pause = status.pause
    val title: String
    val subtitle: String?
    when (status.state) {
        DnsStatus.ACTIVE -> {
            title = stringResource(R.string.dns_state_active_title)
            subtitle = status.profileName?.let { stringResource(R.string.dns_state_active_profile, it) }
                ?: stringResource(R.string.dns_state_active_sub)
        }
        DnsStatus.PAUSED -> {
            val until = DnsLogic.timeOfDay(pause?.until)
            if (remaining != null && until != null) {
                title = stringResource(R.string.dns_state_paused_clock, DnsLogic.clock(remaining))
                subtitle = stringResource(R.string.dns_state_paused_until, until)
            } else {
                title = stringResource(R.string.dns_state_paused_open)
                subtitle = stringResource(if (pause?.byMe == true) R.string.dns_state_paused_open_me else R.string.dns_state_paused_open_other)
            }
        }
        DnsStatus.FALLBACK -> {
            title = stringResource(R.string.dns_state_fallback_title)
            subtitle = DnsLogic.timeOfDay(status.outageSince)?.let { stringResource(R.string.dns_state_fallback_since, it) }
                ?: stringResource(R.string.dns_state_fallback_sub)
        }
        DnsStatus.UNFILTERED -> {
            title = stringResource(R.string.dns_state_unfiltered_title)
            val device = status.device
            subtitle = when {
                device == null -> stringResource(R.string.dns_reason_no_device)
                device.state == "ownDns" && !device.ownDns.isNullOrBlank() -> stringResource(R.string.dns_unfiltered_own_dns, device.ownDns)
                device.state == "ownDns" -> stringResource(R.string.dns_unfiltered_own_dns_plain)
                device.state == "excluded" -> stringResource(R.string.dns_unfiltered_excluded)
                device.state == "disabled" -> stringResource(R.string.dns_unfiltered_disabled)
                else -> null
            }
        }
        DnsStatus.STARTING -> {
            title = stringResource(R.string.dns_state_starting_title)
            subtitle = stringResource(R.string.dns_state_starting_sub)
        }
        else -> {
            title = stringResource(R.string.dns_state_off_title)
            subtitle = null
        }
    }
    val (background, tint) = when (status.state) {
        DnsStatus.ACTIVE -> extra.accentBg to extra.accentText
        DnsStatus.PAUSED -> extra.warnBg to extra.warn
        DnsStatus.FALLBACK -> extra.errorBg to MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.surface to extra.muted
    }
    GcCard(color = background) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            GcIconSquare(size = 48.dp, background = MaterialTheme.colorScheme.surface) {
                Icon(dnsStateIcon(status.state), contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
            }
            // The countdown itself is not announced every second.
            Column(Modifier.weight(1f).then(if (remaining == null) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier)) {
                Text(title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = extra.muted)
            }
        }
    }
}

@Composable
internal fun dnsStateIcon(state: String) = when (state) {
    DnsStatus.ACTIVE -> GcIcons.ShieldCheck
    DnsStatus.FALLBACK -> GcIcons.ShieldX
    else -> GcIcons.Shield
}

@Composable
private fun Counters(status: DnsStatus) {
    val c = status.counters
    val scope = when (c.scope) {
        "device" -> stringResource(R.string.dns_scope_device)
        "person" -> stringResource(R.string.dns_scope_person)
        "server" -> stringResource(R.string.dns_scope_server)
        else -> null
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        GcStatCard(stringResource(R.string.dns_stat_queries), dnsCount(c.queries), subtitle = scope, modifier = Modifier.weight(1f))
        GcStatCard(
            stringResource(R.string.dns_stat_blocked),
            dnsCount(c.blocked),
            subtitle = stringResource(R.string.dns_percent, DnsLogic.percent(c.percent, appLocale())),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun PauseCard(ui: DnsUiState, snapshot: DnsSnapshot, remaining: Long?, onPause: (DnsDuration) -> Unit, onResume: () -> Unit) {
    val extra = GateControlTheme.extraColors
    val status = snapshot.status
    val may = status.may
    val pause = status.pause
    val paused = pause != null
    val own = status.device != null

    GcCard(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.dns_pause_title), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
        if (ui.working) {
            Text(stringResource(R.string.dns_applying), style = MaterialTheme.typography.bodySmall, color = extra.muted)
        }
        (ui.notice as? DnsNotice.Refused)?.let { refused ->
            Text(
                refused.text ?: stringResource(R.string.dns_notice_refused),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DnsLogic.chips(may, pause).forEach { chip ->
                GcFilterChip(
                    text = if (chip.selected && remaining != null) DnsLogic.clock(remaining) else dnsChipLabel(chip.minutes),
                    selected = chip.selected,
                    enabled = chip.enabled && !ui.working,
                    onClick = { onPause(DnsDuration.Minutes(chip.minutes)) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        val foreverOn = paused && pause?.openEnd == true
        GcFilterChip(
            text = stringResource(R.string.dns_chip_forever),
            selected = foreverOn,
            enabled = !paused && may.pause && may.forever && !ui.working,
            onClick = { onPause(DnsDuration.Forever) },
            modifier = Modifier.fillMaxWidth(),
        )
        if (paused && may.resume) {
            GcPrimaryButton(
                text = stringResource(R.string.dns_resume),
                onClick = onResume,
                enabled = !ui.working,
                icon = GcIcons.ShieldCheck,
            )
        }
        val hint = if (pause != null) {
            val at = DnsLogic.timeOfDay(pause.since)
            val planned = DnsLogic.plannedMinutes(pause)
            val who = when {
                at == null -> stringResource(R.string.dns_paused_plain)
                pause.byMe && planned != null -> stringResource(R.string.dns_paused_by_me_for, at, dnsDurationWords(planned))
                pause.byMe -> stringResource(R.string.dns_paused_by_me, at)
                pause.byName != null && planned != null -> stringResource(R.string.dns_paused_by_other_for, pause.byName, at, dnsDurationWords(planned))
                pause.byName != null -> stringResource(R.string.dns_paused_by_other, pause.byName, at)
                else -> stringResource(R.string.dns_paused_at, at)
            }
            val effect = when {
                may.reason == "paused_for_all" || pause.scope == "all" -> stringResource(R.string.dns_paused_for_all)
                own -> stringResource(R.string.dns_paused_effect_device)
                else -> stringResource(R.string.dns_paused_effect_person)
            }
            "$who $effect"
        } else {
            val scope = stringResource(if (own) R.string.dns_pause_hint_device else R.string.dns_pause_hint_person)
            if (may.forever) scope else scope + " " + stringResource(R.string.dns_pause_forever_locked)
        }
        Text(hint, style = MaterialTheme.typography.bodySmall, color = extra.muted)
    }
}

@Composable
private fun FallbackBanner() {
    GcBanner(tone = GcTone.Warn, icon = GcIcons.Alert) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                stringResource(R.string.dns_fallback_banner_title),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(stringResource(R.string.dns_fallback_banner_text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun NotAllowedBanner() {
    GcBanner(tone = GcTone.Info, icon = GcIcons.Lock) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                stringResource(R.string.dns_not_allowed_title),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(stringResource(R.string.dns_not_allowed_profile), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun TopCard(rows: List<Pair<String, String>>) {
    val extra = GateControlTheme.extraColors
    GcCard(contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.Top) {
        GcSectionLabel(stringResource(R.string.dns_top_title), Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        rows.forEach { (domain, count) ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    domain,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(count, style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily), color = extra.muted)
            }
        }
    }
}
