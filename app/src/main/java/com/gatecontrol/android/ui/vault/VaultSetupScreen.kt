package com.gatecontrol.android.ui.vault

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import android.provider.Settings
import android.view.autofill.AutofillManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.gatecontrol.android.R
import com.gatecontrol.android.ui.components.GcBanner
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.components.GcSwitch
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.theme.GateControlTheme
import com.gatecontrol.android.ui.theme.MonoFontFamily
import com.gatecontrol.android.vault.VResult
import com.gatecontrol.android.vault.VaultCrypto
import com.gatecontrol.android.vault.VaultGenerator
import com.gatecontrol.android.vault.VaultRepository

/** Header of the setup and recovery pages: back, title, "Schritt n von 3", the step bar. */
@Composable
fun VaultStepHeader(title: String, step: Int?, onBack: (() -> Unit)?) {
    val x = GateControlTheme.extraColors
    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            GcIconButton(GcIcons.Back, stringResource(R.string.vault_back), onClick = onBack, size = 44.dp, tint = MaterialTheme.colorScheme.onBackground)
        } else {
            Spacer(Modifier.width(12.dp))
        }
        Text(title, style = MaterialTheme.typography.titleLarge.copy(fontSize = 17.sp), modifier = Modifier.weight(1f))
        if (step != null) Text(stringResource(R.string.vault_setup_step, step, 3), color = x.muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(end = 12.dp))
    }
    if (step != null) VaultSteps(step)
}

/** Master password + repeat with the strength meter (mockup A1). */
@Composable
private fun PasswordStep(
    title: String,
    text: String,
    busy: Boolean,
    error: String?,
    onSubmit: (String) -> Unit,
) {
    val x = GateControlTheme.extraColors
    var pw by rememberSaveable { mutableStateOf("") }
    var pw2 by rememberSaveable { mutableStateOf("") }
    val mismatch = pw2.isNotEmpty() && pw2 != pw
    val ok = VaultGenerator.goodMasterPassword(pw) && pw == pw2
    Column(Modifier.fillMaxSize().imePadding()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 28.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            VaultHeroIcon(GcIcons.Vault)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.headlineLarge)
                Text(text, style = MaterialTheme.typography.bodyLarge, color = x.muted)
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                VaultPasswordField(pw, { pw = it }, stringResource(R.string.vault_setup_pw_label), hint = if (pw.isEmpty()) stringResource(R.string.vault_setup_pw_rule) else null)
                VaultStrengthMeter(pw)
            }
            VaultPasswordField(
                pw2, { pw2 = it }, stringResource(R.string.vault_setup_pw_repeat),
                error = if (mismatch) stringResource(R.string.vault_setup_pw_mismatch) else null,
                trailingExtra = {
                    if (pw2.isNotEmpty() && pw2 == pw) Icon(GcIcons.Check, stringResource(R.string.vault_setup_pw_match), tint = x.accentText, modifier = Modifier.size(20.dp))
                },
            )
            GcBanner(tone = GcTone.Warn, icon = GcIcons.Alert) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.vault_setup_warn_title), style = MaterialTheme.typography.titleSmall, color = x.warn)
                    Text(stringResource(R.string.vault_setup_warn_text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                }
            }
            if (error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
        }
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 28.dp)) {
            GcPrimaryButton(
                stringResource(if (busy) R.string.vault_setup_creating else R.string.vault_next),
                onClick = { onSubmit(pw) }, enabled = ok, loading = busy, minHeight = 52.dp,
            )
        }
    }
}

