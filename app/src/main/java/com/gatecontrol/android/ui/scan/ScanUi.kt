package com.gatecontrol.android.ui.scan

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.R
import com.gatecontrol.android.scan.MediaScan
import com.gatecontrol.android.scan.MediaScanJson
import com.gatecontrol.android.scan.ScanBadge
import com.gatecontrol.android.ui.components.GcChip
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.theme.GateControlTheme

// ─── the virus scan of plugin files: marks, texts, asking before a marked file opens ──

/** The short mark of a list ("Wird geprüft …", "Gesperrt – Schadsoftware", …). */
fun scanBadgeText(context: Context, badge: ScanBadge): String = context.getString(
    when (badge) {
        ScanBadge.CHECKING -> R.string.scan_badge_checking
        ScanBadge.BLOCKED_MALWARE -> R.string.scan_badge_blocked_malware
        ScanBadge.BLOCKED_UNCHECKABLE -> R.string.scan_badge_blocked_unscannable
        ScanBadge.WARN_MALWARE -> R.string.scan_badge_warn_malware
        ScanBadge.NOT_CHECKABLE -> R.string.scan_badge_not_checkable
        ScanBadge.REMOVED -> R.string.scan_badge_removed
    },
)

/** The full sentence of a file's state, with the finding ("Gesperrt – Schadsoftware (Eicar-Test-Signature)"). */
fun scanDetailText(context: Context, scan: MediaScan): String? {
    val badge = scan.badge ?: return null
    val finding = scan.finding?.takeIf { scan.state == MediaScan.INFECTED }
    return when (badge) {
        ScanBadge.CHECKING -> context.getString(R.string.scan_detail_checking)
        ScanBadge.BLOCKED_MALWARE -> finding?.let { context.getString(R.string.scan_detail_blocked_malware, it) }
            ?: context.getString(R.string.scan_detail_blocked_malware_plain)
        ScanBadge.BLOCKED_UNCHECKABLE -> context.getString(R.string.scan_detail_blocked_unscannable)
        ScanBadge.WARN_MALWARE -> finding?.let { context.getString(R.string.scan_detail_warn_malware, it) }
            ?: context.getString(R.string.scan_detail_warn_malware_plain)
        ScanBadge.NOT_CHECKABLE -> context.getString(R.string.scan_detail_not_checkable)
        ScanBadge.REMOVED -> context.getString(R.string.scan_badge_removed)
    }
}

/**
 * A refusal of the scan as a sentence ([code] one of [MediaScanJson.CODES]); null for any other code, so the caller
 * keeps its own text. [name]: the file it is about (upload, GateDrop, Mail's blocked attachment).
 */
fun scanCodeText(context: Context, code: String?, finding: String? = null, name: String? = null): String? = when (code) {
    MediaScanJson.SCAN_PENDING -> context.getString(R.string.scan_err_pending)
    MediaScanJson.QUARANTINED -> finding?.let { context.getString(R.string.scan_err_quarantined, it) } ?: context.getString(R.string.scan_err_quarantined_plain)
    MediaScanJson.SCAN_BLOCKED -> name?.let { context.getString(R.string.scan_err_not_checkable, it) } ?: context.getString(R.string.scan_err_blocked)
    MediaScanJson.INFECTED -> name?.let { context.getString(R.string.scan_err_infected, it) } ?: context.getString(R.string.scan_err_infected_plain)
    MediaScanJson.SCAN_UNAVAILABLE -> context.getString(R.string.scan_err_unavailable)
    MediaScanJson.ATTACHMENT_REMOVED -> context.getString(R.string.scan_err_attachment_removed)
    MediaScanJson.ATTACHMENT_BLOCKED -> name?.let { context.getString(R.string.scan_err_attachment_blocked, it) } ?: context.getString(R.string.scan_err_attachment_blocked_plain)
    else -> null
}

/** Why a blocked file does not open (from its own scan, without asking the server). */
fun scanClosedText(context: Context, scan: MediaScan): String? =
    scan.refusalCode?.let { scanCodeText(context, it, scan.finding?.takeIf { scan.state == MediaScan.INFECTED }) }

