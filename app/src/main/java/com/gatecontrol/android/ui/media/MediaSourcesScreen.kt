package com.gatecontrol.android.ui.media

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.media.MediaLogic
import com.gatecontrol.android.media.MediaPhoneLogic
import com.gatecontrol.android.media.MediaQuality
import com.gatecontrol.android.media.MediaSource
import com.gatecontrol.android.media.MediaAwayOriginal
import com.gatecontrol.android.media.MediaPlayAhead
import com.gatecontrol.android.media.MediaSourceKind
import com.gatecontrol.android.media.MediaAddress
import com.gatecontrol.android.media.MediaRoute
import com.gatecontrol.android.media.server.MediaSourceStatus
import com.gatecontrol.android.media.server.MediaSourcesText
import com.gatecontrol.android.media.offline.MediaOfflineLogic
import com.gatecontrol.android.media.offline.OfflineQuality
import com.gatecontrol.android.ui.components.GcBanner
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcLabeledValue
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIconSquare
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcListRow
import com.gatecontrol.android.ui.components.GcRowDivider
import com.gatecontrol.android.ui.components.GcSectionLabel
import com.gatecontrol.android.ui.components.GcSubpageBar
import com.gatecontrol.android.ui.components.GcSwitchRow
import com.gatecontrol.android.ui.theme.GateControlTheme

/**
 * Medien › Quellen (mockup „Quellen (Anzeige)“): a display of the sources the admin set up in the server, with their
 * status; tapping one shows its address, its way and the error in plain words. „Auf diesem Gerät“: „Immer Original“
 * per Jellyfin server, the mobile data limit (question 28), the downloads for offline (#295), „Weiterschauen auf Start“.
 * No „+“, no „Gerät anmelden“, no signing out or removing: that is the admin's, in the server.
 */
