package com.gatecontrol.android.ui.notizen

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.notizen.NArea
import com.gatecontrol.android.notizen.NSourcePrefs
import com.gatecontrol.android.notizen.NSourceStatus
import com.gatecontrol.android.notizen.NotizenFormat
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.components.GcSubpageBar
import com.gatecontrol.android.ui.components.GcSwitch
import com.gatecontrol.android.ui.components.GcTextField
import com.gatecontrol.android.ui.theme.GateControlTheme
import com.gatecontrol.android.ui.theme.MonoFontFamily

@Composable
private fun Messages(code: String?, snack: SnackbarHostState, consume: () -> Unit) {
    val text = code?.let { messageText(it) }
    LaunchedEffect(code) { if (text != null) { snack.showSnackbar(text); consume() } }
}

/** A choice of a few values (`label … value ⌄`). */
@Composable
private fun <T> ChoiceRow(label: String, value: T, options: List<Pair<T, String>>, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        DetailRow(GcIcons.Sliders, label, options.firstOrNull { it.first == value }?.second.orEmpty(), { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (v, l) -> DropdownMenuItem(text = { Text(l) }, onClick = { open = false; onPick(v) }) }
        }
    }
}

// ── Einstellungen (mockup „Einstellungen statt Quellen“) ─────

/**
 * The gear at the bottom left of the side menu: what used to be „Quellen“ (now „Aufgaben aus anderen Plugins“), and
 * — with plugin 1.2.0 — „Mein Tag“, the times of the quick entry, the pushes; „Umziehen“ moved here as well.
 */
