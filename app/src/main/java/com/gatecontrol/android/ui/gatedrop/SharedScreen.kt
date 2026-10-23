package com.gatecontrol.android.ui.gatedrop

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.gatedrop.GateDropLogic
import com.gatecontrol.android.gatedrop.GateDropNotifier
import com.gatecontrol.android.gatedrop.GdDelivery
import com.gatecontrol.android.gatedrop.GdEntry
import com.gatecontrol.android.gatedrop.GdLink
import com.gatecontrol.android.gatedrop.GdReceiveFrom
import com.gatecontrol.android.gatedrop.GdReceived
import com.gatecontrol.android.ui.components.GcBanner
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIconSquare
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcListRow
import com.gatecontrol.android.ui.components.GcRowDivider
import com.gatecontrol.android.ui.components.GcSegmented
import com.gatecontrol.android.ui.components.GcSubpageBar
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.theme.GateControlTheme
import java.util.Locale

/**
 * "Geteilt" (GateDrop, mockup "Empfangen"): what reached this person's
 * devices, what they sent and the links for externals (which can be
 * ended). Reached from Einstellungen and from GateDrop's notifications.
 */
@Composable
fun SharedScreen(
    onBack: () -> Unit,
    initialTab: String = GateDropNotifier.TAB_RECEIVED,
    viewModel: SharedViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val extra = GateControlTheme.extraColors
    val ctx = LocalContext.current
    LaunchedEffect(initialTab) { viewModel.select(initialTab) }
    var ending by remember { mutableStateOf<GdLink?>(null) }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState()),
    ) {
        GcSubpageBar(
            title = stringResource(R.string.gatedrop_shared_title),
            onBack = onBack,
            backDescription = stringResource(R.string.common_back),
            actions = {
                GcIconButton(GcIcons.Refresh, contentDescription = stringResource(R.string.gatedrop_reload), onClick = viewModel::load)
            },
        )
        Column(
            Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ui.receiveFrom?.let { mode -> ReceiveFromCard(mode, ui.receiveFromFailed, viewModel::setReceiveFrom) }
            GcSegmented(
                options = listOf(
                    GateDropNotifier.TAB_RECEIVED to stringResource(R.string.gatedrop_tab_received),
                    GateDropNotifier.TAB_SENT to stringResource(R.string.gatedrop_tab_sent),
                    GateDropNotifier.TAB_LINKS to stringResource(R.string.gatedrop_tab_links),
                ),
                selected = ui.tab,
                onSelect = viewModel::select,
            )
            if (ui.offline && !ui.loading) {
                GcBanner(tone = GcTone.Warn, icon = GcIcons.Alert) {
                    Text(stringResource(R.string.gatedrop_offline), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                }
            }
            when (ui.tab) {
                GateDropNotifier.TAB_SENT -> SentList(ui)
                GateDropNotifier.TAB_LINKS -> LinkList(ui, onCopy = { copy(ctx, it) }, onEnd = { ending = it })
                else -> ReceivedList(ui, onSave = viewModel::save, onOpened = viewModel::opened)
            }
            if (ui.loading) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) }
            }
            Text(
                stringResource(R.string.gatedrop_shared_footer),
                style = MaterialTheme.typography.bodySmall,
                color = extra.muted,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }

    ending?.let { link ->
        AlertDialog(
            onDismissRequest = { ending = null },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.extraLarge,
            title = { Text(stringResource(R.string.gatedrop_link_end_title), style = MaterialTheme.typography.titleLarge) },
            text = { Text(stringResource(R.string.gatedrop_link_end_text), style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.endLink(link.token)
                    ending = null
                }) { Text(stringResource(R.string.gatedrop_link_end), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { ending = null }) { Text(stringResource(R.string.gatedrop_cancel)) } },
        )
    }
}

private fun copy(ctx: Context, text: String) {
    ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("GateDrop", text))
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(ctx, R.string.gatedrop_copied, Toast.LENGTH_SHORT).show()
    }
}

private fun open(ctx: Context, intent: Intent) {
    runCatching { ctx.startActivity(intent) }.onFailure { Toast.makeText(ctx, R.string.gatedrop_no_app, Toast.LENGTH_SHORT).show() }
}

