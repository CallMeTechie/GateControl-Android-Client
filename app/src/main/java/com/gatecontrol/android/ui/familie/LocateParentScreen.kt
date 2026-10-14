package com.gatecontrol.android.ui.familie

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.familie.FamilieDest
import com.gatecontrol.android.familie.LocateMode
import com.gatecontrol.android.familie.LocateRequest
import com.gatecontrol.android.ui.components.GcBanner
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcLabeledValue
import com.gatecontrol.android.ui.components.GcListRow
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.components.GcRowDivider
import com.gatecontrol.android.ui.components.GcSectionLabel
import com.gatecontrol.android.ui.components.GcSubpageBar
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.theme.GateControlTheme
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * Kind orten (mockup AppLocateParent) for one child: ask once, see the
 * request run (with countdown), the result on an OpenStreetMap map with
 * address, accuracy, time and battery, "In Karten öffnen", and the history.
 * With [sos] it first shows that SOS (position, "Gesehen").
 */
@Composable
fun LocateParentScreen(
    childId: Int,
    onBack: () -> Unit,
    requestId: Int? = null,
    sos: FamilieDest.Sos? = null,
    viewModel: LocateViewModel = hiltViewModel(key = "locate-$childId"),
) {
    LaunchedEffect(childId, requestId) { viewModel.start(childId, requestId) }
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val current by viewModel.current.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val address by viewModel.address.collectAsStateWithLifecycle()
    val requesting by viewModel.requesting.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val sosAcked by viewModel.sosAcked.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val extra = GateControlTheme.extraColors
    val child = profile?.member(childId)
    val name = child?.name ?: memberName(profile, childId)
    val errorText = error?.let { familieErrorText(it) }

    LaunchedEffect(errorText) {
        if (errorText != null) {
            Toast.makeText(context, errorText, Toast.LENGTH_LONG).show()
            viewModel.clearError()
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        GcSubpageBar(
            title = if (sos != null) stringResource(R.string.familie_sos_from, name) else stringResource(R.string.familie_locate_child_title, name),
            onBack = onBack,
            backDescription = stringResource(R.string.common_back),
        )
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (sos != null) SosCard(sos, name, sosAcked, onAck = { viewModel.ackSos(sos.sosId) })

            GcCard(contentPadding = PaddingValues(0.dp), verticalArrangement = Arrangement.Top) {
                GcListRow(
                    title = if (child?.age != null) pluralStringResource(R.plurals.familie_child_age, child.age, child.age, name) else name,
                    description = when (child?.locateMode) {
                        LocateMode.AUTO -> stringResource(R.string.familie_locate_mode_auto_parent)
                        LocateMode.CONFIRM -> stringResource(R.string.familie_locate_mode_confirm_parent, name)
                        LocateMode.OFF -> stringResource(R.string.familie_locate_disabled)
                        null -> null
                    },
                    leading = { FamilieAvatar(name, child?.color, size = 44.dp) },
                )
            }

            val disabled = child?.locatable == false || child?.locateMode == LocateMode.OFF
            val req = current
            when {
                disabled -> GcBanner(tone = GcTone.Neutral, icon = GcIcons.Lock) {
                    Text(stringResource(R.string.familie_locate_disabled_long, name), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                }
                req != null && req.status == LocateRequest.PENDING -> PendingCard(req, name)
                req != null && req.status == LocateRequest.ANSWERED && req.hasPosition -> ResultCard(req, name, address, requesting, onAgain = viewModel::request)
                else -> IdleCard(req, name, requesting, onRequest = viewModel::request)
            }

            GcSectionLabel(stringResource(R.string.familie_locate_history), Modifier.padding(start = 4.dp, top = 4.dp))
            val list = history
            when {
                list == null -> FamilieLoading()
                list.isEmpty() -> Text(stringResource(R.string.familie_locate_history_empty), style = MaterialTheme.typography.bodyMedium, color = extra.muted, modifier = Modifier.padding(4.dp))
                else -> GcCard(contentPadding = PaddingValues(vertical = 2.dp), verticalArrangement = Arrangement.Top) {
                    list.take(20).forEachIndexed { i, r ->
                        if (i > 0) GcRowDivider()
                        GcListRow(
                            title = locateStatusLine(r.status, r.at ?: r.createdAt),
                            description = listOfNotNull(
                                stringResource(R.string.familie_locate_by, r.requestedByName ?: memberName(profile, r.requestedBy)),
                                r.accuracy?.let { stringResource(R.string.familie_accuracy_short, it.toInt()) },
                                r.code?.let { failText(it) },
                            ).joinToString(" · "),
                        )
                    }
                }
            }
            Text(stringResource(R.string.familie_locate_retention), style = MaterialTheme.typography.bodySmall, color = extra.muted, modifier = Modifier.padding(horizontal = 4.dp))
        }
    }
}

@Composable
private fun SosCard(sos: FamilieDest.Sos, name: String, acked: Boolean, onAck: () -> Unit) {
    val context = LocalContext.current
    GcCard(borderColor = MaterialTheme.colorScheme.error) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(GcIcons.Siren, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(24.dp))
            Text(stringResource(R.string.familie_sos_needs_help, name), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
        }
        if (sos.lat != null && sos.lon != null) {
            OsmMap(
                point = LatLon(sos.lat, sos.lon),
                radiusM = sos.accuracy,
                contentDescription = stringResource(R.string.familie_map_cd, name),
                modifier = Modifier.fillMaxWidth().height(220.dp),
            )
            sos.accuracy?.let { Text(stringResource(R.string.familie_accuracy, it.toInt()), style = MaterialTheme.typography.bodySmall) }
            GcOutlineButton(text = stringResource(R.string.familie_open_in_maps), onClick = { openInMaps(context, sos.lat, sos.lon, name) }, icon = GcIcons.External)
        } else {
            Text(stringResource(R.string.familie_sos_no_position), style = MaterialTheme.typography.bodySmall)
        }
        if (acked) {
            GcBanner(tone = GcTone.Ok, icon = GcIcons.Check) {
                Text(stringResource(R.string.familie_sos_acked, name), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
            }
        } else {
            GcPrimaryButton(text = stringResource(R.string.familie_action_seen), onClick = onAck, icon = GcIcons.Check)
        }
    }
}

@Composable
private fun IdleCard(last: LocateRequest?, name: String, requesting: Boolean, onRequest: () -> Unit) {
    val extra = GateControlTheme.extraColors
    GcCard {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(GcIcons.Crosshair, contentDescription = null, tint = extra.accentText, modifier = Modifier.size(24.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.familie_locate_once_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.familie_locate_once_text, name), style = MaterialTheme.typography.bodySmall, color = extra.muted)
            }
        }
        if (last != null && last.status != LocateRequest.ANSWERED) {
            GcBanner(tone = GcTone.Warn, icon = GcIcons.Alert) {
                Text(lastOutcome(last, name), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
            }
        }
        GcPrimaryButton(text = stringResource(R.string.familie_locate_request), onClick = onRequest, loading = requesting, icon = GcIcons.Crosshair)
        if (last != null) {
            Text(
                stringResource(R.string.familie_locate_last, locateStatusLine(last.status, last.at ?: last.createdAt)),
                style = MaterialTheme.typography.bodySmall,
                color = extra.muted,
            )
        }
    }
}

