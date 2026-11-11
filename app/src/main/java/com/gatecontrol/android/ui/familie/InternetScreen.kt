package com.gatecontrol.android.ui.familie

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.familie.FamilieDest
import com.gatecontrol.android.familie.internet.InternetBy
import com.gatecontrol.android.familie.internet.InternetChild
import com.gatecontrol.android.familie.internet.InternetLogic
import com.gatecontrol.android.familie.internet.InternetPresence
import com.gatecontrol.android.familie.internet.InternetSchedule
import com.gatecontrol.android.familie.internet.InternetSnapshot
import com.gatecontrol.android.familie.internet.InternetState
import com.gatecontrol.android.familie.internet.PauseRequest
import com.gatecontrol.android.familie.presence.PresenceState
import com.gatecontrol.android.ui.chat.PickChip
import com.gatecontrol.android.ui.components.GcBanner
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcChevron
import com.gatecontrol.android.ui.components.GcChip
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcListRow
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.components.GcRowDivider
import com.gatecontrol.android.ui.components.GcSectionLabel
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.theme.GateControlTheme
import kotlinx.coroutines.delay
import java.time.ZoneId

/** What the page's dialog asks right now. */
private sealed interface InternetDialog {
    data class Pause(val childId: Int) : InternetDialog
    data class Extend(val childId: Int) : InternetDialog
}

/**
 * „Internet zu Hause“ (approved mockup, sections 1–3 and 6): one card per
 * child with its state (on, paused, longer today, not filtered, unreadable),
 * where the child is and how many devices it has at home. Parents get the
 * buttons of the state; a child sees its own card read only. A pause a rule
 * set names the rule ([onOpenRule] opens it).
 */
