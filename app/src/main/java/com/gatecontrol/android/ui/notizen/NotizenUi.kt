package com.gatecontrol.android.ui.notizen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gatecontrol.android.R
import com.gatecontrol.android.notizen.NArea
import com.gatecontrol.android.notizen.NEntry
import com.gatecontrol.android.notizen.NItem
import com.gatecontrol.android.notizen.NotizenFormat
import com.gatecontrol.android.notizen.NTask
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.theme.GateControlTheme

/** The badge colours of the sources (mockup Main): Familie orange, Mail blue, Drive green, Schule violet, Alltag grey. */
@Composable
internal fun sourceColors(plugin: String): Pair<Color, Color> {
    val dark = GateControlTheme.extraColors.isDark
    val (bg, fg) = when (plugin) {
        "gatecontrol-familie" -> Color(0xFFFDE7C8) to Color(0xFF92400E)
        "gatecontrol-mail" -> Color(0xFFDBEAFE) to Color(0xFF1E40AF)
        "gatecontrol-drive" -> Color(0xFFDCEFE5) to Color(0xFF14603D)
        "gatecontrol-schule" -> Color(0xFFEDE9FE) to Color(0xFF5B21B6)
        else -> Color(0xFFE5E7EB) to Color(0xFF374151)
    }
    // dark mode: the same hue as a tint, the text light
    return if (dark) fg.copy(alpha = 0.32f) to bg else bg to fg
}

/** The colours of a note card (`yellow`, `green` …); null: the panel. */
@Composable
internal fun noteColor(color: String?): Color? {
    val dark = GateControlTheme.extraColors.isDark
    val c = when (color) {
        "yellow" -> Color(0xFFFEF3C7)
        "green" -> Color(0xFFDCFCE7)
        "blue" -> Color(0xFFDBEAFE)
        "pink" -> Color(0xFFFCE7F3)
        "grey" -> Color(0xFFE5E7EB)
        "orange" -> Color(0xFFFFEDD5)
        "purple" -> Color(0xFFEDE9FE)
        "red" -> Color(0xFFFEE2E2)
        "teal" -> Color(0xFFCCFBF1)
        else -> return null
    }
    return if (dark) c.copy(alpha = 0.16f) else c
}

/** The badge of an item's source: „Mail · Zu tun“, „Drive · Frist“, „Familie · Packliste“. */
@Composable
internal fun NSourceBadge(plugin: String, label: String, modifier: Modifier = Modifier) {
    val (bg, fg) = sourceColors(plugin)
    Box(
        modifier
            .heightIn(min = 22.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = fg, style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold), maxLines = 1)
    }
}

/** The kind word of an item after its source's name. */
@Composable
internal fun kindLabel(item: NItem): String? = when {
    item.meta["type"] == "shopping" -> stringResource(R.string.notizen_kind_shopping)
    item.meta["type"] == "packing" -> stringResource(R.string.notizen_kind_packing)
    item.kind == "deadline" -> stringResource(R.string.notizen_kind_deadline)
    item.kind == "homework" -> stringResource(R.string.notizen_kind_homework)
    item.kind == "exam" -> stringResource(R.string.notizen_kind_exam)
    item.plugin == "gatecontrol-mail" && item.meta["flagged"] == "true" -> stringResource(R.string.notizen_kind_flagged)
    item.plugin == "gatecontrol-mail" -> stringResource(R.string.notizen_kind_mail_todo)
    else -> null
}

@Composable
internal fun badgeText(item: NItem): String = listOfNotNull(item.sourceLabel, kindLabel(item)).joinToString(" · ")