@Composable
private fun PendingCard(req: LocateRequest, name: String) {
    val extra = GateControlTheme.extraColors
    val expires = FamilieFormat.parseInstant(req.expiresAt)?.toEpochMilli()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(req.id) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    GcCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.familie_locate_running), style = MaterialTheme.typography.titleMedium)
        }
        Text(stringResource(R.string.familie_locate_step_sent, relativeStamp(req.createdAt)), style = MaterialTheme.typography.bodyMedium)
        Text(
            stringResource(
                if (req.mode == LocateMode.CONFIRM) R.string.familie_locate_step_confirm else R.string.familie_locate_step_auto,
                name,
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (expires != null) {
            val left = ((expires - now) / 1000).coerceAtLeast(0)
            Text(
                stringResource(R.string.familie_locate_countdown, String.format(Locale.ROOT, "%d:%02d", left / 60, left % 60)),
                style = MaterialTheme.typography.bodySmall,
                color = extra.muted,
            )
        }
    }
}

@Composable
private fun ResultCard(req: LocateRequest, name: String, address: String?, requesting: Boolean, onAgain: () -> Unit) {
    val context = LocalContext.current
    val lat = req.lat ?: return
    val lon = req.lon ?: return
    GcCard {
        OsmMap(
            point = LatLon(lat, lon),
            radiusM = req.accuracy,
            contentDescription = stringResource(R.string.familie_map_cd, name),
            modifier = Modifier.fillMaxWidth().height(240.dp),
        )
        Text(address ?: String.format(Locale.ROOT, "%.5f, %.5f", lat, lon), style = MaterialTheme.typography.titleMedium)
        Text(relativeStamp(req.at), style = MaterialTheme.typography.bodySmall, color = GateControlTheme.extraColors.muted)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            req.accuracy?.let { GcLabeledValue(stringResource(R.string.familie_accuracy_label), "±${it.toInt()} m", Modifier.weight(1f)) }
            FamilieFormat.parseInstant(req.at)?.let { GcLabeledValue(stringResource(R.string.familie_time_label), FamilieFormat.time(it), Modifier.weight(1f)) }
            req.battery?.let { GcLabeledValue(stringResource(R.string.familie_battery_label), "$it %", Modifier.weight(1f)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GcOutlineButton(text = stringResource(R.string.familie_open_in_maps), onClick = { openInMaps(context, lat, lon, name) }, modifier = Modifier.weight(1f))
            GcPrimaryButton(text = stringResource(R.string.familie_locate_again), onClick = onAgain, loading = requesting, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun lastOutcome(r: LocateRequest, name: String): String = when (r.status) {
    LocateRequest.DECLINED -> stringResource(R.string.familie_locate_outcome_declined, name)
    LocateRequest.EXPIRED -> stringResource(R.string.familie_locate_outcome_expired, name)
    LocateRequest.FAILED -> stringResource(R.string.familie_locate_outcome_failed, name) + (r.code?.let { " (" + failText(it) + ")" } ?: "")
    else -> stringResource(R.string.familie_locate_outcome_other)
}

@Composable
private fun failText(code: String): String = when (code) {
    "no_permission" -> stringResource(R.string.familie_fail_no_permission)
    "location_off" -> stringResource(R.string.familie_fail_location_off)
    "timeout" -> stringResource(R.string.familie_fail_timeout)
    "busy" -> stringResource(R.string.familie_fail_busy)
    else -> code
}

/** geo: link — opens the map app the user chose (Google Maps, OsmAnd, …). */
fun openInMaps(context: Context, lat: Double, lon: Double, label: String) {
    val uri = String.format(Locale.ROOT, "geo:%.6f,%.6f?q=%.6f,%.6f(%s)", lat, lon, lat, lon, Uri.encode(label)).toUri()
    val ok = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    if (!ok) Toast.makeText(context, R.string.familie_no_map_app, Toast.LENGTH_LONG).show()
}