@Composable
fun InternetScreen(
    onBack: () -> Unit,
    onOpenRule: (Int) -> Unit = {},
    viewModel: InternetViewModel = hiltViewModel(),
) {
    val data by viewModel.data.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val working by viewModel.working.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<InternetDialog?>(null) }
    FamilieToasts(viewModel)

    FamiliePageFrame(
        title = stringResource(R.string.familie_internet_title),
        onBack = onBack,
        refreshing = busy && data != null,
        onRefresh = { viewModel.refresh() },
    ) {
        val d = data
        when {
            d == null && error != null -> FamilieErrorBanner(error, onRetry = { viewModel.refresh() })
            d == null -> FamilieLoading()
            !d.payload.available -> {
                if (error != null) FamilieErrorBanner(error)
                InternetUnavailable()
            }
            else -> {
                if (error != null) FamilieErrorBanner(error)
                val now = internetNow(d)
                val zone = InternetLogic.zone(d.payload.timezone)
                val manage = d.payload.manage
                if (d.payload.children.isEmpty()) {
                    FamilieEmpty(GcIcons.Globe, stringResource(R.string.familie_internet_none))
                }
                d.payload.children.forEach { child ->
                    InternetChildCard(
                        child = child,
                        state = InternetLogic.state(child, now),
                        manage = manage,
                        zone = zone,
                        enabled = working == null,
                        onButton = { b ->
                            when (b) {
                                InternetLogic.Button.PAUSE -> dialog = InternetDialog.Pause(child.userId)
                                InternetLogic.Button.EXTEND -> dialog = InternetDialog.Extend(child.userId)
                                InternetLogic.Button.RESUME -> viewModel.resume(child.userId)
                                InternetLogic.Button.ADD_PAUSE -> viewModel.pause(child.userId, PauseRequest.Add(InternetLogic.ADD_MINUTES))
                                InternetLogic.Button.ADD_EXTRA -> viewModel.extendMore(child.userId)
                                InternetLogic.Button.END_EXTRA -> viewModel.endExtra(child.userId)
                            }
                        },
                        onOpenRule = onOpenRule,
                    )
                }
                Text(
                    stringResource(if (manage) R.string.familie_internet_foot else R.string.familie_internet_foot_child),
                    style = MaterialTheme.typography.bodySmall,
                    color = GateControlTheme.extraColors.muted,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }

    val snapshot = data ?: return
    val zone = InternetLogic.zone(snapshot.payload.timezone)
    when (val dl = dialog) {
        is InternetDialog.Pause -> snapshot.payload.child(dl.childId)?.let { child ->
            InternetPauseDialog(
                child = child,
                nowMs = snapshot.nowMs(System.currentTimeMillis()),
                zone = zone,
                onConfirm = { request -> dialog = null; viewModel.pause(child.userId, request) },
                onDismiss = { dialog = null },
            )
        }
        is InternetDialog.Extend -> snapshot.payload.child(dl.childId)?.let { child ->
            InternetExtendDialog(
                child = child,
                nowMs = snapshot.nowMs(System.currentTimeMillis()),
                zone = zone,
                onConfirm = { minutes, fromNow -> dialog = null; viewModel.extend(child.userId, minutes, fromNow) },
                onDismiss = { dialog = null },
            )
        }
        null -> Unit
    }
}

/**
 * "Now" in the server's clock for [snapshot]: moves on every half minute
 * and right after a pause or an extra time ran out, so a card changes its
 * state without a reload.
 */
@Composable
internal fun internetNow(snapshot: InternetSnapshot): Long {
    var now by remember(snapshot) { mutableLongStateOf(snapshot.nowMs(System.currentTimeMillis())) }
    LaunchedEffect(snapshot) {
        while (true) {
            val next = InternetLogic.nextChangeMs(snapshot.payload.children, now)
            delay(if (next != null) (next - now + 500).coerceIn(500, 30_000) else 30_000)
            now = snapshot.nowMs(System.currentTimeMillis())
        }
    }
    return now
}

/** A server without a gateway that filters DNS in the home network. */
@Composable
internal fun InternetUnavailable() {
    GcBanner(tone = GcTone.Info, icon = GcIcons.Info) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                stringResource(R.string.familie_internet_unavailable_title),
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(stringResource(R.string.familie_internet_unavailable_text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun InternetChildCard(
    child: InternetChild,
    state: InternetState,
    manage: Boolean,
    zone: ZoneId,
    enabled: Boolean,
    onButton: (InternetLogic.Button) -> Unit,
    onOpenRule: (Int) -> Unit,
) {
    val extra = GateControlTheme.extraColors
    GcCard(
        borderColor = when (state) {
            InternetState.PAUSED -> extra.warn
            InternetState.EXTENDED -> extra.blue
            else -> extra.border
        },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FamilieAvatar(child.name, child.color, face = child.face, presence = child.presence?.state)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        child.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
                    InternetStateChip(child, state, zone, short = false)
                    internetPresenceText(child.presence)?.let { GcChip(it, tone = GcTone.Neutral, small = true) }
                }
                Text(internetCardText(child, state, manage, zone), style = MaterialTheme.typography.bodySmall, color = extra.muted)
            }
        }
        val by = child.pausedBy?.takeIf { state == InternetState.PAUSED && it.byRule }
        if (by != null) {
            GcBanner(tone = GcTone.Warn, icon = GcIcons.Zap) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(internetRuleLine(by, zone), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                    val ruleId = by.ruleId
                    if (manage && ruleId != null) {
                        Text(
                            stringResource(R.string.familie_internet_open_rule),
                            style = MaterialTheme.typography.labelLarge,
                            color = extra.accentText,
                            modifier = Modifier.clickable(role = Role.Button) { onOpenRule(ruleId) }.padding(vertical = 6.dp),
                        )
                    }
                }
            }
        }
        val buttons = InternetLogic.buttons(state, manage)
        if (buttons.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                buttons.forEach { b ->
                    val label = stringResource(
                        when (b) {
                            InternetLogic.Button.PAUSE -> R.string.familie_internet_pause
                            InternetLogic.Button.EXTEND -> R.string.familie_internet_extend
                            InternetLogic.Button.RESUME -> R.string.familie_internet_resume
                            InternetLogic.Button.ADD_PAUSE, InternetLogic.Button.ADD_EXTRA -> R.string.familie_internet_add_30
                            InternetLogic.Button.END_EXTRA -> R.string.familie_internet_end_extra
                        },
                    )
                    val description = when (b) {
                        InternetLogic.Button.ADD_PAUSE -> stringResource(R.string.familie_internet_add_pause_cd, child.name)
                        InternetLogic.Button.ADD_EXTRA -> stringResource(R.string.familie_internet_add_extra_cd, child.name)
                        else -> null
                    }
                    val modifier = Modifier.described(description)
                    if (b == InternetLogic.Button.RESUME) {
                        GcPrimaryButton(text = label, onClick = { onButton(b) }, enabled = enabled, icon = GcIcons.Play, fillWidth = false, minHeight = 44.dp, modifier = modifier)
                    } else {
                        GcOutlineButton(
                            text = label,
                            onClick = { onButton(b) },
                            enabled = enabled,
                            icon = when (b) {
                                InternetLogic.Button.PAUSE -> GcIcons.Pause
                                InternetLogic.Button.EXTEND -> GcIcons.Clock
                                else -> null
                            },
                            fillWidth = false,
                            minHeight = 44.dp,
                            modifier = modifier,
                        )
                    }
                }
            }
        }
    }
}

