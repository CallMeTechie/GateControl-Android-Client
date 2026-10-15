package com.gatecontrol.android.ui.notifications

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import com.gatecontrol.android.notify.model.PushAction
import com.gatecontrol.android.push.NotificationQuickActions
import com.gatecontrol.android.push.NotifyMediaLoader
import com.gatecontrol.android.ui.components.DeviceAuthResult
import com.gatecontrol.android.ui.components.rememberGcDeviceAuth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.notify.model.PushActionType
import com.gatecontrol.android.notify.model.PushMessage
import com.gatecontrol.android.notify.model.PushPriority
import com.gatecontrol.android.push.PushNavTarget
import com.gatecontrol.android.push.PushText
import com.gatecontrol.android.ui.components.GcChip
import com.gatecontrol.android.ui.components.GcIconSquare
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.components.GcSubpageBar
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.theme.GateControlTheme
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** One message: priority and topic, text, its actions, how it was delivered. */
@Composable
fun NotificationDetailScreen(
    onBack: () -> Unit,
    onNavigate: (PushNavTarget) -> Unit,
    viewModel: NotificationDetailViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val extra = GateControlTheme.extraColors

    LaunchedEffect(viewModel) {
        viewModel.effect.collect { effect ->
            when (effect) {
                is DetailEffect.Navigate -> onNavigate(effect.target)
                is DetailEffect.OpenUrl -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(effect.url))) }
                DetailEffect.PortalUnavailable ->
                    Toast.makeText(context, resources.getString(R.string.push_portal_unavailable), Toast.LENGTH_LONG).show()
                is DetailEffect.Muted -> {
                    val label = PushText.topicLabel(effect.topic)?.let { resources.getString(it) } ?: PushText.fallbackLabel(effect.topic)
                    Toast.makeText(context, resources.getString(R.string.push_action_muted_toast, label), Toast.LENGTH_SHORT).show()
                }
                DetailEffect.Done -> Toast.makeText(context, resources.getString(R.string.push_action_done_toast), Toast.LENGTH_SHORT).show()
                is DetailEffect.QuickResult -> {
                    val text = effect.text ?: resources.getString(
                        when (effect.refusal) {
                            NotificationQuickActions.Decision.EXPIRED -> R.string.push_quick_action_expired
                            null -> if (effect.ok) R.string.push_quick_action_done else R.string.push_quick_action_failed
                            else -> R.string.push_quick_action_unavailable
                        },
                    )
                    Toast.makeText(context, text, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState()),
    ) {
        GcSubpageBar(
            title = stringResource(R.string.push_detail_title),
            onBack = onBack,
            backDescription = stringResource(R.string.push_detail_back),
        )
        val message = ui.message
        if (message == null) {
            if (ui.loaded) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.push_detail_missing), style = MaterialTheme.typography.bodyLarge, color = extra.muted)
                    GcOutlineButton(text = stringResource(R.string.push_detail_open_inbox), onClick = { onNavigate(PushNavTarget.Inbox) })
                }
            }
            return@Column
        }
        DetailContent(message, ui, viewModel)
    }
}

