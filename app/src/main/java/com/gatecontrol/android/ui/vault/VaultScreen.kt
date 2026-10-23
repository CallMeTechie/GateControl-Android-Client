package com.gatecontrol.android.ui.vault

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.ui.components.GcBanner
import com.gatecontrol.android.ui.components.GcChip
import com.gatecontrol.android.ui.components.GcFilterChip
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.components.GcRowDivider
import com.gatecontrol.android.ui.components.GcSecondaryButton
import com.gatecontrol.android.ui.components.GcSectionLabel
import com.gatecontrol.android.ui.components.GcTextField
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.theme.GateControlTheme
import com.gatecontrol.android.ui.theme.MonoFontFamily
import com.gatecontrol.android.vault.Entry
import com.gatecontrol.android.vault.VaultBiometric
import com.gatecontrol.android.vault.VaultRepository
import com.gatecontrol.android.vault.VaultTotp
import com.gatecontrol.android.vault.VaultView
import kotlinx.coroutines.delay

/** Callbacks of the Vault tab into the app's navigation. */
class VaultNav(
    val setup: () -> Unit,
    val recover: () -> Unit,
    val entry: (String) -> Unit,
    val newEntry: (type: String, vault: String?) -> Unit,
    val vaults: () -> Unit,
    val emergency: () -> Unit,
    val import: () -> Unit,
    val trash: () -> Unit,
    val settings: () -> Unit,
)

/** The tab "Vault": intro → setup, unlock (A3), the entries (A4). */
@Composable
fun VaultScreen(nav: VaultNav, viewModel: VaultViewModel = hiltViewModel()) {
    val status by viewModel.status.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.check() }
    when (status) {
        VaultStatus.LOADING -> VaultCentered { CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp) }
        VaultStatus.UNAVAILABLE -> VaultCentered {
            Text(stringResource(R.string.vault_unavailable), textAlign = TextAlign.Center, color = GateControlTheme.extraColors.muted)
            Spacer(Modifier.height(16.dp))
            GcOutlineButton(stringResource(R.string.vault_retry), onClick = { viewModel.check() }, fillWidth = false)
        }
        VaultStatus.NOT_SET_UP -> VaultIntro(onStart = nav.setup)
        VaultStatus.LOCKED -> VaultUnlock(viewModel, onForgot = nav.recover)
        VaultStatus.UNLOCKED -> VaultList(viewModel, nav)
    }
}

@Composable
fun VaultCentered(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) { content() }
}

@Composable
private fun VaultIntro(onStart: () -> Unit) {
    val x = GateControlTheme.extraColors
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp)) {
        Text(stringResource(R.string.vault_title), style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.weight(1f))
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            VaultHeroIcon(GcIcons.Vault)
            Text(stringResource(R.string.vault_intro_title), style = MaterialTheme.typography.headlineLarge)
            Text(stringResource(R.string.vault_intro_text), style = MaterialTheme.typography.bodyLarge, color = x.muted)
        }
        Spacer(Modifier.weight(1f))
        GcPrimaryButton(stringResource(R.string.vault_intro_start), onClick = onStart, minHeight = 52.dp)
        Spacer(Modifier.height(12.dp))
    }
}

