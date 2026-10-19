package com.gatecontrol.android.ui.rules

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.familie.rules.CalendarCondition
import com.gatecontrol.android.familie.rules.DeviceCondition
import com.gatecontrol.android.familie.rules.Rule
import com.gatecontrol.android.familie.rules.RuleAction
import com.gatecontrol.android.familie.rules.RuleActionType
import com.gatecontrol.android.familie.rules.RuleButton
import com.gatecontrol.android.familie.rules.RuleLogic
import com.gatecontrol.android.familie.rules.RuleOp
import com.gatecontrol.android.familie.rules.RuleOptions
import com.gatecontrol.android.familie.rules.RuleRecipients
import com.gatecontrol.android.familie.rules.RuleScope
import com.gatecontrol.android.familie.rules.RuleTimeWindow
import com.gatecontrol.android.familie.rules.RuleTrigger
import com.gatecontrol.android.familie.rules.RuleTriggerType
import com.gatecontrol.android.ui.chat.OptionDialog
import com.gatecontrol.android.ui.chat.PickChip
import com.gatecontrol.android.ui.chat.TimeOnlyDialog
import com.gatecontrol.android.ui.familie.familieErrorMessage
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcChevron
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.components.GcSectionLabel
import com.gatecontrol.android.ui.components.GcSubpageBar
import com.gatecontrol.android.ui.components.GcSwitch
import com.gatecontrol.android.ui.components.GcSwitchRow
import com.gatecontrol.android.ui.components.GcTextField
import com.gatecontrol.android.ui.familie.FamilieConfirmDialog
import com.gatecontrol.android.ui.familie.familieErrorText
import com.gatecontrol.android.ui.theme.GateControlTheme
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** What the editor's dialog edits right now. */
private sealed interface EditorDialog {
    data object Trigger : EditorDialog
    data object AddCondition : EditorDialog
    data object AddAction : EditorDialog
    data object ScheduleTime : EditorDialog
    data object Time : EditorDialog
    data class Device(val index: Int?) : EditorDialog
    data object Calendar : EditorDialog
    data class Quick(val index: Int?) : EditorDialog
    data class Notify(val index: Int?) : EditorDialog
    data class Chat(val index: Int?) : EditorDialog
    data object Delete : EditorDialog
}