internal fun scanTone(badge: ScanBadge): GcTone = when (badge) {
    ScanBadge.CHECKING -> GcTone.Info
    ScanBadge.BLOCKED_MALWARE, ScanBadge.BLOCKED_UNCHECKABLE, ScanBadge.REMOVED -> GcTone.Error
    ScanBadge.WARN_MALWARE -> GcTone.Warn
    ScanBadge.NOT_CHECKABLE -> GcTone.Neutral
}

/** The mark of a file as a small chip; nothing when the scan has nothing to say (or there is no scan). */
@Composable
fun ScanChip(scan: MediaScan?, modifier: Modifier = Modifier) {
    val badge = scan?.badge ?: return
    GcChip(scanBadgeText(LocalContext.current, badge), modifier = modifier, tone = scanTone(badge), small = true)
}

/** The state of a file as a line with an icon (details, a mail's attachment, a message); nothing without a mark. */
@Composable
fun ScanLine(scan: MediaScan?, modifier: Modifier = Modifier) {
    val badge = scan?.badge ?: return
    val text = scanDetailText(LocalContext.current, scan) ?: return
    val x = GateControlTheme.extraColors
    val color = when (scanTone(badge)) {
        GcTone.Error -> MaterialTheme.colorScheme.error
        GcTone.Warn -> x.warn
        GcTone.Info -> x.blue
        else -> x.muted
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(if (badge == ScanBadge.CHECKING) GcIcons.Clock else GcIcons.Alert, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

/** "Schadsoftware gefunden (…) – nur öffnen, wenn du vertraust": [onOpen] only after "Trotzdem öffnen". */
@Composable
fun ScanWarnDialog(scan: MediaScan, onOpen: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(GcIcons.Alert, contentDescription = null, tint = GateControlTheme.extraColors.warn) },
        title = { Text(stringResource(R.string.scan_warn_title)) },
        text = { Text(scanDetailText(context, scan).orEmpty()) },
        confirmButton = {
            TextButton(onClick = onOpen) { Text(stringResource(R.string.scan_warn_open), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.scan_warn_cancel), color = GateControlTheme.extraColors.muted) }
        },
    )
}

/**
 * Opening a file with a scan: a blocked (or removed) one says why in a toast, a marked one asks first, any other
 * runs its action at once.
 */
@Composable
fun rememberScanGate(): (MediaScan?, () -> Unit) -> Unit {
    val context = LocalContext.current
    val ask = rememberScanAsk()
    return remember(context, ask) {
        { scan, go ->
            if (scan?.closed == true) {
                scanClosedText(context, scan)?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
            } else {
                ask(scan, go)
            }
        }
    }
}

/**
 * Asking before a file marked as malware (not blocked) opens: the returned function runs its action at once for any
 * other file, for a marked one only after "Trotzdem öffnen".
 */
@Composable
fun rememberScanAsk(): (MediaScan?, () -> Unit) -> Unit {
    var asking by remember { mutableStateOf<Pair<MediaScan, () -> Unit>?>(null) }
    asking?.let { (scan, go) ->
        ScanWarnDialog(scan, onOpen = { asking = null; go() }, onDismiss = { asking = null })
    }
    return remember { { scan, go -> if (scan?.warn == true) asking = scan to go else go() } }
}

/** The mark of a file as a small round icon (photo tiles): a clock while it is checked, else the warning sign. */
@Composable
fun ScanMark(badge: ScanBadge, modifier: Modifier = Modifier) {
    val x = GateControlTheme.extraColors
    val (bg, fg) = when (scanTone(badge)) {
        GcTone.Error -> x.errorBg to MaterialTheme.colorScheme.error
        GcTone.Warn -> x.warnBg to x.warn
        GcTone.Info -> x.blueBg to x.blue
        else -> x.panel2 to x.muted
    }
    Box(modifier.size(22.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        Icon(if (badge == ScanBadge.CHECKING) GcIcons.Clock else GcIcons.Alert, contentDescription = null, tint = fg, modifier = Modifier.size(14.dp))
    }
}
