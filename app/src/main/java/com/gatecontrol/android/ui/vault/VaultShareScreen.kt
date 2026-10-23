package com.gatecontrol.android.ui.vault

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.ui.components.GcBanner
import com.gatecontrol.android.ui.components.GcChevron
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcListRow
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.components.GcRowDivider
import com.gatecontrol.android.ui.components.GcSecondaryButton
import com.gatecontrol.android.ui.components.GcSectionLabel
import com.gatecontrol.android.ui.components.GcSubpageBar
import com.gatecontrol.android.ui.components.GcTextField
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.theme.GateControlTheme
import com.gatecontrol.android.ui.theme.MonoFontFamily
import com.gatecontrol.android.vault.VPerson
import com.gatecontrol.android.vault.VResult
import com.gatecontrol.android.vault.VaultCrypto

@Composable
fun vaultRoleLabel(role: String): String = stringResource(
    when (role) {
        "manage" -> R.string.vault_role_manage
        "write" -> R.string.vault_role_write
        else -> R.string.vault_role_read
    },
)

/** Round initial of a person. */
@Composable
fun VaultPersonDot(name: String) {
    val (bg, fg) = vaultTone(name)
    Box(Modifier.size(40.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        Text(name.trim().firstOrNull()?.uppercase() ?: "?", color = fg, fontWeight = FontWeight.Bold)
    }
}

/** A person's safety number in two rows of four groups (mockup A8). */
@Composable
fun VaultSafetyNumber(number: String) {
    val groups = number.split(' ')
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        groups.chunked(4).forEach { row ->
            Text(row.joinToString("  "), fontFamily = MonoFontFamily, fontSize = 17.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp)
        }
    }
}

/** All vaults: the personal one, shared ones with their members; a new shared vault. */
@Composable
fun VaultVaultsScreen(onBack: () -> Unit, onOpen: (String) -> Unit, onForgot: () -> Unit, viewModel: VaultViewModel = hiltViewModel()) {
    val x = GateControlTheme.extraColors
    val vaults by viewModel.vaults.collectAsStateWithLifecycle()
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    var create by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    VaultLockGate(viewModel, onForgot) {
        Column(Modifier.fillMaxSize()) {
            GcSubpageBar(stringResource(R.string.vault_vaults_title), onBack = onBack, backDescription = stringResource(R.string.vault_back))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                VaultRowsCard {
                    vaults.forEachIndexed { i, v ->
                        if (i > 0) GcRowDivider()
                        val count = entries.count { it.vaultId == v.id && !it.trashed }
                        GcListRow(
                            title = v.name,
                            description = listOfNotNull(
                                pluralStringResource(R.plurals.vault_entries_count, count, count),
                                if (v.vault.personal) null else stringResource(R.string.vault_members, v.vault.members.size),
                                if (v.vault.personal) null else vaultRoleLabel(v.vault.role),
                            ).joinToString(" · "),
                            onClick = if (v.vault.personal) null else ({ onOpen(v.id) }),
                            leading = { Icon(if (v.vault.personal) GcIcons.Lock else GcIcons.Users, null, tint = if (v.vault.personal) x.accentText else x.blue, modifier = Modifier.size(22.dp)) },
                            trailing = if (v.vault.personal) null else ({ GcChevron() }),
                        )
                    }
                }
                if (create) {
                    VaultRowsCard {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            GcTextField(name, { name = it; error = null }, stringResource(R.string.vault_new_vault_name), placeholder = stringResource(R.string.vault_new_vault_hint), error = error)
                            GcPrimaryButton(stringResource(R.string.vault_create), enabled = name.isNotBlank(), onClick = {
                                viewModel.createVault(name) { r ->
                                    if (r is VResult.Ok) {
                                        create = false
                                        name = ""
                                    } else {
                                        error = viewModel.message(r)
                                    }
                                }
                            })
                        }
                    }
                } else {
                    GcOutlineButton(stringResource(R.string.vault_new_vault), onClick = { create = true }, icon = GcIcons.Plus)
                }
            }
        }
    }
}