/**
 * The rule editor Wann → Nur wenn → Dann (mockups RuleEditor and
 * ReminderEditor). [actionId]: a new reminder for that device (long press on
 * a widget), personal. The dry run of a saved rule says whether it would
 * fire today.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RuleEditorScreen(
    ruleId: Int?,
    actionId: String?,
    personal: Boolean,
    onBack: () -> Unit,
    viewModel: RuleEditorViewModel = hiltViewModel(),
) {
    val ctx = LocalContext.current
    val extra = GateControlTheme.extraColors
    LaunchedEffect(ruleId, actionId, personal) { viewModel.load(ruleId, actionId, personal) }
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val options by viewModel.options.collectAsStateWithLifecycle()
    val can by viewModel.can.collectAsStateWithLifecycle()
    val load by viewModel.loadState.collectAsStateWithLifecycle()
    val saving by viewModel.saving.collectAsStateWithLifecycle()
    val test by viewModel.test.collectAsStateWithLifecycle()
    val nameOf = rememberNameOf()
    val myId = viewModel.myUserId
    var dialog by remember { mutableStateOf<EditorDialog?>(null) }
    val savedText = stringResource(R.string.rules_saved)
    LaunchedEffect(Unit) {
        viewModel.event.collect { e ->
            when (e) {
                RuleEditorEvent.Saved -> {
                    Toast.makeText(ctx, savedText, Toast.LENGTH_SHORT).show()
                    onBack()
                }
                RuleEditorEvent.Deleted -> onBack()
                is RuleEditorEvent.Error -> Toast.makeText(ctx, ruleErrorMessage(ctx, e.code, e.field), Toast.LENGTH_LONG).show()
            }
        }
    }
    val reminder = actionId != null
    val d = draft
    val title = when {
        d?.id != null -> d.title.ifBlank { triggerLabel(d.trigger.type) }
        reminder -> stringResource(R.string.rules_new_reminder)
        else -> stringResource(R.string.rules_new)
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        GcSubpageBar(title = title, onBack = onBack, backDescription = stringResource(R.string.common_back)) {
            if (d?.id != null) GcSwitch(checked = d.active, onCheckedChange = { on -> viewModel.edit { it.copy(active = on) } }, modifier = Modifier.padding(end = 8.dp), enabled = d.pausable && d.editable)
        }
        val device = options.device(actionId)
        if (reminder && device != null) {
            Text(stringResource(R.string.rules_for_device, device.label), style = MaterialTheme.typography.bodyMedium, color = extra.muted, modifier = Modifier.padding(start = 64.dp))
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                load is RulesLoad.Unsupported -> Text(stringResource(R.string.rules_unsupported), style = MaterialTheme.typography.bodyMedium, color = extra.muted)
                load is RulesLoad.Failed -> Text(familieErrorText((load as RulesLoad.Failed).code), color = MaterialTheme.colorScheme.error)
                d == null -> CircularProgressIndicator(Modifier.size(28.dp))
                else -> EditorContent(d, options, can.house, can.personal, myId, nameOf, test, reminder, viewModel, onDialog = { dialog = it })
            }
        }
        if (d != null) {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (d.id != null) GcOutlineButton(text = stringResource(R.string.rules_delete), onClick = { dialog = EditorDialog.Delete }, modifier = Modifier.weight(1f), enabled = !saving && d.editable)
                GcPrimaryButton(
                    text = stringResource(if (reminder) R.string.rules_save_reminder else R.string.rules_save),
                    onClick = viewModel::save,
                    enabled = RuleLogic.valid(d) && d.editable,
                    loading = saving,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
    val current = draft ?: return
    when (val dl = dialog) {
        EditorDialog.Trigger -> OptionDialog(
            stringResource(R.string.rules_when),
            options.triggers.filter { it in RuleTriggerType.ALL }.map { it to triggerLabel(it) },
            current.trigger.type,
            { type ->
                dialog = null
                viewModel.edit { r -> r.copy(trigger = defaultTrigger(type, r.trigger, options)) }
            },
            { dialog = null },
        )
        EditorDialog.ScheduleTime -> TimeOnlyDialog(
            msOf(current.trigger.time ?: "18:00"),
            onPick = { ms -> dialog = null; viewModel.edit { it.copy(trigger = it.trigger.copy(time = hhmm(ms))) } },
            onDismiss = { dialog = null },
        )
        EditorDialog.AddCondition -> OptionDialog(
            stringResource(R.string.rules_add_condition),
            listOfNotNull(
                "time" to stringResource(R.string.rules_cond_time),
                "sunset" to stringResource(R.string.rules_cond_sunset),
                ("device" to stringResource(R.string.rules_cond_device)).takeIf { options.devices.any { it.fields.isNotEmpty() } },
                "calendar" to stringResource(R.string.rules_cond_calendar),
            ),
            null,
            { kind ->
                dialog = when (kind) {
                    "time" -> EditorDialog.Time
                    "device" -> EditorDialog.Device(null)
                    "calendar" -> EditorDialog.Calendar
                    else -> {
                        viewModel.edit { it.copy(conditions = it.conditions.copy(afterSunset = true)) }
                        null
                    }
                }
            },
            { dialog = null },
        )
        EditorDialog.Time -> TimeWindowDialog(current.conditions.time, { w -> dialog = null; viewModel.edit { it.copy(conditions = it.conditions.copy(time = w)) } }, { dialog = null })
        is EditorDialog.Device -> DeviceConditionDialog(
            dl.index?.let { current.conditions.device.getOrNull(it) },
            options,
            onDone = { c ->
                dialog = null
                viewModel.edit { r ->
                    val list = r.conditions.device.toMutableList()
                    if (dl.index != null && dl.index in list.indices) list[dl.index] = c else list += c
                    r.copy(conditions = r.conditions.copy(device = list))
                }
            },
            onDismiss = { dialog = null },
        )
        EditorDialog.Calendar -> CalendarDialog(current.conditions.calendar, options, { c -> dialog = null; viewModel.edit { it.copy(conditions = it.conditions.copy(calendar = c)) } }, { dialog = null })
        EditorDialog.AddAction -> OptionDialog(
            stringResource(R.string.rules_add_action),
            listOfNotNull(
                ("quick" to stringResource(R.string.rules_action_kind_device)).takeIf { options.actions.isNotEmpty() || options.devices.isNotEmpty() },
                "notify" to stringResource(R.string.rules_action_kind_notify),
                ("chat" to stringResource(R.string.rules_action_kind_chat)).takeIf { options.messenger },
            ),
            null,
            { kind ->
                dialog = when (kind) {
                    "quick" -> EditorDialog.Quick(null)
                    "chat" -> EditorDialog.Chat(null)
                    else -> EditorDialog.Notify(null)
                }
            },
            { dialog = null },
        )
        is EditorDialog.Quick -> QuickActionDialog(dl.index?.let { current.actions.getOrNull(it) }, options, { a -> dialog = null; viewModel.edit { putAction(it, dl.index, a) } }, { dialog = null })
        is EditorDialog.Notify -> NotifyDialog(
            dl.index?.let { current.actions.getOrNull(it) },
            current,
            options,
            myId,
            nameOf,
            { a -> dialog = null; viewModel.edit { putAction(it, dl.index, a) } },
            { dialog = null },
        )
        is EditorDialog.Chat -> ChatActionDialog(dl.index?.let { current.actions.getOrNull(it) }, current.scope == RuleScope.PERSONAL, myId, { a -> dialog = null; viewModel.edit { putAction(it, dl.index, a) } }, { dialog = null })
        EditorDialog.Delete -> FamilieConfirmDialog(
            title = stringResource(R.string.rules_delete_title),
            text = stringResource(R.string.rules_delete_text, current.title),
            confirmLabel = stringResource(R.string.rules_delete),
            destructive = true,
            onConfirm = { dialog = null; viewModel.delete() },
            onDismiss = { dialog = null },
        )
        null -> Unit
    }
}

private fun putAction(r: Rule, index: Int?, a: RuleAction): Rule {
    val list = r.actions.toMutableList()
    if (index != null && index in list.indices) list[index] = a else list += a
    return r.copy(actions = list)
}

private fun defaultTrigger(type: String, old: RuleTrigger, options: RuleOptions): RuleTrigger = when (type) {
    old.type -> old
    RuleTriggerType.SCHEDULE -> RuleTrigger(type, weekdays = listOf(1, 2, 3, 4, 5), time = "07:00")
    in RuleTriggerType.PERSON -> RuleTrigger(type, userId = options.people.firstOrNull()?.userId)
    in RuleTriggerType.PLACE -> RuleTrigger(type, placeId = options.places.firstOrNull()?.id)
    RuleTriggerType.ALL_AWAY -> RuleTrigger(type, graceMinutes = 10)
    else -> RuleTrigger(type)
}

private fun msOf(time: String): Long {
    val t = runCatching { LocalTime.parse(time) }.getOrDefault(LocalTime.of(18, 0))
    return ZonedDateTime.now(ZoneId.systemDefault()).with(t).toInstant().toEpochMilli()
}

private fun hhmm(ms: Long): String {
    val t = java.time.Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
    return "%02d:%02d".format(t.hour, t.minute)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditorContent(
    d: Rule,
    options: RuleOptions,
    canHouse: Boolean,
    canPersonal: Boolean,
    myId: Int?,
    nameOf: (Int) -> String?,
    test: com.gatecontrol.android.familie.rules.RuleTestResult?,
    reminder: Boolean,
    vm: RuleEditorViewModel,
    onDialog: (EditorDialog) -> Unit,
) {
    val extra = GateControlTheme.extraColors
    val locale = ruleLocale()
    GcTextField(value = d.title, onValueChange = { t -> vm.edit { it.copy(title = t.take(80)) } }, label = stringResource(R.string.rules_field_title), placeholder = stringResource(R.string.rules_field_title_hint))
    if (canHouse && canPersonal) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PickChip(stringResource(R.string.rules_scope_house), d.scope == RuleScope.HOUSE) { vm.setScope(false) }
            PickChip(stringResource(R.string.rules_scope_personal), d.scope == RuleScope.PERSONAL) { vm.setScope(true) }
        }
    }

    Step(1, stringResource(R.string.rules_when)) {
        SelRow(triggerIcon(d.trigger.type), triggerLabel(d.trigger.type), null) { onDialog(EditorDialog.Trigger) }
        val t = d.trigger
        when (t.type) {
            RuleTriggerType.SCHEDULE -> {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    (1..7).forEach { day ->
                        val on = day in t.weekdays
                        DayChip(dayName(day, locale), on, Modifier.weight(1f)) {
                            vm.edit { r -> r.copy(trigger = r.trigger.copy(weekdays = if (on) r.trigger.weekdays - day else (r.trigger.weekdays + day).sorted())) }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.rules_at), style = MaterialTheme.typography.bodyLarge, color = extra.muted)
                    PickChip(t.time ?: "–", true) { onDialog(EditorDialog.ScheduleTime) }
                }
            }
            in RuleTriggerType.PERSON -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                options.people.forEach { p -> PickChip(p.name, t.userId == p.userId) { vm.edit { r -> r.copy(trigger = r.trigger.copy(userId = p.userId)) } } }
            }
            in RuleTriggerType.PLACE -> {
                if (options.places.isEmpty()) Text(stringResource(R.string.rules_no_places), style = MaterialTheme.typography.bodySmall, color = extra.muted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    options.places.forEach { p -> PickChip(p.name, t.placeId == p.id) { vm.edit { r -> r.copy(trigger = r.trigger.copy(placeId = p.id, placeName = p.name)) } } }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PickChip(stringResource(R.string.rules_anyone), t.userId == null) { vm.edit { r -> r.copy(trigger = r.trigger.copy(userId = null)) } }
                    options.people.forEach { p -> PickChip(p.name, t.userId == p.userId) { vm.edit { r -> r.copy(trigger = r.trigger.copy(userId = p.userId)) } } }
                }
            }
            RuleTriggerType.ALL_AWAY -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0, 5, 10, 30, 60).forEach { m ->
                    PickChip(if (m == 0) stringResource(R.string.rules_at_once) else stringResource(R.string.rules_after_min, m), (t.graceMinutes ?: 10) == m) {
                        vm.edit { r -> r.copy(trigger = r.trigger.copy(graceMinutes = m)) }
                    }
                }
            }
        }
        if (!reminder) Text(stringResource(R.string.rules_when_hint), style = MaterialTheme.typography.bodySmall, color = extra.muted)
    }

    Step(2, stringResource(R.string.rules_only_if)) {
        val c = d.conditions
        c.time?.let { w -> SelRow(GcIcons.Clock, stringResource(R.string.rules_cond_time_value, w.from, w.to), null, onRemove = { vm.edit { it.copy(conditions = it.conditions.copy(time = null)) } }) { onDialog(EditorDialog.Time) } }
        if (c.afterSunset) SelRow(GcIcons.Moon, stringResource(R.string.rules_cond_sunset), null, onRemove = { vm.edit { it.copy(conditions = it.conditions.copy(afterSunset = false)) } }) {}
        c.device.forEachIndexed { i, dc ->
            val field = options.device(dc.actionId)?.fields?.firstOrNull { it.id == dc.field }
            SelRow(
                GcIcons.Battery,
                deviceText(dc, options),
                field?.current?.let { stringResource(R.string.rules_current, it) },
                onRemove = { vm.edit { r -> r.copy(conditions = r.conditions.copy(device = r.conditions.device.filterIndexed { j, _ -> j != i })) } },
            ) { onDialog(EditorDialog.Device(i)) }
        }
        c.calendar?.let { cal ->
            SelRow(
                GcIcons.Calendar,
                calendarText(cal),
                cal.sources.mapNotNull { s -> options.calendars.firstOrNull { it.source == s }?.label }.joinToString(", ").takeIf { it.isNotEmpty() },
                onRemove = { vm.edit { it.copy(conditions = it.conditions.copy(calendar = null)) } },
            ) { onDialog(EditorDialog.Calendar) }
        }
        AddButton(stringResource(R.string.rules_add_condition_hint)) { onDialog(EditorDialog.AddCondition) }
    }

    Step(3, stringResource(R.string.rules_then)) {
        d.actions.forEachIndexed { i, a ->
            val sub = when {
                a.available == false -> stringResource(R.string.rules_action_unavailable)
                a.needsTarget -> stringResource(R.string.rules_action_needs_target)
                a.type == RuleActionType.NOTIFY && a.buttons.isNotEmpty() -> stringResource(R.string.rules_with_buttons, a.buttons.map { buttonLabel(it, options) }.joinToString(", "))
                else -> null
            }
            SelRow(
                when (a.type) {
                    RuleActionType.QUICK_ACTION -> GcIcons.Zap
                    RuleActionType.MESSENGER -> GcIcons.Share
                    else -> GcIcons.Bell
                },
                actionText(a, options, myId, nameOf),
                sub,
                onRemove = if (d.actions.size > 1) ({ vm.edit { r -> r.copy(actions = r.actions.filterIndexed { j, _ -> j != i }) } }) else null,
            ) {
                onDialog(
                    when (a.type) {
                        RuleActionType.QUICK_ACTION -> EditorDialog.Quick(i)
                        RuleActionType.MESSENGER -> EditorDialog.Chat(i)
                        else -> EditorDialog.Notify(i)
                    },
                )
            }
        }
        AddButton(stringResource(R.string.rules_add_action_hint)) { onDialog(EditorDialog.AddAction) }
    }

    if (test != null) {
        GcCard(color = if (test.wouldFire) extra.accentBg else extra.panel2, borderColor = androidx.compose.ui.graphics.Color.Transparent, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(if (test.wouldFire) R.string.rules_test_would else R.string.rules_test_would_not) +
                    test.details.filter { it.ok != null }.joinToString(" · ") { (if (it.ok == false) "✗ " else "") + it.text }.let { if (it.isNotEmpty()) ": $it" else "" },
                style = MaterialTheme.typography.bodyMedium,
                color = if (test.wouldFire) extra.accentText else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
    if (!d.editable) Text(stringResource(R.string.rules_read_only), style = MaterialTheme.typography.bodySmall, color = extra.warn)
    TextButton(onClick = vm::runTest, enabled = RuleLogic.valid(d) || d.id != null) { Text(stringResource(R.string.rules_test_run)) }
    Text(
        listOfNotNull(
            shortTime(d.lastRunAt)?.let { stringResource(R.string.rules_last_run, it) },
            stringResource(if (d.scope == RuleScope.PERSONAL) R.string.rules_rights_personal else R.string.rules_rights_house),
        ).joinToString(" "),
        style = MaterialTheme.typography.bodySmall,
        color = extra.muted,
    )
}

@Composable
private fun buttonLabel(b: String, options: RuleOptions): String = when {
    b == RuleButton.SNOOZE -> stringResource(R.string.rules_button_snooze)
    b == RuleButton.DONE -> stringResource(R.string.rules_button_done)
    b.startsWith(RuleButton.QUICK_PREFIX) -> b.removePrefix(RuleButton.QUICK_PREFIX).let { id -> options.action(id)?.title ?: options.device(id)?.label } ?: stringResource(R.string.rules_button_device)
    else -> b
}

@Composable
private fun Step(num: Int, title: String, content: @Composable ColumnScope.() -> Unit) {
    val extra = GateControlTheme.extraColors
    GcCard(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(26.dp).clip(CircleShape).background(extra.accentBg), contentAlignment = Alignment.Center) {
                Text(num.toString(), style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold), color = extra.accentText)
            }
            Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurface)
        }
        content()
    }
}

/** A chosen element (mockup `.sel`): icon, text, optional subtitle, chevron or remove. */
@Composable
private fun SelRow(icon: ImageVector, text: String, subtitle: String?, onRemove: (() -> Unit)? = null, onClick: () -> Unit) {
    val extra = GateControlTheme.extraColors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(14.dp)).background(extra.panel2)
            .clickable(role = Role.Button, onClick = onClick).padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = extra.accentText, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f)) {
            Text(text, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.onSurface)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = extra.muted) }
        }
        if (onRemove != null) GcIconButton(GcIcons.Close, stringResource(R.string.rules_remove), onClick = onRemove, size = 40.dp, iconSize = 18.dp) else GcChevron()
    }
}

