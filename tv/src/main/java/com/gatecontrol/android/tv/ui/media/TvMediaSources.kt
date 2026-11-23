package com.gatecontrol.android.tv.ui.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.gatecontrol.android.media.MediaSource
import com.gatecontrol.android.tv.R
import com.gatecontrol.android.tv.media.TvAddProblem
import com.gatecontrol.android.tv.media.TvAddStep
import com.gatecontrol.android.tv.media.TvAddUi
import com.gatecontrol.android.tv.media.TvMediaLogic
import com.gatecontrol.android.tv.media.TvMediaUi
import com.gatecontrol.android.tv.ui.components.TvButton
import com.gatecontrol.android.tv.ui.components.TvButtonStyle
import com.gatecontrol.android.tv.ui.components.TvOptionRow
import com.gatecontrol.android.tv.ui.components.TvPill
import com.gatecontrol.android.tv.ui.components.TvPillTone
import com.gatecontrol.android.tv.ui.components.TvRowEnd
import com.gatecontrol.android.tv.ui.components.TvTextInput
import com.gatecontrol.android.tv.ui.drive.requestSoon
import com.gatecontrol.android.tv.ui.theme.GateControlTvTheme
import com.gatecontrol.android.ui.theme.MonoFontFamily

/** Medien › Quellen: the sources, which one the page shows, "immer Original", remove, add one. */
@Composable
fun TvSourcesPage(ui: TvMediaUi, actions: TvMediaActions, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val colors = GateControlTvTheme.colors
    val first = remember { FocusRequester() }
    Column(modifier.fillMaxSize().padding(end = 30.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.tv_media_sources_title), style = MaterialTheme.typography.titleMedium, color = colors.text)
        ui.sources.forEachIndexed { i, s ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TvOptionRow(
                    title = TvMediaLogic.sourceLabel(s),
                    description = listOfNotNull(s.address.substringAfter("://"), s.userName).joinToString(" · ") +
                        if (s.token == null) " · " + stringResource(R.string.tv_media_signed_out_short) else "",
                    end = TvRowEnd.None,
                    onClick = {
                        actions.onSelect(s)
                        onClose()
                    },
                    badge = if (s.id == ui.source?.id) ({ TvPill(stringResource(R.string.tv_media_shown), TvPillTone.Accent, dot = false) }) else null,
                    modifier = Modifier.weight(1f).then(if (i == 0) Modifier.focusRequester(first) else Modifier),
                )
                TvButton(
                    stringResource(if (s.alwaysOriginal) R.string.tv_media_always_original_on else R.string.tv_media_always_original_off),
                    onClick = { actions.onAlwaysOriginal(s, !s.alwaysOriginal) },
                )
                TvButton(stringResource(R.string.tv_media_remove), onClick = { actions.onRemove(s) }, style = TvButtonStyle.Warn)
            }
        }
        TvButton(
            stringResource(R.string.tv_media_add),
            onClick = actions.onAdd,
            style = TvButtonStyle.Primary,
            modifier = if (ui.sources.isEmpty()) Modifier.focusRequester(first) else Modifier,
        )
        Text(stringResource(R.string.tv_media_always_original_hint), style = MaterialTheme.typography.bodySmall, color = colors.muted)
        Text(stringResource(R.string.tv_media_encrypted), style = MaterialTheme.typography.bodySmall, color = colors.muted)
    }
    LaunchedEffect(Unit) { first.requestSoon() }
}

/** Code "482913" as "482 913" (read from the sofa). */
internal fun spacedCode(code: String): String = code.chunked(3).joinToString(" ")

