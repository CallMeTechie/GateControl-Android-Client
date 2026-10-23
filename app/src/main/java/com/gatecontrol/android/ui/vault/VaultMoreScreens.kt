package com.gatecontrol.android.ui.vault

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.ui.components.GcBanner
import com.gatecontrol.android.ui.components.GcChip
import com.gatecontrol.android.ui.components.GcChevron
import com.gatecontrol.android.ui.components.GcFilterChip
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcListRow
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.components.GcRowDivider
import com.gatecontrol.android.ui.components.GcSecondaryButton
import com.gatecontrol.android.ui.components.GcSectionLabel
import com.gatecontrol.android.ui.components.GcSubpageBar
import com.gatecontrol.android.ui.components.GcSwitch
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.theme.GateControlTheme
import com.gatecontrol.android.vault.VEmergency
import com.gatecontrol.android.vault.VPerson
import com.gatecontrol.android.vault.VResult
import com.gatecontrol.android.vault.VaultCrypto
import com.gatecontrol.android.vault.VaultGenerator
import com.gatecontrol.android.vault.VaultImport
import com.gatecontrol.android.vault.VaultSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

/** "48 Stunden", "7 Tage". */
@Composable
fun vaultWait(hours: Int): String =
    if (hours >= 48 && hours % 24 == 0) pluralStringResource(R.plurals.vault_days, hours / 24, hours / 24) else pluralStringResource(R.plurals.vault_hours, hours, hours)

/** Time until [iso] as "41 Std. 12 Min.". */
@Composable
fun vaultUntil(iso: String?): String {
    val left = runCatching { Instant.parse(iso).toEpochMilli() - System.currentTimeMillis() }.getOrDefault(0L).coerceAtLeast(0L)
    val min = left / 60_000
    return stringResource(R.string.vault_em_remaining, (min / 60).toInt(), (min % 60).toInt())
}

private val WAIT_CHOICES = listOf(24, 48, 168, 336, 720)