@Composable
fun NotizenSourcesScreen(nav: NotizenNav, vm: NotizenSourcesViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    Messages(message, snack, vm::consumeMessage)
    val x = GateControlTheme.extraColors
    var time by remember { mutableStateOf<String?>(null) }
    /** A word of the quick entry whose time is being picked (morning, noon …). */
    var word by remember { mutableStateOf<String?>(null) }
    val me = ui.me
    val v12 = me?.has(com.gatecontrol.android.notizen.NFeature.LIST_DAYS) == true
    val canWrite = me?.readOnly != true
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize()) {
            GcSubpageBar(stringResource(R.string.notizen_settings), nav.back, stringResource(R.string.notizen_back))
            LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
                item { Text(stringResource(R.string.notizen_settings_intro), style = MaterialTheme.typography.bodyMedium, color = x.muted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
                // ── Aufgaben aus anderen Plugins ──
                item { NSectionHeader(stringResource(R.string.notizen_settings_sources), null) }
                item { Text(stringResource(R.string.notizen_sources_intro), style = MaterialTheme.typography.bodySmall, color = x.muted, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp)) }
                if (ui.loading) item { NotizenLoading() }
                ui.sources.forEach { s ->
                    item(key = s.plugin) {
                        SourceCard(s, ui.prefs.sources[s.plugin] ?: NSourcePrefs(), ui.prefs.groups, ui.prefs.evening, vm, onTime = { time = s.plugin })
                    }
                }
                if (!ui.loading && ui.sources.isEmpty()) item { Text(stringResource(R.string.notizen_sources_none), style = MaterialTheme.typography.bodySmall, color = x.muted, modifier = Modifier.padding(horizontal = 16.dp)) }
                // ── Mein Tag (1.2.0) ──
                if (v12) {
                    item { NSectionHeader(stringResource(R.string.notizen_seg_day), null) }
                    item {
                        NCard {
                            SettingSwitch(stringResource(R.string.notizen_set_listdays), stringResource(R.string.notizen_set_listdays_hint), ui.prefs.myday.listdays, canWrite) { vm.setMyDay("listdays", it) }
                            Box(Modifier.fillMaxWidth().height(1.dp).background(x.border))
                            SettingSwitch(stringResource(R.string.notizen_set_show_done), null, ui.prefs.myday.showDone, canWrite) { vm.setMyDay("showDone", it) }
                            Box(Modifier.fillMaxWidth().height(1.dp).background(x.border))
                            ChoiceRow(
                                stringResource(R.string.notizen_set_start_area),
                                ui.prefs.startArea,
                                listOf("last" to stringResource(R.string.notizen_set_start_last), NArea.PRIVATE to stringResource(R.string.notizen_area_private), NArea.WORK to stringResource(R.string.notizen_area_work)),
                            ) { vm.setStartArea(it) }
                        }
                    }
                }
                // ── Uhrzeiten für die Schnell-Eingabe ──
                item { NSectionHeader(stringResource(R.string.notizen_set_times), null) }
                item { Text(stringResource(R.string.notizen_set_times_hint), style = MaterialTheme.typography.bodySmall, color = x.muted, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp)) }
                item {
                    NCard {
                        val words = listOf("morning" to R.string.notizen_time_morning, "noon" to R.string.notizen_time_noon, "afternoon" to R.string.notizen_time_afternoon, "evening" to R.string.notizen_time_evening, "night" to R.string.notizen_time_night)
                        words.forEach { (k, label) ->
                            val value = ui.prefs.times[k] ?: com.gatecontrol.android.notizen.QuickParse.DEFAULT_TIMES[k]
                            DetailRow(GcIcons.Clock, stringResource(label), NotizenFormat.time(value).orEmpty(), if (canWrite) ({ word = k }) else null)
                        }
                    }
                }
                // ── Benachrichtigungen (1.2.0) ──
                if (v12) {
                    item { NSectionHeader(stringResource(R.string.notizen_set_notify), null) }
                    item {
                        NCard {
                            SettingSwitch(stringResource(R.string.notizen_set_notify_reminders), null, ui.prefs.notify.reminders, canWrite) { vm.setNotify("reminders", it) }
                            Box(Modifier.fillMaxWidth().height(1.dp).background(x.border))
                            SettingSwitch(stringResource(R.string.notizen_set_notify_shared), null, ui.prefs.notify.shared, canWrite) { vm.setNotify("shared", it) }
                            Box(Modifier.fillMaxWidth().height(1.dp).background(x.border))
                            SettingSwitch(stringResource(R.string.notizen_set_notify_listday), null, ui.prefs.notify.listday, canWrite) { vm.setNotify("listday", it) }
                        }
                    }
                    item { Text(stringResource(R.string.notizen_set_notify_hint), style = MaterialTheme.typography.bodySmall, color = x.faint, modifier = Modifier.padding(16.dp)) }
                }
                // ── Umziehen ──
                if (me?.imports?.let { it.mstodo || it.dsnote } == true && canWrite) {
                    item { NSectionHeader(stringResource(R.string.notizen_import), null) }
                    item {
                        NCard {
                            if (me.imports.mstodo) DetailRow(GcIcons.Download, stringResource(R.string.notizen_set_from_mstodo), "", nav.import, trailing = { Icon(GcIcons.ChevronRight, null, tint = x.muted) })
                            if (me.imports.dsnote) DetailRow(GcIcons.FolderMove, stringResource(R.string.notizen_set_from_dsnote), "", nav.import, trailing = { Icon(GcIcons.ChevronRight, null, tint = x.muted) })
                        }
                    }
                }
                item { Text(stringResource(R.string.notizen_sources_later), style = MaterialTheme.typography.bodySmall, color = x.faint, modifier = Modifier.padding(16.dp)) }
            }
        }
        SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp)) { Snackbar(it) }
    }
    time?.let { plugin ->
        val cur = ui.prefs.sources[plugin]?.evening ?: ui.prefs.evening
        NTimePicker(cur, { t -> time = null; if (t != null) vm.setSource(plugin, NSourcePrefs(evening = t)) }, { time = null })
    }
    word?.let { k ->
        val cur = ui.prefs.times[k] ?: com.gatecontrol.android.notizen.QuickParse.DEFAULT_TIMES[k]
        NTimePicker(cur, { t -> word = null; if (t != null) vm.setTime(k, t) }, { word = null })
    }
}