@Composable
private fun AddButton(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Icon(GcIcons.Plus, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(" $text")
    }
}

@Composable
private fun DayChip(label: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val extra = GateControlTheme.extraColors
    Box(
        modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(12.dp)).background(if (on) scheme.primary else extra.panel2)
            .clickable(role = Role.Checkbox, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold), color = if (on) scheme.onPrimary else scheme.onSurface, maxLines = 1)
    }
}

// ─── dialogs ────────────────────────────────────────────────────────

@Composable
private fun EditDialog(title: String, ok: Boolean, onOk: () -> Unit, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp), content = content) },
        confirmButton = { TextButton(onClick = onOk, enabled = ok) { Text(stringResource(R.string.chat_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.chat_cancel)) } },
    )
}

@Composable
private fun TimeWindowDialog(initial: RuleTimeWindow?, onDone: (RuleTimeWindow) -> Unit, onDismiss: () -> Unit) {
    var from by remember { mutableStateOf(initial?.from ?: "06:00") }
    var to by remember { mutableStateOf(initial?.to ?: "22:00") }
    var pick by remember { mutableStateOf<String?>(null) }
    EditDialog(stringResource(R.string.rules_cond_time), from != to, { onDone(RuleTimeWindow(from, to)) }, onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.rules_from))
            PickChip(from, true) { pick = "from" }
            Text(stringResource(R.string.rules_to))
            PickChip(to, true) { pick = "to" }
        }
    }
    pick?.let { which ->
        TimeOnlyDialog(msOf(if (which == "from") from else to), onPick = { ms -> if (which == "from") from = hhmm(ms) else to = hhmm(ms); pick = null }, onDismiss = { pick = null })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeviceConditionDialog(initial: DeviceCondition?, options: RuleOptions, onDone: (DeviceCondition) -> Unit, onDismiss: () -> Unit) {
    val devices = options.devices.filter { it.fields.isNotEmpty() }
    var actionId by remember { mutableStateOf(initial?.actionId ?: devices.firstOrNull()?.actionId) }
    val dev = options.device(actionId)
    var field by remember(actionId) { mutableStateOf(initial?.field?.takeIf { f -> dev?.fields?.any { it.id == f } == true } ?: dev?.fields?.firstOrNull()?.id) }
    var op by remember { mutableStateOf(initial?.op ?: RuleOp.LT) }
    var value by remember { mutableStateOf(initial?.value?.let(RuleLogic::format) ?: "") }
    val number = value.replace(',', '.').toDoubleOrNull()
    EditDialog(stringResource(R.string.rules_cond_device), actionId != null && field != null && number != null, {
        val a = actionId
        val f = field
        if (a != null && f != null && number != null) onDone(DeviceCondition(a, f, op, number))
    }, onDismiss) {
        GcSectionLabel(stringResource(R.string.rules_device))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            devices.forEach { dv -> PickChip(dv.label, actionId == dv.actionId) { actionId = dv.actionId } }
        }
        dev?.let { dv ->
            GcSectionLabel(stringResource(R.string.rules_value))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                dv.fields.forEach { f -> PickChip(f.label + (f.current?.let { " · $it" } ?: ""), field == f.id) { field = f.id; if (value.isEmpty()) value = RuleLogic.number(f.current)?.let(RuleLogic::format).orEmpty() } }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            RuleOp.ALL.forEach { o -> PickChip(opText(o), op == o) { op = o } }
        }
        val unit = dev?.fields?.firstOrNull { it.id == field }?.unit
        GcTextField(
            value = value,
            onValueChange = { value = it.take(12) },
            label = stringResource(R.string.rules_limit) + (unit?.let { " ($it)" } ?: ""),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CalendarDialog(initial: CalendarCondition?, options: RuleOptions, onDone: (CalendarCondition) -> Unit, onDismiss: () -> Unit) {
    var day by remember { mutableStateOf(initial?.dayOffset ?: 1) }
    val sources = remember { mutableStateListOf<String>().apply { addAll(initial?.sources ?: options.calendars.map { it.source }) } }
    var match by remember { mutableStateOf(initial?.match.orEmpty()) }
    EditDialog(stringResource(R.string.rules_cond_calendar), true, { onDone(CalendarCondition(day, sources.toList(), match.trim(), initial?.memberIds.orEmpty())) }, onDismiss) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PickChip(stringResource(R.string.rules_today), day == 0) { day = 0 }
            PickChip(stringResource(R.string.rules_tomorrow), day == 1) { day = 1 }
        }
        if (options.calendars.isNotEmpty()) {
            GcSectionLabel(stringResource(R.string.rules_calendars))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                options.calendars.forEach { c ->
                    val on = c.source in sources
                    PickChip(c.label, on) { if (on) sources.remove(c.source) else sources.add(c.source) }
                }
            }
        }
        GcTextField(value = match, onValueChange = { match = it.take(80) }, label = stringResource(R.string.rules_title_contains), placeholder = stringResource(R.string.rules_title_contains_hint))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickActionDialog(initial: RuleAction?, options: RuleOptions, onDone: (RuleAction) -> Unit, onDismiss: () -> Unit) {
    // Familie 1.9 lists the quick actions (`actions`); older plugins only the devices.
    val choices = options.actions.map { Triple(it.actionId, it.title, it.kind) }.ifEmpty { options.devices.map { Triple(it.actionId, it.label, it.kind) } }
    var actionId by remember { mutableStateOf(initial?.actionId ?: choices.firstOrNull()?.first) }
    val kind = choices.firstOrNull { it.first == actionId }?.third ?: initial?.kind
    var state by remember { mutableStateOf(initial?.state ?: if (initial != null) "run" else "on") }
    EditDialog(stringResource(R.string.rules_action_kind_device), actionId != null, {
        val a = actionId ?: return@EditDialog
        val s = when (kind) {
            "button" -> null
            "toggle" -> state.takeIf { it != "run" } ?: "on"
            else -> state.takeIf { it != "run" }
        }
        onDone(RuleAction(RuleActionType.QUICK_ACTION, plugin = a.substringBefore('/'), id = a.substringAfter('/'), title = options.labelOf(a) ?: initial?.title, state = s, kind = kind))
    }, onDismiss) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            choices.forEach { (id, label, _) -> PickChip(label, actionId == id) { actionId = id } }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (kind != "button") {
                PickChip(stringResource(R.string.rules_state_on), state == "on") { state = "on" }
                PickChip(stringResource(R.string.rules_state_off), state == "off") { state = "off" }
            }
            if (kind != "toggle") PickChip(stringResource(R.string.rules_state_run), state == "run" || kind == "button") { state = "run" }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NotifyDialog(
    initial: RuleAction?,
    rule: Rule,
    options: RuleOptions,
    myId: Int?,
    nameOf: (Int) -> String?,
    onDone: (RuleAction) -> Unit,
    onDismiss: () -> Unit,
) {
    val personal = rule.scope == RuleScope.PERSONAL
    var action by remember {
        mutableStateOf(initial ?: RuleAction(RuleActionType.NOTIFY, to = if (personal) RuleRecipients(RuleRecipients.ME) else RuleRecipients(RuleRecipients.PARENTS)))
    }
    // The device of the rule (a condition or a switched device) offers its own button.
    val deviceId = rule.conditions.device.firstOrNull()?.actionId ?: rule.actions.firstNotNullOfOrNull { it.actionId }
    EditDialog(stringResource(R.string.rules_action_kind_notify), true, { onDone(action) }, onDismiss) {
        if (!personal) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PickChip(stringResource(R.string.rules_to_parents), action.to?.keyword == RuleRecipients.PARENTS) { action = action.copy(to = RuleRecipients(RuleRecipients.PARENTS)) }
                PickChip(stringResource(R.string.rules_to_all), action.to?.keyword == RuleRecipients.ALL) { action = action.copy(to = RuleRecipients(RuleRecipients.ALL)) }
                val mine = action.to?.keyword == RuleRecipients.ME || (myId != null && action.to?.userIds == listOf(myId))
                PickChip(stringResource(R.string.rules_to_me), mine) { action = action.copy(to = RuleRecipients(RuleRecipients.ME)) }
            }
        } else {
            Text(recipientsText(action.to, myId, nameOf), style = MaterialTheme.typography.bodyMedium)
        }
        GcTextField(value = action.text.orEmpty(), onValueChange = { t -> action = action.copy(text = t.take(500).ifEmpty { null }) }, label = stringResource(R.string.rules_text_optional), placeholder = stringResource(R.string.rules_text_hint))
        GcSwitchRow(stringResource(R.string.rules_facts), stringResource(R.string.rules_facts_desc), action.facts, { action = action.copy(facts = it) })
        // The device's own actions as one button to choose („Laden starten“), not the action its widget hangs on.
        val deviceActions = options.deviceActions(deviceId)
        if (deviceActions.isNotEmpty()) {
            Text(stringResource(R.string.rules_button_pick, options.device(deviceId)?.label ?: deviceActions.first().title), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                deviceActions.forEach { da ->
                    val b = RuleButton.quick(da.actionId)
                    PickChip(da.title, b in action.buttons) { action = RuleLogic.toggleButton(action, b, b !in action.buttons) }
                }
            }
        }
        GcSwitchRow(stringResource(R.string.rules_button_snooze_row), stringResource(R.string.rules_button_snooze_desc), RuleButton.SNOOZE in action.buttons, { on -> action = RuleLogic.toggleButton(action, RuleButton.SNOOZE, on) })
        GcSwitchRow(stringResource(R.string.rules_button_done_row), null, RuleButton.DONE in action.buttons, { on -> action = RuleLogic.toggleButton(action, RuleButton.DONE, on) })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChatActionDialog(initial: RuleAction?, personal: Boolean, myId: Int?, onDone: (RuleAction) -> Unit, onDismiss: () -> Unit) {
    val me = RuleRecipients(RuleRecipients.ME)
    var action by remember { mutableStateOf(initial ?: RuleAction(RuleActionType.MESSENGER, to = if (personal) me else RuleRecipients(RuleRecipients.FAMILY))) }
    EditDialog(stringResource(R.string.rules_action_kind_chat), true, { onDone(action) }, onDismiss) {
        if (!personal) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PickChip(stringResource(R.string.rules_to_family_chat), action.to?.keyword == RuleRecipients.FAMILY) { action = action.copy(to = RuleRecipients(RuleRecipients.FAMILY)) }
                PickChip(stringResource(R.string.rules_to_me), action.to == me || (myId != null && action.to?.userIds == listOf(myId))) { action = action.copy(to = me) }
            }
        } else {
            Text(stringResource(R.string.rules_chat_personal), style = MaterialTheme.typography.bodySmall, color = GateControlTheme.extraColors.muted)
        }
        GcTextField(value = action.text.orEmpty(), onValueChange = { t -> action = action.copy(text = t.take(500).ifEmpty { null }) }, label = stringResource(R.string.rules_text_optional), placeholder = stringResource(R.string.rules_chat_hint))
    }
}

/** Error codes of the rules API (Familie 1.9) as text; [field] where it applies. */
internal fun ruleErrorMessage(ctx: android.content.Context, code: String?, field: String?): String {
    val res = when (code) {
        "state_required" -> R.string.rules_err_state_required
        "too_many" -> R.string.rules_err_too_many
        "forbidden" -> if (field?.endsWith(".to") == true) R.string.rules_err_forbidden_to else R.string.rules_err_forbidden
        "module_off" -> if (field == "trigger") R.string.rules_err_module_off_trigger else null
        else -> null
    }
    return if (res != null) ctx.getString(res) else familieErrorMessage(ctx, code) + (field?.let { " ($it)" } ?: "")
}
