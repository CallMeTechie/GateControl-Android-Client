package com.gatecontrol.android.tv.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.gatecontrol.android.chat.ChatConversation
import com.gatecontrol.android.tv.R
import com.gatecontrol.android.tv.TvChatHintSettings
import com.gatecontrol.android.tv.chat.TvChatLogic
import com.gatecontrol.android.tv.ui.components.TvOptionRow
import com.gatecontrol.android.tv.ui.components.TvRowEnd
import com.gatecontrol.android.tv.ui.theme.GateControlTvTheme
import com.gatecontrol.android.tv.zuhause.TvSourceState

/**
 * Einstellungen › Nachrichten (stage 3e): which chats the TV shows (default: only the family's groups, Frage 14 a),
 * and „Neue Nachricht von …“ over other apps with its quiet hours (Frage 18 a, default off). [mail] is where stage 3f
 * puts the choice of the mail accounts (null: nothing yet).
 */
@Composable
fun TvMessagesOptions(
    chats: List<ChatConversation>,
    chosen: Set<Long>,
    state: TvSourceState,
    onLoad: () -> Unit,
    onChoose: (Long, Boolean) -> Unit,
    onDefault: () -> Unit,
    hint: TvChatHintSettings,
    onHint: (TvChatHintSettings) -> Unit,
    mail: (@Composable () -> Unit)? = null,
) {
    val colors = GateControlTvTheme.colors
    LaunchedEffect(Unit) { onLoad() }
    Text(stringResource(R.string.tv_set_messages_desc), style = MaterialTheme.typography.bodySmall, color = colors.muted, modifier = Modifier.padding(bottom = 4.dp))
    TvOptionRow(
        title = stringResource(R.string.tv_msg_hint),
        description = stringResource(R.string.tv_msg_hint_desc),
        end = TvRowEnd.Switch(hint.on),
        onClick = { onHint(hint.copy(on = !hint.on)) },
    )
    if (hint.on) {
        TvOptionRow(
            title = stringResource(R.string.tv_msg_hint_quiet),
            description = stringResource(R.string.tv_bell_quiet_desc, hhmm(hint.quietFrom), hhmm(hint.quietTo)),
            end = TvRowEnd.Switch(hint.quiet),
            onClick = { onHint(hint.copy(quiet = !hint.quiet)) },
        )
        if (hint.quiet) {
            TvOptionRow(
                title = stringResource(R.string.tv_bell_quiet_from, hhmm(hint.quietFrom)),
                description = stringResource(R.string.tv_bell_quiet_step),
                end = TvRowEnd.Arrow,
                onClick = { onHint(hint.copy(quietFrom = (hint.quietFrom + QUIET_STEP_MIN) % (24 * 60))) },
            )
            TvOptionRow(
                title = stringResource(R.string.tv_bell_quiet_to, hhmm(hint.quietTo)),
                description = stringResource(R.string.tv_bell_quiet_step),
                end = TvRowEnd.Arrow,
                onClick = { onHint(hint.copy(quietTo = (hint.quietTo + QUIET_STEP_MIN) % (24 * 60))) },
            )
        }
    }
    mail?.invoke()
    Text(stringResource(R.string.tv_msg_chats), style = MaterialTheme.typography.titleSmall, color = colors.text, modifier = Modifier.padding(top = 8.dp))
    Text(stringResource(R.string.tv_msg_chats_desc), style = MaterialTheme.typography.bodySmall, color = colors.muted)
    TvOptionRow(
        title = stringResource(R.string.tv_msg_chats_default),
        description = stringResource(R.string.tv_msg_chats_default_desc),
        end = TvRowEnd.Arrow,
        onClick = onDefault,
    )
    when {
        chats.isNotEmpty() -> {
            val unnamed = stringResource(R.string.tv_chats_unnamed)
            for (c in chats) {
                val on = c.id in chosen
                TvOptionRow(
                    title = TvChatLogic.title(c, unnamed),
                    description = listOfNotNull(
                        stringResource(if (c.isGroup) R.string.tv_msg_chat_group else R.string.tv_msg_chat_direct),
                        c.bridge?.label,
                        stringResource(R.string.tv_msg_chat_family).takeIf { TvChatLogic.familyGroup(c) },
                    ).joinToString(" · "),
                    end = TvRowEnd.Switch(on),
                    onClick = { onChoose(c.id, !on) },
                )
            }
        }
        state == TvSourceState.LOADING -> Text(stringResource(R.string.tv_home_loading), style = MaterialTheme.typography.bodyMedium, color = colors.muted)
        state == TvSourceState.OFFLINE -> Text(stringResource(R.string.tv_home_offline), style = MaterialTheme.typography.bodyMedium, color = colors.muted)
        state == TvSourceState.MISSING -> Text(stringResource(R.string.tv_chats_missing), style = MaterialTheme.typography.bodyMedium, color = colors.muted)
        else -> Text(stringResource(R.string.tv_msg_chats_none), style = MaterialTheme.typography.bodyMedium, color = colors.muted)
    }
}