/** One shared vault: members with roles, add a person after comparing the safety number (mockup A8). */
@Composable
fun VaultShareScreen(id: String, onBack: () -> Unit, onForgot: () -> Unit, viewModel: VaultViewModel = hiltViewModel()) {
    val x = GateControlTheme.extraColors
    val vaults by viewModel.vaults.collectAsStateWithLifecycle()
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    var people by remember { mutableStateOf<List<VPerson>?>(null) }
    var adding by remember { mutableStateOf<VPerson?>(null) }
    var addRole by remember { mutableStateOf("write") }
    var picker by remember { mutableStateOf(false) }
    var roleMenu by remember { mutableStateOf<Int?>(null) }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    var rename by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { viewModel.people { r -> people = r.valueOrNull() } }
    VaultLockGate(viewModel, onForgot) {
        val v = vaults.firstOrNull { it.id == id }
        if (v == null) {
            LaunchedEffect(Unit) { onBack() }
            return@VaultLockGate
        }
        val me = viewModel.myUserId()
        val manage = v.vault.canManage
        val count = entries.count { it.vaultId == id && !it.trashed }
        Column(Modifier.fillMaxSize()) {
            GcSubpageBar(stringResource(R.string.vault_share_title, v.name), onBack = onBack, backDescription = stringResource(R.string.vault_back))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(Modifier.padding(horizontal = 4.dp)) {
                    GcSectionLabel(stringResource(R.string.vault_members, v.vault.members.size), Modifier.weight(1f))
                    GcSectionLabel(pluralStringResource(R.plurals.vault_entries_count, count, count))
                }
                if (v.vault.needsRekey || v.vault.pending != null) {
                    GcBanner(tone = GcTone.Info, icon = GcIcons.Key) { Text(stringResource(R.string.vault_rekey_needed), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium) }
                }
                VaultRowsCard {
                    v.vault.members.forEachIndexed { i, m ->
                        if (i > 0) GcRowDivider()
                        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            VaultPersonDot(m.name)
                            Text(if (m.userId == me) stringResource(R.string.vault_you, m.name) else m.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            Box {
                                val canChange = manage && m.userId != me
                                Row(
                                    Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, x.border2, RoundedCornerShape(10.dp)).background(x.panel2)
                                        .then(if (canChange) Modifier.clickable(role = Role.DropdownList) { roleMenu = m.userId } else Modifier).padding(horizontal = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) { Text(vaultRoleLabel(m.role), style = MaterialTheme.typography.labelLarge) }
                                DropdownMenu(roleMenu == m.userId, onDismissRequest = { roleMenu = null }) {
                                    listOf("read", "write", "manage").forEach { r ->
                                        DropdownMenuItem(text = { Text(vaultRoleLabel(r)) }, onClick = {
                                            roleMenu = null
                                            viewModel.setRole(id, m.userId, r) { res -> error = viewModel.message(res) }
                                        })
                                    }
                                    DropdownMenuItem(text = { Text(stringResource(R.string.vault_remove_member), color = MaterialTheme.colorScheme.error) }, onClick = {
                                        roleMenu = null
                                        confirm = (m.name to { viewModel.removeMember(id, m.userId) { res -> error = viewModel.message(res) } })
                                    })
                                }
                            }
                        }
                    }
                }
                val add = adding
                if (add != null && add.publicKey != null) {
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(x.blueBg).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(stringResource(R.string.vault_add_title, add.name), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.vault_safety_text, add.name), style = MaterialTheme.typography.bodyMedium, color = x.muted)
                        val number = remember(add) { runCatching { VaultCrypto.safetyNumber(VaultCrypto.unb64(add.publicKey)) }.getOrNull() }
                        if (number != null) VaultSafetyNumber(number)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("read", "write", "manage").forEach { r ->
                                com.gatecontrol.android.ui.components.GcFilterChip(vaultRoleLabel(r), addRole == r, onClick = { addRole = r })
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            GcOutlineButton(stringResource(R.string.vault_cancel), onClick = { adding = null }, modifier = Modifier.weight(1f))
                            GcPrimaryButton(stringResource(R.string.vault_safety_match), modifier = Modifier.weight(1f), icon = GcIcons.Check, onClick = {
                                viewModel.addMember(id, add, addRole) { r ->
                                    if (r is VResult.Ok) adding = null else error = viewModel.message(r)
                                }
                            })
                        }
                    }
                } else if (manage && v.vault.pending == null) {
                    Box {
                        GcPrimaryButton(stringResource(R.string.vault_add_person), onClick = { picker = true }, icon = GcIcons.Plus)
                        DropdownMenu(picker, onDismissRequest = { picker = false }) {
                            val candidates = people.orEmpty().filter { p -> v.vault.members.none { it.userId == p.id } }
                            if (candidates.isEmpty()) DropdownMenuItem(text = { Text(stringResource(R.string.vault_no_people)) }, onClick = { picker = false }, enabled = false)
                            candidates.forEach { p ->
                                DropdownMenuItem(
                                    text = { Text(if (p.publicKey == null) "${p.name} – ${stringResource(R.string.vault_person_no_vault)}" else p.name) },
                                    enabled = p.publicKey != null,
                                    onClick = { picker = false; adding = p; addRole = "write" },
                                )
                            }
                        }
                    }
                }
                if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.vault_remove_info), style = MaterialTheme.typography.bodySmall, color = x.muted, modifier = Modifier.padding(horizontal = 4.dp))
                if (manage) GcSecondaryButton(stringResource(R.string.vault_rename), onClick = { rename = v.name }, icon = GcIcons.Edit)
                GcSecondaryButton(stringResource(R.string.vault_leave), onClick = { confirm = ("#leave" to { viewModel.removeMember(id, me ?: -1) { r -> if (r is VResult.Ok) onBack() else error = viewModel.message(r) } }) }, icon = GcIcons.Back)
                if (manage) GcSecondaryButton(stringResource(R.string.vault_delete_vault), onClick = {
                    confirm = ("#delete" to { viewModel.deleteVault(id) { r -> if (r is VResult.Ok) onBack() else error = viewModel.message(r) } })
                }, icon = GcIcons.Trash)
            }
        }
        confirm?.let { (who, action) ->
            AlertDialog(
                onDismissRequest = { confirm = null },
                text = {
                    Text(
                        when {
                            who == "#delete" -> stringResource(R.string.vault_delete_vault_confirm, v.name)
                            who == "#leave" -> stringResource(R.string.vault_leave_confirm, v.name)
                            else -> stringResource(R.string.vault_member_remove_confirm, who, v.name)
                        },
                    )
                },
                confirmButton = {
                    TextButton(onClick = { confirm = null; action() }) {
                        Text(
                            stringResource(
                                when (who) {
                                    "#delete" -> R.string.vault_delete_vault
                                    "#leave" -> R.string.vault_leave
                                    else -> R.string.vault_remove_member
                                },
                            ),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                },
                dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.vault_cancel)) } },
            )
        }
        rename?.let { current ->
            var value by remember { mutableStateOf(current) }
            AlertDialog(
                onDismissRequest = { rename = null },
                title = { Text(stringResource(R.string.vault_rename)) },
                text = { GcTextField(value, { value = it }, stringResource(R.string.vault_new_vault_name)) },
                confirmButton = { TextButton(enabled = value.isNotBlank(), onClick = { rename = null; viewModel.renameVault(id, value) { r -> error = viewModel.message(r) } }) { Text(stringResource(R.string.vault_save)) } },
                dismissButton = { TextButton(onClick = { rename = null }) { Text(stringResource(R.string.vault_cancel)) } },
            )
        }
    }
}

/** The trash: restore or delete for good (deleted by the server after 30 days anyway). */
@Composable
fun VaultTrashScreen(onBack: () -> Unit, onOpen: (String) -> Unit, onForgot: () -> Unit, viewModel: VaultViewModel = hiltViewModel()) {
    val x = GateControlTheme.extraColors
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    VaultLockGate(viewModel, onForgot) {
        val trash = entries.filter { it.trashed }
        Column(Modifier.fillMaxSize()) {
            GcSubpageBar(stringResource(R.string.vault_section_trash), onBack = onBack, backDescription = stringResource(R.string.vault_back))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (trash.isEmpty()) {
                    Text(stringResource(R.string.vault_trash_empty), color = x.muted, modifier = Modifier.padding(16.dp))
                } else {
                    Text(stringResource(R.string.vault_trashed_info), style = MaterialTheme.typography.bodySmall, color = x.muted, modifier = Modifier.padding(horizontal = 4.dp))
                    VaultRowsCard {
                        trash.forEachIndexed { i, e ->
                            if (i > 0) GcRowDivider()
                            VaultEntryRow(e, shared = false, showCode = false, onClick = { onOpen(e.id) })
                        }
                    }
                }
            }
        }
    }
}