/** Mockup A3: fingerprint first, the master password below; works offline. */
@Composable
private fun VaultUnlock(viewModel: VaultViewModel, onForgot: () -> Unit) {
    val x = GateControlTheme.extraColors
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sync by viewModel.sync.collectAsStateWithLifecycle()
    val bio = remember { viewModel.biometricEnabled() }
    var showPassword by rememberSaveable { mutableStateOf(!bio) }
    var password by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val wrong = stringResource(R.string.vault_unlock_wrong)
    val invalidated = stringResource(R.string.vault_bio_invalidated)

    fun finger() {
        viewModel.unlockWithFinger(context) { o ->
            when (o) {
                is VaultBiometric.Outcome.Ok -> Unit
                VaultBiometric.Outcome.Invalidated -> { error = invalidated; showPassword = true }
                VaultBiometric.Outcome.Cancelled -> Unit
                VaultBiometric.Outcome.Failed -> showPassword = true
            }
        }
    }
    LaunchedEffect(Unit) { if (bio) finger() }

    fun submit() {
        if (password.isEmpty() || busy) return
        busy = true
        error = null
        viewModel.unlock(password) { r ->
            busy = false
            if (r != VaultRepository.Unlock.Ok) error = wrong else password = ""
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.vault_title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            if (sync == VaultRepository.Sync.Offline) GcChip(stringResource(R.string.vault_offline), small = true)
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
        ) {
            Box {
                Box(
                    Modifier.size(104.dp).clip(RoundedCornerShape(32.dp)).background(MaterialTheme.colorScheme.surface)
                        .border(1.dp, x.border, RoundedCornerShape(32.dp)),
                    contentAlignment = Alignment.Center,
                ) { Icon(GcIcons.Vault, contentDescription = null, tint = x.accentText, modifier = Modifier.size(48.dp)) }
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(start = 0.dp).size(34.dp).clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.background),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(28.dp).clip(RoundedCornerShape(10.dp)).background(x.warn), contentAlignment = Alignment.Center) {
                        Icon(GcIcons.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.background, modifier = Modifier.size(16.dp))
                    }
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.vault_locked_title), style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center)
                val count = state.items.values.count { !it.purged }
                Text(
                    stringResource(
                        R.string.vault_locked_info,
                        pluralStringResource(R.plurals.vault_entries_count, count, count),
                        pluralStringResource(R.plurals.vault_vaults_count, state.vaults.size, state.vaults.size),
                    ) + if (state.lastSyncMs > 0) "\n" + stringResource(R.string.vault_last_sync, DateUtils.getRelativeTimeSpanString(state.lastSyncMs)) else "",
                    style = MaterialTheme.typography.bodyMedium, color = x.muted, textAlign = TextAlign.Center,
                )
            }
            if (bio && !showPassword) {
                Box(
                    Modifier.size(96.dp).clip(CircleShape).background(x.accentBg).border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                        .clickable(role = Role.Button, onClick = { finger() }),
                    contentAlignment = Alignment.Center,
                ) { Icon(GcIcons.Fingerprint, contentDescription = stringResource(R.string.vault_bio_button), tint = x.accentText, modifier = Modifier.size(46.dp)) }
                Text(stringResource(R.string.vault_bio_hint), color = x.muted, style = MaterialTheme.typography.bodyMedium)
            }
            if (showPassword) {
                VaultPasswordField(
                    value = password,
                    onValueChange = { password = it; error = null },
                    label = stringResource(R.string.vault_setup_pw_label),
                    error = error,
                )
                GcPrimaryButton(
                    stringResource(if (busy) R.string.vault_unlocking else R.string.vault_unlock_button),
                    onClick = { submit() }, enabled = password.isNotEmpty(), loading = busy, minHeight = 52.dp,
                )
            } else if (error != null) {
                Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            }
        }
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (bio && !showPassword) {
                GcOutlineButton(stringResource(R.string.vault_unlock_password), onClick = { showPassword = true }, minHeight = 52.dp)
            } else if (bio) {
                GcOutlineButton(stringResource(R.string.vault_bio_button), onClick = { finger() }, icon = GcIcons.Fingerprint, minHeight = 52.dp)
            }
            GcSecondaryButton(stringResource(R.string.vault_forgot), onClick = onForgot)
        }
    }
}

/** Sync state chip of the header (mockup A4 "Abgeglichen"). */
@Composable
fun VaultSyncChip(sync: VaultRepository.Sync) {
    when (sync) {
        VaultRepository.Sync.Running -> GcChip(stringResource(R.string.vault_syncing), small = true)
        VaultRepository.Sync.Offline -> GcChip(stringResource(R.string.vault_offline), small = true)
        is VaultRepository.Sync.Error -> GcChip(stringResource(R.string.vault_offline), small = true, tone = GcTone.Warn)
        else -> GcChip(stringResource(R.string.vault_synced), small = true, tone = GcTone.Ok)
    }
}

