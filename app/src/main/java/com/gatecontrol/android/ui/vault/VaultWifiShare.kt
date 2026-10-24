package com.gatecontrol.android.ui.vault

import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.gatecontrol.android.R
import com.gatecontrol.android.ui.components.GcBanner
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcListRow
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcRowDivider
import com.gatecontrol.android.ui.components.GcSectionLabel
import com.gatecontrol.android.ui.components.GcSecondaryButton
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.theme.GateControlTheme
import com.gatecontrol.android.vault.Entry
import com.gatecontrol.android.vault.EntryData
import com.gatecontrol.android.vault.VResult
import com.gatecontrol.android.vault.VWifiShares
import com.gatecontrol.android.vault.VaultWifiQr
import kotlinx.coroutines.delay
import java.time.Instant

/** Time left of a share: { R.string, args } — "noch 4:59", "noch 5 Std.", "noch 3 Tage"; null: until ended; -1: over. */
internal fun wifiShareLeft(expiresAt: String?, nowMs: Long): Pair<Int, Any?>? {
    if (expiresAt == null) return null
    val end = runCatching { Instant.parse(expiresAt).toEpochMilli() }.getOrNull() ?: return null
    val sec = (end - nowMs) / 1000
    return when {
        sec <= 0 -> -1 to null
        sec < 3600 -> R.string.vault_wifi_share_left_min to "%d:%02d".format(sec / 60, sec % 60)
        sec < 2 * 86400 -> R.string.vault_wifi_share_left_hours to ((sec + 3599) / 3600).toInt()
        else -> R.string.vault_wifi_share_left_days to ((sec + 86399) / 86400).toInt()
    }
}

/**
 * The Wi-Fi QR code of an entry, large and dark on white (also in dark mode) so phones read it; "Vollbild" shows it
 * as big as the screen allows with the display at full brightness while it is open.
 */
@Composable
fun VaultWifiQrCard(data: EntryData) {
    val text = remember(data) { VaultWifiQr.text(data) } ?: return
    val ssid = data.field("ssid")
    var full by remember { mutableStateOf(false) }
    val x = GateControlTheme.extraColors
    VaultRowsCard {
        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.vault_wifi_qr_title), style = MaterialTheme.typography.labelMedium, color = x.faint, modifier = Modifier.fillMaxWidth())
            Box(
                Modifier.widthIn(max = 280.dp).fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(16.dp)).background(Color.White)
                    .clickable(role = Role.Button, onClickLabel = stringResource(R.string.vault_wifi_qr_full)) { full = true }.padding(12.dp),
            ) {
                VaultQrImage(text, stringResource(R.string.vault_wifi_qr_label, ssid), Modifier.fillMaxSize())
            }
            Text(stringResource(R.string.vault_wifi_qr_hint), style = MaterialTheme.typography.bodySmall, color = x.muted, textAlign = TextAlign.Center)
            GcSecondaryButton(stringResource(R.string.vault_wifi_qr_full), onClick = { full = true }, icon = GcIcons.Expand, fillWidth = false)
        }
    }
    if (full) VaultQrFullscreen(text, ssid) { full = false }
}

/** The QR code as big as the screen allows, on white, the display at full brightness and kept on while shown. */
@Composable
fun VaultQrFullscreen(text: String, ssid: String, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val view = LocalView.current
        DisposableEffect(view) {
            (view.parent as? DialogWindowProvider)?.window?.let { w ->
                w.attributes = w.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL }
                w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            onDispose { }
        }
        Column(
            Modifier.fillMaxSize().background(Color.White).clickable(role = Role.Button, onClickLabel = stringResource(R.string.close)) { onClose() }.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
        ) {
            Text(ssid, style = MaterialTheme.typography.headlineMedium, color = Color(0xFF0D1015), maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            VaultQrImage(text, stringResource(R.string.vault_wifi_qr_label, ssid), Modifier.widthIn(max = 520.dp).fillMaxWidth().aspectRatio(1f))
            Text(stringResource(R.string.vault_wifi_qr_close), style = MaterialTheme.typography.bodyMedium, color = Color(0xFF5B6474))
        }
    }
}

/**
 * „Freigaben“ of a Wi-Fi entry (plugin 1.4.0): the running shares with time left and who shared them, „älterer
 * Stand“ with „Aktualisieren“ when the entry changed since, „Beenden“; „Freigabe auf Wandtablet“ (only with the
 * Familie plugin) and „Freigabe auf Portalseite“ with the duration and the warning that everyone there sees the
 * password. The copy is built from the decrypted entry in [VaultViewModel.shareWifi].
 */
