package com.gatecontrol.android.ui.notizen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.notizen.NResult
import com.gatecontrol.android.notizen.NotizenVaultSession
import com.gatecontrol.android.ui.components.DeviceAuthResult
import com.gatecontrol.android.ui.components.GcBanner
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.components.GcSubpageBar
import com.gatecontrol.android.ui.components.GcTextField
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.components.rememberGcDeviceAuth
import com.gatecontrol.android.ui.theme.GateControlTheme
import com.gatecontrol.android.ui.theme.MonoFontFamily
import kotlinx.coroutines.launch

/** Copies a recovery key, marked sensitive (the clipboard preview of Android 13+ hides it). */
internal fun copySecret(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    val clip = ClipData.newPlainText("GateControl", text)
    clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    cm.setPrimaryClip(clip)
}

/** The recovery key of a new Tresor, shown once (to write down or copy) before the Tresor is made. */
@Composable
internal fun RecoveryKeyDialog(keys: List<Pair<String, String>>, onConfirm: () -> Unit, onDismiss: (() -> Unit)?) {
    val context = LocalContext.current
    var written by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { onDismiss?.invoke() },
        title = { Text(stringResource(R.string.notizen_recovery_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.notizen_recovery_text), style = MaterialTheme.typography.bodyMedium)
                keys.forEach { (name, key) ->
                    if (keys.size > 1) Text(name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        key,
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = MonoFontFamily, fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(GateControlTheme.extraColors.panel2).padding(12.dp),
                    )
                    GcOutlineButton(stringResource(R.string.notizen_copy), { copySecret(context, key) }, icon = GcIcons.Copy, fillWidth = false)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(written, { written = it })
                    Text(stringResource(R.string.notizen_recovery_written), style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm, enabled = written) { Text(stringResource(R.string.notizen_recovery_go)) } },
        dismissButton = onDismiss?.let { d -> { TextButton(onClick = d) { Text(stringResource(R.string.notizen_cancel)) } } },
    )
}

/** „Neues Notizbuch › Tresor“: a name, the recovery key, then the Tresor. */
@Composable
internal fun NewTresorFlow(session: NotizenVaultSession, area: String, onDone: () -> Unit) {
    var name by rememberSaveable { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf<NotizenVaultSession.Draft?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) { onDispose { if (!busy) draft?.wipe() } }
    if (name == null) {
        NameDialog(stringResource(R.string.notizen_new_tresor), onDismiss = onDone, onDone = { name = it; draft = session.draft() })
        return
    }
    val d = draft ?: return
    error?.let { code ->
        AlertDialog(onDismissRequest = onDone, text = { Text(messageText(code)) }, confirmButton = { TextButton(onClick = onDone) { Text(stringResource(R.string.notizen_ok)) } })
        return
    }
    RecoveryKeyDialog(
        listOf(name!! to d.recoveryText),
        onConfirm = {
            if (busy) return@RecoveryKeyDialog
            busy = true
            scope.launch {
                val r = session.create(d, name!!, area)
                busy = false
                if (r is NResult.Ok) onDone() else error = r.code() ?: "failed"
            }
        },
        onDismiss = { d.wipe(); onDone() },
    )
}

/**
 * A Tresor notebook on this phone (route `notizen/vault/<id>`, from a push with `?requests=1`): open it with the device confirmation, ask for access
 * (with the safety number to compare), open it with the recovery key, approve other devices, a new key after a member
 * left, lock it.
 */