/** A spoken name for a button whose label alone ("+30 Min.") does not say what it adds to. */
private fun Modifier.described(description: String?): Modifier =
    if (description == null) this else semantics { contentDescription = description }

/** The state as a chip: "Internet an", "pausiert bis 18:30" ([short]: "Pause bis 18:30"), … */
@Composable
internal fun InternetStateChip(child: InternetChild, state: InternetState, zone: ZoneId, short: Boolean) {
    when (state) {
        InternetState.PAUSED -> GcChip(
            stringResource(if (short) R.string.familie_internet_pause_until else R.string.familie_internet_paused_until, InternetLogic.time(child.paused, zone) ?: "–"),
            tone = GcTone.Warn,
            small = true,
        )
        InternetState.EXTENDED -> GcChip(
            stringResource(if (short) R.string.familie_internet_longer_until else R.string.familie_internet_extended_until, InternetLogic.time(child.extended, zone) ?: "–"),
            tone = GcTone.Info,
            small = true,
        )
        InternetState.ON -> GcChip(stringResource(R.string.familie_internet_on), tone = GcTone.Ok, small = true)
        InternetState.UNFILTERED -> GcChip(stringResource(R.string.familie_internet_unfiltered), tone = GcTone.Neutral, small = true)
        // The card's text says it; the short row has no room for that.
        InternetState.ERROR -> if (short) GcChip(stringResource(R.string.familie_internet_unreadable_chip), tone = GcTone.Neutral, small = true)
    }
}

/** "zu Hause · WLAN", "zu Hause · App", "unterwegs"; null when the child does not share (nothing is shown). */
@Composable
internal fun internetPresenceText(presence: InternetPresence?): String? {
    val p = InternetLogic.shownPresence(presence) ?: return null
    return when {
        p.state == PresenceState.AWAY -> stringResource(R.string.familie_presence_away)
        p.source == InternetPresence.SOURCE_WIFI -> stringResource(R.string.familie_internet_home_wifi)
        p.source == InternetPresence.SOURCE_APP -> stringResource(R.string.familie_internet_home_app)
        else -> stringResource(R.string.familie_presence_home)
    }
}

/** "30 Min.", "1 Std.", "1 Std. 30 Min." */
@Composable
internal fun internetDuration(minutes: Int): String {
    val (h, m) = InternetLogic.hoursMinutes(minutes)
    return when {
        h == 0 -> stringResource(R.string.familie_internet_min, m)
        m == 0 -> stringResource(R.string.familie_internet_hours, h)
        else -> stringResource(R.string.familie_internet_hours, h) + " " + stringResource(R.string.familie_internet_min, m)
    }
}

/** "Soziale Netzwerke, Spiele und Videos" / "gesamtes Internet". */
@Composable
private fun scheduleBlocks(s: InternetSchedule): String {
    if (s.all || s.categories.isEmpty()) return stringResource(R.string.familie_internet_block_all)
    val names = s.categories.map {
        stringResource(
            when (it) {
                InternetSchedule.SOCIAL -> R.string.familie_internet_cat_social
                InternetSchedule.GAMES -> R.string.familie_internet_cat_games
                else -> R.string.familie_internet_cat_video
            },
        )
    }
    return if (names.size == 1) names[0] else stringResource(R.string.familie_internet_and, names.dropLast(1).joinToString(", "), names.last())
}

