package com.gatecontrol.android.ui.vault

import android.content.Intent
import androidx.core.net.toUri
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcRowDivider
import com.gatecontrol.android.ui.components.GcSecondaryButton
import com.gatecontrol.android.ui.components.GcSectionLabel
import com.gatecontrol.android.ui.components.GcBanner
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.theme.GateControlTheme
import com.gatecontrol.android.vault.Entry
import com.gatecontrol.android.vault.EntryData
import com.gatecontrol.android.vault.VResult
import com.gatecontrol.android.vault.VaultMatch
import com.gatecontrol.android.vault.VaultRepository
import com.gatecontrol.android.vault.VaultTotp
import com.gatecontrol.android.vault.VaultWifiQr
import kotlinx.coroutines.delay
import java.time.Instant

/** "vor 3 Tagen" from an ISO time; empty when unknown. */
fun vaultAgo(iso: String?): String =
    runCatching { DateUtils.getRelativeTimeSpanString(Instant.parse(iso).toEpochMilli()).toString() }.getOrDefault("")

/** A short message at the bottom ("Passwort kopiert · wird in 30 s gelöscht"), gone after a moment. */
@Composable
fun VaultToast(text: String?, onGone: () -> Unit, modifier: Modifier = Modifier) {
    if (text == null) return
    LaunchedEffect(text) {
        delay(2500)
        onGone()
    }
    Row(
        modifier.fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.onBackground)
            .padding(horizontal = 16.dp, vertical = 14.dp).semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(GcIcons.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Text(text, color = MaterialTheme.colorScheme.background, style = MaterialTheme.typography.titleSmall)
    }
}

