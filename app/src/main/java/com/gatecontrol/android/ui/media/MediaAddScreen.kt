package com.gatecontrol.android.ui.media

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.media.MediaAddProblem
import com.gatecontrol.android.media.MediaAddress
import com.gatecontrol.android.media.MediaLogic
import com.gatecontrol.android.media.MediaSourceKind
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcChip
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.components.GcRowDivider
import com.gatecontrol.android.ui.components.GcSectionLabel
import com.gatecontrol.android.ui.components.GcSegmented
import com.gatecontrol.android.ui.components.GcSubpageBar
import com.gatecontrol.android.ui.components.GcTextField
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.theme.GateControlTheme
import com.gatecontrol.android.ui.theme.MonoFontFamily

/**
 * Medien › Quellen › Quelle hinzufügen (mockup 6): 1 · Typ, 2 · the Jellyfin server found in the Wi-Fi (or its
 * address), 3 · Quick Connect or name and password. A share: its address, name and password.
 */
@Composable
fun MediaAddScreen(onBack: () -> Unit, onDone: () -> Unit, viewModel: MediaAddViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val x = GateControlTheme.extraColors
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState())) {
        GcSubpageBar(title = stringResource(R.string.media_add_source), onBack = onBack, backDescription = stringResource(R.string.common_back))
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (ui.done) {
                Done(ui, onDone)
                return@Column
            }
            GcSectionLabel(stringResource(R.string.media_step_type), modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
            GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.Top) {
                KindRow(MediaSourceKind.JELLYFIN, ui.kind, R.string.media_type_jellyfin, R.string.media_type_jellyfin_text, recommended = true, onChoose = viewModel::chooseKind)
                GcRowDivider()
                KindRow(MediaSourceKind.SMB, ui.kind, R.string.media_type_smb, R.string.media_type_smb_text, onChoose = viewModel::chooseKind)
                GcRowDivider()
                KindRow(MediaSourceKind.WEBDAV, ui.kind, R.string.media_type_webdav, R.string.media_type_webdav_text, onChoose = viewModel::chooseKind)
            }
            if (ui.kind == MediaSourceKind.JELLYFIN) JellyfinSteps(ui, viewModel) else ShareStep(ui, viewModel)
            Text(stringResource(R.string.media_encrypted), style = MaterialTheme.typography.bodySmall, color = x.faint, modifier = Modifier.padding(horizontal = 4.dp))
        }
    }
}

@Composable
private fun KindRow(kind: MediaSourceKind, chosen: MediaSourceKind, title: Int, text: Int, recommended: Boolean = false, onChoose: (MediaSourceKind) -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = kind == chosen, role = Role.RadioButton) { onChoose(kind) }.padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = kind == chosen, onClick = null, modifier = Modifier.padding(horizontal = 8.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                if (recommended) GcChip(text = stringResource(R.string.media_recommended), tone = GcTone.Ok)
            }
            Text(stringResource(text), style = MaterialTheme.typography.bodySmall, color = GateControlTheme.extraColors.muted)
        }
    }
}