/** "Nächste Sperrzeit ab 21:00: …" / "Sperrzeit bis 06:00: …" / "Keine Sperrzeiten". */
@Composable
private fun scheduleLine(s: InternetSchedule?, zone: ZoneId): String = when {
    s == null -> stringResource(R.string.familie_internet_no_schedule)
    s.active -> InternetLogic.time(s.to, zone)?.let { stringResource(R.string.familie_internet_schedule_active, it, scheduleBlocks(s)) }
        ?: stringResource(R.string.familie_internet_schedule_active_open, scheduleBlocks(s))
    else -> stringResource(R.string.familie_internet_next_schedule, InternetLogic.time(s.from, zone) ?: "–", scheduleBlocks(s))
}

/** "von Marc um 16:50" / "von der Regel „Abendessen“ (Anna) um 17:30"; null when nobody of the family set it. */
@Composable
private fun byLine(by: InternetBy?, zone: ZoneId): String? {
    if (by == null || by.name.isBlank()) return null
    val at = InternetLogic.time(by.at, zone) ?: return stringResource(R.string.familie_internet_by, by.name)
    return when {
        !by.byRule -> stringResource(R.string.familie_internet_by_at, by.name, at)
        by.ruleTitle.isBlank() -> stringResource(R.string.familie_internet_by_rule_untitled_at, by.name, at)
        else -> stringResource(R.string.familie_internet_by_rule_at, by.ruleTitle, by.name, at)
    }
}

/** "Pausiert von der Regel „Abendessen“ · Anna · 17:30" (the title of a rule may be empty). */
@Composable
internal fun internetRuleLine(by: InternetBy, zone: ZoneId): String {
    val head = if (by.ruleTitle.isBlank()) stringResource(R.string.familie_internet_paused_by_rule_untitled) else stringResource(R.string.familie_internet_paused_by_rule, by.ruleTitle)
    return listOfNotNull(head, by.name.takeIf { it.isNotBlank() }, InternetLogic.time(by.at, zone)).joinToString(" · ")
}

/** The line under a child's name on the page. */
@Composable
private fun internetCardText(child: InternetChild, state: InternetState, manage: Boolean, zone: ZoneId): String {
    val devices = pluralStringResource(R.plurals.familie_internet_devices, child.devices, child.devices)
    return when (state) {
        InternetState.PAUSED -> {
            val base = pluralStringResource(
                if (manage) R.plurals.familie_internet_paused_text else R.plurals.familie_internet_paused_text_child,
                child.devices,
                child.devices,
            )
            // A rule's pause has its own notice below.
            val by = child.pausedBy?.takeIf { !it.byRule }?.let { byLine(it, zone) }
            if (by != null) base + " " + by.replaceFirstChar { it.titlecase(appLocale()) } + "." else base
        }
        InternetState.EXTENDED -> listOfNotNull(
            child.nextSchedule?.let { s -> InternetLogic.time(s.from, zone)?.let { stringResource(R.string.familie_internet_schedule_suspended, it) } }
                ?: stringResource(R.string.familie_internet_schedules_suspended),
            byLine(child.extendedBy, zone),
            devices,
            if (manage && child.presence?.state == PresenceState.AWAY) stringResource(R.string.familie_internet_extra_when_home, child.name) else null,
        ).joinToString(" · ")
        InternetState.ERROR -> stringResource(R.string.familie_internet_unreadable)
        InternetState.ON -> scheduleLine(child.nextSchedule, zone) + " · " + devices
        InternetState.UNFILTERED -> stringResource(if (child.devices == 0) R.string.familie_internet_no_devices_text else R.string.familie_internet_no_filter_text)
    }
}

// ─── card on the Familie start screen ───────────────────────────────

/**
 * The card „Internet zu Hause“ of the start screen: for parents one row per
 * child with where it is and the state as a chip; for a child one row with
 * its own state ("Pause bis 18:30 · Regel „Abendessen“ · noch 38 Min.").
 */