/** A live 2FA code (ticks every second). */
@Composable
fun rememberTotp(field: String): VaultTotp.Code? {
    val params = remember(field) { VaultTotp.parse(field) } ?: return null
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(params) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000 - now % 1000)
        }
    }
    return remember(params, now / 1000) { VaultTotp.code(params, now) }
}

@Composable
fun VaultEntryRow(entry: Entry, shared: Boolean, showCode: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val x = GateControlTheme.extraColors
    Row(
        modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        VaultLetter(entry.data.title)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(entry.data.title.ifBlank { "–" }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = vaultSubtitle(entry.data)
            if (sub.isNotBlank() || entry.pending) {
                Text(
                    if (entry.pending) listOf(sub, stringResource(R.string.vault_pending)).filter { it.isNotBlank() }.joinToString(" · ") else sub,
                    style = MaterialTheme.typography.bodySmall, color = x.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (showCode && entry.data.totp.isNotEmpty()) {
            rememberTotp(entry.data.totp)?.let { c ->
                Text(
                    VaultTotp.pretty(c.code),
                    fontFamily = MonoFontFamily, fontWeight = FontWeight.Medium, color = x.accentText,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(x.accentBg).padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
        if (shared) Icon(GcIcons.Users, contentDescription = stringResource(R.string.vault_shared), tint = x.blue, modifier = Modifier.size(18.dp))
    }
}

/** A card holding rows with hairlines between them (mockup A4's list blocks). */
@Composable
fun VaultRowsCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surface)
            .border(1.dp, GateControlTheme.extraColors.border, RoundedCornerShape(18.dp)),
    ) { content() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VaultList(viewModel: VaultViewModel, nav: VaultNav) {
    val x = GateControlTheme.extraColors
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val vaults by viewModel.vaults.collectAsStateWithLifecycle()
    val sync by viewModel.sync.collectAsStateWithLifecycle()
    val notices by viewModel.notices.collectAsStateWithLifecycle()
    val unreadable by viewModel.unreadable.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    var typePicker by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.syncNow() }

    val live = entries.filter { !it.trashed }
    val sharedIds = vaults.filter { !it.vault.personal }.map { it.id }.toSet()
    val shown = live.filter { e ->
        (filter == null || e.vaultId == filter) && (query.isBlank() || listOf(e.data.title, e.data.username, e.data.urls.joinToString(" "), e.data.notes)
            .any { it.contains(query.trim(), ignoreCase = true) })
    }
    val favs = if (query.isBlank()) shown.filter { it.data.fav } else emptyList()

    Box(Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(bottom = 96.dp), modifier = Modifier.fillMaxSize()) {
            item {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.vault_title), style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f))
                    VaultSyncChip(sync)
                    Spacer(Modifier.width(4.dp))
                    GcIconButton(GcIcons.Lock, stringResource(R.string.vault_lock), onClick = { viewModel.lock() }, size = 44.dp)
                    Box {
                        GcIconButton(GcIcons.MoreVertical, stringResource(R.string.vault_more), onClick = { menu = true }, size = 44.dp)
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            listOf(
                                R.string.vault_menu_vaults to nav.vaults,
                                R.string.vault_menu_emergency to nav.emergency,
                                R.string.vault_menu_import to nav.import,
                                R.string.vault_menu_trash to nav.trash,
                                R.string.vault_menu_settings to nav.settings,
                                R.string.vault_menu_sync to { viewModel.syncNow() },
                            ).forEach { (label, action) ->
                                DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = { menu = false; action() })
                            }
                        }
                    }
                }
            }
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    GcTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = stringResource(R.string.vault_search),
                        placeholder = stringResource(R.string.vault_search_hint, pluralStringResource(R.plurals.vault_entries_count, live.size, live.size)),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(),
                    )
                    if (vaults.size > 1) {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GcFilterChip("${stringResource(R.string.vault_filter_all)} ${live.size}", filter == null, onClick = { filter = null })
                            vaults.forEach { v ->
                                GcFilterChip("${v.name} ${live.count { it.vaultId == v.id }}", filter == v.id, onClick = { filter = if (filter == v.id) null else v.id })
                            }
                        }
                    }
                    notices.lastOrNull()?.let { n ->
                        GcBanner(tone = GcTone.Warn, icon = GcIcons.Alert) {
                            Text(n, modifier = Modifier.weight(1f).clickable { viewModel.clearNotices() }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                    if (unreadable > 0) {
                        GcBanner(tone = GcTone.Warn, icon = GcIcons.Alert) {
                            Text(pluralStringResource(R.plurals.vault_unreadable, unreadable, unreadable), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
            if (live.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.vault_empty), color = x.muted, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                    )
                }
            } else if (shown.isEmpty()) {
                item { Text(stringResource(R.string.vault_no_results), color = x.muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(32.dp)) }
            }
            if (favs.isNotEmpty()) {
                item { GcSectionLabel(stringResource(R.string.vault_section_favorites), Modifier.padding(start = 20.dp, top = 14.dp, bottom = 8.dp)) }
                item {
                    VaultRowsCard(Modifier.padding(horizontal = 16.dp)) {
                        favs.forEachIndexed { i, e ->
                            if (i > 0) GcRowDivider()
                            VaultEntryRow(e, e.vaultId in sharedIds, showCode = true, onClick = { nav.entry(e.id) })
                        }
                    }
                }
            }
            if (shown.isNotEmpty()) {
                item { GcSectionLabel(stringResource(R.string.vault_section_all), Modifier.padding(start = 20.dp, top = 18.dp, bottom = 8.dp)) }
                // one card per first letter keeps very long lists light (a single card would not be lazy)
                val groups = shown.groupBy { it.data.title.trim().firstOrNull()?.uppercaseChar() ?: '#' }
                groups.forEach { (_, list) ->
                    item {
                        VaultRowsCard(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                            list.forEachIndexed { i, e ->
                                if (i > 0) GcRowDivider()
                                VaultEntryRow(e, e.vaultId in sharedIds, showCode = false, onClick = { nav.entry(e.id) })
                            }
                        }
                    }
                }
            }
        }
        Box(
            Modifier.align(Alignment.BottomEnd).padding(end = 18.dp, bottom = 18.dp).size(60.dp).clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.primary).clickable(role = Role.Button, onClick = { typePicker = true }),
            contentAlignment = Alignment.Center,
        ) { Icon(GcIcons.Plus, contentDescription = stringResource(R.string.vault_new_entry), tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(26.dp)) }
    }

    if (typePicker) {
        ModalBottomSheet(onDismissRequest = { typePicker = false }, containerColor = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.vault_new_entry), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
                VaultRowsCard {
                    listOf("login", "totp", "note", "card", "wifi", "identity").forEachIndexed { i, t ->
                        if (i > 0) GcRowDivider()
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button) { typePicker = false; nav.newEntry(t, filter) }
                                .padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Icon(vaultTypeIcon(t), contentDescription = null, tint = x.accentText, modifier = Modifier.size(22.dp))
                            Text(vaultTypeLabel(t), style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
        }
    }
}

/** Gate for Vault pages: while locked (auto-lock in the background) the unlock screen shows in their place. */
@Composable
fun VaultLockGate(viewModel: VaultViewModel, onForgot: () -> Unit, content: @Composable () -> Unit) {
    val unlocked by viewModel.unlocked.collectAsStateWithLifecycle()
    if (unlocked) content() else VaultUnlock(viewModel, onForgot)
}

/** The name of a vault for the screens ("Persönlich" stays as encrypted at setup). */
fun vaultName(v: VaultView?): String = v?.name ?: "…"
