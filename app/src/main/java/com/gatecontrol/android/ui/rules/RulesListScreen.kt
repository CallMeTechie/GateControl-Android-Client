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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import com.gatecontrol.android.familie.rules.RuleOptions
import com.gatecontrol.android.ui.familie.familieErrorMessage
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcChevron
import com.gatecontrol.android.ui.components.GcFilterChip
import com.gatecontrol.android.ui.components.GcIconSquare
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcListRow
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.components.GcRowDivider
import com.gatecontrol.android.ui.components.GcSectionLabel
import com.gatecontrol.android.ui.components.GcSubpageBar
import com.gatecontrol.android.ui.components.GcSwitch
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
                    stringResource(R.string.rules_summary_active, s.active),
                    stringResource(R.string.rules_summary_paused, s.paused).takeIf { s.paused > 0 },
                    shortTime(s.lastRunAt)?.let { stringResource(R.string.rules_summary_last, it) },
                ).joinToString(" · "),
                onClick = onOpen,
                leading = { Icon(GcIcons.Zap, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp)) },
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
    onNewRule: () -> Unit,
    viewModel: RulesListViewModel = hiltViewModel(),
) {
    val ctx = LocalContext.current
    val extra = GateControlTheme.extraColors
    val load by viewModel.load.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val nameOf = rememberNameOf()
    val myId = rememberMyFamilieId()
    val options by viewModel.options.collectAsStateWithLifecycle()
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
            GcPrimaryButton(text = stringResource(R.string.rules_new), onClick = onNewRule, icon = GcIcons.Plus, modifier = Modifier.padding(16.dp))
        }
    }
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