/** The round check box of a row (24 dp, a 44 dp touch target). */
@Composable
internal fun NCheck(done: Boolean, label: String, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val x = GateControlTheme.extraColors
    val doneLabel = stringResource(R.string.notizen_done_state)
    val openLabel = stringResource(R.string.notizen_open_state)
    Box(
        modifier
            .size(44.dp)
            .clip(CircleShape)
            .then(
                if (onClick != null) {
                    Modifier.clickable(role = Role.Checkbox, onClick = onClick).semantics {
                        contentDescription = label
                        stateDescription = if (done) doneLabel else openLabel
                    }
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(if (done) scheme.primary else Color.Transparent)
                .border(2.dp, if (done) scheme.primary else x.faint, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (done) Icon(GcIcons.Check, contentDescription = null, tint = scheme.onPrimary, modifier = Modifier.size(16.dp))
        }
    }
}

/** What a row needs to know besides the entry. */
data class RowCtx(
    val today: String,
    val lang: String,
    /** list id → „Privat › Gesundheit“. */
    val listPath: (Long?) -> String?,
    val words: Triple<String, String, String>,
    /** The time first (a row of „Termine heute“). */
    val timeFirst: Boolean = false,
    /** The due day is written (the rows of „Demnächst“, „Geplant“, a list). */
    val showDay: Boolean = true,
    /** Plugin 1.3.0: the mark „im Kalender“ shows (Mail offers the calendar, the administrator and I have it on). */
    val calendar: Boolean = false,
)

@Composable
internal fun rowCtx(today: String, lang: String, listPath: (Long?) -> String?, showDay: Boolean = true, timeFirst: Boolean = false, calendar: Boolean = false) = RowCtx(
    today, lang, listPath,
    Triple(stringResource(R.string.notizen_today_word), stringResource(R.string.notizen_tomorrow_word), stringResource(R.string.notizen_yesterday_word)),
    timeFirst, showDay, calendar,
)

/** A little mark of a row with its sign: „Erinnerung“, „im Kalender“ (plugin 1.3.0, mockup „Am nächsten Tag in ‚Mein Tag‘“). */
@Composable
private fun NRowMark(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, lead: Boolean) {
    val x = GateControlTheme.extraColors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        if (lead) Text("·", style = MaterialTheme.typography.bodySmall, color = x.muted)
        Icon(icon, null, tint = x.muted, modifier = Modifier.size(12.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = x.muted)
    }
}

/**
 * A row of a list: a task of the hub or an item of another plugin (mockup Main / Listen). [showAssignee] (a shared
 * list): whom a task is assigned to as a little picture at the end — [onAssignee] lets one change it there.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NEntryRow(
    entry: NEntry,
    ctx: RowCtx,
    onToggle: (() -> Unit)?,
    onOpen: () -> Unit,
    divider: Boolean = true,
    showAssignee: Boolean = false,
    onAssignee: (() -> Unit)? = null,
) {
    val x = GateControlTheme.extraColors
    val scheme = MaterialTheme.colorScheme
    val item = entry as? NItem
    val task = entry as? NTask
    val due = item?.show ?: item?.due ?: task?.due
    val overdue = item?.overdue == true || task?.overdue == true
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable(role = Role.Button, onClick = onOpen)
                .padding(start = 4.dp, end = 14.dp, top = 4.dp, bottom = 6.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (ctx.timeFirst) {
                Text(
                    NotizenFormat.time(due?.time) ?: stringResource(R.string.notizen_all_day),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.width(56.dp).padding(start = 10.dp, top = 12.dp),
                )
            } else {
                val canTick = item?.canComplete != false && task?.canComplete != false
                NCheck(entry.done, stringResource(R.string.notizen_done_cd, entry.title), if (canTick) onToggle else null, Modifier.alpha(if (canTick && onToggle != null) 1f else 0.45f))
            }
            Column(Modifier.weight(1f).padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    entry.title,
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                    color = if (entry.done) x.faint else scheme.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                    val meta = mutableListOf<String>()
                    if (item != null) NSourceBadge(item.plugin, badgeText(item))
                    val dueText = if (ctx.timeFirst) null else NotizenFormat.due(due, ctx.today, ctx.lang, ctx.words)
                    if (dueText != null && (ctx.showDay || due?.time != null)) {
                        val text = if (!ctx.showDay && due?.date == ctx.today) NotizenFormat.time(due.time) ?: dueText else dueText
                        Text(text, style = MaterialTheme.typography.bodySmall, color = if (overdue) scheme.error else x.muted, fontWeight = if (overdue) FontWeight.SemiBold else null)
                    }
                    // plugin 1.3.0: a reminder still to come, the task's event in my calendar
                    val shown = dueText != null && (ctx.showDay || due?.time != null)
                    val reminder = task != null && !task.done && task.reminder != null
                    if (reminder) NRowMark(GcIcons.Bell, stringResource(R.string.notizen_mark_reminder), lead = shown)
                    val inCalendar = task != null && ctx.calendar && task.calendar?.synced == true && task.calendar.mine
                    if (inCalendar) NRowMark(GcIcons.Calendar, stringResource(R.string.notizen_mark_calendar), lead = shown || reminder)
                    if (item != null) {
                        item.subtitle?.let { meta.add(it) }
                        item.meta["child"]?.let { meta.add(it) }
                        item.assignee?.name?.takeIf { item.meta["child"] == null }?.let { meta.add(it) }
                        item.meta["stars"]?.toIntOrNull()?.let { meta.add(starsText(it)) }
                        item.meta["amount"]?.let { meta.add(it) }
                        item.progress?.let { meta.add("${it.done}/${it.total}") }
                    }
                    if (task != null) NSyncMark(task.pending, task.conflict)
                    if (task != null) {
                        ctx.listPath(task.listId)?.let { meta.add(it) }
                        task.progress?.takeIf { it.total > 0 }?.let { meta.add(stringResource(R.string.notizen_subtasks_n, it.done, it.total)) }
                        task.repeatText?.let { meta.add(stringResource(R.string.notizen_repeats, it.replaceFirstChar { c -> c.lowercase() })) }
                        if (!showAssignee) task.assignee?.name?.let { meta.add(it) }
                        if (task.attachments > 0) meta.add(stringResource(R.string.notizen_attachments_n, task.attachments))
                    }
                    if (meta.isNotEmpty()) {
                        Text(
                            (if (dueText != null || item != null || reminder || inCalendar) "· " else "") + meta.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = x.muted,
                        )
                    }
                }
            }
            if (entry.important) {
                Icon(GcIcons.StarFilled, contentDescription = stringResource(R.string.notizen_important), tint = Color(0xFFB45309), modifier = Modifier.padding(top = 12.dp).size(18.dp))
            }
            if (showAssignee && task != null) AssigneeChip(task.assignee, onAssignee)
        }
        if (divider) Box(Modifier.fillMaxWidth().height(1.dp).background(x.border))
    }
}

/** Whom a task is assigned to: the little picture and the first name; „+ Person“ when nobody (and it can be changed). */
@Composable
private fun AssigneeChip(who: com.gatecontrol.android.notizen.NPerson?, onClick: (() -> Unit)?) {
    val x = GateControlTheme.extraColors
    if (who == null && onClick == null) return
    val label = who?.name?.let { stringResource(R.string.notizen_assigned_to, it) } ?: stringResource(R.string.notizen_assign_person)
    Row(
        Modifier
            .padding(top = 8.dp)
            .heightIn(min = 32.dp)
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, x.border2, RoundedCornerShape(16.dp))
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClickLabel = label, onClick = onClick) else Modifier)
            .semantics { contentDescription = label }
            .padding(start = 4.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (who != null) {
            NAvatar(who.name, 22.dp)
            Text(who.name.orEmpty().substringBefore(' ').take(14), style = MaterialTheme.typography.labelMedium, maxLines = 1)
        } else {
            Icon(GcIcons.Plus, null, tint = x.muted, modifier = Modifier.padding(start = 4.dp).size(14.dp))
            Text(stringResource(R.string.notizen_person), style = MaterialTheme.typography.labelMedium, color = x.muted)
        }
    }
}

