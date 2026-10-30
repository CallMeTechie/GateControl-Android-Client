package com.gatecontrol.android.ui.mail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.R
import com.gatecontrol.android.drive.DNode
import com.gatecontrol.android.drive.DResult
import com.gatecontrol.android.drive.DriveApi
import com.gatecontrol.android.mail.MailLogic
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.components.GcSegmented
import com.gatecontrol.android.ui.components.GcTextField
import com.gatecontrol.android.ui.theme.GateControlTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The search of "Aus Drive" (Drive's own API, as in the Drive tab). */
@HiltViewModel
class MailDrivePickerViewModel @Inject constructor(private val drive: DriveApi) : ViewModel() {

    data class State(val query: String = "", val busy: Boolean = false, val hits: List<DNode> = emptyList(), val error: String? = null)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var job: Job? = null

    fun search(q: String) {
        _state.value = _state.value.copy(query = q)
        job?.cancel()
        if (q.trim().length < 2) {
            _state.value = _state.value.copy(hits = emptyList(), busy = false, error = null)
            return
        }
        job = viewModelScope.launch {
            delay(300)
            _state.value = _state.value.copy(busy = true)
            _state.value = when (val r = drive.search(q.trim())) {
                // files only, never a vault's (they are end-to-end encrypted)
                is DResult.Ok -> _state.value.copy(busy = false, hits = r.value.filter { !it.isDir && it.vaultId == null }.take(50), error = null)
                is DResult.Failed -> _state.value.copy(busy = false, error = r.code)
                DResult.Offline -> _state.value.copy(busy = false, error = "offline")
                DResult.NotConfigured -> _state.value.copy(busy = false, error = "not_configured")
            }
        }
    }
}

/**
 * "Anhang hinzufügen" (mockup "Schreiben: Anhang aus Drive"): "Vom Gerät" or "Aus Drive" — Drive's search, each hit
 * as a copy or a link (large files as a link first), what a link means, and "N Dateien hinzufügen".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MailAttachSheet(
    drive: Boolean,
    onDevice: () -> Unit,
    onDrive: (List<MailDrivePick>) -> Unit,
    onDismiss: () -> Unit,
    viewModel: MailDrivePickerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val picked = remember { mutableStateMapOf<Long, MailDrivePick>() }
    val extra = GateControlTheme.extraColors
    val context = mailContext()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(bottom = 20.dp)) {
            Row(Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                com.gatecontrol.android.ui.components.GcOutlineButton(stringResource(R.string.mail_attach_device), onClick = onDevice, modifier = Modifier.weight(1f), minHeight = 44.dp)
                if (drive) {
                    Box(
                        Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp)).background(extra.accentBg),
                        contentAlignment = Alignment.Center,
                    ) { Text(stringResource(R.string.mail_attach_drive), color = extra.accentText, fontWeight = FontWeight.Bold) }
                }
            }
            if (!drive) return@Column
            GcTextField(
                value = state.query,
                onValueChange = viewModel::search,
                label = stringResource(R.string.mail_attach_drive_search),
                modifier = Modifier.padding(horizontal = 16.dp),
                trailingIcon = { if (state.busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) },
            )
            if (state.hits.isNotEmpty()) {
                Text(stringResource(R.string.mail_attach_hits), style = MaterialTheme.typography.bodySmall, color = extra.muted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            }
            state.error?.let { Text(mailText(context, it), style = MaterialTheme.typography.bodySmall, color = extra.muted, modifier = Modifier.padding(16.dp)) }
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(state.hits, key = { it.id }) { n ->
                    val big = MailLogic.linkFirst(n.size)
                    val pick = picked[n.id]
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        FileBadge(n.name, n.mime ?: "application/octet-stream", size = 36)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(n.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOfNotNull(n.path?.replace("/", " › "), mailSize(n.size), stringResource(R.string.mail_attach_too_big).takeIf { big }).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = extra.muted,
                                maxLines = 2,
                            )
                        }
                        Box(Modifier.width(132.dp)) {
                            GcSegmented(
                                options = listOf("none" to "–", "copy" to stringResource(R.string.mail_attach_copy), "link" to stringResource(R.string.mail_attach_link)),
                                selected = when {
                                    pick == null -> "none"
                                    pick.asLink -> "link"
                                    else -> "copy"
                                },
                                onSelect = { v ->
                                    when (v) {
                                        "none" -> picked.remove(n.id)
                                        else -> picked[n.id] = MailDrivePick(n.id, n.name, n.size, n.mime ?: "application/octet-stream", asLink = v == "link" || (big && v != "copy"))
                                    }
                                },
                            )
                        }
                    }
                }
            }
            picked.values.filter { it.asLink }.forEach { p ->
                Column(
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.background).padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(stringResource(R.string.mail_attach_link_for, p.name), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                    Text(pluralStringResource(R.plurals.mail_attach_link_terms, MailDrivePick.LINK_DAYS, MailDrivePick.LINK_DAYS), style = MaterialTheme.typography.bodySmall, color = extra.muted)
                }
            }
            if (picked.isNotEmpty()) {
                GcPrimaryButton(
                    pluralStringResource(R.plurals.mail_attach_add_n, picked.size, picked.size),
                    onClick = { onDrive(picked.values.toList()) },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    icon = GcIcons.Plus,
                )
            }
        }
    }
}