@Composable
fun MediaSourcesScreen(
    onBack: () -> Unit,
    onOffline: () -> Unit = {},
    /** The id of a source whose dialog opens at once („Neu anmelden“ of a source set up by hand). */
    openSource: String? = null,
    viewModel: MediaSourcesViewModel = hiltViewModel(),
) {
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    val sources by viewModel.sources.collectAsStateWithLifecycle()
    val server by viewModel.server.collectAsStateWithLifecycle()
    val cap by viewModel.mobileCap.collectAsStateWithLifecycle()
    val startCard by viewModel.startCard.collectAsStateWithLifecycle()
    val x = GateControlTheme.extraColors
    var open by remember { mutableStateOf<MediaSourceStatus?>(null) }
    var asked by rememberSaveable { mutableStateOf(openSource) }
    LaunchedEffect(asked, rows) {
        val id = asked ?: return@LaunchedEffect
        rows.firstOrNull { it.source.id == id }?.let {
            open = it
            asked = null
        }
    }
    LifecycleResumeEffect(viewModel) {
        viewModel.onShown()
        onPauseOrDispose { }
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState())) {
        GcSubpageBar(
            title = stringResource(R.string.media_sources),
            onBack = onBack,
            backDescription = stringResource(R.string.common_back),
            actions = {
                if (server.refreshing) {
                    CircularProgressIndicator(Modifier.size(18.dp).padding(end = 4.dp), strokeWidth = 2.dp)
                } else {
                    GcIconButton(GcIcons.Refresh, contentDescription = stringResource(R.string.media_sources_reload), onClick = viewModel::reload, tint = MaterialTheme.colorScheme.onBackground, modifier = Modifier.testTag(RELOAD_TAG))
                }
            },
        )
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            val now = System.currentTimeMillis()
            Text(
                stringResource(R.string.media_sources_admin) + " " + stringResource(R.string.media_sources_stand, agoText(now, server.lastContact)),
                style = MaterialTheme.typography.bodySmall,
                color = x.muted,
                modifier = Modifier.padding(horizontal = 4.dp).testTag(ADMIN_HINT_TAG),
            )
            if (server.offlineHint(now)) {
                GcBanner(tone = GcTone.Warn, icon = GcIcons.Alert) {
                    Text(stringResource(R.string.media_sources_offline_hint, agoText(now, server.lastContact), server.maxOfflineDays), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (server.unavailable) {
                GcBanner(tone = GcTone.Warn, icon = GcIcons.Alert) { Text(stringResource(R.string.media_server_off), style = MaterialTheme.typography.bodySmall) }
            }
            if (rows.isEmpty()) {
                GcCard { Text(stringResource(R.string.media_sources_none), style = MaterialTheme.typography.bodyMedium, color = x.muted) }
            } else {
                GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.Top) {
                    rows.forEachIndexed { i, r ->
                        if (i > 0) GcRowDivider()
                        SourceRow(r, onClick = { open = r })
                    }
                }
            }

            // --- what concerns only this phone ---
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                GcSectionLabel(stringResource(R.string.media_this_device), modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
                GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.Top) {
                    sources.filter { it.kind == MediaSourceKind.JELLYFIN }.forEach { s ->
                        GcSwitchRow(
                            label = stringResource(R.string.media_always_original),
                            description = stringResource(R.string.media_always_original_desc, s.name),
                            checked = s.alwaysOriginal,
                            onCheckedChange = { viewModel.setAlwaysOriginal(s, it) },
                        )
                    }
                    MobileCapRow(cap, onCap = viewModel::setMobileCap)
                    // loading ahead away (part 2, stage 1): the space, and what plays when the original does not go through
                    val aheadGb by viewModel.aheadGb.collectAsStateWithLifecycle()
                    AheadRow(aheadGb, onGb = viewModel::setAheadGb)
                    if (aheadGb > 0 && sources.any { it.kind == MediaSourceKind.JELLYFIN }) {
                        val away by viewModel.awayOriginal.collectAsStateWithLifecycle()
                        AwayOriginalRow(away, onChoose = viewModel::setAwayOriginal)
                    }
                    // the downloads for offline (question 22 a: Jellyfin only), under „Auf diesem Gerät“
                    if (sources.any { it.kind == MediaSourceKind.JELLYFIN }) {
                        val quality by viewModel.offlineQuality.collectAsStateWithLifecycle()
                        val limitGb by viewModel.offlineLimitGb.collectAsStateWithLifecycle()
                        val used by viewModel.offlineUsed.collectAsStateWithLifecycle()
                        OfflineQualityRow(quality, onQuality = viewModel::setOfflineQuality)
                        OfflineLimitRow(limitGb, used, onLimit = viewModel::setOfflineLimitGb)
                        GcListRow(
                            title = stringResource(R.string.media_offline_open),
                            description = MediaOfflineLogic.usedText(used, MediaOfflineLogic.gb(limitGb)),
                            onClick = onOffline,
                            trailing = { Icon(GcIcons.ChevronRight, contentDescription = null, tint = x.faint, modifier = Modifier.size(18.dp)) },
                            modifier = Modifier.testTag(OFFLINE_SETTINGS_TAG),
                        )
                    }
                    GcSwitchRow(
                        label = stringResource(R.string.media_start_card),
                        description = stringResource(R.string.media_start_card_desc),
                        checked = startCard,
                        onCheckedChange = viewModel::setStartCard,
                    )
                }
            }
        }
    }
    open?.let { r -> SourceDetail(r, onDismiss = { open = null }) }
}

@Composable
private fun sourceName(s: MediaSource): String = MediaLogic.sourceLabel(s, stringResource(R.string.media_share))

/** „vor 3 Min.“ */
@Composable
internal fun agoText(now: Long, at: Long): String {
    val (unit, n) = MediaSourcesText.ago(now, at) ?: return stringResource(R.string.media_ago_never)
    return when (unit) {
        'n' -> stringResource(R.string.media_ago_now)
        'm' -> stringResource(R.string.media_ago_min, n.toInt())
        'h' -> stringResource(R.string.media_ago_hours, n.toInt())
        else -> stringResource(R.string.media_ago_days, n.toInt())
    }
}