/** Quelle hinzufügen (mockup 10): 1 · Typ, 2 · Adresse (found in the home network), 3 · Anmeldung (Quick Connect). */
@Composable
fun TvAddSourcePage(add: TvAddUi, actions: TvMediaActions, modifier: Modifier = Modifier) {
    val colors = GateControlTvTheme.colors
    val first = remember { FocusRequester() }
    val addressField = remember { FocusRequester() }
    val loginFocus = remember { FocusRequester() }
    Column(modifier.fillMaxSize().padding(end = 30.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.tv_media_add), style = MaterialTheme.typography.titleMedium, color = colors.text)
        if (add.done) {
            Text(stringResource(R.string.tv_media_added), style = MaterialTheme.typography.bodyLarge, color = colors.accentText)
            TvButton(stringResource(R.string.tv_media_done), onClick = actions.onAddCancel, style = TvButtonStyle.Primary, modifier = Modifier.focusRequester(first))
            LaunchedEffect(Unit) { first.requestSoon() }
            return@Column
        }
        Section(stringResource(R.string.tv_media_step_type))
        TvOptionRow(
            title = stringResource(R.string.tv_media_type_jellyfin),
            description = stringResource(R.string.tv_media_type_jellyfin_text),
            end = if (add.step == TvAddStep.TYPE) TvRowEnd.Arrow else TvRowEnd.None,
            onClick = actions.onJellyfin,
            badge = { TvPill(stringResource(R.string.tv_media_recommended), TvPillTone.Accent, dot = false) },
            modifier = Modifier.width(560.dp).focusRequester(first),
        )
        if (add.step == TvAddStep.TYPE) {
            for ((title, text) in listOf(
                R.string.tv_media_type_smb to R.string.tv_media_type_smb_text,
                R.string.tv_media_type_webdav to R.string.tv_media_type_webdav_text,
                R.string.tv_media_type_nfs to R.string.tv_media_type_nfs_text,
            )) {
                TvOptionRow(
                    title = stringResource(title),
                    description = stringResource(text),
                    end = TvRowEnd.None,
                    onClick = {},
                    badge = { TvPill(stringResource(R.string.tv_media_later), TvPillTone.Neutral, dot = false) },
                    modifier = Modifier.width(560.dp),
                )
            }
            Text(stringResource(R.string.tv_media_smb_webdav_hint), style = MaterialTheme.typography.bodySmall, color = colors.muted, modifier = Modifier.width(560.dp))
        }
        if (add.step != TvAddStep.TYPE) {
            Section(stringResource(R.string.tv_media_step_address))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (add.searching) TvPill(stringResource(R.string.tv_media_searching), TvPillTone.Info)
                else TvButton(stringResource(R.string.tv_media_search_again), onClick = actions.onSearch)
                if (!add.searching && add.found.isEmpty()) Text(stringResource(R.string.tv_media_none_found), style = MaterialTheme.typography.bodySmall, color = colors.muted)
            }
            for (hint in add.found) {
                TvOptionRow(
                    title = hint.name,
                    description = hint.address.substringAfter("://"),
                    end = TvRowEnd.Arrow,
                    onClick = { actions.onPick(hint) },
                    modifier = Modifier.width(560.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom, modifier = Modifier.width(560.dp)) {
                TvTextInput(
                    value = add.address,
                    onValueChange = actions.onAddress,
                    label = stringResource(R.string.tv_media_address),
                    placeholder = "192.168.1.20:8096",
                    mono = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { actions.onCheck() }),
                    fieldModifier = Modifier.focusRequester(addressField),
                    modifier = Modifier.weight(1f),
                )
                TvButton(stringResource(R.string.tv_media_check), onClick = actions.onCheck, enabled = !add.checking)
            }
            val server = add.server
            when {
                add.checking -> TvPill(stringResource(R.string.tv_media_checking), TvPillTone.Info)
                server != null -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    TvPill(stringResource(R.string.tv_media_found, server.name, server.version ?: ""), TvPillTone.Accent)
                    if (add.step == TvAddStep.ADDRESS) {
                        TvButton(stringResource(R.string.tv_media_next), onClick = actions.onLogin, style = TvButtonStyle.Primary, modifier = Modifier.focusRequester(loginFocus))
                        LaunchedEffect(server.id) { loginFocus.requestSoon() }
                    }
                }
                add.problem != null && add.step == TvAddStep.ADDRESS -> Text(problemText(add.problem), style = MaterialTheme.typography.bodySmall, color = colors.warn)
            }
        }
        if (add.step == TvAddStep.LOGIN) {
            Login(add, actions)
        }
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.tv_media_encrypted), style = MaterialTheme.typography.bodySmall, color = colors.muted)
        Text(stringResource(R.string.tv_media_tunnel_note), style = MaterialTheme.typography.bodySmall, color = colors.muted)
        TvButton(stringResource(R.string.tv_cancel), onClick = actions.onAddCancel)
        Spacer(Modifier.height(20.dp))
    }
    LaunchedEffect(add.step) {
        if (add.step == TvAddStep.TYPE) first.requestSoon()
        if (add.step == TvAddStep.ADDRESS && add.found.isEmpty()) addressField.requestSoon()
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.labelMedium, color = GateControlTvTheme.colors.muted, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun problemText(p: TvAddProblem): String = stringResource(
    when (p) {
        TvAddProblem.BAD_ADDRESS -> R.string.tv_media_problem_address
        TvAddProblem.NOT_FOUND -> R.string.tv_media_problem_not_found
        TvAddProblem.OLD_SERVER -> R.string.tv_media_problem_old
        TvAddProblem.NO_QUICK_CONNECT -> R.string.tv_media_problem_no_quick_connect
        TvAddProblem.WRONG_LOGIN -> R.string.tv_media_problem_login
        TvAddProblem.OFFLINE -> R.string.tv_media_offline
        TvAddProblem.FAILED -> R.string.tv_media_failed
    },
)

@Composable
private fun Login(add: TvAddUi, actions: TvMediaActions) {
    val colors = GateControlTvTheme.colors
    val method = remember { FocusRequester() }
    val nameField = remember { FocusRequester() }
    var name by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    Section(stringResource(R.string.tv_media_step_login))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TvButton(
            stringResource(R.string.tv_media_quick_connect),
            onClick = { actions.onQuickConnect(true) },
            style = if (add.quickConnect) TvButtonStyle.Primary else TvButtonStyle.Secondary,
            modifier = Modifier.focusRequester(method),
        )
        TvButton(
            stringResource(R.string.tv_media_password_login),
            onClick = { actions.onQuickConnect(false) },
            style = if (!add.quickConnect) TvButtonStyle.Primary else TvButtonStyle.Secondary,
        )
    }
    add.problem?.let { Text(problemText(it), style = MaterialTheme.typography.bodySmall, color = colors.warn) }
    if (add.quickConnect) {
        val code = add.code
        if (code != null) {
            Text(stringResource(R.string.tv_media_quick_code), style = MaterialTheme.typography.labelMedium, color = colors.muted)
            Text(spacedCode(code), style = MaterialTheme.typography.displaySmall.copy(fontFamily = MonoFontFamily), color = colors.text)
            Text(stringResource(R.string.tv_media_quick_hint), style = MaterialTheme.typography.bodySmall, color = colors.muted, modifier = Modifier.width(560.dp))
        }
        if (add.waiting) TvPill(stringResource(R.string.tv_media_waiting), TvPillTone.Info)
        if (add.signingIn) TvPill(stringResource(R.string.tv_media_signing_in), TvPillTone.Info)
    } else {
        Column(Modifier.width(420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TvTextInput(name, { name = it }, label = stringResource(R.string.tv_media_user), fieldModifier = Modifier.focusRequester(nameField))
            TvTextInput(
                password,
                { password = it },
                label = stringResource(R.string.tv_media_password),
                password = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { actions.onPassword(name, password) }),
            )
            TvButton(
                stringResource(if (add.signingIn) R.string.tv_media_signing_in else R.string.tv_media_connect),
                onClick = { actions.onPassword(name, password) },
                style = TvButtonStyle.Primary,
                enabled = !add.signingIn && name.isNotBlank(),
            )
        }
        LaunchedEffect(Unit) { nameField.requestSoon() }
    }
    LaunchedEffect(Unit) { method.requestSoon() }
}
