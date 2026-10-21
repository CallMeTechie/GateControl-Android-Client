package com.gatecontrol.android.ui.gatedrop

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gatecontrol.android.R
import com.gatecontrol.android.chat.ChatPerson
import com.gatecontrol.android.gatedrop.GateDropLogic
import com.gatecontrol.android.gatedrop.GateDropNotifier
import com.gatecontrol.android.gatedrop.GdDevice
import com.gatecontrol.android.gatedrop.GdDeviceState
import com.gatecontrol.android.gatedrop.GdExpiry
import com.gatecontrol.android.gatedrop.GdLinkRequest
import com.gatecontrol.android.gatedrop.GdPerson
import com.gatecontrol.android.profile.AvatarRing
import com.gatecontrol.android.profile.AvatarUrls
import com.gatecontrol.android.ui.components.GcAvatar
import com.gatecontrol.android.ui.components.GcBanner
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcChevron
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIconSquare
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcListRow
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.components.GcRowDivider
import com.gatecontrol.android.ui.components.GcSecondaryButton
import com.gatecontrol.android.ui.components.GcSectionLabel
import com.gatecontrol.android.ui.components.GcSegmented
import com.gatecontrol.android.ui.components.GcSwitchRow
import com.gatecontrol.android.ui.components.GcTextField
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.theme.GateControlTheme
import com.gatecontrol.android.ui.theme.MonoFontFamily
import java.util.Locale

/**
 * GateDrop's share sheet (mockup "Android · Teilen mit GateControl" and
 * "Link für Externe") in the app's own design: the bottom sheet, cards,
 * list rows, segmented control, switch rows and buttons the rest of the
 * app uses. Sections: Personen, Meine Geräte, Ablegen (NAS), Link für
 * Externe, an optional note and the send button (multi-select).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GateDropShareSheet(
    ui: GdShareUi,
    serverUrl: String,
    /** The chats exist: "Im Chat teilen …" opens today's chat sharing. */
    onShareInChat: (() -> Unit)?,
    onTogglePerson: (String) -> Unit,
    onToggleDevice: (String) -> Unit,
    onToggleNas: (String) -> Unit,
    onNote: (String) -> Unit,
    onOpenLink: () -> Unit,
    onLinkDraft: (GdLinkRequest) -> Unit,
    onApplyLink: () -> Unit,
    onRemoveLink: () -> Unit,
    onCloseLink: () -> Unit,
    onRetry: () -> Unit,
    onSend: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        scrimColor = GateControlTheme.extraColors.scrim,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(
            Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (ui.stage) {
                GdShareStage.LINK_OPTIONS -> LinkOptions(ui, onLinkDraft, onApplyLink, onRemoveLink, onCloseLink)
                GdShareStage.PREPARING, GdShareStage.WAITING -> Progress(ui)
                GdShareStage.RESULT -> LinkResult(ui, onDismiss)
                GdShareStage.FAILED -> Failed(onDismiss)
                else -> Main(ui, serverUrl, onShareInChat, onTogglePerson, onToggleDevice, onToggleNas, onNote, onOpenLink, onRetry, onSend)
            }
        }
    }
}

