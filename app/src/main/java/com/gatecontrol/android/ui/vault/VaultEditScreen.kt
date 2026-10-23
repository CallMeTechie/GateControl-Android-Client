package com.gatecontrol.android.ui.vault

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.ui.components.GcChevron
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.components.GcSecondaryButton
import com.gatecontrol.android.ui.components.GcSectionLabel
import com.gatecontrol.android.ui.components.GcSegmented
import com.gatecontrol.android.ui.components.GcSwitchRow
import com.gatecontrol.android.ui.components.GcTextField
import com.gatecontrol.android.ui.theme.GateControlTheme
import com.gatecontrol.android.ui.theme.MonoFontFamily
import com.gatecontrol.android.vault.EntryData
import com.gatecontrol.android.vault.VaultGenerator
import com.gatecontrol.android.vault.VaultRepository
import com.gatecontrol.android.vault.VaultTotp

/** The fields an entry type edits, in order (key → label). */
private fun fieldsOf(type: String): List<Pair<String, Int>> = when (type) {
    "login" -> listOf("username" to R.string.vault_field_username, "password" to R.string.vault_field_password, "totp" to R.string.vault_field_totp_secret)
    "totp" -> listOf("totp" to R.string.vault_field_totp_secret)
    "card" -> listOf("holder" to R.string.vault_field_holder, "number" to R.string.vault_field_number, "expiry" to R.string.vault_field_expiry, "cvc" to R.string.vault_field_cvc, "pin" to R.string.vault_field_pin)
    "wifi" -> listOf("ssid" to R.string.vault_field_ssid, "password" to R.string.vault_field_password, "security" to R.string.vault_field_security)
    "identity" -> listOf("name" to R.string.vault_field_name, "email" to R.string.vault_field_email, "phone" to R.string.vault_field_phone, "address" to R.string.vault_field_address, "birthday" to R.string.vault_field_birthday)
    else -> emptyList()
}

private val SECRET_FIELDS = setOf("password", "cvc", "pin", "number", "totp")

/** Generated password in the sheet: digits blue, symbols amber (mockup A6). */
@Composable
private fun ColoredPassword(text: String) {
    val x = GateControlTheme.extraColors
    val s = buildAnnotatedString {
        text.forEach { c ->
            when {
                c.isDigit() -> withStyle(SpanStyle(color = x.blue)) { append(c) }
                !c.isLetterOrDigit() -> withStyle(SpanStyle(color = x.warn)) { append(c) }
                else -> append(c)
            }
        }
    }
    Text(s, fontFamily = MonoFontFamily, fontSize = 20.sp, letterSpacing = 1.sp, modifier = Modifier.fillMaxWidth())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultGeneratorSheet(onDismiss: () -> Unit, onUse: (String) -> Unit) {
    val x = GateControlTheme.extraColors
    var o by remember { mutableStateOf(VaultGenerator.Options()) }
    var pw by remember { mutableStateOf(VaultGenerator.generate(o)) }
    fun set(n: VaultGenerator.Options) {
        o = n
        pw = VaultGenerator.generate(n)
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.vault_generate), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(x.panel2).border(1.dp, x.border2, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                    GcIconButton(GcIcons.Refresh, stringResource(R.string.vault_gen_again), onClick = { pw = VaultGenerator.generate(o) }, size = 44.dp, tint = MaterialTheme.colorScheme.onSurface)
                }
            }
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.background).padding(16.dp)) { ColoredPassword(pw) }
            GcSegmented(
                options = listOf(false to stringResource(R.string.vault_gen_random), true to stringResource(R.string.vault_gen_words)),
                selected = o.words, onSelect = { set(o.copy(words = it)) },
            )
            if (o.words) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.vault_gen_word_count), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Text("${o.wordCount}", fontFamily = MonoFontFamily)
                }
                Slider(o.wordCount.toFloat(), onValueChange = { set(o.copy(wordCount = it.toInt())) }, valueRange = 3f..8f, steps = 4, colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary, activeTrackColor = MaterialTheme.colorScheme.primary))
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.vault_gen_length), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Text("${o.length}", fontFamily = MonoFontFamily)
                }
                Slider(o.length.toFloat(), onValueChange = { set(o.copy(length = it.toInt())) }, valueRange = 8f..64f, colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary, activeTrackColor = MaterialTheme.colorScheme.primary))
                VaultRowsCard {
                    GcSwitchRow(description = null, label = stringResource(R.string.vault_gen_upper), checked = o.upper, onCheckedChange = { set(o.copy(upper = it)) })
                    GcSwitchRow(description = null, label = stringResource(R.string.vault_gen_digits), checked = o.digits, onCheckedChange = { set(o.copy(digits = it)) })
                    GcSwitchRow(description = null, label = stringResource(R.string.vault_gen_symbols), checked = o.symbols, onCheckedChange = { set(o.copy(symbols = it)) })
                    GcSwitchRow(description = null, label = stringResource(R.string.vault_gen_ambiguous), checked = o.avoidAmbiguous, onCheckedChange = { set(o.copy(avoidAmbiguous = it)) })
                }
            }
            GcPrimaryButton(stringResource(R.string.vault_gen_use), onClick = { onUse(pw) }, minHeight = 52.dp)
        }
    }
}