@Composable
private fun DetailContent(message: PushMessage, ui: NotificationDetailUiState, viewModel: NotificationDetailViewModel) {
    val extra = GateControlTheme.extraColors
    val context = LocalContext.current
    val resources = LocalResources.current
    val auth = rememberGcDeviceAuth()
    var confirm by remember { mutableStateOf<PushAction?>(null) }

    // A plugin's quick action: asked first when it wants (confirm) or needs (auth:'device') it,
    // then the device unlock (biometrics / screen lock) for auth:'device'.
    fun runChecked(action: PushAction) {
        if (!action.needsDeviceAuth) {
            viewModel.runAction(action)
            return
        }
        auth.authenticate(resources.getString(R.string.push_quick_auth_title), action.label) { result ->
            when (result) {
                DeviceAuthResult.SUCCESS -> viewModel.runAction(action)
                DeviceAuthResult.CANCELLED -> Unit
                DeviceAuthResult.FAILED ->
                    Toast.makeText(context, resources.getString(R.string.push_quick_auth_failed), Toast.LENGTH_LONG).show()
                DeviceAuthResult.UNAVAILABLE ->
                    Toast.makeText(context, resources.getString(R.string.push_quick_auth_unavailable), Toast.LENGTH_LONG).show()
            }
        }
    }
    val onAction: (PushAction) -> Unit = { action ->
        when {
            !action.isQuickAction -> viewModel.runAction(action)
            action.needsDeviceAuth && !auth.available() ->
                Toast.makeText(context, resources.getString(R.string.push_quick_auth_unavailable), Toast.LENGTH_LONG).show()
            action.confirm || action.needsDeviceAuth -> confirm = action
            else -> runChecked(action)
        }
    }
    confirm?.let { action ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.extraLarge,
            title = { Text(stringResource(R.string.push_quick_confirm_title, action.label), style = MaterialTheme.typography.titleLarge) },
            text = { Text(message.title, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    runChecked(action)
                }) { Text(action.label) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
    val (fg, bg) = messageColors(message)
    val priority = message.priorityLevel
    val topic = topicLabel(message.topic, ui.topicLabel)
    val zone = ZoneId.systemDefault()
    val day = localDate(message, zone)
    val dayText = when (day) {
        LocalDate.now(zone) -> stringResource(R.string.push_inbox_today).lowercase()
        LocalDate.now(zone).minusDays(1) -> stringResource(R.string.push_inbox_yesterday).lowercase()
        null -> ""
        else -> dateOf(day)
    }
    val whenText = listOf(dayText, timeOf(message, zone)).filter { it.isNotEmpty() }.joinToString(", ")

    Column(
        Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
            GcIconSquare(size = 52.dp, background = bg) {
                Icon(if (priority == PushPriority.CRITICAL) GcIcons.Alert else topicIcon(message.topic), contentDescription = null, tint = fg, modifier = Modifier.size(26.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                GcChip(
                    text = stringResource(PushText.priorityLabel(priority)) + " · " + topic,
                    tone = when (priority) {
                        PushPriority.CRITICAL -> GcTone.Error
                        PushPriority.HIGH -> GcTone.Warn
                        PushPriority.INFO -> GcTone.Neutral
                        PushPriority.NORMAL -> if (message.topic.startsWith("plugin:")) GcTone.Info else GcTone.Ok
                    },
                    small = true,
                )
                Text(message.title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground)
                Text(
                    if (ui.host.isNotEmpty()) stringResource(R.string.push_detail_from, whenText, ui.host) else whenText,
                    style = MaterialTheme.typography.bodySmall,
                    color = extra.muted,
                )
            }
        }

        rememberPushPicture(message.imageUrl, NotifyMediaLoader.NOTIFICATION_SIDE_PX)?.let { picture ->
            Image(
                picture,
                contentDescription = stringResource(R.string.push_detail_image_desc),
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)),
            )
        }

        if (message.body.isNotBlank()) {
            Text(message.body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        }

        val actions = message.actions
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            actions.forEachIndexed { i, action ->
                val busy = ui.busyAction == action.id
                if (i == 0) {
                    GcPrimaryButton(text = action.label, onClick = { onAction(action) }, loading = busy)
                } else {
                    GcOutlineButton(text = action.label, onClick = { onAction(action) }, loading = busy)
                }
            }
            if (actions.none { it.type == PushActionType.MUTE_1H }) {
                GcOutlineButton(
                    text = stringResource(R.string.push_detail_mute),
                    onClick = viewModel::muteTopic,
                    loading = ui.busyAction == PushActionType.MUTE_1H,
                )
            }
        }

        val footer = buildList {
            if (message.receivedAt > 0 && message.via != null) {
                val at = java.time.Instant.ofEpochMilli(message.receivedAt).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm:ss"))
                add(stringResource(if (message.via == "tunnel") R.string.push_detail_delivered_tunnel else R.string.push_detail_delivered_direct, at))
            }
            if (message.collapseKey != null && priority >= PushPriority.HIGH) add(stringResource(R.string.push_detail_collapse_hint))
        }
        if (footer.isNotEmpty()) {
            Text(footer.joinToString(" "), style = MaterialTheme.typography.bodySmall, color = extra.faint, modifier = Modifier.fillMaxWidth())
        }
    }
}