@Composable
private fun Header(ui: GdShareUi) {
    val extra = GateControlTheme.extraColors
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0] ?: Locale.ROOT
    val subtitle = when {
        ui.sources.isNotEmpty() -> pluralStringResource(R.plurals.gatedrop_share_files, ui.sources.size, ui.sources.size) +
            (ui.totalBytes.takeIf { it > 0 }?.let { " · " + GateDropNotifier.size(it, locale) } ?: "")
        GateDropLogic.asLink(ui.text) != null -> stringResource(R.string.gatedrop_share_link_item)
        else -> stringResource(R.string.gatedrop_share_text_item)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        GcIconSquare(background = extra.accentBg) {
            Icon(if (ui.sources.isEmpty()) GcIcons.Link else GcIcons.Share, contentDescription = null, tint = extra.accentText, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.gatedrop_share_title), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = extra.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Main(
    ui: GdShareUi,
    serverUrl: String,
    onShareInChat: (() -> Unit)?,
    onTogglePerson: (String) -> Unit,
    onToggleDevice: (String) -> Unit,
    onToggleNas: (String) -> Unit,
    onNote: (String) -> Unit,
    onOpenLink: () -> Unit,
    onRetry: () -> Unit,
    onSend: () -> Unit,
) {
    val extra = GateControlTheme.extraColors
    Header(ui)
    if (ui.skipped > 0) {
        GcBanner(tone = GcTone.Warn, icon = GcIcons.Alert) {
            Text(pluralStringResource(R.plurals.gatedrop_skipped, ui.skipped, ui.skipped), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
        }
    }
    val targets = ui.targets
    when {
        targets == null && ui.loading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp)) }
        targets == null -> GcBanner(tone = GcTone.Warn, icon = GcIcons.Alert) {
            Text(stringResource(R.string.gatedrop_targets_error), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            TextButton(onClick = onRetry) { Text(stringResource(R.string.gatedrop_retry)) }
        }
        else -> {
            // ─── Personen ───
            if (targets.people.isNotEmpty()) {
                GcSectionLabel(stringResource(R.string.gatedrop_people))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    targets.people.forEach { p ->
                        PersonChoice(p, serverUrl, p.id in ui.selection.people) { onTogglePerson(p.id) }
                    }
                }
            }
            // ─── Meine Geräte ───
            if (targets.devices.isNotEmpty()) {
                GcSectionLabel(stringResource(R.string.gatedrop_devices))
                GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.Top) {
                    targets.devices.forEachIndexed { i, d ->
                        if (i > 0) GcRowDivider()
                        DeviceRow(d, d.id in ui.selection.devices) { onToggleDevice(d.id) }
                    }
                }
            }
            // ─── Ablegen (NAS) ───
            if (targets.nasOffered) {
                GcSectionLabel(stringResource(R.string.gatedrop_nas))
                GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.Top) {
                    targets.nas.forEachIndexed { i, n ->
                        if (i > 0) GcRowDivider()
                        val checked = n.id in ui.selection.nas
                        CheckRow(
                            title = n.label,
                            description = n.path?.takeIf { it != n.label },
                            icon = Icons.Outlined.Folder,
                            checked = checked,
                            onClick = { onToggleNas(n.id) },
                        )
                    }
                }
            }
            // ─── Link für Externe ───
            if (targets.linkOffered && ui.sources.isNotEmpty()) {
                GcSectionLabel(stringResource(R.string.gatedrop_link_section))
                GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.Top) {
                    val link = ui.selection.link
                    GcListRow(
                        title = stringResource(R.string.gatedrop_link_row),
                        description = if (link == null) stringResource(R.string.gatedrop_link_row_desc) else linkSummary(link),
                        onClick = onOpenLink,
                        leading = { Icon(GcIcons.Link, contentDescription = null, tint = extra.accentText, modifier = Modifier.size(22.dp)) },
                        trailing = { if (link != null) SelectedMark() else GcChevron() },
                        selected = link != null,
                    )
                }
            }
            if (targets.people.isEmpty() && targets.devices.isEmpty() && !targets.nasOffered && !targets.linkOffered) {
                Text(stringResource(R.string.gatedrop_no_targets), style = MaterialTheme.typography.bodyMedium, color = extra.muted)
            }
        }
    }
    GcTextField(
        value = ui.note,
        onValueChange = onNote,
        label = stringResource(R.string.gatedrop_note),
        placeholder = stringResource(R.string.gatedrop_note_hint),
    )
    val names = targets?.let { GateDropLogic.chosenNames(ui.selection, it) }.orEmpty() +
        listOfNotNull(ui.selection.link?.takeIf { targets?.linkOffered == true }?.let { stringResource(R.string.gatedrop_link_name) })
    GcPrimaryButton(
        text = if (names.isEmpty()) stringResource(R.string.gatedrop_send) else stringResource(R.string.gatedrop_send_to, joinNames(names)),
        onClick = onSend,
        enabled = ui.canSend,
    )
    if (onShareInChat != null) {
        GcSecondaryButton(text = stringResource(R.string.gatedrop_in_chat), onClick = onShareInChat)
    }
}

@Composable
private fun joinNames(names: List<String>): String {
    if (names.size <= 1) return names.firstOrNull().orEmpty()
    val shown = if (names.size > 3) names.take(2) + pluralStringResource(R.plurals.gatedrop_more, names.size - 2, names.size - 2) else names
    return stringResource(R.string.gatedrop_and, shown.dropLast(1).joinToString(", "), shown.last())
}

@Composable
private fun linkSummary(link: GdLinkRequest): String {
    val validity = expiryLabel(link.expiry)
    return if (link.pin) stringResource(R.string.gatedrop_link_chosen_pin, validity) else validity
}

