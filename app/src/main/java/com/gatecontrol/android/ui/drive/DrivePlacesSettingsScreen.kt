package com.gatecontrol.android.ui.drive

import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.drive.DMe
import com.gatecontrol.android.drive.faces.FaceModelState
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcListRow
import com.gatecontrol.android.ui.components.GcRowDivider
import com.gatecontrol.android.ui.components.GcSubpageBar
import com.gatecontrol.android.ui.components.GcSwitchRow
import com.gatecontrol.android.ui.settings.SettingsGroup
import com.gatecontrol.android.ui.theme.GateControlTheme

/**
 * Drive › Einstellungen › "Orte und Personen" (Drive 1.16): whether places go with the photos, "Orte nachtragen",
 * deleting the places; "Personen erkennen" (off until switched on, switching off deletes every face datum), "Nur beim
 * Laden" of this phone, the children's folders. Only what the server offers shows.
 */
@Composable
fun DrivePlacesSettingsScreen(onBack: () -> Unit, viewModel: DrivePlacesSettingsViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val x = GateControlTheme.extraColors
    val me by viewModel.live.me.collectAsStateWithLifecycle()
    val s by viewModel.settings.collectAsStateWithLifecycle()
    val geo by viewModel.geo.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val model by viewModel.model.collectAsStateWithLifecycle(FaceModelState(FaceModelState.Phase.READY))
    val message by viewModel.message.collectAsStateWithLifecycle()
    var askPeopleOff by remember { mutableStateOf(false) }
    var askPlacesOff by remember { mutableStateOf(false) }
    var canLook by remember { mutableStateOf(viewModel.canLook()) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        canLook = viewModel.canLook()
        if (canLook) viewModel.fillPlaces()
    }
    LifecycleResumeEffect(viewModel) {
        canLook = viewModel.canLook()
        viewModel.load()
        onPauseOrDispose { }
    }
    LaunchedEffect(message) {
        message?.let {
            Toast.makeText(context, peopleText(context, it), Toast.LENGTH_SHORT).show()
            viewModel.clearMessage()
        }
    }
    val places = me?.has(DMe.FEATURE_PHOTO_PLACES) == true
    val people = me?.has(DMe.FEATURE_PHOTO_PEOPLE) == true

    Column(Modifier.fillMaxSize()) {
        GcSubpageBar(stringResource(R.string.drive_pp_settings_title), onBack = onBack, backDescription = stringResource(R.string.drive_back))
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (places) {
                SettingsGroup(stringResource(R.string.drive_pp_places)) {
                    GcSwitchRow(
                        label = stringResource(R.string.drive_pp_keep_geo),
                        description = stringResource(R.string.drive_pp_keep_geo_desc),
                        checked = s.keepGeo,
                        onCheckedChange = viewModel::setKeepGeo,
                        enabled = !busy,
                    )
                    // only when the server can give photos without GPS data (else it does not report the switch)
                    s.stripGeoOnShare?.let { strip ->
                        GcRowDivider()
                        GcSwitchRow(
                            label = stringResource(R.string.drive_pp_strip_geo),
                            description = stringResource(R.string.drive_pp_strip_geo_desc),
                            checked = strip,
                            onCheckedChange = viewModel::setStripGeo,
                            enabled = !busy,
                        )
                    }
                    if (s.keepGeo) {
                        GcRowDivider()
                        GcListRow(
                            title = stringResource(R.string.drive_pp_fill),
                            description = when {
                                !canLook -> stringResource(R.string.drive_pp_fill_permission)
                                geo.running -> stringResource(R.string.drive_pp_fill_running, geo.looked, geo.located)
                                geo.lastRunAt > 0 -> stringResource(R.string.drive_pp_fill_last, geo.looked, geo.located)
                                else -> stringResource(R.string.drive_pp_fill_desc)
                            },
                            onClick = {
                                if (canLook) {
                                    viewModel.fillPlaces()
                                } else {
                                    val read = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
                                    permissions.launch(arrayOf(read, Manifest.permission.ACCESS_MEDIA_LOCATION))
                                }
                            },
                            leading = { Icon(GcIcons.Pin, contentDescription = null, tint = x.accentText, modifier = Modifier.size(22.dp)) },
                        )
                    }
                    GcRowDivider()
                    GcListRow(
                        title = stringResource(R.string.drive_pp_delete_places),
                        description = stringResource(R.string.drive_pp_delete_places_desc),
                        titleColor = MaterialTheme.colorScheme.error,
                        onClick = { askPlacesOff = true },
                        leading = { Icon(GcIcons.Trash, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(22.dp)) },
                    )
                }
            }
            if (people) {
                SettingsGroup(stringResource(R.string.drive_pp_people)) {
                    GcSwitchRow(
                        label = stringResource(R.string.drive_pp_people_switch),
                        description = stringResource(R.string.drive_pp_people_switch_desc),
                        checked = s.people,
                        onCheckedChange = { on -> if (on) viewModel.setPeople(true) else askPeopleOff = true },
                        enabled = !busy,
                    )
                    if (s.people) {
                        // the feature model is loaded once after switching on
                        if (model.phase != FaceModelState.Phase.READY) {
                            FaceModelCard(
                                model,
                                onMobile = { viewModel.loadModel(true) },
                                onRetry = { viewModel.loadModel(false) },
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                        }
                        GcRowDivider()
                        GcSwitchRow(
                            label = stringResource(R.string.drive_pp_charging),
                            description = stringResource(R.string.drive_pp_charging_desc),
                            checked = s.chargingOnly,
                            onCheckedChange = viewModel::setChargingOnly,
                        )
                        GcRowDivider()
                        GcSwitchRow(
                            label = stringResource(R.string.drive_pp_kids),
                            description = stringResource(R.string.drive_pp_kids_desc),
                            checked = s.peopleKids,
                            onCheckedChange = viewModel::setKids,
                            enabled = !busy,
                        )
                        GcRowDivider()
                        GcListRow(
                            title = stringResource(R.string.drive_pp_delete_faces),
                            description = stringResource(R.string.drive_pp_delete_faces_desc),
                            titleColor = MaterialTheme.colorScheme.error,
                            onClick = { askPeopleOff = true },
                            leading = { Icon(GcIcons.Trash, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(22.dp)) },
                        )
                    }
                }
                if (!s.people) {
                    GcCard(color = x.panel2, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.drive_pp_how), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        listOf(R.string.drive_pp_how_1, R.string.drive_pp_how_5, R.string.drive_pp_how_2, R.string.drive_pp_how_3, R.string.drive_pp_how_4).forEach {
                            Text("• " + stringResource(it), style = MaterialTheme.typography.bodySmall, color = x.muted)
                        }
                    }
                }
                Text(stringResource(R.string.drive_pp_licenses), style = MaterialTheme.typography.bodySmall, color = x.faint, modifier = Modifier.padding(horizontal = 4.dp))
            }
        }
    }
    if (askPeopleOff) {
        AlertDialog(
            onDismissRequest = { askPeopleOff = false },
            title = { Text(stringResource(R.string.drive_pp_off_title)) },
            text = { Text(stringResource(R.string.drive_pp_off_text)) },
            confirmButton = { TextButton(onClick = { askPeopleOff = false; viewModel.setPeople(false) }) { Text(stringResource(R.string.drive_pp_off_go), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { askPeopleOff = false }) { Text(stringResource(R.string.drive_cancel)) } },
        )
    }
    if (askPlacesOff) {
        AlertDialog(
            onDismissRequest = { askPlacesOff = false },
            title = { Text(stringResource(R.string.drive_pp_delete_places_title)) },
            text = { Text(stringResource(R.string.drive_pp_delete_places_text)) },
            confirmButton = { TextButton(onClick = { askPlacesOff = false; viewModel.deletePlaces() }) { Text(stringResource(R.string.drive_pp_delete_places_go), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { askPlacesOff = false }) { Text(stringResource(R.string.drive_cancel)) } },
        )
    }
}