private fun time(ctx: Context, at: Long?): String =
    if (at == null || at <= 0) "" else DateUtils.getRelativeTimeSpanString(at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()


/** "Wer darf an dieses Gerät senden?" — only me (default) or everyone on the own GateControl. */
@Composable
private fun ReceiveFromCard(mode: String, failed: Boolean, onSelect: (String) -> Unit) {
    val extra = GateControlTheme.extraColors
    GcCard(contentPadding = PaddingValues(16.dp)) {
        Text(stringResource(R.string.gatedrop_receive_from_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        GcSegmented(
            options = listOf(
                GdReceiveFrom.SELF to stringResource(R.string.gatedrop_receive_from_self),
                GdReceiveFrom.EVERYONE to stringResource(R.string.gatedrop_receive_from_everyone),
            ),
            selected = mode,
            onSelect = onSelect,
        )
        Text(
            stringResource(if (mode == GdReceiveFrom.EVERYONE) R.string.gatedrop_receive_from_everyone_desc else R.string.gatedrop_receive_from_self_desc),
            style = MaterialTheme.typography.bodySmall,
            color = extra.muted,
        )
        if (failed) {
            Text(stringResource(R.string.gatedrop_receive_from_failed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun EmptyCard(text: String) {
    GcCard(contentPadding = PaddingValues(16.dp)) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = GateControlTheme.extraColors.muted)
    }
}

/** Leading square: the file type (PDF, IMG …), "Aa" for a text, the link symbol for a link. */
@Composable
private fun KindSquare(label: String?, link: Boolean = false) {
    val extra = GateControlTheme.extraColors
    GcIconSquare(size = 40.dp, background = if (link) extra.blueBg else extra.panel2) {
        when {
            link -> Icon(GcIcons.Link, contentDescription = null, tint = extra.blue, modifier = Modifier.size(18.dp))
            label != null -> Text(label, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 11.sp), color = extra.muted, textAlign = TextAlign.Center)
            else -> Icon(GcIcons.File, contentDescription = null, tint = extra.muted, modifier = Modifier.size(18.dp))
        }
    }
}

private fun typeLabel(name: String?, mime: String?): String? {
    val ext = name?.substringAfterLast('.', "")?.takeIf { it.length in 1..4 }?.uppercase(Locale.ROOT)
    return ext ?: when {
        mime?.startsWith("image/") == true -> "IMG"
        mime?.startsWith("video/") == true -> "VID"
        else -> null
    }
}

@Composable
private fun ReceivedList(ui: SharedUi, onSave: (GdReceived) -> Unit, onOpened: (GdReceived) -> Unit) {
    val ctx = LocalContext.current
    val extra = GateControlTheme.extraColors
    if (ui.received.isEmpty()) {
        if (!ui.loading) EmptyCard(stringResource(R.string.gatedrop_empty_received))
        return
    }
    GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.Top) {
        ui.received.forEachIndexed { i, e ->
            if (i > 0) GcRowDivider()
            val from = e.from ?: stringResource(R.string.gatedrop_someone)
            val sub = stringResource(R.string.gatedrop_from, from, time(ctx, e.at))
            val saving = e.key in ui.saving
            when {
                e.files.isNotEmpty() -> {
                    val single = e.files.singleOrNull()
                    val saved = e.files.filter { it.uri != null }
                    GcListRow(
                        title = single?.name ?: pluralStringResource(R.plurals.gatedrop_files, e.files.size, e.files.size),
                        description = sub + if (saved.size == e.files.size) " · " + stringResource(R.string.gatedrop_saved_locally) else "",
                        leading = { KindSquare(typeLabel(single?.name, single?.mime)) },
                        onClick = {
                            val uri = single?.uri
                            if (uri != null) {
                                onOpened(e)
                                open(ctx, Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), single.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                            } else if (saved.isNotEmpty() && saved.size == e.files.size) {
                                open(ctx, Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS))
                            }
                        },
                        trailing = {
                            when {
                                saving -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                saved.size < e.files.size && e.sendId != null && e.files.any { it.mediaId != null } ->
                                    GcIconButton(GcIcons.Download, contentDescription = stringResource(R.string.gatedrop_save), onClick = { onSave(e) }, tint = extra.accentText)
                                saved.isNotEmpty() -> Icon(GcIcons.External, contentDescription = null, tint = extra.faint, modifier = Modifier.size(20.dp))
                            }
                        },
                    )
                }
                e.link != null -> GcListRow(
                    title = stringResource(R.string.gatedrop_item_link, e.link),
                    description = sub,
                    leading = { KindSquare(null, link = true) },
                    onClick = {
                        onOpened(e)
                        open(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(e.link)))
                    },
                    trailing = { GcIconButton(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.gatedrop_copy), onClick = { copy(ctx, e.link) }) },
                )
                else -> GcListRow(
                    title = stringResource(R.string.gatedrop_item_text, e.text?.lineSequence()?.firstOrNull().orEmpty().take(60)),
                    description = sub,
                    leading = { KindSquare("Aa") },
                    onClick = {
                        e.text?.let { copy(ctx, it) }
                        onOpened(e)
                    },
                    trailing = { Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.gatedrop_copy), tint = extra.faint, modifier = Modifier.size(20.dp)) },
                )
            }
        }
    }
}