/** New entry ([type]) or an existing one ([id]); [vaultHint] preselects the vault. */
@Composable
fun VaultEditScreen(
    id: String?,
    type: String?,
    vaultHint: String?,
    onDone: (String) -> Unit,
    onBack: () -> Unit,
    onForgot: () -> Unit,
    viewModel: VaultViewModel = hiltViewModel(),
) {
    val x = GateControlTheme.extraColors
    val vaults by viewModel.vaults.collectAsStateWithLifecycle()
    VaultLockGate(viewModel, onForgot) {
        val existing = remember(id) { id?.let { viewModel.repository.entry(it) } }
        val start = existing?.data ?: EntryData(type = type?.takeIf { it in EntryData.TYPES } ?: "login", title = "")
        val writable = vaults.filter { it.vault.canWrite }
        var draft by remember(id) { mutableStateOf(start) }
        var vaultId by rememberSaveable(id) {
            mutableStateOf(existing?.vaultId ?: writable.firstOrNull { it.id == vaultHint }?.id ?: writable.firstOrNull { it.vault.personal }?.id ?: writable.firstOrNull()?.id)
        }
        var generator by remember { mutableStateOf(false) }
        var discard by remember { mutableStateOf(false) }
        var vaultMenu by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var busy by remember { mutableStateOf(false) }
        val dirty = draft != start || (existing != null && vaultId != existing.vaultId)
        BackHandler(enabled = dirty) { discard = true }
        val msgTitle = stringResource(R.string.vault_edit_title_required)
        val msgReadOnly = stringResource(R.string.vault_save_readonly)
        val msgTooLarge = stringResource(R.string.vault_save_too_large)
        val msgLocked = stringResource(R.string.vault_save_locked)

        fun save() {
            val title = draft.title.trim().ifEmpty {
                // a 2FA code may take its title from the otpauth link
                VaultTotp.label(draft.totp).orEmpty()
            }
            if (title.isEmpty()) return run { error = msgTitle }
            val target = vaultId ?: return run { error = msgReadOnly }
            busy = true
            viewModel.save(existing?.id, target, draft.copy(title = title)) { r ->
                busy = false
                when (r) {
                    is VaultRepository.Save.Ok -> onDone(r.id)
                    VaultRepository.Save.ReadOnly -> error = msgReadOnly
                    VaultRepository.Save.TooLarge -> error = msgTooLarge
                    VaultRepository.Save.Locked -> error = msgLocked
                }
            }
        }

        Column(Modifier.fillMaxSize().imePadding()) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                GcIconButton(GcIcons.Close, stringResource(R.string.vault_cancel), onClick = { if (dirty) discard = true else onBack() }, size = 44.dp, tint = MaterialTheme.colorScheme.onBackground)
                Text(
                    if (existing != null) stringResource(R.string.vault_edit_title) else stringResource(
                        when (draft.type) {
                            "totp" -> R.string.vault_new_totp
                            "note" -> R.string.vault_new_note
                            "card" -> R.string.vault_new_card
                            "wifi" -> R.string.vault_new_wifi
                            "identity" -> R.string.vault_new_identity
                            else -> R.string.vault_new_login
                        },
                    ),
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = 17.sp), modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { save() }, enabled = !busy) {
                    Text(stringResource(R.string.vault_save), color = x.accentText, style = MaterialTheme.typography.titleMedium)
                }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                GcTextField(draft.title, { draft = draft.copy(title = it) }, stringResource(R.string.vault_field_title))
                fieldsOf(draft.type).forEach { (key, label) ->
                    val v = draft.field(key)
                    val update: (String) -> Unit = { nv -> draft = draft.copy(fields = draft.fields + (key to nv)) }
                    when {
                        key == "password" -> VaultPasswordField(v, update, stringResource(label), trailingExtra = {
                            GcIconButton(GcIcons.Refresh, stringResource(R.string.vault_generate), onClick = { generator = true }, size = 44.dp, iconSize = 20.dp)
                        })
                        key in SECRET_FIELDS && key != "totp" -> VaultPasswordField(v, update, stringResource(label))
                        key == "totp" -> GcTextField(
                            v, update, stringResource(label), mono = true,
                            error = if (v.isNotBlank() && VaultTotp.parse(v) == null) stringResource(R.string.vault_totp_invalid) else null,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                        )
                        key == "address" -> GcTextField(v, update, stringResource(label), singleLine = false)
                        else -> GcTextField(
                            v, update, stringResource(label),
                            keyboardOptions = when (key) {
                                "email", "username" -> KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false)
                                "phone" -> KeyboardOptions(keyboardType = KeyboardType.Phone)
                                "number", "expiry" -> KeyboardOptions(keyboardType = KeyboardType.Number)
                                else -> KeyboardOptions.Default
                            },
                        )
                    }
                }
                if (draft.type == "login") {
                    val urls = draft.urls.ifEmpty { listOf("") }
                    urls.forEachIndexed { i, u ->
                        GcTextField(u, { nv -> draft = draft.copy(urls = urls.toMutableList().also { it[i] = nv }) }, stringResource(R.string.vault_field_url),
                            placeholder = "example.com", keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false))
                    }
                    draft.apps.forEachIndexed { i, a ->
                        GcTextField(a, { nv -> draft = draft.copy(apps = draft.apps.toMutableList().also { it[i] = nv }) }, stringResource(R.string.vault_field_app),
                            mono = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                            trailingIcon = { GcIconButton(GcIcons.Close, stringResource(R.string.vault_custom_remove), onClick = { draft = draft.copy(apps = draft.apps.filterIndexed { j, _ -> j != i }) }, size = 44.dp, iconSize = 18.dp) })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GcSecondaryButton(stringResource(R.string.vault_add_url), onClick = { draft = draft.copy(urls = urls + "") }, icon = GcIcons.Plus, fillWidth = false)
                        GcSecondaryButton(stringResource(R.string.vault_add_app), onClick = { draft = draft.copy(apps = draft.apps + "") }, icon = GcIcons.Plus, fillWidth = false)
                    }
                }
                // vault
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.vault_field_vault), style = MaterialTheme.typography.titleSmall, color = x.muted)
                    Box {
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.background)
                                .border(1.dp, x.border2, RoundedCornerShape(14.dp)).clickable(role = Role.Button) { vaultMenu = true }.padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            val v = vaults.firstOrNull { it.id == vaultId }
                            Icon(if (v?.vault?.personal != false) GcIcons.Lock else GcIcons.Users, null, tint = x.muted, modifier = Modifier.size(18.dp))
                            Text(vaultName(v), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            GcChevron()
                        }
                        DropdownMenu(vaultMenu, onDismissRequest = { vaultMenu = false }) {
                            writable.forEach { v -> DropdownMenuItem(text = { Text(v.name) }, onClick = { vaultId = v.id; vaultMenu = false }) }
                        }
                    }
                }
                GcTextField(draft.notes, { draft = draft.copy(notes = it) }, stringResource(R.string.vault_field_notes), singleLine = false)
                // more fields
                GcSectionLabel(stringResource(R.string.vault_custom_fields))
                draft.custom.forEachIndexed { i, c ->
                    VaultRowsCard {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            fun upd(n: EntryData.CustomField) { draft = draft.copy(custom = draft.custom.toMutableList().also { it[i] = n }) }
                            GcTextField(c.name, { upd(c.copy(name = it)) }, stringResource(R.string.vault_custom_name),
                                trailingIcon = { GcIconButton(GcIcons.Close, stringResource(R.string.vault_custom_remove), onClick = { draft = draft.copy(custom = draft.custom.filterIndexed { j, _ -> j != i }) }, size = 44.dp, iconSize = 18.dp) })
                            if (c.secret) VaultPasswordField(c.value, { upd(c.copy(value = it)) }, stringResource(R.string.vault_custom_value))
                            else GcTextField(c.value, { upd(c.copy(value = it)) }, stringResource(R.string.vault_custom_value))
                            GcSwitchRow(description = null, label = stringResource(R.string.vault_custom_secret), checked = c.secret, onCheckedChange = { upd(c.copy(secret = it)) })
                        }
                    }
                }
                GcSecondaryButton(stringResource(R.string.vault_custom_add), onClick = { draft = draft.copy(custom = draft.custom + EntryData.CustomField("", "", false)) }, icon = GcIcons.Plus, fillWidth = false)
                if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
            }
        }
        if (generator) {
            VaultGeneratorSheet(onDismiss = { generator = false }, onUse = { pw ->
                draft = draft.copy(fields = draft.fields + ("password" to pw))
                generator = false
            })
        }
        if (discard) {
            AlertDialog(
                onDismissRequest = { discard = false },
                text = { Text(stringResource(R.string.vault_discard)) },
                confirmButton = { TextButton(onClick = { discard = false; onBack() }) { Text(stringResource(R.string.vault_discard_yes), color = MaterialTheme.colorScheme.error) } },
                dismissButton = { TextButton(onClick = { discard = false }) { Text(stringResource(R.string.vault_keep_editing)) } },
            )
        }
        LaunchedEffect(vaults) { if (vaultId == null) vaultId = writable.firstOrNull()?.id }
    }
}