/** Emergency access (mockup A9): requests to me, my trusted people, those who trust me. */
@Composable
fun VaultEmergencyScreen(onBack: () -> Unit, onOpen: (String) -> Unit, onForgot: () -> Unit, viewModel: VaultViewModel = hiltViewModel()) {
    val x = GateControlTheme.extraColors
    val list by viewModel.emergency.collectAsStateWithLifecycle()
    var people by remember { mutableStateOf<List<VPerson>?>(null) }
    var adding by remember { mutableStateOf<VPerson?>(null) }
    var wait by remember { mutableIntStateOf(48) }
    var picker by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf<String?>(null) }
    var confirmRequest by remember { mutableStateOf<VEmergency?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val done: (VResult<*>) -> Unit = { r -> error = viewModel.message(r) }
    LaunchedEffect(Unit) {
        viewModel.loadEmergency(done)
        viewModel.people { r -> people = r.valueOrNull() }
    }
    VaultLockGate(viewModel, onForgot) {
        Column(Modifier.fillMaxSize()) {
            GcSubpageBar(stringResource(R.string.vault_em_title), onBack = onBack, backDescription = stringResource(R.string.vault_back))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                val l = list
                if (l == null) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error)
                    return@Column
                }
                // someone asks for my vault
                l.granted.filter { it.status == "requested" }.forEach { e ->
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(x.warnBg).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(R.string.vault_em_requested, e.granteeName), style = MaterialTheme.typography.titleMedium, color = x.warn)
                        Text(stringResource(R.string.vault_em_requested_text, e.granteeName, vaultUntil(e.releaseAt)), style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            GcOutlineButton(stringResource(R.string.vault_em_deny), onClick = { viewModel.emergencyStep(e.id, "deny", done) }, modifier = Modifier.weight(1f))
                            GcPrimaryButton(stringResource(R.string.vault_em_approve), onClick = { viewModel.emergencyStep(e.id, "approve", done) }, modifier = Modifier.weight(1f))
                        }
                    }
                }
                GcSectionLabel(stringResource(R.string.vault_em_mine), Modifier.padding(start = 4.dp))
                VaultRowsCard {
                    if (l.granted.isEmpty()) Text(stringResource(R.string.vault_em_none), color = x.muted, modifier = Modifier.padding(16.dp))
                    l.granted.forEachIndexed { i, e ->
                        if (i > 0) GcRowDivider()
                        Box {
                            GcListRow(
                                title = e.granteeName,
                                description = stringResource(R.string.vault_em_wait, vaultWait(e.waitHours)),
                                onClick = { menu = e.id },
                                leading = { VaultPersonDot(e.granteeName) },
                                trailing = { EmergencyStatus(e.status) },
                            )
                            DropdownMenu(menu == e.id, onDismissRequest = { menu = null }) {
                                if (e.status == "granted") DropdownMenuItem(text = { Text(stringResource(R.string.vault_em_take_back)) }, onClick = { menu = null; viewModel.emergencyStep(e.id, "deny", done) })
                                DropdownMenuItem(text = { Text(stringResource(R.string.vault_em_remove), color = MaterialTheme.colorScheme.error) }, onClick = { menu = null; viewModel.removeTrusted(e.id, done) })
                            }
                        }
                    }
                }
                val add = adding
                if (add?.publicKey != null) {
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(x.blueBg).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.vault_add_title, add.name), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.vault_em_wait_label), style = MaterialTheme.typography.titleSmall, color = x.muted)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            WAIT_CHOICES.forEach { h -> GcFilterChip(vaultWait(h), wait == h, onClick = { wait = h }) }
                        }
                        Text(stringResource(R.string.vault_safety_text, add.name), style = MaterialTheme.typography.bodyMedium, color = x.muted)
                        remember(add) { runCatching { VaultCrypto.safetyNumber(VaultCrypto.unb64(add.publicKey)) }.getOrNull() }?.let { VaultSafetyNumber(it) }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            GcOutlineButton(stringResource(R.string.vault_cancel), onClick = { adding = null }, modifier = Modifier.weight(1f))
                            GcPrimaryButton(stringResource(R.string.vault_safety_match), icon = GcIcons.Check, modifier = Modifier.weight(1f), onClick = {
                                viewModel.addTrusted(add, wait) { r -> if (r is VResult.Ok) adding = null else error = viewModel.message(r) }
                            })
                        }
                    }
                } else {
                    Box {
                        GcOutlineButton(stringResource(R.string.vault_em_add), onClick = { picker = true }, icon = GcIcons.Plus)
                        DropdownMenu(picker, onDismissRequest = { picker = false }) {
                            val candidates = people.orEmpty().filter { p -> l.granted.none { it.granteeId == p.id } }
                            if (candidates.isEmpty()) DropdownMenuItem(text = { Text(stringResource(R.string.vault_no_people)) }, onClick = { picker = false }, enabled = false)
                            candidates.forEach { p ->
                                DropdownMenuItem(
                                    text = { Text(if (p.publicKey == null) "${p.name} – ${stringResource(R.string.vault_person_no_vault)}" else p.name) },
                                    enabled = p.publicKey != null, onClick = { picker = false; adding = p; wait = 48 },
                                )
                            }
                        }
                    }
                }
                if (l.received.isNotEmpty()) {
                    GcSectionLabel(stringResource(R.string.vault_em_for), Modifier.padding(start = 4.dp, top = 6.dp))
                    VaultRowsCard {
                        l.received.forEachIndexed { i, e ->
                            if (i > 0) GcRowDivider()
                            Box {
                                GcListRow(
                                    title = e.grantorName,
                                    description = when (e.status) {
                                        "requested" -> stringResource(R.string.vault_em_waiting_until, vaultUntil(e.releaseAt), e.grantorName)
                                        else -> stringResource(R.string.vault_em_wait_label) + " " + vaultWait(e.waitHours)
                                    },
                                    leading = { VaultPersonDot(e.grantorName) },
                                    onClick = { menu = "r:" + e.id },
                                    trailing = {
                                        when (e.status) {
                                            "granted" -> GcOutlineButton(stringResource(R.string.vault_em_open), onClick = { onOpen(e.id) }, fillWidth = false, minHeight = 40.dp)
                                            "ready" -> GcOutlineButton(stringResource(R.string.vault_em_request), onClick = { confirmRequest = e }, fillWidth = false, minHeight = 40.dp)
                                            else -> EmergencyStatus(e.status)
                                        }
                                    },
                                )
                                DropdownMenu(menu == "r:" + e.id, onDismissRequest = { menu = null }) {
                                    DropdownMenuItem(text = { Text(stringResource(R.string.vault_em_decline), color = MaterialTheme.colorScheme.error) }, onClick = { menu = null; viewModel.removeTrusted(e.id, done) })
                                }
                            }
                        }
                    }
                }
                if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.vault_em_info), style = MaterialTheme.typography.bodySmall, color = x.muted, modifier = Modifier.padding(horizontal = 4.dp))
            }
        }
        confirmRequest?.let { e ->
            AlertDialog(
                onDismissRequest = { confirmRequest = null },
                text = { Text(stringResource(R.string.vault_em_request_confirm, e.grantorName, vaultWait(e.waitHours))) },
                confirmButton = { TextButton(onClick = { confirmRequest = null; viewModel.emergencyStep(e.id, "request", done) }) { Text(stringResource(R.string.vault_em_request)) } },
                dismissButton = { TextButton(onClick = { confirmRequest = null }) { Text(stringResource(R.string.vault_cancel)) } },
            )
        }
    }
}