/** The recovery key, once (mockup A2): QR + groups, print, copy, three tips, the confirmation. */
@Composable
fun VaultRecoveryKeyContent(key: String, clipboardSeconds: Int, onContinue: () -> Unit) {
    val x = GateControlTheme.extraColors
    val context = LocalContext.current
    var kept by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.vault_recovery_title), style = MaterialTheme.typography.headlineLarge)
                Text(stringResource(R.string.vault_recovery_text), style = MaterialTheme.typography.bodyLarge, color = x.muted)
            }
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surface).border(1.dp, x.border, RoundedCornerShape(18.dp)).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                VaultQrImage(key, stringResource(R.string.vault_recovery_qr), Modifier.size(112.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    key.split('-').chunked(2).forEach { pair ->
                        Text(pair.joinToString("  "), fontFamily = MonoFontFamily, fontWeight = FontWeight.Medium, fontSize = 15.sp, letterSpacing = 0.6.sp)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GcOutlineButton(stringResource(R.string.vault_recovery_print), onClick = { VaultPrint.recoveryKey(context, key) }, icon = GcIcons.Printer, modifier = Modifier.weight(1f))
                GcOutlineButton(stringResource(R.string.vault_copy), onClick = { VaultClipboard.copy(context, key, secret = true, seconds = clipboardSeconds * 2) }, icon = GcIcons.Copy, modifier = Modifier.weight(1f))
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(R.string.vault_recovery_tip1, R.string.vault_recovery_tip2, R.string.vault_recovery_tip3).forEachIndexed { i, tip ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("${i + 1}", color = x.accentText, fontWeight = FontWeight.Bold)
                        Text(stringResource(tip), color = x.muted, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface).border(1.dp, x.border, RoundedCornerShape(16.dp))
                    .toggleable(kept, role = Role.Checkbox, onValueChange = { kept = it }).heightIn(min = 52.dp).padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(kept, onCheckedChange = null, colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary))
                Text(stringResource(R.string.vault_recovery_confirm), style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(4.dp))
        }
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 28.dp)) {
            GcPrimaryButton(stringResource(R.string.vault_next), onClick = onContinue, enabled = kept, minHeight = 52.dp)
        }
    }
}

/** GateControl is the autofill service in use (Android checks that it is ours). */
fun vaultAutofillActive(context: Context): Boolean =
    runCatching { context.getSystemService(AutofillManager::class.java)?.hasEnabledAutofillServices() == true }.getOrDefault(false)

fun openAutofillSettings(context: Context) {
    runCatching {
        context.startActivity(Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE, ("package:" + context.packageName).toUri()))
    }
}

/** Step 3: fingerprint and autofill. */
@Composable
private fun FinishStep(viewModel: VaultViewModel, onDone: () -> Unit) {
    val x = GateControlTheme.extraColors
    val context = LocalContext.current
    var bio by remember { mutableStateOf(viewModel.biometricEnabled()) }
    var autofill by remember { mutableStateOf(vaultAutofillActive(context)) }
    val owner = LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) autofill = vaultAutofillActive(context) }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 28.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            VaultHeroIcon(GcIcons.ShieldCheck)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.vault_setup_done_title), style = MaterialTheme.typography.headlineLarge)
                Text(stringResource(R.string.vault_setup_done_text), style = MaterialTheme.typography.bodyLarge, color = x.muted)
            }
            VaultRowsCard {
                if (viewModel.biometric.available()) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Icon(GcIcons.Fingerprint, null, tint = x.accentText, modifier = Modifier.size(24.dp))
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.vault_bio_title), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.vault_bio_text), style = MaterialTheme.typography.bodySmall, color = x.muted)
                        }
                        GcSwitch(bio, onCheckedChange = { on -> viewModel.setBiometric(context, on) { bio = it } })
                    }
                    com.gatecontrol.android.ui.components.GcRowDivider()
                }
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Icon(GcIcons.Key, null, tint = x.accentText, modifier = Modifier.size(24.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.vault_autofill_title), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.vault_autofill_text), style = MaterialTheme.typography.bodySmall, color = x.muted)
                    }
                    if (autofill) {
                        com.gatecontrol.android.ui.components.GcChip(stringResource(R.string.vault_autofill_active), tone = GcTone.Ok, small = true)
                    } else {
                        GcOutlineButton(stringResource(R.string.vault_autofill_open), onClick = { openAutofillSettings(context) }, fillWidth = false, minHeight = 40.dp)
                    }
                }
            }
        }
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 28.dp)) {
            GcPrimaryButton(stringResource(R.string.vault_setup_finish), onClick = onDone, minHeight = 52.dp)
        }
    }
}

