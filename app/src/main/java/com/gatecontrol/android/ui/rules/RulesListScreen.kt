package com.gatecontrol.android.ui.rules

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.familie.FamilieRepository
import com.gatecontrol.android.familie.rules.Rule
import com.gatecontrol.android.familie.rules.RuleFilter
import com.gatecontrol.android.familie.rules.RuleLogic
import com.gatecontrol.android.familie.rules.RuleOpen
import com.gatecontrol.android.familie.rules.RuleOptions
import com.gatecontrol.android.familie.rules.RuleTemplate
import com.gatecontrol.android.familie.rules.RuleVacation
import com.gatecontrol.android.ui.components.GcBanner
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcChevron
import com.gatecontrol.android.ui.components.GcFilterChip
import com.gatecontrol.android.ui.components.GcIconSquare
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcListRow
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.components.GcRowDivider
import com.gatecontrol.android.ui.components.GcSectionLabel
import com.gatecontrol.android.ui.components.GcSubpageBar
import com.gatecontrol.android.ui.components.GcSwitch
import com.gatecontrol.android.ui.components.GcSwitchRow
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.familie.FamilieDateField
import com.gatecontrol.android.ui.familie.FamilieFormat
import com.gatecontrol.android.ui.familie.familieErrorMessage
import com.gatecontrol.android.ui.familie.familieErrorText
import com.gatecontrol.android.ui.theme.GateControlTheme
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Names of Familie members for rule texts (no view model of its own). */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface RulesEntryPoint {
    fun familie(): FamilieRepository
}

@Composable
internal fun rememberNameOf(): (Int) -> String? {
    val ctx = LocalContext.current.applicationContext
    val repo = androidx.compose.runtime.remember { EntryPointAccessors.fromApplication(ctx, RulesEntryPoint::class.java).familie() }
    val profile by repo.profile.collectAsStateWithLifecycle()
    return { id -> profile?.nameOf(id) }
}

@Composable
internal fun rememberMyFamilieId(): Int? {
    val ctx = LocalContext.current.applicationContext
    val repo = androidx.compose.runtime.remember { EntryPointAccessors.fromApplication(ctx, RulesEntryPoint::class.java).familie() }
    val profile by repo.profile.collectAsStateWithLifecycle()
    return profile?.me?.userId
}

/** A time of today as "17:40", else with the weekday ("So 18:00"). */
@Composable
internal fun shortTime(iso: String?): String? {
    val ms = RuleLogic.epoch(iso) ?: return null
    val zone = ZoneId.systemDefault()
    val t = Instant.ofEpochMilli(ms).atZone(zone)
    val pattern = if (t.toLocalDate() == LocalDate.now(zone)) "HH:mm" else "EEE HH:mm"
    return DateTimeFormatter.ofPattern(pattern, ruleLocale()).format(t)
}

/**
 * Profile row "Regeln und Erinnerungen" under "Orte für Regeln teilen"
 * (mockup ProfileRules); [showLabel] the section label when the presence
 * cards above are not shown.
 */
@Composable
fun ProfileRulesRow(onOpen: () -> Unit, showLabel: Boolean, modifier: Modifier = Modifier, viewModel: ProfileRulesViewModel = hiltViewModel()) {
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    val summary by viewModel.summary.collectAsStateWithLifecycle()
    val s = summary ?: return
    val extra = GateControlTheme.extraColors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (showLabel) GcSectionLabel(stringResource(R.string.profile_section_familie), Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
        GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.Top) {
            GcListRow(
                title = stringResource(R.string.rules_title),
                description = listOfNotNull(
                    pluralStringResource(R.plurals.rules_summary_open, s.open, s.open).takeIf { s.open > 0 },
                    stringResource(R.string.rules_summary_vacation).takeIf { s.vacation },
                    stringResource(R.string.rules_summary_active, s.active),
                    stringResource(R.string.rules_summary_paused, s.paused).takeIf { s.paused > 0 && !s.vacation },
                    shortTime(s.lastRunAt)?.let { stringResource(R.string.rules_summary_last, it) },
                ).joinToString(" · "),
                onClick = onOpen,
                leading = {
                    Box {
                        Icon(GcIcons.Zap, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                        if (s.open > 0) OpenMark(Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-6).dp), small = true)
                    }
                },
                trailing = { GcChevron() },
            )
        }
        Text(stringResource(R.string.rules_profile_hint), style = MaterialTheme.typography.bodySmall, color = extra.faint, modifier = Modifier.padding(horizontal = 4.dp))
    }
}