@Composable
private fun expiryLabel(e: GdExpiry): String = stringResource(
    when (e) {
        GdExpiry.HOUR -> R.string.gatedrop_expiry_1h
        GdExpiry.DAY -> R.string.gatedrop_expiry_1d
        GdExpiry.WEEK -> R.string.gatedrop_expiry_7d
        GdExpiry.ONCE -> R.string.gatedrop_expiry_once
    },
)

@Composable
private fun expiryShort(e: GdExpiry): String = stringResource(
    when (e) {
        GdExpiry.HOUR -> R.string.gatedrop_expiry_1h_short
        GdExpiry.DAY -> R.string.gatedrop_expiry_1d_short
        GdExpiry.WEEK -> R.string.gatedrop_expiry_7d_short
        GdExpiry.ONCE -> R.string.gatedrop_expiry_once_short
    },
)

/** A check in the primary colour (selected row). */
@Composable
private fun SelectedMark() {
    val scheme = MaterialTheme.colorScheme
    Box(Modifier.size(24.dp).clip(CircleShape).background(scheme.primary), contentAlignment = Alignment.Center) {
        Icon(GcIcons.Check, contentDescription = null, tint = scheme.onPrimary, modifier = Modifier.size(14.dp))
    }
}

/** A person (or the family) as a round avatar with its name; picked: ring and check. */
@Composable
private fun PersonChoice(p: GdPerson, serverUrl: String, checked: Boolean, onClick: () -> Unit) {
    val extra = GateControlTheme.extraColors
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier.width(68.dp).clip(RoundedCornerShape(14.dp)).clickable(role = Role.Checkbox, onClick = onClick).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            val ring = Modifier.size(56.dp).then(if (checked) Modifier.border(2.dp, scheme.primary, CircleShape) else Modifier).padding(4.dp)
            if (p.isFamily) {
                Box(ring.clip(CircleShape).background(extra.accentBg), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Groups, contentDescription = null, tint = extra.accentText, modifier = Modifier.size(24.dp))
                }
            } else {
                Box(ring, contentAlignment = Alignment.Center) {
                    GcAvatar(
                        url = AvatarUrls.resolve(serverUrl, p.avatar),
                        initials = p.initials ?: ChatPerson.initialsOf(p.name),
                        colorHex = p.colorHex,
                        ring = AvatarRing.None,
                        size = 48.dp,
                    )
                }
            }
            if (checked) {
                Box(Modifier.align(Alignment.BottomEnd).size(20.dp).clip(CircleShape).background(scheme.primary).border(2.dp, scheme.surface, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(GcIcons.Check, contentDescription = null, tint = scheme.onPrimary, modifier = Modifier.size(11.dp))
                }
            }
        }
        Text(
            if (p.isFamily) stringResource(R.string.gatedrop_family) else p.name.substringBefore(' '),
            style = MaterialTheme.typography.labelMedium,
            color = if (checked) scheme.onSurface else extra.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun DeviceRow(d: GdDevice, checked: Boolean, onClick: () -> Unit) {
    val state = GateDropLogic.deviceState(d)
    CheckRow(
        title = d.name,
        description = stringResource(
            when (state) {
                GdDeviceState.DIRECT -> R.string.gatedrop_device_direct
                GdDeviceState.ONLINE -> R.string.gatedrop_device_online
                GdDeviceState.OFFLINE -> R.string.gatedrop_device_offline
            },
        ),
        descriptionAccent = state == GdDeviceState.DIRECT,
        icon = if (d.platform == "android" || d.platform == "ios") Icons.Outlined.PhoneAndroid else Icons.Outlined.Computer,
        checked = checked,
        onClick = onClick,
    )
}

/** List row with a checkbox (`.li` + checkbox), as the app's pickers. */
@Composable
private fun CheckRow(title: String, description: String?, icon: ImageVector, checked: Boolean, onClick: () -> Unit, descriptionAccent: Boolean = false) {
    val extra = GateControlTheme.extraColors
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Checkbox, onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, contentDescription = null, tint = extra.accentText, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            description?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = if (descriptionAccent) extra.accentText else extra.muted)
            }
        }
        Checkbox(checked = checked, onCheckedChange = null, colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary))
    }
}

// ─── Link für Externe ───────────────────────────────────────────────

