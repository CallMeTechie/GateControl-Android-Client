package com.gatecontrol.android.ui.mail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.mail.MAccount
import com.gatecontrol.android.mail.MProvider
import com.gatecontrol.android.mail.MailLogic
import com.gatecontrol.android.ui.components.GcBanner
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.components.GcSegmented
import com.gatecontrol.android.ui.components.GcSubpageBar
import com.gatecontrol.android.ui.components.GcTextField
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.setup.openSigninBrowser
import com.gatecontrol.android.ui.theme.GateControlTheme

/**
 * "Konto hinzufügen" (mockup "Konto hinzufügen"): the providers (Microsoft with a note while the server has no
 * client id), then the form with the provider's hint, "Verbinden"; or the Microsoft sign-in in the browser.
 */
@Composable
fun MailAddAccountScreen(onBack: () -> Unit, onDone: (Long) -> Unit, viewModel: MailAddAccountViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val me by viewModel.me.collectAsStateWithLifecycle()
    val event by viewModel.event.collectAsStateWithLifecycle()
    val context = mailContext()
    val extra = GateControlTheme.extraColors
    LaunchedEffect(event) {
        when (val e = event) {
            null -> Unit
            is AddAccountEvent.Browser -> if (!openSigninBrowser(context, e.url)) toast(context, context.getString(R.string.mail_no_app))
            is AddAccountEvent.Done -> {
                toast(context, context.getString(R.string.mail_account_added, e.account.label))
                onDone(e.account.id)
            }
        }
        if (event != null) viewModel.consumed()
    }
    // back from the browser without an answer: the buttons work again
    LifecycleResumeEffect(viewModel) {
        viewModel.browserClosed()
        onPauseOrDispose { }
    }

    val limitReached = me?.let { it.accounts.size >= it.limits.accountsMax } == true && viewModel.relogin == null
    Column(Modifier.fillMaxSize().imePadding()) {
        GcSubpageBar(
            title = stringResource(if (viewModel.relogin != null) R.string.mail_relogin else R.string.mail_add_account),
            onBack = { if (state.provider != null && viewModel.relogin == null) viewModel.pick(null) else onBack() },
            backDescription = stringResource(R.string.mail_back),
        )
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (limitReached) {
                GcBanner(tone = GcTone.Warn, icon = GcIcons.Alert) {
                    Text((me?.limits?.accountsMax ?: 0).let { n -> pluralStringResource(R.plurals.mail_limit_reached, n, n) }, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (state.loading) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            if (state.provider == null && !state.loading && viewModel.relogin == null) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surface)) {
                    state.providers.forEachIndexed { i, p ->
                        if (i > 0) MailDivider()
                        ProviderRow(p, microsoftReady = viewModel.microsoftReady(), enabled = !limitReached) { viewModel.pick(p) }
                    }
                }
                if (state.providers.isEmpty()) state.error?.let { ErrorBox(mailText(context, it), onRetry = null) }
            }
            val p = state.provider
            if (p != null && p.auth == MProvider.AUTH_MICROSOFT) {
                MicrosoftBox(state, ready = viewModel.microsoftReady(), onStart = viewModel::startMicrosoft)
            } else if (p != null) {
                AccountFormCard(p, state, viewModel, enabled = !limitReached)
            }
        }
    }
}

/** The provider's mark (mockup: colored square with letters). */
@Composable
private fun ProviderLogo(id: String) {
    val (bg, fg, text) = when (id) {
        MAccount.PROVIDER_M365, MAccount.PROVIDER_OUTLOOK -> Triple(Color(0xFF1F4FA0), Color.White, "MS")
        MAccount.PROVIDER_GMX -> Triple(Color(0xFF1C449B), Color.White, "GMX")
        MAccount.PROVIDER_WEBDE -> Triple(Color(0xFFFFD800), Color(0xFF1A1D22), "WEB")
        MAccount.PROVIDER_TONLINE -> Triple(Color(0xFFE20074), Color.White, "T")
        MAccount.PROVIDER_GMAIL -> Triple(Color.White, Color(0xFFD93025), "G")
        else -> Triple(GateControlTheme.extraColors.panel2, MaterialTheme.colorScheme.onSurface, "@")
    }
    Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(bg), contentAlignment = Alignment.Center) {
        Text(text, color = fg, fontWeight = FontWeight.Bold, fontSize = 11.sp)
    }
}