@Composable
internal fun InternetHomeCard(snapshot: InternetSnapshot, selected: Boolean, onOpen: (FamilieDest) -> Unit) {
    val now = internetNow(snapshot)
    val zone = InternetLogic.zone(snapshot.payload.timezone)
    FamilieSectionCard(
        title = stringResource(R.string.familie_internet_title),
        icon = GcIcons.Globe,
        link = stringResource(R.string.familie_open),
        onLink = { onOpen(FamilieDest.Internet) },
        selected = selected,
    ) {
        snapshot.payload.children.forEachIndexed { i, child ->
            if (i > 0) GcRowDivider()
            val state = InternetLogic.state(child, now)
            if (snapshot.payload.manage) {
                GcListRow(
                    title = child.name,
                    description = internetPresenceText(child.presence)
                        ?: if (state == InternetState.UNFILTERED && child.devices == 0) stringResource(R.string.familie_internet_no_devices) else null,
                    onClick = { onOpen(FamilieDest.Internet) },
                    leading = { FamilieAvatar(child.name, child.color, size = 36.dp, face = child.face, presence = child.presence?.state) },
                    trailing = { InternetStateChip(child, state, zone, short = true) },
                )
            } else {
                OwnInternetRow(child, state, now, zone) { onOpen(FamilieDest.Internet) }
            }
        }
    }
}

/** A child's own row: the state as the title, who set it and how long it lasts below. */
@Composable
private fun OwnInternetRow(child: InternetChild, state: InternetState, now: Long, zone: ZoneId, onClick: () -> Unit) {
    val title: String
    val description: String?
    val chip: Pair<String, GcTone>?
    when (state) {
        InternetState.PAUSED -> {
            title = stringResource(R.string.familie_internet_pause_until, InternetLogic.time(child.paused, zone) ?: "–")
            description = listOfNotNull(ownBy(child.pausedBy), leftText(child.paused, now)).joinToString(" · ")
            chip = stringResource(R.string.familie_internet_chip_paused) to GcTone.Warn
        }
        InternetState.EXTENDED -> {
            title = stringResource(R.string.familie_internet_extended_title, InternetLogic.time(child.extended, zone) ?: "–")
            description = listOfNotNull(ownBy(child.extendedBy), leftText(child.extended, now)).joinToString(" · ")
            chip = stringResource(R.string.familie_internet_chip_extra) to GcTone.Info
        }
        InternetState.ON -> {
            title = stringResource(R.string.familie_internet_on)
            description = scheduleLine(child.nextSchedule, zone)
            chip = null
        }
        InternetState.UNFILTERED -> {
            title = stringResource(R.string.familie_internet_unfiltered_title)
            description = stringResource(R.string.familie_internet_no_devices)
            chip = null
        }
        InternetState.ERROR -> {
            title = stringResource(R.string.familie_internet_title)
            description = stringResource(R.string.familie_internet_unreadable)
            chip = null
        }
    }
    GcListRow(
        title = title,
        description = description,
        onClick = onClick,
        leading = { FamilieAvatar(child.name, child.color, size = 36.dp, face = child.face, presence = child.presence?.state) },
        trailing = { if (chip != null) GcChip(chip.first, tone = chip.second, small = true) else GcChevron() },
    )
}

/** "Regel „Abendessen“" / "Regel" / "Anna". */
@Composable
private fun ownBy(by: InternetBy?): String? = when {
    by == null -> null
    by.byRule && by.ruleTitle.isNotBlank() -> stringResource(R.string.familie_internet_rule_named, by.ruleTitle)
    by.byRule -> stringResource(R.string.familie_internet_rule)
    else -> by.name.takeIf { it.isNotBlank() }
}

/** "noch 38 Min." */
@Composable
private fun leftText(until: String?, now: Long): String? =
    InternetLogic.minutesLeft(until, now).takeIf { it > 0 }?.let { stringResource(R.string.familie_internet_left, internetDuration(it)) }

// ─── dialogs ────────────────────────────────────────────────────────

/** Dialog of the page in the look of the Familie dialogs: title, choices, confirm / cancel. */
@Composable
private fun InternetChoiceDialog(
    title: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.extraLarge,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp), content = content) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