@Composable
private fun statusText(status: String?): String? = when (status) {
    GdDelivery.PENDING -> stringResource(R.string.gatedrop_status_pending)
    GdDelivery.WAITING -> stringResource(R.string.gatedrop_status_waiting_any)
    GdDelivery.DIRECT -> stringResource(R.string.gatedrop_status_direct)
    GdDelivery.DELIVERED -> stringResource(R.string.gatedrop_status_delivered)
    GdDelivery.OPENED -> stringResource(R.string.gatedrop_status_opened)
    GdDelivery.FAILED -> stringResource(R.string.gatedrop_status_failed)
    GdDelivery.EXPIRED -> stringResource(R.string.gatedrop_status_expired)
    else -> null
}

@Composable
private fun deliveryName(d: GdDelivery): String = d.targetName ?: when (d.kind) {
    "link" -> stringResource(R.string.gatedrop_link_name)
    "nas" -> stringResource(R.string.gatedrop_nas)
    "person" -> if (d.target == "family") stringResource(R.string.gatedrop_family) else stringResource(R.string.gatedrop_people)
    else -> stringResource(R.string.gatedrop_devices)
}

@Composable
private fun SentList(ui: SharedUi) {
    val ctx = LocalContext.current
    if (ui.sent.isEmpty()) {
        if (!ui.loading) EmptyCard(stringResource(R.string.gatedrop_empty_sent))
        return
    }
    GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.Top) {
        ui.sent.forEachIndexed { i, e: GdEntry ->
            if (i > 0) GcRowDivider()
            val single = e.items.singleOrNull()
            val title = when {
                e.items.size > 1 -> pluralStringResource(R.plurals.gatedrop_files, e.items.size, e.items.size)
                single != null -> single.name
                e.link != null -> stringResource(R.string.gatedrop_item_link, e.link)
                else -> stringResource(R.string.gatedrop_item_text, e.text?.lineSequence()?.firstOrNull().orEmpty().take(60))
            }
            val to = e.deliveries.map { deliveryName(it) }.distinct().take(3).joinToString(", ")
            val overall = GateDropLogic.overallStatus(e.deliveries)
            // The target device was offline: it fetches the send once it is online.
            val waiting = e.deliveries.firstOrNull { it.status == GdDelivery.WAITING }
            val status = if (overall == GdDelivery.WAITING && waiting != null) {
                waiting.targetName?.let { stringResource(R.string.gatedrop_status_waiting, it) } ?: statusText(overall)
            } else {
                statusText(overall)
            }
            GcListRow(
                title = title,
                description = listOfNotNull(
                    to.takeIf { it.isNotBlank() }?.let { stringResource(R.string.gatedrop_to, it) },
                    time(ctx, e.at).takeIf { it.isNotBlank() },
                    status,
                ).joinToString(" · "),
                leading = { KindSquare(typeLabel(single?.name, single?.mime), link = e.items.isEmpty() && e.link != null) },
            )
        }
    }
}

@Composable
private fun LinkList(ui: SharedUi, onCopy: (String) -> Unit, onEnd: (GdLink) -> Unit) {
    val ctx = LocalContext.current
    if (ui.links.isEmpty()) {
        if (!ui.loading) EmptyCard(stringResource(R.string.gatedrop_empty_links))
        return
    }
    GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.Top) {
        ui.links.forEachIndexed { i, l ->
            if (i > 0) GcRowDivider()
            val expired = l.expiresAt?.let { it < System.currentTimeMillis() } == true
            val state = when {
                l.state == GdLink.STATE_REVOKED -> stringResource(R.string.gatedrop_link_state_revoked)
                l.state == GdLink.STATE_LOCKED -> stringResource(R.string.gatedrop_link_state_locked)
                l.state == GdLink.STATE_EXPIRED || expired -> stringResource(R.string.gatedrop_link_state_expired)
                else -> l.expiresAt?.let {
                    stringResource(R.string.gatedrop_link_expires, DateUtils.formatDateTime(ctx, it, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH))
                }
            }
            val active = l.active && !expired
            GcListRow(
                title = l.title ?: l.url ?: l.token,
                description = listOfNotNull(
                    state,
                    pluralStringResource(R.plurals.gatedrop_link_downloads, l.downloads, l.downloads),
                    if (l.pin) stringResource(R.string.gatedrop_link_pin_label) else null,
                ).joinToString(" · "),
                leading = { KindSquare(null, link = true) },
                onClick = l.url?.takeIf { active }?.let { url -> { onCopy(url) } },
                trailing = {
                    if (active) {
                        TextButton(onClick = { onEnd(l) }) { Text(stringResource(R.string.gatedrop_link_end), color = MaterialTheme.colorScheme.error) }
                    }
                },
            )
        }
    }
}
