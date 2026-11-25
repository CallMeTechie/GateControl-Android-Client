package com.gatecontrol.android.ui.media

import android.app.Activity
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.R
import com.gatecontrol.android.media.MediaGate
import com.gatecontrol.android.media.MediaSourceKind
import com.gatecontrol.android.media.server.MediaMigration
import com.gatecontrol.android.ui.components.GcBanner
import com.gatecontrol.android.ui.components.GcIconSquare
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.theme.GateControlTheme

/**
 * The VPN for media away (Netz-Regel): „VPN wird verbunden …“ while the app starts it, Android's consent asked once
 * when it is missing ([onConsent] with the answer), „Unterwegs nur über VPN · VPN einschalten“ when it did not come up.
 * Nothing while the VPN is not needed or up.
 */
@Composable
fun MediaVpnGate(gate: MediaGate, onConsent: (Boolean) -> Unit, onRetry: () -> Unit) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        onConsent(r.resultCode == Activity.RESULT_OK)
    }
    var asked by remember { mutableStateOf(false) }
    fun ask() {
        val intent = runCatching { VpnService.prepare(context) }.getOrNull()
        if (intent == null) onConsent(true) else runCatching { launcher.launch(intent) }.onFailure { onConsent(false) }
    }
    // the consent is asked once by itself; afterwards only with the button
    LaunchedEffect(gate) {
        if (gate == MediaGate.CONSENT && !asked) {
            asked = true
            ask()
        }
    }
    when (gate) {
        MediaGate.OPEN -> Unit
        MediaGate.CONNECTING, MediaGate.CONSENT -> GcBanner(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).testTag(VPN_GATE_TAG), tone = GcTone.Info) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Text(stringResource(R.string.media_vpn_connecting), style = MaterialTheme.typography.bodyMedium)
        }
        MediaGate.BLOCKED -> GcBanner(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).testTag(VPN_GATE_TAG), tone = GcTone.Warn, icon = GcIcons.Shield) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.media_vpn_blocked), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.media_vpn_blocked_text), style = MaterialTheme.typography.bodySmall)
                GcPrimaryButton(
                    stringResource(R.string.media_vpn_on),
                    onClick = { if (runCatching { VpnService.prepare(context) != null }.getOrDefault(false)) ask() else onRetry() },
                    modifier = Modifier.testTag(VPN_ON_TAG),
                )
            }
        }
    }
}

/**
 * „Medien kommen jetzt vom Server“, once after the update (question 33 a): the sources set up on this phone before,
 * taken over (same Jellyfin server, same share: progress and downloads stay) or removed (the server does not have
 * them). „Verstanden“ removes the rest.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaMigrationSheet(items: List<MediaMigration>, onDone: () -> Unit) {
    val x = GateControlTheme.extraColors
    ModalBottomSheet(
        onDismissRequest = onDone,
        containerColor = MaterialTheme.colorScheme.surface,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 28.dp).testTag(MIGRATION_TAG), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.media_migr_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.media_migr_text), style = MaterialTheme.typography.bodyMedium, color = x.muted)
            items.forEach { m ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    GcIconSquare(background = if (m.takenAs != null) x.accentBg else x.panel2) {
                        Icon(
                            if (m.kind == MediaSourceKind.JELLYFIN) GcIcons.Film else GcIcons.Folder,
                            contentDescription = null,
                            tint = if (m.takenAs != null) x.accentText else x.muted,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text(m.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                        Text(
                            when {
                                m.takenAs == null -> stringResource(R.string.media_migr_removed)
                                m.kind == MediaSourceKind.JELLYFIN -> stringResource(R.string.media_migr_taken_jf)
                                else -> stringResource(R.string.media_migr_taken_share, "${m.takenAs} (NAS)")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (m.takenAs != null) x.accentText else x.warn,
                        )
                    }
                }
            }
            GcPrimaryButton(stringResource(R.string.media_migr_ok), onClick = onDone, modifier = Modifier.fillMaxWidth().testTag(MIGRATION_OK_TAG))
        }
    }
}

internal const val VPN_GATE_TAG = "media-vpn-gate"
internal const val VPN_ON_TAG = "media-vpn-on"
internal const val MIGRATION_TAG = "media-migration"
internal const val MIGRATION_OK_TAG = "media-migration-ok"