/** A switch of the settings with its text (the whole row switches). */
@Composable
private fun SettingSwitch(title: String, hint: String?, value: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = value, enabled = enabled, role = androidx.compose.ui.semantics.Role.Switch, onValueChange = onChange)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = GateControlTheme.extraColors.muted) }
        }
        GcSwitch(value, null, enabled = enabled)
    }
}

@Composable
private fun SourceCard(s: NSourceStatus, p: NSourcePrefs, groups: Map<String, String>, evening: String, vm: NotizenSourcesViewModel, onTime: () -> Unit) {
    val x = GateControlTheme.extraColors
    val on = p.on != false && s.enabled
    Column(Modifier.padding(bottom = 12.dp)) {
        NCard {
            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NSourceBadge(s.plugin, s.label)
                Column(Modifier.weight(1f)) {
                    Text(s.hint.orEmpty(), style = MaterialTheme.typography.bodySmall, color = x.muted)
                    val status = when {
                        !s.enabled -> stringResource(R.string.notizen_source_off_admin)
                        !s.available -> stringResource(R.string.notizen_source_missing)
                        s.error != null -> stringResource(R.string.notizen_source_error, s.error)
                        else -> null
                    }
                    status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                }
                GcSwitch(on, if (s.enabled) { v -> vm.setSource(s.plugin, NSourcePrefs(on = v)) } else null, enabled = s.enabled)
            }
            if (on && s.available) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(x.border))
                when (s.plugin) {
                    "gatecontrol-familie" -> {
                        ChoiceRow(stringResource(R.string.notizen_src_kids), p.kids ?: "all", listOf("all" to stringResource(R.string.notizen_src_kids_all), "mine" to stringResource(R.string.notizen_src_kids_mine), "none" to stringResource(R.string.notizen_src_kids_none))) {
                            vm.setSource(s.plugin, NSourcePrefs(kids = it))
                        }
                        DetailRow(GcIcons.Cart, stringResource(R.string.notizen_src_shopping), "", null) { GcSwitch(p.shoppingInMyDay == true, { vm.setSource(s.plugin, NSourcePrefs(shoppingInMyDay = it)) }) }
                    }
                    "gatecontrol-mail" -> DetailRow(GcIcons.Flag, stringResource(R.string.notizen_src_flagged), "", null) { GcSwitch(p.flagged != false, { vm.setSource(s.plugin, NSourcePrefs(flagged = it)) }) }
                    "gatecontrol-alltag" -> DetailRow(GcIcons.Clock, stringResource(R.string.notizen_src_evening), NotizenFormat.time(p.evening ?: evening).orEmpty(), onTime)
                }
                s.groups.forEach { g ->
                    val key = s.plugin + "|" + g.id
                    ChoiceRow(
                        g.label,
                        groups[key] ?: "",
                        listOf("" to stringResource(R.string.notizen_group_as_source), NArea.PRIVATE to stringResource(R.string.notizen_group_to, stringResource(R.string.notizen_area_private)), NArea.WORK to stringResource(R.string.notizen_group_to, stringResource(R.string.notizen_area_work)), "hide" to stringResource(R.string.notizen_group_hide)),
                    ) { vm.setGroup(key, it.ifEmpty { null }) }
                }
                DetailRow(GcIcons.Refresh, stringResource(R.string.notizen_src_sync), s.lastSync?.let { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(it)) }.orEmpty(), { vm.sync(s.plugin) })
            }
        }
    }
}

// ── Umziehen (mockup Import) ────────────────────────────────

