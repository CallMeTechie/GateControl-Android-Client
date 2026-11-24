package com.gatecontrol.android.tv.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.gatecontrol.android.mail.MAccount
import com.gatecontrol.android.tv.R
import com.gatecontrol.android.tv.ui.components.TvOptionRow
import com.gatecontrol.android.tv.ui.components.TvRowEnd
import com.gatecontrol.android.tv.ui.theme.GateControlTvTheme
import com.gatecontrol.android.tv.zuhause.TvSourceState

/**
 * Einstellungen › Nachrichten (stage 3f, Frage 14 a): which mail accounts of the person who paired the TV show on it, each
 * with a switch. None by default — the TV is the family's, mail is one person's.
 */
@Composable
fun TvMailOptions(
    accounts: List<MAccount>,
    chosen: Set<Long>,
    state: TvSourceState,
    onAccount: (Long, Boolean) -> Unit,
) {
    val colors = GateControlTvTheme.colors
    Text(stringResource(R.string.tv_mail_settings_accounts), style = MaterialTheme.typography.titleMedium, color = colors.text)
    Text(stringResource(R.string.tv_mail_settings_desc), style = MaterialTheme.typography.bodySmall, color = colors.muted)
    when {
        accounts.isNotEmpty() -> accounts.forEach { a ->
            val on = a.id in chosen
            TvOptionRow(
                title = a.label,
                description = a.email.takeIf { it != a.label },
                end = TvRowEnd.Switch(on),
                onClick = { onAccount(a.id, !on) },
            )
        }
        state == TvSourceState.LOADING -> Text(stringResource(R.string.tv_home_loading), style = MaterialTheme.typography.bodySmall, color = colors.faint)
        state == TvSourceState.OFFLINE -> Text(stringResource(R.string.tv_home_offline), style = MaterialTheme.typography.bodySmall, color = colors.faint)
        state == TvSourceState.OK -> Text(stringResource(R.string.tv_mail_settings_none), style = MaterialTheme.typography.bodySmall, color = colors.faint)
        else -> Text(stringResource(R.string.tv_mail_missing), style = MaterialTheme.typography.bodySmall, color = colors.faint)
    }
}