@Composable
fun VaultWifiShares(entry: Entry, canWrite: Boolean, viewModel: VaultViewModel, onMessage: (String) -> Unit) {
    val x = GateControlTheme.extraColors
    var shares by remember(entry.id) { mutableStateOf<VResult<VWifiShares>?>(null) }
    var loadedAt by remember { mutableLongStateOf(0L) }
    var ask by remember { mutableStateOf<String?>(null) }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    fun reload() {
        loadedAt = System.currentTimeMillis()
        viewModel.wifiShares(entry.id) { shares = it }
    }
    LaunchedEffect(entry.id, entry.rev) { reload() }
    LaunchedEffect(entry.id) {
        while (true) {
            delay(1000)
            nowMs = System.currentTimeMillis()
            val list = (shares as? VResult.Ok)?.value?.shares.orEmpty()
            if (list.any { wifiShareLeft(it.expiresAt, nowMs)?.first == -1 } && nowMs - loadedAt > 15_000) reload()
        }
    }
    val done: (VResult<*>, Int) -> Unit = { r, ok ->
        if (r is VResult.Ok) onMessage(viewModel.text(ok)) else viewModel.shareMessage(r)?.let(onMessage)
        reload()
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        GcSectionLabel(stringResource(R.string.vault_wifi_share_title), Modifier.padding(start = 4.dp))
        when (val r = shares) {
            null -> Text(stringResource(R.string.vault_wifi_share_loading), style = MaterialTheme.typography.bodyMedium, color = x.muted, modifier = Modifier.padding(start = 4.dp))
            is VResult.Ok -> {
                val list = r.value.shares
                if (list.isEmpty()) {
                    Text(stringResource(R.string.vault_wifi_share_none), style = MaterialTheme.typography.bodyMedium, color = x.muted, modifier = Modifier.padding(start = 4.dp))
                } else {
                    VaultRowsCard {
                        list.forEachIndexed { i, s ->
                            if (i > 0) GcRowDivider()
                            val left = wifiShareLeft(s.expiresAt, nowMs)
                            val leftText = when {
                                left == null -> stringResource(R.string.vault_wifi_share_forever)
                                left.first == -1 -> stringResource(R.string.vault_wifi_share_expired)
                                else -> stringResource(left.first, left.second!!)
                            }
                            val same = VaultWifiQr.same(entry.data, s)
                            val desc = listOfNotNull(
                                leftText,
                                s.createdBy.takeIf { it.isNotBlank() }?.let { stringResource(R.string.vault_wifi_share_by, it) },
                                if (same) null else stringResource(R.string.vault_wifi_share_stale),
                            ).joinToString(" · ")
                            GcListRow(
                                title = stringResource(if (s.target == VaultWifiQr.TARGET_WALL) R.string.vault_wifi_share_on_wall else R.string.vault_wifi_share_on_portal),
                                description = desc,
                                leading = { Icon(if (s.target == VaultWifiQr.TARGET_WALL) GcIcons.Monitor else GcIcons.Globe, null, tint = x.accentText, modifier = Modifier.size(22.dp)) },
                                trailing = if (!s.canEnd) null else {
                                    {
                                        Row {
                                            if (!same && !entry.trashed) TextButton(onClick = { viewModel.shareWifi(entry.id, s.target, "keep") { done(it, R.string.vault_wifi_share_updated) } }) {
                                                Text(stringResource(R.string.vault_wifi_share_update))
                                            }
                                            TextButton(onClick = { viewModel.endWifiShare(s.id) { done(it, R.string.vault_wifi_share_ended) } }) {
                                                Text(stringResource(R.string.vault_wifi_share_end), color = MaterialTheme.colorScheme.error)
                                            }
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
                VaultWifiQr.starts(r.value, canWrite && !entry.trashed).forEach { t ->
                    GcOutlineButton(
                        stringResource(if (t == VaultWifiQr.TARGET_WALL) R.string.vault_wifi_share_to_wall else R.string.vault_wifi_share_to_portal),
                        onClick = { ask = t },
                        icon = if (t == VaultWifiQr.TARGET_WALL) GcIcons.Monitor else GcIcons.Globe,
                    )
                }
                if (canWrite && !r.value.wall && list.none { it.target == VaultWifiQr.TARGET_WALL }) {
                    Text(stringResource(R.string.vault_wifi_share_no_wall), style = MaterialTheme.typography.bodySmall, color = x.muted, modifier = Modifier.padding(start = 4.dp))
                }
            }
            else -> Text(viewModel.shareMessage(r).orEmpty(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 4.dp))
        }
    }
    ask?.let { target ->
        var choice by remember(target) { mutableStateOf("1h") }
        val labels = mapOf(
            "5m" to R.string.vault_wifi_share_d_5m, "1h" to R.string.vault_wifi_share_d_1h,
            "24h" to R.string.vault_wifi_share_d_24h, "forever" to R.string.vault_wifi_share_d_forever,
        )
        AlertDialog(
            onDismissRequest = { ask = null },
            title = { Text(stringResource(if (target == VaultWifiQr.TARGET_WALL) R.string.vault_wifi_share_to_wall else R.string.vault_wifi_share_to_portal)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.vault_wifi_share_ask, entry.data.field("ssid")))
                    Column {
                        VaultWifiQr.DURATIONS.forEach { d ->
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(choice == d, role = Role.RadioButton) { choice = d },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(choice == d, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary))
                                Text(stringResource(labels.getValue(d)))
                            }
                        }
                    }
                    GcBanner(tone = GcTone.Warn, icon = GcIcons.Alert) {
                        Text(
                            stringResource(if (target == VaultWifiQr.TARGET_WALL) R.string.vault_wifi_share_warn_wall else R.string.vault_wifi_share_warn_portal),
                            color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Text(stringResource(R.string.vault_wifi_share_copy_hint), style = MaterialTheme.typography.bodySmall, color = x.muted)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    ask = null
                    viewModel.shareWifi(entry.id, target, choice) {
                        done(it, if (target == VaultWifiQr.TARGET_WALL) R.string.vault_wifi_share_started_wall else R.string.vault_wifi_share_started_portal)
                    }
                }) { Text(stringResource(R.string.vault_wifi_share_start)) }
            },
            dismissButton = { TextButton(onClick = { ask = null }) { Text(stringResource(R.string.vault_cancel)) } },
        )
    }
}