@Composable
internal fun wayText(route: MediaRoute): String = stringResource(
    when (route) {
        MediaRoute.HOME -> R.string.media_way_home
        MediaRoute.VPN -> R.string.media_way_vpn
        MediaRoute.DIRECT -> R.string.media_way_direct
        MediaRoute.BLOCKED -> R.string.media_way_blocked
    },
)

/** A problem of a source (a code of the plugin or of the sign-in) in plain words. */
@Composable
internal fun sourceProblemText(code: String, source: MediaSource?): String = when (code) {
    "no_mapping" -> stringResource(R.string.media_pp_no_mapping)
    "quick_connect_off" -> stringResource(R.string.media_pp_quick_connect_off)
    "admin_user", "user_disabled" -> stringResource(R.string.media_pp_admin_user)
    "not_configured" -> stringResource(R.string.media_pp_not_configured)
    "rate_limited" -> stringResource(R.string.media_pp_rate_limited)
    "jellyfin_unreachable", "jellyfin_error", "key_invalid" -> stringResource(R.string.media_pp_unreachable)
    com.gatecontrol.android.media.server.MediaPairEnd.OFFLINE ->
        stringResource(R.string.media_pp_offline, source?.let { MediaAddress.host(it.address) }?.takeIf { MediaAddress.isPrivateIpv4(it) }?.substringBeforeLast('.')?.let { "$it.0/24" } ?: "192.168.1.0/24")
    com.gatecontrol.android.media.server.MediaPairEnd.BLOCKED -> stringResource(R.string.media_pp_vpn)
    MediaSourcesText.NO_PASSWORD -> stringResource(R.string.media_pp_no_password)
    MediaSourcesText.SIGNED_OUT -> stringResource(R.string.media_pp_signed_out)
    MediaSourcesText.UNAVAILABLE -> stringResource(R.string.media_server_off)
    else -> stringResource(R.string.media_pp_failed)
}

/** „angemeldet als mila · automatisch“, „jetzt WebDAV · unterwegs durch den Tunnel“, or what is wrong. */
@Composable
private fun statusLine(r: MediaSourceStatus): String {
    val s = r.source
    r.problem?.let { return sourceProblemText(it, s) }
    if (r.pairing) return stringResource(R.string.media_status_pairing)
    return when {
        s.kind == MediaSourceKind.JELLYFIN && s.token != null -> stringResource(R.string.media_status_signed_in_auto, s.userName ?: s.linkedUserName ?: "?") + " · " + wayText(r.route)
        s.kind == MediaSourceKind.JELLYFIN -> stringResource(R.string.media_status_jellyfin_waiting)
        r.partner != null -> {
            val now = if (r.route == MediaRoute.HOME) "SMB" else "WebDAV"
            stringResource(R.string.media_status_share, stringResource(R.string.media_status_share_now, now), stringResource(R.string.media_pair_note))
        }
        else -> stringResource(R.string.media_status_share, if (s.kind == MediaSourceKind.SMB) "SMB" else "WebDAV", wayText(r.route))
    }
}

/** A source: its name, its status, „bereit“ / „Fehler“. */
@Composable
private fun SourceRow(r: MediaSourceStatus, onClick: () -> Unit) {
    val x = GateControlTheme.extraColors
    val s = r.source
    GcListRow(
        title = sourceName(s),
        description = statusLine(r),
        onClick = onClick,
        modifier = Modifier.testTag(SOURCE_ROW_TAG),
        leading = {
            GcIconSquare(background = if (s.kind == MediaSourceKind.JELLYFIN) x.accentBg else x.panel2) {
                Icon(if (s.kind == MediaSourceKind.JELLYFIN) GcIcons.Film else GcIcons.Folder, contentDescription = null, tint = if (s.kind == MediaSourceKind.JELLYFIN) x.accentText else x.muted, modifier = Modifier.size(22.dp))
            }
        },
        trailing = {
            Text(
                stringResource(
                    when {
                        r.pairing -> R.string.media_status_pairing
                        r.ready -> R.string.media_status_ready
                        else -> R.string.media_status_problem
                    },
                ),
                style = MaterialTheme.typography.labelMedium,
                color = if (r.ready || r.pairing) x.accentText else x.warn,
            )
        },
    )
}

