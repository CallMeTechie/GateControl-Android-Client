package com.gatecontrol.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.R
import com.gatecontrol.android.common.ClientPolicy
import com.gatecontrol.android.ui.theme.GateControlTheme
import com.gatecontrol.android.util.openSystemVpnSettings

/** Small "Vom Administrator festgelegt" line under a locked setting. */
@Composable
fun GcPolicyLockedHint(modifier: Modifier = Modifier, text: String = stringResource(R.string.policy_locked_hint)) {
    val extra = GateControlTheme.extraColors
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(GcIcons.Lock, contentDescription = null, tint = extra.muted, modifier = Modifier.size(14.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = extra.muted)
    }
}

/**
 * Prominent hint when the client policy requires a kill switch or an
 * always-on VPN: Android lets only the user (or an MDM) switch on
 * "Always-on VPN" and "Block connections without VPN" in the system
 * settings, so the app explains it and opens those settings.
 */
@Composable
fun GcPolicySystemVpnCard(policy: ClientPolicy, modifier: Modifier = Modifier) {
    if (!policy.needsSystemVpnSettings) return
    val context = LocalContext.current
    GcBanner(modifier = modifier, tone = GcTone.Warn, icon = GcIcons.Lock) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.policy_system_vpn_title), style = MaterialTheme.typography.titleSmall)
            if (policy.autoConnect == ClientPolicy.AutoConnect.ALWAYS_ON) {
                Text(stringResource(R.string.policy_system_vpn_always_on), style = MaterialTheme.typography.bodySmall)
            }
            if (policy.killSwitch == ClientPolicy.KillSwitch.REQUIRED) {
                Text(stringResource(R.string.policy_system_vpn_kill_switch), style = MaterialTheme.typography.bodySmall)
            }
            GcOutlineButton(
                text = stringResource(R.string.policy_open_vpn_settings),
                onClick = { context.openSystemVpnSettings() },
                minHeight = 40.dp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** "This device is managed" note for the settings page. */
@Composable
fun GcPolicyManagedBanner(policy: ClientPolicy, modifier: Modifier = Modifier) {
    if (!policy.managed) return
    GcBanner(modifier = modifier, tone = GcTone.Info, icon = GcIcons.ShieldCheck) {
        Text(stringResource(R.string.policy_managed_banner), style = MaterialTheme.typography.bodySmall)
    }
}