/** 2 · found in the Wi-Fi (or typed), 3 · Quick Connect or name and password. */
@Composable
private fun JellyfinSteps(ui: MediaAddUi, viewModel: MediaAddViewModel) {
    val x = GateControlTheme.extraColors
    GcSectionLabel(stringResource(R.string.media_step_found), modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
    if (ui.searching) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Text(stringResource(R.string.media_searching), style = MaterialTheme.typography.bodyMedium, color = x.muted)
        }
    }
    ui.found.forEach { hint ->
        val chosen = ui.server != null && ui.address == hint.address
        GcCard(
            Modifier.clip(MaterialTheme.shapes.medium).selectable(selected = chosen, role = Role.RadioButton) { viewModel.pick(hint) },
            borderColor = if (chosen) MaterialTheme.colorScheme.primary else x.border,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(GcIcons.Film, contentDescription = null, tint = x.accentText, modifier = Modifier.size(22.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.media_found_name, hint.name.ifBlank { "Jellyfin" }), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(hint.address.substringAfter("://"), style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily), color = x.muted)
                }
                if (chosen) Icon(GcIcons.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            }
        }
    }
    if (ui.searched && ui.found.isEmpty() && !ui.searching) {
        Text(stringResource(if (ui.away) R.string.media_search_away else R.string.media_none_found), style = MaterialTheme.typography.bodySmall, color = x.muted, modifier = Modifier.padding(horizontal = 4.dp))
    }
    GcTextField(
        value = ui.address,
        onValueChange = viewModel::setAddress,
        label = stringResource(R.string.media_address),
        placeholder = "192.168.1.20:8096",
        mono = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
        error = ui.problem?.takeIf { ui.server == null }?.let { problemText(it, MediaSourceKind.JELLYFIN, ui.address) },
        hint = ui.server?.let { stringResource(R.string.media_found, it.name, it.version ?: "?") },
        modifier = Modifier.testTag(ADDRESS_TAG),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GcOutlineButton(stringResource(R.string.media_search_again), onClick = viewModel::search, enabled = !ui.searching, modifier = Modifier.weight(1f))
        GcPrimaryButton(stringResource(R.string.media_check), onClick = viewModel::check, loading = ui.checking, enabled = ui.address.isNotBlank(), modifier = Modifier.weight(1f))
    }
    if (ui.server == null) return
    GcSectionLabel(stringResource(R.string.media_step_login), modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
    GcSegmented(
        options = listOf(true to stringResource(R.string.media_quick_connect), false to stringResource(R.string.media_password_login)),
        selected = ui.quickConnect,
        onSelect = viewModel::chooseQuickConnect,
    )
    if (ui.quickConnect) {
        GcCard {
            Text(stringResource(R.string.media_quick_code), style = MaterialTheme.typography.labelMedium, color = x.muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Text(
                ui.code?.let { formatCode(it) } ?: "…",
                fontFamily = MonoFontFamily,
                fontSize = 28.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().testTag(QUICK_CODE_TAG),
            )
            Text(stringResource(R.string.media_quick_hint), style = MaterialTheme.typography.bodySmall, color = x.muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
            if (ui.waiting || ui.signingIn) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Text(
                stringResource(if (ui.signingIn) R.string.media_signing_in else R.string.media_waiting),
                style = MaterialTheme.typography.bodyMedium,
                color = x.muted,
            )
        }
        ui.problem?.let { Text(problemText(it, MediaSourceKind.JELLYFIN, ui.address), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    } else {
        LoginFields(ui, onSubmit = viewModel::signIn, submit = stringResource(R.string.media_sign_in))
    }
}

/** A share: address, name and password on one step (mockup 6). */
@Composable
private fun ShareStep(ui: MediaAddUi, viewModel: MediaAddViewModel) {
    GcSectionLabel(stringResource(R.string.media_step_share), modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
    GcTextField(
        value = ui.address,
        onValueChange = viewModel::setAddress,
        label = stringResource(R.string.media_address),
        placeholder = if (ui.kind == MediaSourceKind.SMB) "\\\\nas\\filme" else "https://192.168.1.20:5006/filme",
        mono = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
        hint = stringResource(if (ui.kind == MediaSourceKind.SMB) R.string.media_share_hint_smb else R.string.media_share_hint_webdav),
        modifier = Modifier.testTag(ADDRESS_TAG),
    )
    LoginFields(ui, onSubmit = viewModel::connectShare, submit = stringResource(R.string.media_connect), guest = true)
    Text(stringResource(R.string.media_smb_webdav_hint), style = MaterialTheme.typography.bodySmall, color = GateControlTheme.extraColors.muted, modifier = Modifier.padding(horizontal = 4.dp))
}

/** Name and password: sent once (Jellyfin) or kept encrypted (a share). */
@Composable
private fun LoginFields(ui: MediaAddUi, onSubmit: (String, String) -> Unit, submit: String, guest: Boolean = false) {
    var name by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    GcTextField(
        value = name,
        onValueChange = { name = it },
        label = stringResource(R.string.media_user),
        hint = if (guest) stringResource(R.string.media_share_login_hint) else null,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        modifier = Modifier.testTag(USER_TAG),
    )
    GcTextField(
        value = password,
        onValueChange = { password = it },
        label = stringResource(R.string.media_password),
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        error = ui.problem?.let { problemText(it, ui.kind) },
        modifier = Modifier.testTag(PASSWORD_TAG),
    )
    GcPrimaryButton(submit, onClick = { onSubmit(name, password) }, loading = ui.signingIn, enabled = guest || name.isNotBlank())
}

/** Signed in or connected; a share paired with its partner. */
@Composable
private fun Done(ui: MediaAddUi, onDone: () -> Unit) {
    val x = GateControlTheme.extraColors
    GcCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(GcIcons.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Text(stringResource(R.string.media_added), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }
        ui.pairedWith?.let {
            Text(stringResource(R.string.media_paired, MediaLogic.shareAddress(it)), style = MaterialTheme.typography.bodySmall, color = x.muted)
        }
        GcPrimaryButton(stringResource(R.string.media_done), onClick = onDone)
    }
}

/** "482913" → "482 913". */
internal fun formatCode(code: String): String = if (code.length == 6 && code.all { it.isDigit() }) code.substring(0, 3) + " " + code.substring(3) else code

@Composable
private fun problemText(p: MediaAddProblem, kind: MediaSourceKind, address: String = ""): String {
    if (p == MediaAddProblem.OFFLINE_AWAY && !kind.share) {
        val host = MediaAddress.normalize(address)?.let(MediaAddress::host)
        return stringResource(R.string.media_problem_unreachable_away, MediaAddress.subnet24(host) ?: host ?: "192.168.1.0/24")
    }
    return problemTextOf(p, kind)
}

@Composable
private fun problemTextOf(p: MediaAddProblem, kind: MediaSourceKind): String = stringResource(
    when (p) {
        MediaAddProblem.BAD_ADDRESS -> when (kind) {
            MediaSourceKind.JELLYFIN -> R.string.media_problem_address
            MediaSourceKind.SMB -> R.string.media_problem_share_address
            MediaSourceKind.WEBDAV -> R.string.media_problem_webdav_address
        }
        MediaAddProblem.NOT_FOUND -> if (kind.share) R.string.media_problem_share_not_found else R.string.media_problem_not_found
        MediaAddProblem.OLD_SERVER -> R.string.media_problem_old
        MediaAddProblem.NO_QUICK_CONNECT -> R.string.media_problem_no_quick_connect
        MediaAddProblem.WRONG_LOGIN -> R.string.media_problem_login
        MediaAddProblem.OFFLINE -> if (kind.share) R.string.media_problem_share_offline else R.string.media_problem_unreachable
        MediaAddProblem.OFFLINE_AWAY -> R.string.media_problem_share_offline
        MediaAddProblem.CERTIFICATE -> R.string.media_problem_certificate
        MediaAddProblem.FAILED -> R.string.media_problem_failed
    },
)

internal const val ADDRESS_TAG = "media-address"
internal const val USER_TAG = "media-user"
internal const val PASSWORD_TAG = "media-password"
internal const val QUICK_CODE_TAG = "media-quick-code"