@Composable
private fun EmergencyStatus(status: String) = when (status) {
    "requested" -> GcChip(stringResource(R.string.vault_em_status_requested), tone = GcTone.Warn, small = true)
    "granted" -> GcChip(stringResource(R.string.vault_em_status_granted), tone = GcTone.Ok, small = true)
    else -> GcChip(stringResource(R.string.vault_em_status_ready), small = true)
}

/** A released emergency access: the grantor's vaults, read only, in memory while this screen is open. */
@Composable
fun VaultEmergencyViewScreen(id: String, onBack: () -> Unit, onForgot: () -> Unit, viewModel: VaultViewModel = hiltViewModel()) {
    val x = GateControlTheme.extraColors
    val view by viewModel.emergencyView.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf<String?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(id) { viewModel.openEmergency(id) }
    VaultLockGate(viewModel, onForgot) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                val v = (view as? VResult.Ok)?.value
                GcSubpageBar(if (v != null) stringResource(R.string.vault_em_view_title, v.grantorName) else stringResource(R.string.vault_em_title), onBack = onBack, backDescription = stringResource(R.string.vault_back))
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when (val r = view) {
                        null -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        is VResult.Ok -> {
                            GcBanner(tone = GcTone.Info, icon = GcIcons.Eye) { Text(stringResource(R.string.vault_em_read_only), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium) }
                            r.value.vaults.forEach { vault ->
                                val list = r.value.entries.filter { it.vaultId == vault.id && !it.trashed }
                                if (list.isEmpty()) return@forEach
                                GcSectionLabel(vault.name, Modifier.padding(start = 4.dp, top = 6.dp))
                                VaultRowsCard {
                                    list.forEachIndexed { i, e ->
                                        if (i > 0) GcRowDivider()
                                        VaultEntryRow(e, shared = false, showCode = false, onClick = { open = if (open == e.id) null else e.id })
                                        if (open == e.id) Box(Modifier.padding(12.dp)) { VaultEntryFields(e.data, viewModel.clipboardSeconds) { toast = it } }
                                    }
                                }
                            }
                        }
                        else -> Text(viewModel.message(r).orEmpty(), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            VaultToast(toast, onGone = { toast = null }, modifier = Modifier.align(Alignment.BottomCenter))
        }
    }
}

