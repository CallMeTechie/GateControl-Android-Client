package com.gatecontrol.android.ui.chat.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.R
import com.gatecontrol.android.chat.ChatApprovers
import com.gatecontrol.android.chat.ChatConversation
import com.gatecontrol.android.chat.ChatDoorbellStatus
import com.gatecontrol.android.chat.ChatHomeInfo
import com.gatecontrol.android.chat.ChatHomeJson
import com.gatecontrol.android.ui.chat.ChatText
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcListRow
import com.gatecontrol.android.ui.components.GcSectionLabel
import com.gatecontrol.android.ui.theme.GateControlTheme

/**
 * "Haus-Aktionen" (mockup ChatHomeSettings): where doorbell cards go and
 * what the last ring did, who approves door requests and for how long, how
 * long a device may be shared — the administrator's settings, shown read
 * only (Messenger C4/C5).
 */
@Composable
fun ChatHomeDefaults(home: ChatHomeInfo, conversations: List<ChatConversation>, doorbell: ChatDoorbellStatus? = null) {
    val ctx = LocalContext.current
    val extra = GateControlTheme.extraColors
    val group = ChatHomeJson.familyGroup(conversations)

    Label(stringResource(R.string.chat_home_bell))
    Card {
        GcListRow(
            title = stringResource(R.string.chat_home_bell_target),
            description = when (group) {
                null -> stringResource(R.string.chat_home_bell_target_none)
                else -> stringResource(R.string.chat_home_bell_group, group.title.orEmpty()) + " · " +
                    pluralStringResource(R.plurals.chat_home_members, group.members.size, group.members.size)
            },
            trailing = { Value(group?.title ?: "–") },
        )
        val ringAt = doorbell?.lastRingAt
        when {
            doorbell != null && ringAt != null -> {
                val ok = doorbell.chatOk
                StatusRow(
                    icon = if (ok == false) Icons.Outlined.ErrorOutline else Icons.Outlined.CheckCircle,
                    error = ok == false,
                    title = doorbell.source ?: stringResource(R.string.chat_home_bell),
                    description = listOfNotNull(
                        stringResource(R.string.chat_home_bell_last, ChatText.clock(ctx, ringAt)),
                        when {
                            ok == true && doorbell.fallback -> stringResource(R.string.chat_home_bell_fallback)
                            ok == true -> stringResource(R.string.chat_home_bell_delivered)
                            ok == false -> bellError(doorbell.chatError)
                            else -> null
                        },
                    ).joinToString(" · "),
                )
            }
            home.doorbellLastCardAt != null -> StatusRow(
                Icons.Outlined.CheckCircle,
                false,
                stringResource(R.string.chat_home_bell),
                stringResource(R.string.chat_home_bell_last_card, ChatText.clock(ctx, home.doorbellLastCardAt)),
            )
            else -> StatusRow(Icons.AutoMirrored.Outlined.HelpOutline, false, stringResource(R.string.chat_home_bell_none), stringResource(R.string.chat_home_bell_why))
        }
    }

    Label(stringResource(R.string.chat_home_requests))
    Card {
        GcListRow(
            title = stringResource(R.string.chat_home_approvers),
            trailing = {
                Value(
                    when (home.approversDefault) {
                        ChatApprovers.PARENTS -> stringResource(R.string.chat_req_parents)
                        ChatApprovers.RIGHTS -> stringResource(R.string.chat_req_rights)
                        else -> "–"
                    },
                )
            },
        )
        GcListRow(title = stringResource(R.string.chat_home_ttl), trailing = { Value(stringResource(R.string.chat_home_minutes, home.requestTtlMin)) })
        GcListRow(
            title = stringResource(R.string.chat_home_children),
            description = stringResource(R.string.chat_home_children_desc),
            trailing = { Value(onOff(home.childrenMayRequest)) },
        )
    }

    Label(stringResource(R.string.chat_home_grants))
    Card {
        GcListRow(title = stringResource(R.string.chat_home_grant_max), trailing = { Value(pluralStringResource(R.plurals.chat_home_days, home.grantMaxDays, home.grantMaxDays)) })
        GcListRow(
            title = stringResource(R.string.chat_share_off_end),
            description = stringResource(R.string.chat_home_off_default_desc),
            trailing = { Value(onOff(home.offAtEndDefault)) },
        )
    }
    Text(
        stringResource(if (home.known) R.string.chat_home_admin_note else R.string.chat_home_old_server),
        style = MaterialTheme.typography.bodySmall,
        color = extra.muted,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

/** Why a doorbell card did not arrive (door plugin `chat.error`), readable. */
@Composable
private fun bellError(code: String?): String = when (code) {
    null -> stringResource(R.string.chat_home_bell_failed)
    "not_found" -> stringResource(R.string.chat_home_bell_err_not_found)
    "unavailable" -> stringResource(R.string.chat_home_bell_err_unavailable)
    else -> stringResource(R.string.chat_home_bell_err_other, code)
}

@Composable
private fun Label(text: String) = GcSectionLabel(text, Modifier.padding(start = 4.dp, top = 4.dp))

@Composable
private fun Card(content: @Composable () -> Unit) =
    GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) { content() }

@Composable
private fun Value(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), color = GateControlTheme.extraColors.accentText)
}

@Composable
private fun onOff(v: Boolean?): String = when (v) {
    true -> stringResource(R.string.chat_home_on)
    false -> stringResource(R.string.chat_home_off)
    null -> "–"
}

@Composable
private fun StatusRow(icon: ImageVector, error: Boolean, title: String, description: String) {
    val extra = GateControlTheme.extraColors
    GcListRow(
        title = title,
        description = description,
        leading = { Icon(icon, contentDescription = null, tint = if (error) MaterialTheme.colorScheme.error else extra.accentText, modifier = Modifier.size(22.dp)) },
    )
}
