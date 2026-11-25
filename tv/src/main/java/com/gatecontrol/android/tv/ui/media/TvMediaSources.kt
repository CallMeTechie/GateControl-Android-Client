package com.gatecontrol.android.tv.ui.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.gatecontrol.android.media.MediaGate
import com.gatecontrol.android.media.MediaLogic
import com.gatecontrol.android.media.MediaRoute
import com.gatecontrol.android.media.MediaSource
import com.gatecontrol.android.media.MediaSourceKind
import com.gatecontrol.android.media.server.MediaMigration
import com.gatecontrol.android.media.server.MediaPairEnd
import com.gatecontrol.android.media.server.MediaServerState
import com.gatecontrol.android.media.server.MediaSourceStatus
import com.gatecontrol.android.media.server.MediaSourcesText
import com.gatecontrol.android.tv.R
import com.gatecontrol.android.tv.ui.components.TvButton
import com.gatecontrol.android.tv.ui.components.TvButtonStyle
import com.gatecontrol.android.tv.ui.components.TvOptionRow
import com.gatecontrol.android.tv.ui.components.TvPill
import com.gatecontrol.android.tv.ui.components.TvPillTone
import com.gatecontrol.android.tv.ui.components.TvRowEnd
import com.gatecontrol.android.tv.ui.drive.requestSoon
import com.gatecontrol.android.tv.ui.theme.GateControlTvTheme

/**
 * Medien › Quellen on the TV (mockup „TV · Medien › Quellen“): a display of the sources the admin set up in the
 * server, with their status (signed in as …, the way, the error in plain words); which Jellyfin server the page shows,
 * „Immer Original“, „Neu laden“. Nothing to set up at the TV: no code on the screen, no adding, no removing.
 */
@Composable
fun TvSourcesPage(rows: List<MediaSourceStatus>, server: MediaServerState, current: MediaSource?, actions: TvMediaActions, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val colors = GateControlTvTheme.colors
    val first = remember { FocusRequester() }
    Column(modifier.fillMaxSize().padding(end = 30.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.tv_media_sources_title), style = MaterialTheme.typography.titleMedium, color = colors.text)
        Text(stringResource(R.string.tv_media_sources_admin), style = MaterialTheme.typography.bodyMedium, color = colors.text)
        Text(
            stringResource(R.string.tv_media_sources_nothing_here) + " " + stringResource(R.string.tv_media_sources_stand, tvAgoText(System.currentTimeMillis(), server.lastContact)),
            style = MaterialTheme.typography.bodySmall,
            color = colors.muted,
        )
        if (server.offlineHint(System.currentTimeMillis())) {
            Text(stringResource(R.string.tv_media_offline_hint, server.maxOfflineDays), style = MaterialTheme.typography.bodySmall, color = colors.warn)
        }
        if (server.unavailable) Text(stringResource(R.string.tv_media_server_off), style = MaterialTheme.typography.bodySmall, color = colors.warn)
        val shareWord = stringResource(R.string.tv_media_share)
        rows.forEachIndexed { i, r ->
            val s = r.source
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TvOptionRow(
                    title = MediaLogic.sourceLabel(s, shareWord),
                    description = tvStatusLine(r),
                    end = TvRowEnd.None,
                    onClick = {
                        if (s.kind == MediaSourceKind.JELLYFIN) actions.onSelect(s)
                        onClose()
                    },
                    badge = {
                        when {
                            r.pairing -> TvPill(stringResource(R.string.tv_media_status_pairing), TvPillTone.Info, dot = false)
                            r.ready -> TvPill(stringResource(R.string.tv_media_status_ready), TvPillTone.Accent, dot = false)
                            else -> TvPill(stringResource(R.string.tv_media_status_problem), TvPillTone.Warn, dot = false)
                        }
                    },
                    modifier = Modifier.weight(1f).then(if (i == 0) Modifier.focusRequester(first) else Modifier),
                )
                // a share plays the original anyway
                if (!s.kind.share) {
                    TvButton(
                        stringResource(if (s.alwaysOriginal) R.string.tv_media_always_original_on else R.string.tv_media_always_original_off),
                        onClick = { actions.onAlwaysOriginal(s, !s.alwaysOriginal) },
                    )
                }
                if (s.kind == MediaSourceKind.JELLYFIN && s.id == current?.id && rows.count { it.source.kind == MediaSourceKind.JELLYFIN } > 1) {
                    TvPill(stringResource(R.string.tv_media_shown), TvPillTone.Accent, dot = false)
                }
            }
        }
        if (rows.isEmpty()) Text(stringResource(R.string.tv_media_sources_none), style = MaterialTheme.typography.bodyLarge, color = colors.muted)
        TvButton(
            stringResource(R.string.tv_media_reload),
            onClick = actions.onReload,
            style = TvButtonStyle.Primary,
            modifier = if (rows.isEmpty()) Modifier.focusRequester(first) else Modifier,
        )
        Text(stringResource(R.string.tv_media_always_original_hint), style = MaterialTheme.typography.bodySmall, color = colors.muted)
        rows.firstNotNullOfOrNull { it.source.deviceName }?.let {
            Text(stringResource(R.string.tv_media_device_name, it), style = MaterialTheme.typography.bodySmall, color = colors.muted)
        }
    }
    LaunchedEffect(Unit) { first.requestSoon() }
}