/** Tapping a source: its address (at home and away), its way now and what is wrong. */
@Composable
private fun SourceDetail(r: MediaSourceStatus, onDismiss: () -> Unit) {
    val s = r.source
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(sourceName(s)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val addresses = listOfNotNull(s, r.partner).joinToString("\n") { if (it.kind == MediaSourceKind.SMB) MediaLogic.shareAddress(it) else it.address }
                GcLabeledValue(stringResource(R.string.media_detail_address), addresses)
                listOfNotNull(s, r.partner).firstNotNullOfOrNull { it.awayAddress }?.let { GcLabeledValue(stringResource(R.string.media_detail_address_away), it) }
                GcLabeledValue(stringResource(R.string.media_detail_way), wayText(r.route))
                Text(statusLine(r), style = MaterialTheme.typography.bodyMedium)
                s.deviceName?.let { Text(stringResource(R.string.media_detail_device, it), style = MaterialTheme.typography.bodySmall, color = GateControlTheme.extraColors.muted) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.media_detail_close)) } },
    )
}

/** "Mobilfunk-Grenze · 3 Mbit/s ▾ · etwa 1,4 GB pro Stunde" (question 28). */
@Composable
private fun MobileCapRow(cap: Long?, onCap: (Long?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val x = GateControlTheme.extraColors
    GcListRow(
        title = stringResource(R.string.media_mobile_cap),
        description = cap?.let { stringResource(R.string.media_mobile_cap_desc, MediaPhoneLogic.gbPerHour(it)) } ?: stringResource(R.string.media_mobile_cap_none_desc),
        onClick = { open = true },
        trailing = {
            Box {
                Text(cap?.let { MediaQuality.label(it) } ?: stringResource(R.string.media_mobile_cap_none), style = MaterialTheme.typography.labelLarge, color = x.accentText)
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    MediaPhoneLogic.MOBILE_CAPS.forEach { c ->
                        DropdownMenuItem(
                            text = { Text(c?.let { MediaQuality.label(it) } ?: stringResource(R.string.media_mobile_cap_none)) },
                            onClick = {
                                open = false
                                onCap(c)
                            },
                            trailingIcon = if (c == cap) { { Icon(GcIcons.Check, contentDescription = null, modifier = Modifier.size(18.dp)) } } else null,
                        )
                    }
                }
            }
        },
    )
}

/** "Unterwegs mitladen · 4 GB ▾": the space on the phone for one playback, deleted after it; "Aus". */
@Composable
internal fun AheadRow(gb: Int, onGb: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    @Composable
    fun name(v: Int) = if (v <= 0) stringResource(R.string.media_ahead_off) else stringResource(R.string.media_offline_gb, v)
    GcListRow(
        title = stringResource(R.string.media_ahead_setting),
        description = stringResource(if (gb > 0) R.string.media_ahead_setting_desc else R.string.media_ahead_setting_off_desc),
        onClick = { open = true },
        trailing = {
            Box {
                Text(name(gb), style = MaterialTheme.typography.labelLarge, color = GateControlTheme.extraColors.accentText)
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    MediaPlayAhead.LIMITS_GB.forEach { v ->
                        DropdownMenuItem(
                            text = { Text(name(v)) },
                            onClick = {
                                open = false
                                onGb(v)
                            },
                            trailingIcon = if (v == gb) { { Icon(GcIcons.Check, contentDescription = null, modifier = Modifier.size(18.dp)) } } else null,
                        )
                    }
                }
            }
        },
        modifier = Modifier.testTag(AHEAD_SETTING_TAG),
    )
}