@Composable
private fun LinkOptions(
    ui: GdShareUi,
    onLinkDraft: (GdLinkRequest) -> Unit,
    onApply: () -> Unit,
    onRemove: () -> Unit,
    onClose: () -> Unit,
) {
    val extra = GateControlTheme.extraColors
    val draft = ui.linkDraft
    Row(verticalAlignment = Alignment.CenterVertically) {
        GcIconButton(GcIcons.Back, contentDescription = stringResource(R.string.common_back), onClick = onClose, tint = MaterialTheme.colorScheme.onSurface)
        Text(stringResource(R.string.gatedrop_link_title), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
    }
    Text(
        pluralStringResource(R.plurals.gatedrop_link_intro, ui.sources.size, ui.sources.size),
        style = MaterialTheme.typography.bodyMedium,
        color = extra.muted,
    )
    GcSectionLabel(stringResource(R.string.gatedrop_link_valid))
    GcSegmented(
        options = GdExpiry.entries.map { it to expiryShort(it) },
        selected = draft.expiry,
        onSelect = { onLinkDraft(draft.copy(expiry = it)) },
    )
    Text(expiryLabel(draft.expiry), style = MaterialTheme.typography.bodySmall, color = extra.muted)
    GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.Top) {
        GcSwitchRow(
            label = stringResource(R.string.gatedrop_link_pin),
            description = stringResource(R.string.gatedrop_link_pin_desc),
            checked = draft.pin,
            onCheckedChange = { onLinkDraft(draft.copy(pin = it)) },
        )
    }
    GcPrimaryButton(text = stringResource(R.string.gatedrop_link_apply), onClick = onApply)
    if (ui.selection.link != null) GcSecondaryButton(text = stringResource(R.string.gatedrop_link_remove), onClick = onRemove)
}

@Composable
private fun Progress(ui: GdShareUi) {
    val extra = GateControlTheme.extraColors
    Header(ui)
    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val p = ui.percent
        Text(
            when {
                ui.stage == GdShareStage.PREPARING -> stringResource(R.string.gatedrop_preparing)
                p != null -> stringResource(R.string.gatedrop_uploading_percent, p)
                else -> stringResource(R.string.gatedrop_waiting_link)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = extra.muted,
        )
        if (p != null) LinearProgressIndicator(progress = { p / 100f }, modifier = Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(stringResource(R.string.gatedrop_progress_background), style = MaterialTheme.typography.bodySmall, color = extra.faint)
    }
}

@Composable
private fun LinkResult(ui: GdShareUi, onDone: () -> Unit) {
    val extra = GateControlTheme.extraColors
    val ctx = LocalContext.current
    val link = ui.link ?: return
    Text(stringResource(R.string.gatedrop_link_title), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
    GcCard(color = extra.panel2, contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        GcSectionLabel(stringResource(R.string.gatedrop_link_label))
        Text(link.url, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = MonoFontFamily), color = MaterialTheme.colorScheme.onSurface)
        link.pin?.let { pin ->
            GcSectionLabel(stringResource(R.string.gatedrop_link_pin_label), modifier = Modifier.padding(top = 6.dp))
            Text(pin.toCharArray().joinToString(" "), style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 4.sp), color = MaterialTheme.colorScheme.onSurface)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GcOutlineButton(
            text = stringResource(R.string.gatedrop_copy),
            onClick = {
                ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("GateDrop", link.url))
                if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
                    android.widget.Toast.makeText(ctx, R.string.gatedrop_copied, android.widget.Toast.LENGTH_SHORT).show()
                }
            },
            modifier = Modifier.weight(1f),
        )
        GcPrimaryButton(
            text = stringResource(R.string.gatedrop_link_share),
            onClick = {
                runCatching {
                    ctx.startActivity(
                        Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link.url), null),
                    )
                }
            },
            modifier = Modifier.weight(1f),
        )
    }
    Text(stringResource(R.string.gatedrop_link_footer), style = MaterialTheme.typography.bodySmall, color = extra.muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    GcSecondaryButton(text = stringResource(R.string.gatedrop_done), onClick = onDone)
}

@Composable
private fun Failed(onDone: () -> Unit) {
    GcBanner(tone = GcTone.Error, icon = GcIcons.Alert) {
        Column {
            Text(stringResource(R.string.gatedrop_send_failed), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(stringResource(R.string.gatedrop_send_failed_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
        }
    }
    GcSecondaryButton(text = stringResource(R.string.gatedrop_done), onClick = onDone)
}