/** Import (mockup A10): pick an export, see what is in it, import into a vault — read on the device only. */
@Composable
fun VaultImportScreen(onBack: () -> Unit, onForgot: () -> Unit, viewModel: VaultViewModel = hiltViewModel()) {
    val x = GateControlTheme.extraColors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val vaults by viewModel.vaults.collectAsStateWithLifecycle()
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    var fileName by remember { mutableStateOf<String?>(null) }
    var outcome by remember { mutableStateOf<VaultImport.Outcome?>(null) }
    var target by remember { mutableStateOf<String?>(null) }
    var vaultMenu by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf<Int?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            val (name, text) = withContext(Dispatchers.IO) {
                val n = runCatching {
                    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                }.getOrNull()
                val t = runCatching {
                    context.contentResolver.openInputStream(uri)?.use { s ->
                        // at most 20 MB: an export is text, a bigger file is not one
                        val out = java.io.ByteArrayOutputStream()
                        val buf = ByteArray(64 * 1024)
                        while (out.size() < 20 * 1024 * 1024) {
                            val n = s.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                        }
                        out.toString(Charsets.UTF_8.name())
                    }
                }.getOrNull()
                n to t
            }
            fileName = name
            outcome = text?.let { withContext(Dispatchers.Default) { viewModel.parseImport(it) } } ?: VaultImport.Outcome.Unknown
            busy = false
        }
    }
    val types = arrayOf("application/json", "text/csv", "text/comma-separated-values", "text/plain", "application/octet-stream")
    VaultLockGate(viewModel, onForgot) {
        val writable = vaults.filter { it.vault.canWrite }
        if (target == null) target = writable.firstOrNull { it.vault.personal }?.id ?: writable.firstOrNull()?.id
        Column(Modifier.fillMaxSize()) {
            GcSubpageBar(stringResource(R.string.vault_import_title), onBack = onBack, backDescription = stringResource(R.string.vault_back))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                GcSectionLabel(stringResource(R.string.vault_import_from), Modifier.padding(start = 4.dp))
                val sources = listOf(
                    "Bitwarden" to stringResource(R.string.vault_import_fmt_bitwarden),
                    "1Password" to stringResource(R.string.vault_import_fmt_csv),
                    "KeePass" to stringResource(R.string.vault_import_fmt_keepass),
                    "Chrome / Edge" to stringResource(R.string.vault_import_fmt_csv),
                )
                sources.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { (name, fmt) ->
                            Column(
                                Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface).border(1.dp, x.border, RoundedCornerShape(16.dp))
                                    .clickable(role = Role.Button) { done = null; pick.launch(types) }.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(name, style = MaterialTheme.typography.titleMedium)
                                Text(fmt, style = MaterialTheme.typography.bodySmall, color = x.muted)
                            }
                        }
                    }
                }
                if (busy) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                when (val o = outcome) {
                    VaultImport.Outcome.Encrypted -> GcBanner(tone = GcTone.Warn, icon = GcIcons.Alert) { Text(stringResource(R.string.vault_import_encrypted), color = MaterialTheme.colorScheme.onSurface) }
                    VaultImport.Outcome.Unknown -> GcBanner(tone = GcTone.Warn, icon = GcIcons.Alert) { Text(stringResource(R.string.vault_import_unknown), color = MaterialTheme.colorScheme.onSurface) }
                    is VaultImport.Outcome.Ok -> {
                        val (fresh, dupes) = remember(o, entries.size) { VaultImport.withoutDuplicates(o.result.entries, entries.map { it.data }) }
                        VaultRowsCard {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Icon(GcIcons.File, null, tint = x.accentText, modifier = Modifier.size(22.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(fileName ?: o.result.source, style = MaterialTheme.typography.titleMedium)
                                        Text(stringResource(R.string.vault_import_found, o.result.entries.size), style = MaterialTheme.typography.bodySmall, color = x.muted)
                                    }
                                    GcSecondaryButton(stringResource(R.string.vault_import_other), onClick = { pick.launch(types) }, fillWidth = false)
                                }
                                GcRowDivider()
                                listOf(
                                    stringResource(R.string.vault_import_logins, o.result.logins),
                                    stringResource(R.string.vault_import_totp, o.result.withTotp),
                                    stringResource(R.string.vault_import_notes, o.result.notes),
                                    stringResource(R.string.vault_import_cards, o.result.cards),
                                ).forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                                if (dupes > 0) Text(stringResource(R.string.vault_import_dupes, dupes), style = MaterialTheme.typography.bodyMedium, color = x.warn)
                            }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(stringResource(R.string.vault_import_into), style = MaterialTheme.typography.titleSmall, color = x.muted)
                            Box {
                                Row(
                                    Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.background)
                                        .border(1.dp, x.border2, RoundedCornerShape(14.dp)).clickable(role = Role.Button) { vaultMenu = true }.padding(horizontal = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(vaultName(vaults.firstOrNull { it.id == target }), modifier = Modifier.weight(1f))
                                    GcChevron()
                                }
                                DropdownMenu(vaultMenu, onDismissRequest = { vaultMenu = false }) {
                                    writable.forEach { v -> DropdownMenuItem(text = { Text(v.name) }, onClick = { target = v.id; vaultMenu = false }) }
                                }
                            }
                        }
                        GcBanner(tone = GcTone.Info, icon = GcIcons.Shield) { Text(stringResource(R.string.vault_import_info), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium) }
                        if (done != null) {
                            GcBanner(tone = GcTone.Ok, icon = GcIcons.Check) { Text(stringResource(R.string.vault_import_done, done!!), color = MaterialTheme.colorScheme.onSurface) }
                        } else {
                            GcPrimaryButton(stringResource(R.string.vault_import_button, fresh.size), enabled = fresh.isNotEmpty() && target != null && !busy, loading = busy, minHeight = 52.dp, onClick = {
                                busy = true
                                viewModel.import(target!!, fresh) { n -> busy = false; done = n }
                            })
                        }
                    }
                    null -> Unit
                }
            }
        }
    }
}