/** "Regeln und Erinnerungen" (mockup RulesList). */
@Composable
fun RulesListScreen(
    onBack: () -> Unit,
    onOpenRule: (Int) -> Unit,
    /** A new rule; the id of a [RuleTemplate] or null for an empty one. */
    onNewRule: (String?) -> Unit,
    viewModel: RulesListViewModel = hiltViewModel(),
) {
    val ctx = LocalContext.current
    val extra = GateControlTheme.extraColors
    val load by viewModel.load.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val nameOf = rememberNameOf()
    val myId = rememberMyFamilieId()
    val options by viewModel.options.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    var picking by rememberSaveable { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    LaunchedEffect(Unit) {
        viewModel.error.collect { Toast.makeText(ctx, familieErrorMessage(ctx, it), Toast.LENGTH_LONG).show() }
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        GcSubpageBar(title = stringResource(R.string.rules_title), onBack = onBack, backDescription = stringResource(R.string.common_back))
        Box(Modifier.weight(1f)) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when (val l = load) {
                    RulesLoad.Loading -> CircularProgressIndicator(Modifier.padding(24.dp).size(28.dp))
                    RulesLoad.Unsupported -> Text(stringResource(R.string.rules_unsupported), style = MaterialTheme.typography.bodyMedium, color = extra.muted)
                    is RulesLoad.Failed -> Text(familieErrorText(l.code), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    is RulesLoad.Loaded -> {
                        val vacation = l.payload.vacation
                        if (vacation != null && l.payload.can.house) {
                            VacationCard(vacation, busy = RulesListViewModel.VACATION in busy, onSet = viewModel::setVacation)
                        } else if (vacation?.on == true) {
                            GcBanner(tone = GcTone.Info, icon = GcIcons.Sun) {
                                Text(vacationText(vacation), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                        if (l.payload.open.isNotEmpty()) {
                            GcSectionLabel(stringResource(R.string.rules_open_today), Modifier.padding(start = 4.dp, top = 4.dp))
                            GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                                RuleLogic.openSorted(l.payload.open, myId).forEachIndexed { i, o ->
                                    if (i > 0) GcRowDivider()
                                    OpenRow(o, myId, nameOf, busy = "${o.ruleId}|${o.key}" in busy, onAction = { done -> viewModel.openAction(o, done) })
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            RuleFilter.entries.forEach { f ->
                                GcFilterChip(
                                    stringResource(
                                        when (f) {
                                            RuleFilter.ALL -> R.string.rules_filter_all
                                            RuleFilter.REMINDERS -> R.string.rules_filter_reminders
                                            RuleFilter.PRESENCE -> R.string.rules_filter_presence
                                            RuleFilter.PLACES -> R.string.rules_filter_places
                                        },
                                    ),
                                    filter == f,
                                    { viewModel.setFilter(f) },
                                )
                            }
                        }
                        val rules = RuleLogic.filter(l.payload.rules, filter)
                        if (rules.isEmpty()) Text(stringResource(R.string.rules_none), style = MaterialTheme.typography.bodyMedium, color = extra.muted)
                        rules.forEach { r -> RuleRow(r, options, myId, nameOf, onClick = { r.id?.let(onOpenRule) }, onActive = { viewModel.setActive(r, it) }) }
                        if (l.payload.log.isNotEmpty()) {
                            GcSectionLabel(stringResource(R.string.rules_log), Modifier.padding(start = 4.dp, top = 8.dp))
                            GcCard(contentPadding = PaddingValues(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                                l.payload.log.take(30).forEachIndexed { i, e ->
                                    if (i > 0) GcRowDivider()
                                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Text(shortTime(e.at) ?: "–", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.width(72.dp))
                                        Text(
                                            e.text ?: when {
                                                !e.fired -> stringResource(R.string.rules_log_not_fired, e.ruleName)
                                                !e.ok -> stringResource(R.string.rules_log_failed, e.ruleName)
                                                else -> stringResource(R.string.rules_log_fired, e.ruleName)
                                            },
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = if (e.ok) extra.muted else MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        val can = (load as? RulesLoad.Loaded)?.payload?.can
        if (can?.any == true) {
            val templates = RuleLogic.templates(options, can)
            GcPrimaryButton(
                text = stringResource(R.string.rules_new),
                onClick = { if (templates.isEmpty()) onNewRule(null) else picking = true },
                icon = GcIcons.Plus,
                modifier = Modifier.padding(16.dp),
            )
            if (picking) {
                TemplateDialog(templates, onPick = { t -> picking = false; onNewRule(t?.id) }, onDismiss = { picking = false })
            }
        }
    }
}

/** The „!“ of an open reminder (as on a device widget). */
@Composable
private fun OpenMark(modifier: Modifier = Modifier, small: Boolean = false) {
    val extra = GateControlTheme.extraColors
    Box(
        modifier.size(if (small) 14.dp else 22.dp).clip(CircleShape).background(extra.warn),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "!",
            style = (if (small) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelLarge).copy(fontWeight = FontWeight.Black),
            color = Color.White,
        )
    }
}

/**
 * An open reminder of today: since when (or until when snoozed), whose (a parent sees the
 * children's too, read only); one's own with „In 1 Std.“ and „Erledigt“.
 */
@Composable
private fun OpenRow(o: RuleOpen, myId: Int?, nameOf: (Int) -> String?, busy: Boolean, onAction: (done: Boolean) -> Unit) {
    val extra = GateControlTheme.extraColors
    val mine = o.isMine(myId)
    val whose = o.userId?.takeIf { !mine }?.let { nameOf(it) }
    val time = if (o.snoozed) shortTime(o.snoozeUntil)?.let { stringResource(R.string.rules_open_snoozed, it) } else shortTime(o.since)?.let { stringResource(R.string.rules_open_since, it) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OpenMark()
            Column(Modifier.weight(1f)) {
                Text(o.title.ifBlank { stringResource(R.string.rules_trigger_schedule) }, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                val line = listOfNotNull(whose, time).joinToString(" · ")
                if (line.isNotEmpty()) Text(line, style = MaterialTheme.typography.bodySmall, color = extra.muted)
            }
        }
        if (mine && o.key != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GcOutlineButton(
                    text = stringResource(R.string.rules_button_snooze),
                    onClick = { onAction(false) },
                    enabled = !busy && !o.snoozed,
                    modifier = Modifier.weight(1f),
                    icon = GcIcons.Clock,
                    minHeight = 40.dp,
                )
                GcPrimaryButton(
                    text = stringResource(R.string.rules_button_done),
                    onClick = { onAction(true) },
                    enabled = !busy,
                    loading = busy,
                    modifier = Modifier.weight(1f),
                    icon = GcIcons.Check,
                    minHeight = 40.dp,
                )
            }
        }
    }
}

@Composable
private fun vacationText(v: RuleVacation): String {
    val until = v.until?.let { FamilieFormat.parseDate(it) }?.let { FamilieFormat.day(it, ruleLocale()) }
    return if (until != null) stringResource(R.string.rules_vacation_on_until, until, v.paused) else stringResource(R.string.rules_vacation_on, v.paused)
}

/**
 * The holiday mode (parents): a switch, the last day (optional). On, every active house rule
 * rests; off — by hand or after the last day — exactly those run again.
 */
@Composable
private fun VacationCard(v: RuleVacation, busy: Boolean, onSet: (on: Boolean, until: String?) -> Unit) {
    val extra = GateControlTheme.extraColors
    var until by rememberSaveable(v.on, v.until) { mutableStateOf(v.until) }
    GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        GcSwitchRow(
            label = stringResource(R.string.rules_vacation),
            description = if (v.on) vacationText(v) else stringResource(R.string.rules_vacation_hint),
            checked = v.on,
            onCheckedChange = { on -> onSet(on, if (on) until else null) },
            icon = GcIcons.Sun,
            enabled = !busy,
        )
        FamilieDateField(
            label = stringResource(R.string.rules_vacation_until),
            value = until,
            onValueChange = { d ->
                until = d?.takeIf { it >= LocalDate.now().toString() }
                if (v.on) onSet(true, until)
            },
            clearable = true,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
        )
        if (!v.on && until == null) {
            Text(stringResource(R.string.rules_vacation_open_end), style = MaterialTheme.typography.bodySmall, color = extra.faint, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp))
        }
    }
}

/** „Neue Regel“: an empty rule or one of the templates that fit this household. */
@Composable
private fun TemplateDialog(templates: List<RuleTemplate>, onPick: (RuleTemplate?) -> Unit, onDismiss: () -> Unit) {
    val extra = GateControlTheme.extraColors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rules_new)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                Text(stringResource(R.string.rules_tpl_intro), style = MaterialTheme.typography.bodySmall, color = extra.muted, modifier = Modifier.padding(bottom = 8.dp))
                templates.forEach { t ->
                    GcListRow(
                        title = templateTitle(t),
                        description = templateHint(t),
                        onClick = { onPick(t) },
                        leading = {
                            GcIconSquare(background = extra.accentBg) { Icon(templateIcon(t), contentDescription = null, tint = extra.accentText, modifier = Modifier.size(20.dp)) }
                        },
                    )
                }
                GcRowDivider()
                GcListRow(
                    title = stringResource(R.string.rules_tpl_empty),
                    description = stringResource(R.string.rules_tpl_empty_hint),
                    onClick = { onPick(null) },
                    leading = {
                        GcIconSquare(background = extra.panel2) { Icon(GcIcons.Plus, contentDescription = null, tint = extra.muted, modifier = Modifier.size(20.dp)) }
                    },
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun RuleRow(r: Rule, options: RuleOptions, myId: Int?, nameOf: (Int) -> String?, onClick: () -> Unit, onActive: (Boolean) -> Unit) {
    val extra = GateControlTheme.extraColors
    GcCard(contentPadding = PaddingValues(0.dp)) {
        GcListRow(
            title = r.title.ifBlank { triggerLabel(r.trigger.type) },
            description = ruleSummary(r, options, myId, nameOf),
            onClick = onClick,
            leading = {
                GcIconSquare(background = if (r.active) extra.accentBg else extra.panel2) {
                    Icon(triggerIcon(r.trigger.type), contentDescription = null, tint = if (r.active) extra.accentText else extra.faint, modifier = Modifier.size(22.dp))
                }
            },
            trailing = { Box(contentAlignment = Alignment.Center) { GcSwitch(checked = r.active, onCheckedChange = if (r.pausable) onActive else null, enabled = r.pausable) } },
        )
    }
}