/** „vor 3 Min.“ */
@Composable
internal fun tvAgoText(now: Long, at: Long): String {
    val (unit, n) = MediaSourcesText.ago(now, at) ?: return stringResource(R.string.tv_media_ago_never)
    return when (unit) {
        'n' -> stringResource(R.string.tv_media_ago_now)
        'm' -> stringResource(R.string.tv_media_ago_min, n.toInt())
        'h' -> stringResource(R.string.tv_media_ago_hours, n.toInt())
        else -> stringResource(R.string.tv_media_ago_days, n.toInt())
    }
}

@Composable
private fun tvWayText(route: MediaRoute): String = stringResource(
    when (route) {
        MediaRoute.HOME -> R.string.tv_media_way_home
        MediaRoute.VPN -> R.string.tv_media_way_vpn
        MediaRoute.DIRECT -> R.string.tv_media_way_direct
        MediaRoute.BLOCKED -> R.string.tv_media_way_blocked
    },
)

@Composable
private fun tvProblemText(code: String): String = stringResource(
    when (code) {
        "no_mapping" -> R.string.tv_media_pp_no_mapping
        "quick_connect_off" -> R.string.tv_media_pp_quick_connect_off
        "admin_user", "user_disabled" -> R.string.tv_media_pp_admin_user
        "not_configured" -> R.string.tv_media_pp_not_configured
        "rate_limited" -> R.string.tv_media_pp_rate_limited
        "jellyfin_unreachable", "jellyfin_error", "key_invalid" -> R.string.tv_media_pp_unreachable
        MediaPairEnd.OFFLINE -> R.string.tv_media_pp_offline
        MediaPairEnd.BLOCKED -> R.string.tv_media_pp_vpn
        MediaSourcesText.NO_PASSWORD -> R.string.tv_media_pp_no_password
        MediaSourcesText.SIGNED_OUT -> R.string.tv_media_signed_out_short
        else -> R.string.tv_media_pp_failed
    },
)

/** „angemeldet als mila · unterwegs durch den Tunnel“, „SMB zu Hause · WebDAV unterwegs“, or what is wrong. */
@Composable
private fun tvStatusLine(r: MediaSourceStatus): String {
    val s = r.source
    r.problem?.let { return tvProblemText(it) }
    if (r.pairing) return stringResource(R.string.tv_media_status_pairing)
    return when {
        s.kind == MediaSourceKind.JELLYFIN && s.token != null -> stringResource(R.string.tv_media_signed_in_auto, s.userName ?: s.linkedUserName ?: "?") + " · " + tvWayText(r.route)
        s.kind == MediaSourceKind.JELLYFIN -> stringResource(R.string.tv_media_not_signed_in)
        r.partner != null -> stringResource(R.string.tv_media_pair_note) + " · " + tvWayText(r.route)
        else -> (if (s.kind == MediaSourceKind.SMB) "SMB" else "WebDAV") + " · " + tvWayText(r.route)
    }
}

/** Away the VPN for media: „VPN wird verbunden …“, or „Unterwegs nur über VPN“ with „VPN einschalten“. */
@Composable
internal fun TvVpnGate(gate: MediaGate, onVpn: () -> Unit, focus: FocusRequester? = null) {
    val colors = GateControlTvTheme.colors
    when (gate) {
        MediaGate.OPEN -> Unit
        MediaGate.CONNECTING, MediaGate.CONSENT -> TvPill(stringResource(R.string.tv_media_vpn_connecting), TvPillTone.Info)
        MediaGate.BLOCKED -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.tv_media_vpn_blocked), style = MaterialTheme.typography.bodyMedium, color = colors.warn)
            TvButton(stringResource(R.string.tv_media_vpn_on), onClick = onVpn, style = TvButtonStyle.Primary, modifier = focus?.let { Modifier.focusRequester(it) } ?: Modifier)
        }
    }
}

/** „Medien kommen jetzt vom Server“, once after the update (question 33 a): taken over or removed; „Verstanden“. */
@Composable
fun TvMigrationPage(items: List<MediaMigration>, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val colors = GateControlTvTheme.colors
    val first = remember { FocusRequester() }
    Column(modifier.fillMaxSize().padding(end = 30.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.tv_media_migr_title), style = MaterialTheme.typography.titleMedium, color = colors.text)
        Text(stringResource(R.string.tv_media_migr_text), style = MaterialTheme.typography.bodyMedium, color = colors.muted)
        items.forEach { m ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(m.label, style = MaterialTheme.typography.bodyLarge, color = colors.text)
                Text(
                    when {
                        m.takenAs == null -> stringResource(R.string.tv_media_migr_removed)
                        m.kind == MediaSourceKind.JELLYFIN -> stringResource(R.string.tv_media_migr_taken_jf)
                        else -> stringResource(R.string.tv_media_migr_taken_share, "${m.takenAs} (NAS)")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (m.takenAs != null) colors.accentText else colors.warn,
                )
            }
        }
        TvButton(stringResource(R.string.tv_media_migr_ok), onClick = onDone, style = TvButtonStyle.Primary, modifier = Modifier.focusRequester(first))
    }
    LaunchedEffect(Unit) { first.requestSoon() }
}
