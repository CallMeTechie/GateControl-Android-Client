package com.gatecontrol.android.rdp

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.R
import com.gatecontrol.android.rdp.freerdp.RdpSessionEvent

/**
 * Certificate-verification dialog shown by `RdpSessionActivity` when the
 * FreeRDP `OnVerifyCertificate*` callbacks fire. Returns one of:
 *   - `0` → reject
 *   - `1` → trust once (do not persist)
 *   - `2` → always trust (persist fingerprint)
 */
@Composable
fun CertificateVerifyDialog(
    unknown: RdpSessionEvent.VerifyCertificate?,
    changed: RdpSessionEvent.VerifyChangedCertificate?,
    onVerdict: (Int) -> Unit,
) {
    val titleRes = if (unknown != null) {
        R.string.rdp_cert_unknown_title
    } else {
        R.string.rdp_cert_changed_title
    }
    val host = unknown?.host ?: changed?.host ?: return
    val port = (unknown?.port ?: changed?.port ?: 0L).toInt()
    val commonName = unknown?.commonName ?: changed?.commonName ?: ""
    val fingerprint = unknown?.fingerprint ?: changed?.fingerprint ?: ""

    AlertDialog(
        onDismissRequest = { onVerdict(0) },
        title = { Text(stringResource(titleRes)) },
        text = {
            Column {
                Text(stringResource(R.string.rdp_cert_host, host, port))
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.rdp_cert_common_name, commonName))
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.rdp_cert_fingerprint, fingerprint))
            }
        },
        confirmButton = {
            Column(Modifier.fillMaxWidth()) {
                Button(
                    onClick = { onVerdict(2) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                ) { Text(stringResource(R.string.rdp_cert_trust_always)) }
                OutlinedButton(
                    onClick = { onVerdict(1) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                ) { Text(stringResource(R.string.rdp_cert_trust_once)) }
            }
        },
        dismissButton = {
            TextButton(onClick = { onVerdict(0) }) {
                Text(stringResource(R.string.rdp_cert_reject))
            }
        }
    )
}