@Composable
private fun starsText(n: Int): String = if (n == 1) stringResource(R.string.notizen_star_one) else stringResource(R.string.notizen_stars_n, n)

/** A section title with its count (`.sec`): „Überfällig“ in red. */
@Composable
internal fun NSectionHeader(title: String, count: Int?, danger: Boolean = false, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold),
            color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        if (action != null && onAction != null) {
            Text(
                action,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onAction).padding(horizontal = 6.dp, vertical = 4.dp),
            )
        } else if (count != null) {
            Text(count.toString(), style = MaterialTheme.typography.labelMedium, color = GateControlTheme.extraColors.muted)
        }
    }
}

/** A card of rows (`.card`): 16 dp corners, hairline border, no inner padding. */
@Composable
internal fun NCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, GateControlTheme.extraColors.border),
    ) { Column(content = content) }
}

/** „Privat ⌄“ / „Arbeit ⌄“ (`Bereich wählen`). */
@Composable
internal fun NAreaPill(area: String, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = areaLabel(area)
    Box {
        Row(
            Modifier
                .heightIn(min = 40.dp)
                .clip(RoundedCornerShape(20.dp))
                .border(1.dp, GateControlTheme.extraColors.border2, RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surface)
                .clickable(role = Role.Button, onClickLabel = stringResource(R.string.notizen_area_choose, label)) { open = true }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(if (area == NArea.WORK) GcIcons.Briefcase else Icons.Outlined.Home, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
            Text(label, style = MaterialTheme.typography.titleSmall)
            Icon(GcIcons.ChevronDown, null, modifier = Modifier.size(14.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf(NArea.PRIVATE, NArea.WORK).forEach { a ->
                DropdownMenuItem(text = { Text(areaLabel(a)) }, onClick = { open = false; onChange(a) }, leadingIcon = if (a == area) { { Icon(GcIcons.Check, null) } } else null)
            }
        }
    }
}

@Composable
internal fun areaLabel(area: String): String = stringResource(if (area == NArea.WORK) R.string.notizen_area_work else R.string.notizen_area_private)

/**
 * „Offline – Stand von …“, what waits. [canEdit] (plugin 1.1.0): changing works offline too, it is sent later —
 * else changing waits for the server.
 */
@Composable
internal fun NOfflineHint(offline: Boolean, pending: Int, canEdit: Boolean = false) {
    if (!offline && pending == 0) return
    val x = GateControlTheme.extraColors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(12.dp)).background(x.warnBg).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(GcIcons.WifiOff, null, tint = x.warn, modifier = Modifier.size(18.dp))
        Text(
            listOfNotNull(
                if (offline) stringResource(if (canEdit) R.string.notizen_offline_hint_edit else R.string.notizen_offline_hint) else null,
                if (pending > 0) stringResource(R.string.notizen_pending_n, pending) else null,
            ).joinToString(" "),
            style = MaterialTheme.typography.bodySmall,
            color = x.warn,
        )
    }
}

/** A short text for a result code of the view models. */
@Composable
internal fun messageText(code: String): String = when (code) {
    "offline" -> stringResource(R.string.notizen_msg_offline)
    "offline_read", "offline_write" -> stringResource(R.string.notizen_msg_offline_write)
    "queued" -> stringResource(R.string.notizen_msg_queued)
    "gone" -> stringResource(R.string.notizen_msg_gone)
    "attach_offline" -> stringResource(R.string.notizen_msg_attach_offline)
    "line_offline" -> stringResource(R.string.notizen_msg_line_offline)
    "read_only", "login" -> stringResource(R.string.notizen_msg_read_only)
    "forbidden" -> stringResource(R.string.notizen_msg_forbidden)
    "not_found" -> stringResource(R.string.notizen_msg_not_found)
    "unavailable" -> stringResource(R.string.notizen_msg_unavailable)
    "too_large" -> stringResource(R.string.notizen_msg_too_large)
    "quota" -> stringResource(R.string.notizen_msg_quota)
    "busy" -> stringResource(R.string.notizen_msg_busy)
    "note_conflict", "conflict" -> stringResource(R.string.notizen_msg_conflict)
    "locked" -> stringResource(R.string.notizen_msg_locked)
    "encrypted" -> stringResource(R.string.notizen_msg_locked)
    "stale_key" -> stringResource(R.string.notizen_msg_stale_key)
    "vault_no_task" -> stringResource(R.string.notizen_msg_vault_no_task)
    "line_empty" -> stringResource(R.string.notizen_msg_line_empty)
    "line_task" -> stringResource(R.string.notizen_msg_line_task)
    "save_first" -> stringResource(R.string.notizen_msg_save_first)
    "task_saved" -> stringResource(R.string.notizen_msg_task_saved)
    "note_saved" -> stringResource(R.string.notizen_msg_note_saved)
    "list_created" -> stringResource(R.string.notizen_msg_list_created)
    "next" -> stringResource(R.string.notizen_msg_next)
    "sync_scheduled" -> stringResource(R.string.notizen_msg_sync_scheduled)
    "sync_recent" -> stringResource(R.string.notizen_msg_sync_recent)
    "consented" -> stringResource(R.string.notizen_msg_consented)
    "consent_failed" -> stringResource(R.string.notizen_msg_consent_failed)
    "consent_required" -> stringResource(R.string.notizen_msg_consent_required)
    "import_done" -> stringResource(R.string.notizen_msg_import_done)
    "import_failed" -> stringResource(R.string.notizen_msg_import_failed)
    "unlinked" -> stringResource(R.string.notizen_msg_unlinked)
    "dsnote_done" -> stringResource(R.string.notizen_msg_dsnote_done)
    "dsnote_password" -> stringResource(R.string.notizen_msg_dsnote_password)
    "approved" -> stringResource(R.string.notizen_msg_approved)
    "rotated" -> stringResource(R.string.notizen_msg_rotated)
    "recovery_key" -> stringResource(R.string.notizen_msg_recovery_key)
    "auth_required" -> stringResource(R.string.notizen_msg_auth)
    "no_screen_lock" -> stringResource(R.string.notizen_msg_no_lock)
    "decrypt" -> stringResource(R.string.notizen_msg_decrypt)
    "copied" -> stringResource(R.string.notizen_msg_copied)
    "not_here" -> stringResource(R.string.notizen_msg_not_here)
    "off" -> stringResource(R.string.notizen_msg_off)
    "owner" -> stringResource(R.string.notizen_msg_owner_only)
    "default" -> stringResource(R.string.notizen_msg_default)
    "bound" -> stringResource(R.string.notizen_nb_no_actions)
    "group_created" -> stringResource(R.string.notizen_msg_group_created)
    "group_dissolved" -> stringResource(R.string.notizen_msg_group_dissolved)
    "list_deleted" -> stringResource(R.string.notizen_msg_list_deleted)
    "list_left" -> stringResource(R.string.notizen_msg_list_left)
    "notebook_deleted" -> stringResource(R.string.notizen_msg_notebook_deleted)
    "notebook_left" -> stringResource(R.string.notizen_msg_notebook_left)
    "restored" -> stringResource(R.string.notizen_msg_restored)
    "saved" -> stringResource(R.string.notizen_msg_saved)
    else -> stringResource(R.string.notizen_msg_failed, code)
}

/** „Noch nicht gesendet“ (made or changed offline) or „Anderswo geändert“ (a change of this phone refused: open it). */
@Composable
internal fun NSyncMark(pending: Boolean, conflict: Boolean) {
    if (!pending && !conflict) return
    val x = GateControlTheme.extraColors
    val color = if (conflict) MaterialTheme.colorScheme.error else x.muted
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Icon(if (conflict) GcIcons.Alert else GcIcons.Clock, null, tint = color, modifier = Modifier.size(12.dp))
        Text(
            stringResource(if (conflict) R.string.notizen_sync_conflict else R.string.notizen_sync_pending),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = if (conflict) FontWeight.SemiBold else null,
        )
    }
}

@Composable
internal fun NSpacer(h: Int) = Spacer(Modifier.height(h.dp))

/** The icon of the tab (a ticked box, as the mockup's bar). */
internal val NotizenTabIcon = Icons.Outlined.CheckBox