@Composable
fun NotizenVaultScreen(onBack: () -> Unit, onOpened: () -> Unit, stayAfterUnlock: Boolean = false, vm: NotizenVaultViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    val text = message?.let { messageText(it) }
    LaunchedEffect(message) { if (text != null) { snack.showSnackbar(text); vm.consumeMessage() } }
    val auth = rememberGcDeviceAuth()
    val authTitle = stringResource(R.string.notizen_tresor_auth)
    var recovery by rememberSaveable { mutableStateOf("") }
    val x = GateControlTheme.extraColors
    val openNow: () -> Unit = {
        auth.authenticate(authTitle, null) { r ->
            when (r) {
                // from a push „Neues Gerät möchte Zugriff“: unlocked, the page stays with the device to approve
                DeviceAuthResult.SUCCESS -> vm.open(if (stayAfterUnlock) ({}) else onOpened)
                DeviceAuthResult.UNAVAILABLE -> vm.open(if (stayAfterUnlock) ({}) else onOpened)
                else -> Unit
            }
        }
    }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState())) {
            GcSubpageBar(ui.name ?: stringResource(R.string.notizen_tresor), onBack, stringResource(R.string.notizen_back))
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(stringResource(R.string.notizen_tresor_intro), style = MaterialTheme.typography.bodyMedium, color = x.muted)
                when (val a = ui.access) {
                    NotizenVaultSession.Access.Open -> {
                        GcBanner(tone = GcTone.Ok, icon = GcIcons.LockOpen) { Text(stringResource(R.string.notizen_tresor_open), style = MaterialTheme.typography.bodyMedium) }
                        if (ui.vault?.needsRotation == true) {
                            GcCard {
                                Text(stringResource(R.string.notizen_tresor_rotate_hint), style = MaterialTheme.typography.bodyMedium)
                                GcPrimaryButton(stringResource(R.string.notizen_tresor_rotate), vm::rotate, loading = ui.busy)
                            }
                        }
                        if (ui.requests.isNotEmpty()) {
                            Text(stringResource(R.string.notizen_tresor_requests), style = MaterialTheme.typography.titleMedium)
                            ui.requests.forEach { d ->
                                GcCard {
                                    Text(d.name ?: stringResource(R.string.notizen_tresor_device), style = MaterialTheme.typography.titleSmall)
                                    Text(stringResource(R.string.notizen_tresor_compare), style = MaterialTheme.typography.bodySmall, color = x.muted)
                                    Text(vm.session.safetyOf(d).orEmpty(), style = MaterialTheme.typography.titleMedium.copy(fontFamily = MonoFontFamily))
                                    GcPrimaryButton(stringResource(R.string.notizen_tresor_approve), { vm.approve(d) })
                                }
                            }
                        }
                        GcPrimaryButton(stringResource(R.string.notizen_tresor_show_notes), onOpened)
                        GcOutlineButton(stringResource(R.string.notizen_tresor_lock), vm::lock, icon = GcIcons.Lock)
                    }
                    is NotizenVaultSession.Access.Pending -> {
                        GcCard {
                            Text(stringResource(R.string.notizen_tresor_pending), style = MaterialTheme.typography.bodyMedium)
                            Text(a.safetyNumber, style = MaterialTheme.typography.titleLarge.copy(fontFamily = MonoFontFamily))
                        }
                        GcOutlineButton(stringResource(R.string.notizen_tresor_check_again), openNow, loading = ui.busy)
                    }
                    is NotizenVaultSession.Access.Unknown, null -> {
                        GcPrimaryButton(stringResource(R.string.notizen_tresor_unlock), openNow, loading = ui.busy, icon = GcIcons.Fingerprint)
                        if (a is NotizenVaultSession.Access.Unknown) {
                            if (a.revoked) Text(stringResource(R.string.notizen_tresor_revoked), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            GcOutlineButton(stringResource(R.string.notizen_tresor_ask), vm::ask, loading = ui.busy)
                        }
                    }
                }
                if (ui.access != NotizenVaultSession.Access.Open) {
                    GcCard {
                        Text(stringResource(R.string.notizen_tresor_with_recovery), style = MaterialTheme.typography.titleSmall)
                        GcTextField(recovery, { recovery = it.take(80) }, stringResource(R.string.notizen_recovery_key), mono = true)
                        GcOutlineButton(stringResource(R.string.notizen_tresor_recover), { vm.recover(recovery, if (stayAfterUnlock) ({}) else onOpened) }, enabled = recovery.length >= 40, loading = ui.busy)
                    }
                }
            }
        }
        SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).padding(16.dp)) { Snackbar(it) }
    }
}