@Composable
fun NotizenImportScreen(nav: NotizenNav, vm: NotizenImportViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    Messages(message, snack, vm::consumeMessage)
    val context = LocalContext.current
    val x = GateControlTheme.extraColors
    val lang = ui.me?.lang ?: "de"
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u: Uri? -> if (u != null) vm.pick(u) }
    var password by rememberSaveable { mutableStateOf("") }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            GcSubpageBar(stringResource(R.string.notizen_import), nav.back, stringResource(R.string.notizen_back))
            Text(stringResource(R.string.notizen_import_intro), style = MaterialTheme.typography.bodyMedium, color = x.muted, modifier = Modifier.padding(16.dp))
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (ui.me?.imports?.mstodo == true) {
                    val m = ui.ms
                    GcCard {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Badge("To Do")
                            Column { Text("Microsoft To Do", style = MaterialTheme.typography.titleMedium); Text(stringResource(R.string.notizen_mstodo_hint), style = MaterialTheme.typography.bodySmall, color = x.muted) }
                        }
                        when {
                            m.loading && m.accounts.isEmpty() -> NotizenLoading()
                            !m.available -> Text(stringResource(if (m.reason == "mail_missing") R.string.notizen_mstodo_no_mail else if (m.reason == "off") R.string.notizen_mstodo_off else R.string.notizen_mstodo_unavailable), style = MaterialTheme.typography.bodySmall, color = x.muted)
                            m.accounts.isEmpty() -> Text(stringResource(R.string.notizen_mstodo_no_account), style = MaterialTheme.typography.bodySmall, color = x.muted)
                            else -> {
                                val acc = m.accounts.firstOrNull { it.id == m.account }
                                ChoiceRow(stringResource(R.string.notizen_mstodo_account), m.account, m.accounts.map { it.id to it.email }) { vm.selectAccount(it!!) }
                                if (acc != null && !acc.tasks) {
                                    Text(stringResource(R.string.notizen_mstodo_consent_hint), style = MaterialTheme.typography.bodySmall, color = x.muted)
                                    GcPrimaryButton(stringResource(R.string.notizen_mstodo_consent), vm::consent, loading = m.consent != null)
                                }
                                m.preview?.let { pv ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Stat(pv.totalLists.toString(), stringResource(R.string.notizen_stat_lists), Modifier.weight(1f))
                                        Stat(pv.totalOpen.toString(), stringResource(R.string.notizen_stat_open), Modifier.weight(1f))
                                        Stat(pv.totalAttachments.toString(), stringResource(R.string.notizen_stat_attached), Modifier.weight(1f))
                                    }
                                    m.lists.forEach { l ->
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            androidx.compose.material3.Checkbox(l.include, { vm.setList(l.copy(include = it)) })
                                            Text("${l.name} (${l.open})", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                            Text(
                                                areaLabel(l.area),
                                                style = MaterialTheme.typography.labelLarge,
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) { vm.setList(l.copy(area = if (l.area == NArea.WORK) NArea.PRIVATE else NArea.WORK)) }.padding(6.dp),
                                            )
                                        }
                                    }
                                    ChoiceRow(
                                        stringResource(R.string.notizen_mstodo_done),
                                        m.doneDays,
                                        listOf(0 to stringResource(R.string.notizen_done_none), 30 to stringResource(R.string.notizen_done_days, 30), 90 to stringResource(R.string.notizen_done_days, 90), 365 to stringResource(R.string.notizen_done_days, 365), -1 to stringResource(R.string.notizen_done_all)),
                                        vm::setDoneDays,
                                    )
                                    DetailRow(GcIcons.Refresh, stringResource(R.string.notizen_mstodo_two_way), "", null) { GcSwitch(m.twoWay, if (ui.me?.imports?.mstodoSync == true) vm::setTwoWay else null, enabled = ui.me?.imports?.mstodoSync == true) }
                                    Text(stringResource(R.string.notizen_mstodo_two_way_hint), style = MaterialTheme.typography.bodySmall, color = x.muted)
                                    if (m.running || m.state != null) {
                                        val total = m.lists.filter { it.include }.sumOf { it.open }.coerceAtLeast(1)
                                        LinearProgressIndicator(progress = { ((m.stats["tasks"] ?: 0).toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                                        Text(stringResource(R.string.notizen_job_stats, m.stats["tasks"] ?: 0, m.stats["lists"] ?: 0, m.stats["attachments"] ?: 0), style = MaterialTheme.typography.bodySmall, color = x.muted)
                                    }
                                    GcPrimaryButton(stringResource(R.string.notizen_mstodo_take, m.lists.filter { it.include }.sumOf { it.open }), vm::startMs, loading = m.running, enabled = !m.running && m.lists.any { it.include })
                                }
                                if (m.links.any { it.twoWay }) {
                                    Text(stringResource(R.string.notizen_mstodo_links), style = MaterialTheme.typography.titleSmall)
                                    m.links.filter { it.twoWay }.forEach { l ->
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(l.listName + (l.error?.let { " · " + stringResource(R.string.notizen_sync_error) } ?: ""), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                            TextButton(onClick = { vm.unlink(l) }) { Text(stringResource(R.string.notizen_mstodo_unlink)) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (ui.me?.imports?.dsnote == true) {
                    val d = ui.ds
                    GcCard {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Badge("DS")
                            Column { Text("Synology DS Note", style = MaterialTheme.typography.titleMedium); Text(stringResource(R.string.notizen_dsnote_hint), style = MaterialTheme.typography.bodySmall, color = x.muted) }
                        }
                        GcOutlineButton(stringResource(R.string.notizen_dsnote_pick), { pick.launch(arrayOf("application/octet-stream", "application/zip", "*/*")) }, icon = GcIcons.File, loading = d.reading)
                        Text(stringResource(R.string.notizen_dsnote_nas), style = MaterialTheme.typography.bodySmall, color = x.faint)
                        d.error?.let { Text(stringResource(R.string.notizen_dsnote_bad), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                        d.summary?.let { s ->
                            Text(stringResource(R.string.notizen_dsnote_found, d.fileName.orEmpty()), style = MaterialTheme.typography.titleSmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Stat(s.notebooks.size.toString(), stringResource(R.string.notizen_stat_notebooks), Modifier.weight(1f))
                                Stat(s.notes.toString(), stringResource(R.string.notizen_stat_notes), Modifier.weight(1f))
                                Stat(NotizenFormat.bytes(s.bytes, lang), stringResource(R.string.notizen_stat_bytes), Modifier.weight(1f))
                            }
                            if (s.protectedNotes > 0) {
                                Text(stringResource(R.string.notizen_dsnote_protected, s.protectedNotes), style = MaterialTheme.typography.bodySmall, color = x.muted)
                                GcTextField(password, { password = it.take(200) }, stringResource(R.string.notizen_dsnote_password), visualTransformation = PasswordVisualTransformation())
                            }
                            Text(stringResource(R.string.notizen_dsnote_where), style = MaterialTheme.typography.bodySmall, color = x.muted)
                            if (d.running || d.result != null) {
                                LinearProgressIndicator(progress = { (d.done.toFloat() / s.notes.coerceAtLeast(1)).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                                Text(stringResource(R.string.notizen_dsnote_progress, d.done, s.notes), style = MaterialTheme.typography.bodySmall, color = x.muted)
                            }
                            d.result?.let { r ->
                                Text(stringResource(R.string.notizen_dsnote_done, r.notes, r.skipped + r.protectedSkipped), style = MaterialTheme.typography.bodyMedium)
                                if (r.protectedSkipped > 0) Text(stringResource(R.string.notizen_dsnote_prot_skipped, r.protectedSkipped), style = MaterialTheme.typography.bodySmall, color = x.muted)
                            }
                            GcPrimaryButton(stringResource(R.string.notizen_dsnote_take, s.notes), { vm.startDs(password) }, loading = d.running, enabled = !d.running)
                        }
                    }
                }
            }
        }
        SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp)) { Snackbar(it) }
    }
    ui.ms.consent?.let { c ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.notizen_mstodo_consent)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.notizen_mstodo_code_text, c.verificationUri ?: "microsoft.com/devicelogin"), style = MaterialTheme.typography.bodyMedium)
                    Text(c.userCode.orEmpty(), style = MaterialTheme.typography.headlineMedium.copy(fontFamily = MonoFontFamily, fontWeight = FontWeight.Bold))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GcOutlineButton(stringResource(R.string.notizen_copy), { c.userCode?.let { copyPlain(context, it) } }, fillWidth = false, icon = GcIcons.Copy)
                        GcOutlineButton(stringResource(R.string.notizen_mstodo_open_link), {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(c.verificationUri ?: "https://microsoft.com/devicelogin")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        }, fillWidth = false, icon = GcIcons.External)
                    }
                    Text(stringResource(R.string.notizen_mstodo_waiting), style = MaterialTheme.typography.bodySmall, color = x.muted)
                }
            },
            confirmButton = { TextButton(onClick = vm::cancelConsent) { Text(stringResource(R.string.notizen_cancel)) } },
        )
    }
    if (ui.ds.recovery.isNotEmpty()) RecoveryKeyDialog(ui.ds.recovery, onConfirm = vm::confirmRecovery, onDismiss = null)
}