/** "Pause für Lea": 30 Min., 1 Std., 2 Std. or until a time of day (the next such time in the family's zone). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun InternetPauseDialog(child: InternetChild, nowMs: Long, zone: ZoneId, onConfirm: (PauseRequest) -> Unit, onDismiss: () -> Unit) {
    // 0: until a time of day.
    var minutes by rememberSaveable { mutableIntStateOf(InternetLogic.PAUSE_MINUTES.first()) }
    var until by rememberSaveable { mutableStateOf(InternetLogic.defaultUntil(nowMs, zone)) }
    val extra = GateControlTheme.extraColors
    InternetChoiceDialog(
        title = stringResource(R.string.familie_internet_pause_for, child.name),
        confirmLabel = stringResource(R.string.familie_internet_pause),
        onConfirm = { onConfirm(if (minutes > 0) PauseRequest.Minutes(minutes) else PauseRequest.Until(until)) },
        onDismiss = onDismiss,
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            InternetLogic.PAUSE_MINUTES.forEach { m -> PickChip(internetDuration(m), minutes == m) { minutes = m } }
            PickChip(stringResource(R.string.familie_internet_until_time), minutes == 0) { minutes = 0 }
        }
        if (minutes == 0) {
            FamilieTimeField(
                label = stringResource(R.string.familie_internet_pause_until_label),
                value = until,
                onValueChange = { t -> InternetLogic.hhmm(t)?.let { until = it } },
                clearable = false,
            )
        }
        val hint = when {
            minutes > 0 -> stringResource(R.string.familie_internet_until_hint, InternetLogic.time(nowMs + minutes * 60_000L, zone))
            InternetLogic.untilIsTomorrow(until, nowMs, zone) -> stringResource(R.string.familie_internet_until_hint_tomorrow, until)
            else -> stringResource(R.string.familie_internet_until_hint_today, until)
        }
        Text(hint + " " + stringResource(R.string.familie_internet_child_notified, child.name), style = MaterialTheme.typography.bodySmall, color = extra.muted)
    }
}

/**
 * "Heute länger für Lea": 15 Min., 30 Min. or 1 Std. — counted from the
 * start of the next blocked time when that one still comes today, or from
 * now. Without a blocked time today the choice is gone and it starts now.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun InternetExtendDialog(child: InternetChild, nowMs: Long, zone: ZoneId, onConfirm: (Int, Boolean) -> Unit, onDismiss: () -> Unit) {
    var minutes by rememberSaveable { mutableIntStateOf(InternetLogic.EXTEND_MINUTES[1]) }
    var fromNow by rememberSaveable { mutableStateOf(false) }
    val scheduleStart = InternetLogic.extendFromSchedule(child, nowMs, zone)
    val extra = GateControlTheme.extraColors
    InternetChoiceDialog(
        title = stringResource(R.string.familie_internet_extend_for, child.name),
        confirmLabel = stringResource(R.string.familie_internet_allow),
        onConfirm = { onConfirm(minutes, fromNow && scheduleStart != null) },
        onDismiss = onDismiss,
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            InternetLogic.EXTEND_MINUTES.forEach { m -> PickChip(internetDuration(m), minutes == m) { minutes = m } }
        }
        if (scheduleStart != null) {
            GcSectionLabel(stringResource(R.string.familie_internet_counts_from))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PickChip(stringResource(R.string.familie_internet_from_schedule, InternetLogic.time(scheduleStart, zone)), !fromNow) { fromNow = false }
                PickChip(stringResource(R.string.familie_internet_from_now), fromNow) { fromNow = true }
            }
        }
        val end = InternetLogic.time(InternetLogic.extendEnd(minutes, fromNow, scheduleStart, nowMs), zone)
        val hint = if (scheduleStart != null && !fromNow) stringResource(R.string.familie_internet_extend_hint_schedule, end) else stringResource(R.string.familie_internet_extend_hint_now, end)
        Text(hint + " " + stringResource(R.string.familie_internet_child_notified, child.name), style = MaterialTheme.typography.bodySmall, color = extra.muted)
    }
}