/** "Original passt unterwegs nicht · Fragen ▾" (decision 89 b): ask, or always the same. */
@Composable
internal fun AwayOriginalRow(choice: MediaAwayOriginal, onChoose: (MediaAwayOriginal) -> Unit) {
    var open by remember { mutableStateOf(false) }
    @Composable
    fun name(v: MediaAwayOriginal) = stringResource(
        when (v) {
            MediaAwayOriginal.ASK -> R.string.media_away_setting_ask
            MediaAwayOriginal.CONVERTED -> R.string.media_away_converted
            MediaAwayOriginal.WAIT -> R.string.media_away_wait
            MediaAwayOriginal.NOW -> R.string.media_away_now
        },
    )
    GcListRow(
        title = stringResource(R.string.media_away_setting),
        description = stringResource(R.string.media_away_setting_desc),
        onClick = { open = true },
        trailing = {
            Box {
                Text(name(choice), style = MaterialTheme.typography.labelLarge, color = GateControlTheme.extraColors.accentText)
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    MediaAwayOriginal.entries.forEach { v ->
                        DropdownMenuItem(
                            text = { Text(name(v)) },
                            onClick = {
                                open = false
                                onChoose(v)
                            },
                            trailingIcon = if (v == choice) { { Icon(GcIcons.Check, contentDescription = null, modifier = Modifier.size(18.dp)) } } else null,
                        )
                    }
                }
            }
        },
    )
}

/** "Qualität für Downloads · 1080p ▾". */
@Composable
private fun OfflineQualityRow(quality: OfflineQuality, onQuality: (OfflineQuality) -> Unit) {
    var open by remember { mutableStateOf(false) }
    GcListRow(
        title = stringResource(R.string.media_offline_setting_quality),
        description = stringResource(R.string.media_offline_setting_quality_desc),
        onClick = { open = true },
        trailing = {
            Box {
                Text(qualityName(quality), style = MaterialTheme.typography.labelLarge, color = GateControlTheme.extraColors.accentText)
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    OfflineQuality.entries.forEach { q ->
                        DropdownMenuItem(
                            text = { Text(qualityName(q)) },
                            onClick = {
                                open = false
                                onQuality(q)
                            },
                            trailingIcon = if (q == quality) { { Icon(GcIcons.Check, contentDescription = null, modifier = Modifier.size(18.dp)) } } else null,
                        )
                    }
                }
            }
        },
    )
}

/** "Speichergrenze · 20 GB ▾ · Belegt 7,4 GB". */
@Composable
private fun OfflineLimitRow(limitGb: Int, used: Long, onLimit: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    GcListRow(
        title = stringResource(R.string.media_offline_setting_limit),
        description = stringResource(R.string.media_offline_setting_limit_desc, MediaOfflineLogic.sizeText(used)),
        onClick = { open = true },
        trailing = {
            Box {
                Text(stringResource(R.string.media_offline_gb, limitGb), style = MaterialTheme.typography.labelLarge, color = GateControlTheme.extraColors.accentText)
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    MediaOfflineLogic.LIMITS_GB.forEach { gb ->
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.media_offline_gb, gb)) },
                            onClick = {
                                open = false
                                onLimit(gb)
                            },
                            trailingIcon = if (gb == limitGb) { { Icon(GcIcons.Check, contentDescription = null, modifier = Modifier.size(18.dp)) } } else null,
                        )
                    }
                }
            }
        },
    )
}

internal const val RELOAD_TAG = "media-sources-reload"
internal const val ADMIN_HINT_TAG = "media-sources-admin"
internal const val SOURCE_ROW_TAG = "media-source-row"
internal const val OFFLINE_SETTINGS_TAG = "media-offline-settings"
internal const val AHEAD_SETTING_TAG = "media-ahead-setting"