/** Setup in three steps: master password → recovery key (only once, no way back) → fingerprint and autofill. */
@Composable
fun VaultSetupScreen(onBack: () -> Unit, onDone: () -> Unit, viewModel: VaultViewModel = hiltViewModel()) {
    var step by rememberSaveable { mutableIntStateOf(1) }
    var recovery by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // the recovery key exists only on this page: no leaving it by back
    BackHandler(enabled = step >= 2) { }
    Column(Modifier.fillMaxSize()) {
        VaultStepHeader(stringResource(R.string.vault_setup_title), step, if (step == 1) onBack else null)
        when (step) {
            1 -> PasswordStep(
                title = stringResource(R.string.vault_setup_pw_title),
                text = stringResource(R.string.vault_setup_pw_text),
                busy = busy, error = error,
                onSubmit = { pw ->
                    busy = true
                    error = null
                    viewModel.setup(pw) { r ->
                        busy = false
                        if (r is VResult.Ok) {
                            recovery = r.value
                            step = 2
                        } else {
                            error = viewModel.message(r)
                        }
                    }
                },
            )
            2 -> VaultRecoveryKeyContent(recovery.orEmpty(), viewModel.clipboardSeconds) { recovery = null; step = 3 }
            else -> FinishStep(viewModel, onDone)
        }
    }
}

/** "Passwort vergessen?": recovery key → new master password → the new recovery key, once. */
@Composable
fun VaultRecoverScreen(onBack: () -> Unit, onDone: () -> Unit, viewModel: VaultViewModel = hiltViewModel()) {
    val x = GateControlTheme.extraColors
    var step by rememberSaveable { mutableIntStateOf(1) }
    var key by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var newKey by rememberSaveable { mutableStateOf<String?>(null) }
    val wrong = stringResource(R.string.vault_recover_wrong)
    val typo = stringResource(R.string.vault_recover_typo)
    BackHandler(enabled = step == 3) { }
    Column(Modifier.fillMaxSize()) {
        VaultStepHeader(stringResource(R.string.vault_recover_title), null, if (step < 3) onBack else null)
        when (step) {
            1 -> Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                VaultHeroIcon(GcIcons.Key)
                Text(stringResource(R.string.vault_recover_text), style = MaterialTheme.typography.bodyLarge, color = x.muted)
                com.gatecontrol.android.ui.components.GcTextField(
                    value = key, onValueChange = { key = it.uppercase(); error = null },
                    label = stringResource(R.string.vault_recover_key), mono = true, singleLine = false,
                    placeholder = "XXXX-XXXX-…", error = error,
                )
                GcPrimaryButton(stringResource(R.string.vault_recover_button), loading = busy, enabled = key.isNotBlank(), minHeight = 52.dp, onClick = {
                    error = try {
                        VaultCrypto.parseRecoveryKey(key)
                        null
                    } catch (e: VaultCrypto.VaultCryptoException) {
                        typo
                    }
                    if (error == null) {
                        busy = true
                        viewModel.unlockWithRecovery(key) { r ->
                            busy = false
                            if (r == VaultRepository.Unlock.Ok) step = 2 else error = wrong
                        }
                    }
                })
            }
            2 -> PasswordStep(
                title = stringResource(R.string.vault_recover_new_pw),
                text = stringResource(R.string.vault_setup_warn_text),
                busy = busy, error = error,
                onSubmit = { pw ->
                    busy = true
                    error = null
                    viewModel.completeRecovery(pw) { r ->
                        busy = false
                        if (r is VResult.Ok) {
                            key = ""
                            newKey = r.value
                            step = 3
                        } else {
                            error = viewModel.message(r)
                        }
                    }
                },
            )
            else -> VaultRecoveryKeyContent(newKey.orEmpty(), viewModel.clipboardSeconds) {
                newKey = null
                onDone()
            }
        }
    }
    LaunchedEffect(Unit) { viewModel.repository.ensureLoaded() }
}