@Composable
private fun ProviderRow(p: MProvider, microsoftReady: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val extra = GateControlTheme.extraColors
    val ms = p.auth == MProvider.AUTH_MICROSOFT
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ProviderLogo(p.id)
        Column(Modifier.weight(1f)) {
            Text(p.label, style = MaterialTheme.typography.bodyLarge)
            if (ms && !microsoftReady) Text(stringResource(R.string.mail_ms_not_set_up), style = MaterialTheme.typography.bodySmall, color = extra.warn)
        }
        Text(
            stringResource(
                when (p.auth) {
                    MProvider.AUTH_MICROSOFT -> R.string.mail_auth_microsoft
                    MProvider.AUTH_APP_PASSWORD -> R.string.mail_auth_app_password
                    MProvider.AUTH_IMAP -> R.string.mail_auth_imap
                    else -> R.string.mail_auth_password
                },
            ),
            style = MaterialTheme.typography.labelSmall,
            color = extra.muted,
            modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(extra.panel2).padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun MicrosoftBox(state: AddAccountState, ready: Boolean, onStart: () -> Unit) {
    val context = mailContext()
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surface).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ProviderLogo(MAccount.PROVIDER_M365)
            Text(stringResource(R.string.mail_provider_microsoft), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }
        if (!ready) {
            GcBanner(tone = GcTone.Warn, icon = GcIcons.Info) { Text(stringResource(R.string.mail_ms_not_set_up_text), style = MaterialTheme.typography.bodySmall) }
            return@Column
        }
        Text(stringResource(R.string.mail_ms_text), style = MaterialTheme.typography.bodySmall, color = GateControlTheme.extraColors.muted)
        state.error?.let { e ->
            GcBanner(tone = GcTone.Error, icon = GcIcons.Alert) {
                Text(
                    listOfNotNull(
                        when (e) {
                            "cancelled" -> stringResource(R.string.mail_ms_cancelled)
                            "ms_failed" -> stringResource(R.string.mail_ms_failed)
                            MailAddAccountViewModel.MS_NOT_SET_UP -> stringResource(R.string.mail_ms_not_set_up_text)
                            else -> mailText(context, e)
                        },
                        state.errorHint,
                    ).joinToString("\n"),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        GcPrimaryButton(
            stringResource(if (state.waitingBrowser) R.string.mail_ms_waiting else R.string.mail_ms_start),
            onClick = onStart,
            loading = state.busy,
            enabled = !state.busy,
        )
    }
}

/** "<Anbieter> einrichten": address, password (app password), name and color, the provider's hint, "Verbinden". */
@Composable
private fun AccountFormCard(p: MProvider, state: AddAccountState, viewModel: MailAddAccountViewModel, enabled: Boolean) {
    val f = state.form
    val context = mailContext()
    val extra = GateControlTheme.extraColors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f), RoundedCornerShape(18.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.mail_setup_provider, p.label), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        GcTextField(
            value = f.email,
            onValueChange = { v -> viewModel.edit { it.copy(email = v.trim()) } },
            label = stringResource(R.string.mail_email),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.onFocusChanged { if (!it.isFocused) viewModel.discover() },
        )
        GcTextField(
            value = f.password,
            onValueChange = { v -> viewModel.edit { it.copy(password = v) } },
            label = stringResource(if (p.auth == MProvider.AUTH_APP_PASSWORD) R.string.mail_app_password else R.string.mail_password),
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        GcTextField(value = f.name, onValueChange = { v -> viewModel.edit { it.copy(name = v) } }, label = stringResource(R.string.mail_name_color))
        ColorChoice(f.color) { c -> viewModel.edit { it.copy(color = c) } }
        GcSegmented(
            options = listOf(MAccount.KIND_PERSONAL to stringResource(R.string.mail_kind_personal), MAccount.KIND_BUSINESS to stringResource(R.string.mail_kind_business)),
            selected = f.kind,
            onSelect = { k -> viewModel.edit { it.copy(kind = k) } },
        )
        if (f.manual) ServerFields(f, viewModel)
        state.hint?.let { hint ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(extra.warnBg).padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(GcIcons.Info, contentDescription = null, tint = extra.warn, modifier = Modifier.size(18.dp))
                Text(hint, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            }
        }
        p.help?.let { url -> TextButton(onClick = { openLink(context, url) }) { Text(stringResource(R.string.mail_provider_help)) } }
        state.error?.let { e ->
            GcBanner(tone = GcTone.Error, icon = GcIcons.Alert) {
                Text(listOfNotNull(mailText(context, e), state.errorHint).joinToString("\n"), style = MaterialTheme.typography.bodySmall)
            }
        }
        Text(stringResource(R.string.mail_password_note), style = MaterialTheme.typography.bodySmall, color = extra.muted)
        GcPrimaryButton(stringResource(R.string.mail_connect), onClick = viewModel::connect, loading = state.busy, enabled = enabled && !state.busy)
    }
}

/** IMAP and SMTP by hand ("Andere / eigene Domain"); only encrypted connections are offered. */
@Composable
private fun ServerFields(f: AccountForm, viewModel: MailAddAccountViewModel) {
    val sec = listOf("tls" to "SSL/TLS", "starttls" to "STARTTLS")
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        GcTextField(value = f.username, onValueChange = { v -> viewModel.edit { it.copy(username = v) } }, label = stringResource(R.string.mail_username), placeholder = f.email)
        Text(stringResource(R.string.mail_imap), style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GcTextField(value = f.imapHost, onValueChange = { v -> viewModel.edit { it.copy(imapHost = v.trim()) } }, label = stringResource(R.string.mail_server), modifier = Modifier.weight(1f))
            GcTextField(value = f.imapPort, onValueChange = { v -> viewModel.edit { it.copy(imapPort = v.filter(Char::isDigit).take(5)) } }, label = stringResource(R.string.mail_port), modifier = Modifier.width(96.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }
        GcSegmented(options = sec, selected = f.imapSecurity, onSelect = { v -> viewModel.edit { it.copy(imapSecurity = v, imapPort = if (v == "tls") "993" else "143") } })
        Text(stringResource(R.string.mail_smtp), style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GcTextField(value = f.smtpHost, onValueChange = { v -> viewModel.edit { it.copy(smtpHost = v.trim()) } }, label = stringResource(R.string.mail_server), modifier = Modifier.weight(1f))
            GcTextField(value = f.smtpPort, onValueChange = { v -> viewModel.edit { it.copy(smtpPort = v.filter(Char::isDigit).take(5)) } }, label = stringResource(R.string.mail_port), modifier = Modifier.width(96.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }
        GcSegmented(options = sec, selected = f.smtpSecurity, onSelect = { v -> viewModel.edit { it.copy(smtpSecurity = v, smtpPort = if (v == "tls") "465" else "587") } })
    }
}

/** The account's color: the dots of the mockups. */
@Composable
internal fun ColorChoice(selected: String?, onPick: (String) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        MailLogic.COLORS.forEach { c ->
            val on = c.equals(selected, ignoreCase = true)
            val color = accountColor(c, Color.Gray)
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .then(if (on) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                    .selectable(selected = on, role = Role.RadioButton) { onPick(c) }
                    .semantics { contentDescription = c },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(24.dp).clip(CircleShape).background(color))
            }
        }
    }
}