/** Vault settings: auto-lock, fingerprint, autofill, master password, safety number, delete. */
@Composable
fun VaultSettingsScreen(onBack: () -> Unit, onForgot: () -> Unit, viewModel: VaultViewModel = hiltViewModel()) {
    val x = GateControlTheme.extraColors
    val context = LocalContext.current
    var autoLock by remember { mutableIntStateOf(viewModel.autoLockMinutes) }
    var bio by remember { mutableStateOf(viewModel.biometricEnabled()) }
    var changing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val changed = stringResource(R.string.vault_change_pw_done)
    VaultLockGate(viewModel, onForgot) {
        Column(Modifier.fillMaxSize()) {
            GcSubpageBar(stringResource(R.string.vault_settings_title), onBack = onBack, backDescription = stringResource(R.string.vault_back))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                GcSectionLabel(stringResource(R.string.vault_auto_lock), Modifier.padding(start = 4.dp))
                VaultRowsCard {
                    VaultSession.AUTO_LOCK_CHOICES.forEachIndexed { i, m ->
                        if (i > 0) GcRowDivider()
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 52.dp).selectable(autoLock == m, role = Role.RadioButton) { autoLock = m; viewModel.autoLockMinutes = m }.padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(autoLock == m, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary))
                            Text(if (m == 0) stringResource(R.string.vault_auto_lock_now) else pluralStringResource(R.plurals.vault_auto_lock_after, m, m))
                        }
                    }
                }
                VaultRowsCard {
                    if (viewModel.biometric.available()) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            Icon(GcIcons.Fingerprint, null, tint = x.accentText, modifier = Modifier.size(22.dp))
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.vault_bio_title), style = MaterialTheme.typography.titleMedium)
                                Text(stringResource(R.string.vault_bio_text), style = MaterialTheme.typography.bodySmall, color = x.muted)
                            }
                            GcSwitch(bio, onCheckedChange = { on -> viewModel.setBiometric(context, on) { bio = it } })
                        }
                        GcRowDivider()
                    }
                    GcListRow(
                        title = stringResource(R.string.vault_autofill_setting),
                        description = stringResource(R.string.vault_autofill_text),
                        onClick = { openAutofillSettings(context) },
                        leading = { Icon(GcIcons.Key, null, tint = x.accentText, modifier = Modifier.size(22.dp)) },
                        trailing = { if (vaultAutofillActive(context)) GcChip(stringResource(R.string.vault_autofill_active), tone = GcTone.Ok, small = true) else GcChevron() },
                    )
                    GcRowDivider()
                    GcListRow(
                        title = stringResource(R.string.vault_change_pw),
                        onClick = { changing = true },
                        leading = { Icon(GcIcons.Lock, null, tint = x.accentText, modifier = Modifier.size(22.dp)) },
                        trailing = { GcChevron() },
                    )
                }
                viewModel.mySafetyNumber()?.let { n ->
                    VaultRowsCard {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.vault_my_safety), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.vault_my_safety_text), style = MaterialTheme.typography.bodySmall, color = x.muted)
                            VaultSafetyNumber(n)
                        }
                    }
                }
                if (message != null) Text(message!!, color = x.accentText, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                GcSecondaryButton(stringResource(R.string.vault_delete_account), onClick = { deleting = true }, icon = GcIcons.Trash)
            }
        }
        if (changing) {
            var pw by remember { mutableStateOf("") }
            var pw2 by remember { mutableStateOf("") }
            var busy by remember { mutableStateOf(false) }
            var err by remember { mutableStateOf<String?>(null) }
            AlertDialog(
                onDismissRequest = { if (!busy) changing = false },
                title = { Text(stringResource(R.string.vault_change_pw)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        VaultPasswordField(pw, { pw = it }, stringResource(R.string.vault_recover_new_pw))
                        VaultStrengthMeter(pw)
                        VaultPasswordField(pw2, { pw2 = it }, stringResource(R.string.vault_setup_pw_repeat), error = if (pw2.isNotEmpty() && pw2 != pw) stringResource(R.string.vault_setup_pw_mismatch) else err)
                    }
                },
                confirmButton = {
                    TextButton(enabled = !busy && pw == pw2 && VaultGenerator.goodMasterPassword(pw), onClick = {
                        busy = true
                        viewModel.changePassword(pw) { r ->
                            busy = false
                            if (r is VResult.Ok) {
                                changing = false
                                message = changed
                            } else {
                                err = viewModel.message(r)
                            }
                        }
                    }) { Text(stringResource(R.string.vault_save)) }
                },
                dismissButton = { TextButton(onClick = { changing = false }, enabled = !busy) { Text(stringResource(R.string.vault_cancel)) } },
            )
        }
        if (deleting) {
            AlertDialog(
                onDismissRequest = { deleting = false },
                text = { Text(stringResource(R.string.vault_delete_account_confirm)) },
                confirmButton = {
                    TextButton(onClick = {
                        deleting = false
                        viewModel.deleteAccount { r -> if (r is VResult.Ok) onBack() else message = viewModel.message(r) }
                    }) { Text(stringResource(R.string.vault_delete_account), color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.vault_cancel)) } },
            )
        }
    }
}