/** The fields of an entry, read-only, with copy / show (mockup A5). Also used for emergency access. */
@Composable
fun VaultEntryFields(data: EntryData, clipboardSeconds: Int, onCopied: (String) -> Unit) {
    val x = GateControlTheme.extraColors
    val context = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    var shown by remember { mutableStateOf(setOf<String>()) }
    fun copy(label: String, value: String, secret: Boolean) {
        VaultClipboard.copy(context, value, secret, clipboardSeconds)
        onCopied(if (secret) resources.getString(R.string.vault_copied_secret, label, clipboardSeconds) else resources.getString(R.string.vault_copied))
    }

    @Composable
    fun plain(key: String, label: Int, mono: Boolean = false) {
        val v = data.field(key)
        if (v.isEmpty()) return
        val l = stringResource(label)
        VaultFieldRow(l, v, mono = mono) {
            GcIconButton(GcIcons.Copy, stringResource(R.string.vault_copy_x, l), onClick = { copy(l, v, false) }, size = 44.dp, iconSize = 20.dp)
        }
    }

    @Composable
    fun secret(key: String, label: Int) {
        val v = data.field(key)
        if (v.isEmpty()) return
        val l = stringResource(label)
        val visible = key in shown
        VaultFieldRow(l, if (visible) v else "•".repeat(minOf(v.length, 12)), mono = true) {
            GcIconButton(
                if (visible) GcIcons.EyeOff else GcIcons.Eye,
                stringResource(if (visible) R.string.vault_hide_password else R.string.vault_show_password),
                onClick = { shown = if (visible) shown - key else shown + key }, size = 44.dp, iconSize = 20.dp,
            )
            GcIconButton(GcIcons.Copy, stringResource(R.string.vault_copy_x, l), onClick = { copy(l, v, true) }, size = 44.dp, iconSize = 20.dp)
        }
    }

    val rows = mutableListOf<@Composable () -> Unit>()
    when (data.type) {
        "login" -> {
            rows += { plain("username", R.string.vault_field_username) }
            rows += { secret("password", R.string.vault_field_password) }
        }
        "card" -> {
            rows += { plain("holder", R.string.vault_field_holder) }
            rows += { secret("number", R.string.vault_field_number) }
            rows += { plain("expiry", R.string.vault_field_expiry) }
            rows += { secret("cvc", R.string.vault_field_cvc) }
            rows += { secret("pin", R.string.vault_field_pin) }
        }
        "wifi" -> {
            rows += { plain("ssid", R.string.vault_field_ssid) }
            rows += { secret("password", R.string.vault_field_password) }
            rows += { plain("security", R.string.vault_field_security) }
        }
        "identity" -> {
            rows += { plain("name", R.string.vault_field_name) }
            rows += { plain("email", R.string.vault_field_email) }
            rows += { plain("phone", R.string.vault_field_phone) }
            rows += { plain("address", R.string.vault_field_address) }
            rows += { plain("birthday", R.string.vault_field_birthday) }
        }
    }
    if (data.totp.isNotEmpty()) rows += {
        val l = stringResource(R.string.vault_field_totp)
        val code = rememberTotp(data.totp)
        if (code == null) {
            VaultFieldRow(l, stringResource(R.string.vault_totp_invalid), valueColor = MaterialTheme.colorScheme.error)
        } else {
            VaultFieldRow(l, VaultTotp.pretty(code.code), big = true, valueColor = x.accentText) {
                VaultTotpRing(code.secondsLeft, code.period)
                GcIconButton(GcIcons.Copy, stringResource(R.string.vault_copy_x, l), onClick = { copy(l, code.code, true) }, size = 44.dp, iconSize = 20.dp)
            }
        }
    }
    val links = data.urls.filter { it.isNotBlank() }
    if (links.isNotEmpty() || data.apps.isNotEmpty()) rows += {
        val first = links.firstOrNull()
        VaultFieldRow(
            stringResource(R.string.vault_field_urls),
            (links.mapNotNull { VaultMatch.host(it) ?: it } + data.apps).joinToString(" · "),
        ) {
            if (first != null) {
                GcIconButton(GcIcons.External, stringResource(R.string.vault_open_website), size = 44.dp, iconSize = 20.dp, onClick = {
                    val url = if (first.contains("://")) first else "https://$first"
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                })
            }
        }
    }
    data.custom.forEach { c ->
        rows += {
            val visible = "custom:${c.name}" in shown
            VaultFieldRow(c.name.ifBlank { "…" }, if (c.secret && !visible) "•".repeat(minOf(c.value.length, 12)) else c.value, mono = c.secret) {
                if (c.secret) {
                    GcIconButton(
                        if (visible) GcIcons.EyeOff else GcIcons.Eye, stringResource(R.string.vault_show_password),
                        onClick = { shown = if (visible) shown - "custom:${c.name}" else shown + "custom:${c.name}" }, size = 44.dp, iconSize = 20.dp,
                    )
                }
                GcIconButton(GcIcons.Copy, stringResource(R.string.vault_copy_x, c.name), onClick = { copy(c.name, c.value, c.secret) }, size = 44.dp, iconSize = 20.dp)
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (rows.isNotEmpty()) {
            VaultRowsCard {
                rows.forEachIndexed { i, r ->
                    if (i > 0) GcRowDivider()
                    r()
                }
            }
        }
        if (data.notes.isNotBlank()) {
            VaultRowsCard {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.vault_field_notes), style = MaterialTheme.typography.labelMedium, color = x.faint)
                    Text(data.notes, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

/** Title block: letter, title, vault chip. */
@Composable
fun VaultEntryHead(data: EntryData, vaultLabel: String, personal: Boolean) {
    val x = GateControlTheme.extraColors
    Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        VaultLetter(data.title, size = 56.dp)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(data.title.ifBlank { "–" }, style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(
                Modifier.clip(RoundedCornerShape(7.dp)).background(x.panel2).padding(horizontal = 9.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(if (personal) GcIcons.Lock else GcIcons.Users, null, tint = x.muted, modifier = Modifier.size(12.dp))
                Text(vaultLabel, style = MaterialTheme.typography.labelMedium, color = x.muted)
            }
        }
    }
}

@Composable
fun VaultEntryScreen(
    id: String,
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    onHistory: (String) -> Unit,
    onForgot: () -> Unit,
    viewModel: VaultViewModel = hiltViewModel(),
) {
    val x = GateControlTheme.extraColors
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val vaults by viewModel.vaults.collectAsStateWithLifecycle()
    var toast by remember { mutableStateOf<String?>(null) }
    var confirmPurge by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    VaultLockGate(viewModel, onForgot) {
        val entry: Entry? = entries.firstOrNull { it.id == id }
        if (entry == null) {
            LaunchedEffect(Unit) { onBack() }
            return@VaultLockGate
        }
        val vault = vaults.firstOrNull { it.id == entry.vaultId }
        val canWrite = vault?.vault?.canWrite == true
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    GcIconButton(GcIcons.Back, stringResource(R.string.vault_back), onClick = onBack, size = 44.dp, tint = MaterialTheme.colorScheme.onBackground)
                    Spacer(Modifier.weight(1f))
                    if (canWrite && !entry.trashed) {
                        GcIconButton(
                            if (entry.data.fav) GcIcons.StarFilled else GcIcons.Star,
                            stringResource(if (entry.data.fav) R.string.vault_fav_remove else R.string.vault_fav_add),
                            onClick = { viewModel.toggleFavorite(entry) }, size = 44.dp,
                            tint = if (entry.data.fav) x.warn else x.muted,
                        )
                        GcIconButton(GcIcons.Edit, stringResource(R.string.vault_edit_title), onClick = { onEdit(entry.id) }, size = 44.dp)
                    }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    VaultEntryHead(entry.data, vaultName(vault), vault?.vault?.personal != false)
                    if (entry.trashed) GcBanner(tone = GcTone.Warn, icon = GcIcons.Trash) { Text(stringResource(R.string.vault_trashed_info), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium) }
                    if (!canWrite) GcBanner(tone = GcTone.Info, icon = GcIcons.Eye) { Text(stringResource(R.string.vault_read_only), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium) }
                    VaultEntryFields(entry.data, viewModel.clipboardSeconds) { toast = it }
                    if (entry.data.type == "wifi") {
                        // 1.4.0: the Wi-Fi QR code, large; the shares on the wall tablet / portal (not before the server has the entry)
                        VaultWifiQrCard(entry.data)
                        if (!entry.pending && !entry.trashed && VaultWifiQr.of(entry.data) != null) VaultWifiShares(entry, canWrite, viewModel) { toast = it }
                    }
                    Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        val ago = vaultAgo(entry.updatedAt)
                        if (entry.pending) {
                            Text(stringResource(R.string.vault_pending), style = MaterialTheme.typography.bodySmall, color = x.warn)
                        } else if (ago.isNotEmpty()) {
                            Text(
                                if (entry.device != null) stringResource(R.string.vault_changed, ago, entry.device) else stringResource(R.string.vault_changed_short, ago),
                                style = MaterialTheme.typography.bodySmall, color = x.faint,
                            )
                        }
                        if (!entry.pending && entry.rev > 1) {
                            Text(
                                stringResource(R.string.vault_history_link), color = x.accentText, style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.clickable { onHistory(entry.id) }.padding(vertical = 8.dp),
                            )
                        }
                    }
                    if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    if (canWrite) {
                        if (entry.trashed) {
                            GcOutlineButton(stringResource(R.string.vault_restore_action), onClick = { viewModel.restore(entry.id) { error = viewModel.message(it) } }, icon = GcIcons.Refresh)
                            GcSecondaryButton(stringResource(R.string.vault_purge_action), onClick = { confirmPurge = true }, icon = GcIcons.Trash)
                        } else if (!entry.pending) {
                            GcSecondaryButton(stringResource(R.string.vault_trash_action), onClick = { viewModel.trash(entry.id) { r -> if (r is VResult.Ok) onBack() else error = viewModel.message(r) } }, icon = GcIcons.Trash)
                        }
                    }
                }
            }
            VaultToast(toast, onGone = { toast = null }, modifier = Modifier.align(Alignment.BottomCenter))
        }
        if (confirmPurge) {
            AlertDialog(
                onDismissRequest = { confirmPurge = false },
                text = { Text(stringResource(R.string.vault_purge_confirm, entry.data.title)) },
                confirmButton = {
                    TextButton(onClick = {
                        confirmPurge = false
                        viewModel.purge(entry.id) { r -> if (r is VResult.Ok) onBack() else error = viewModel.message(r) }
                    }) { Text(stringResource(R.string.vault_purge_action), color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton(onClick = { confirmPurge = false }) { Text(stringResource(R.string.vault_cancel)) } },
            )
        }
    }
}

/** Earlier versions (≤ 10) of an entry; one can be made the current one again. */
@Composable
fun VaultHistoryScreen(id: String, onBack: () -> Unit, onForgot: () -> Unit, viewModel: VaultViewModel = hiltViewModel()) {
    val x = GateControlTheme.extraColors
    var result by remember { mutableStateOf<VResult<List<VaultRepository.Version>>?>(null) }
    var open by remember { mutableStateOf<Int?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(id) { viewModel.history(id) { result = it } }
    VaultLockGate(viewModel, onForgot) {
        val entry = viewModel.repository.entry(id)
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                com.gatecontrol.android.ui.components.GcSubpageBar(stringResource(R.string.vault_history_title), onBack = onBack, backDescription = stringResource(R.string.vault_back))
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when (val r = result) {
                        null -> androidx.compose.material3.CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        is VResult.Ok -> if (r.value.isEmpty()) {
                            Text(stringResource(R.string.vault_history_empty), color = x.muted)
                        } else {
                            r.value.forEach { v ->
                                VaultRowsCard {
                                    Column(Modifier.fillMaxWidth().clickable { open = if (open == v.rev) null else v.rev }.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(v.data.title, style = MaterialTheme.typography.titleMedium)
                                        Text(listOf(vaultAgo(v.updatedAt), v.device.orEmpty()).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = x.muted)
                                    }
                                    if (open == v.rev) {
                                        GcRowDivider()
                                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                            VaultEntryFields(v.data, viewModel.clipboardSeconds) { toast = it }
                                            if (entry != null) {
                                                GcOutlineButton(stringResource(R.string.vault_history_restore), icon = GcIcons.History, onClick = {
                                                    viewModel.save(entry.id, entry.vaultId, v.data) { onBack() }
                                                })
                                            }
                                        }
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