internal fun copyPlain(context: android.content.Context, text: String) {
    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager ?: return
    cm.setPrimaryClip(android.content.ClipData.newPlainText("GateControl", text))
}

@Composable
private fun Badge(text: String) {
    Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(GateControlTheme.extraColors.accentBg), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = GateControlTheme.extraColors.accentText)
    }
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(GateControlTheme.extraColors.panel2).padding(10.dp)) {
        Text(value, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
        Text(label, style = MaterialTheme.typography.bodySmall, color = GateControlTheme.extraColors.muted)
    }
}

// ── Suchen ──────────────────────────────────────────────────

/** The search over tasks and notes (the server's full-text index; „zahnarzt“ finds „Zahnärztin“). */
@Composable
fun NotizenSearchScreen(nav: NotizenNav, vm: NotizenSearchViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val x = GateControlTheme.extraColors
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).imePadding()) {
        GcSubpageBar(stringResource(R.string.notizen_search), nav.back, stringResource(R.string.notizen_back))
        BasicTextField(
            ui.query,
            vm::setQuery,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            decorationBox = { inner -> Box(Modifier.padding(14.dp)) { if (ui.query.isEmpty()) Text(stringResource(R.string.notizen_search_placeholder), color = x.faint); inner() } },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).heightIn(min = 52.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surface).focusRequester(focus),
        )
        if (ui.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
        ui.error?.let { Text(messageText(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
        val r = ui.result ?: return@Column
        val today = java.time.LocalDate.now().toString()
        val rc = rowCtx(today, java.util.Locale.getDefault().language.takeIf { it == "en" } ?: "de", { null })
        LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
            val entries = r.tasks + r.items
            if (entries.isNotEmpty()) {
                item { NSectionHeader(stringResource(R.string.notizen_seg_tasks), entries.size) }
                item { EntryCard(entries, rc, vm::toggle, nav) }
            }
            if (r.notes.isNotEmpty()) {
                item { NSectionHeader(stringResource(R.string.notizen_seg_notes), r.notes.size) }
                item {
                    NCard {
                        r.notes.forEachIndexed { i, n ->
                            Column(Modifier.fillMaxWidth().clickable(role = Role.Button) { nav.note(n.id) }.padding(14.dp)) {
                                Text(n.title.orEmpty().ifEmpty { stringResource(R.string.notizen_untitled) }, style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp))
                                n.snippet?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = x.muted, maxLines = 2) }
                            }
                            if (i < r.notes.size - 1) Box(Modifier.fillMaxWidth().height(1.dp).background(x.border))
                        }
                    }
                }
            }
            if (entries.isEmpty() && r.notes.isEmpty()) item { Text(stringResource(R.string.notizen_search_none), style = MaterialTheme.typography.bodyMedium, color = x.muted, modifier = Modifier.padding(16.dp)) }
        }
    }
}
