package com.gatecontrol.android.ui.drive

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.R
import com.gatecontrol.android.drive.DNode
import com.gatecontrol.android.drive.DriveLogic
import com.gatecontrol.android.drive.work.DriveWorkLogic
import com.gatecontrol.android.drive.work.DriveWorkPhotos
import com.gatecontrol.android.drive.work.WorkSettings
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcSubpageBar
import com.gatecontrol.android.ui.theme.GateControlTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

/** "Aufräumen": the AI's suggestions among the photos already backed up, ticked from 70 %, moved together. */
@HiltViewModel
class DriveCleanupViewModel @Inject constructor(private val work: DriveWorkPhotos) : ViewModel() {

    sealed interface State {
        data class Looking(val done: Int, val total: Int) : State
        /** The AI cannot tell yet (too few examples). */
        data object Learning : State
        data class Ready(val list: List<DriveWorkLogic.Suggestion>) : State
    }

    private val _state = MutableStateFlow<State>(State.Looking(0, 0))
    val state: StateFlow<State> = _state.asStateFlow()
    private val _selected = MutableStateFlow<Set<Long>>(emptySet())
    val selected: StateFlow<Set<Long>> = _selected.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    private val _done = MutableStateFlow(false)
    /** Moved: the page closes. */
    val done: StateFlow<Boolean> = _done.asStateFlow()

    val settings: StateFlow<WorkSettings> = work.settings
    val learned: StateFlow<DriveWorkLogic.Learned> = work.learned

    init {
        viewModelScope.launch {
            if (!work.learnedNow().usable) {
                _state.value = State.Learning
                return@launch
            }
            val list = work.suggest { done, total -> _state.value = State.Looking(done, total) }
            _selected.value = list.filter { it.preselected }.map { it.node.id }.toSet()
            _state.value = State.Ready(list)
        }
    }

    fun toggle(id: Long) = _selected.update { if (id in it) it - id else it + id }

    /** "Alle" / "Keine" of a month. */
    fun allOrNone(ids: List<Long>) = _selected.update { cur -> if (ids.all { it in cur }) cur - ids.toSet() else cur + ids }

    fun clearMessage() { _message.value = null }

    fun go() {
        val list = (_state.value as? State.Ready)?.list ?: return
        if (_busy.value) return
        val chosen = list.filter { it.node.id in _selected.value }.map { it.node }
        val left = list.filter { it.node.id !in _selected.value }.map { it.node }
        viewModelScope.launch {
            _busy.value = true
            try {
                val r = work.cleanup(chosen, left)
                if (r.error != null) {
                    _message.value = r.error
                } else {
                    _message.value = if (chosen.isEmpty()) "cleanup_left" else "cleanup_done:${r.count}"
                    _done.value = true
                }
            } finally {
                _busy.value = false
            }
        }
    }
}

private fun monthName(month: String?): String? = month?.let {
    runCatching { YearMonth.parse(it).format(DateTimeFormatter.ofPattern("LLLL yyyy", Locale.getDefault())) }.getOrNull()
}

/** Drive › Foto-Sicherung › Aufräumen. */
@Composable
fun DriveCleanupScreen(onBack: () -> Unit, viewModel: DriveCleanupViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val x = GateControlTheme.extraColors
    val state by viewModel.state.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val done by viewModel.done.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val learned by viewModel.learned.collectAsStateWithLifecycle()
    val workLabel = settings.folderLabel ?: stringResource(R.string.drive_work_work_default)
    val workName = workLabel.substringAfterLast(" › ")

    LaunchedEffect(message) {
        val m = message ?: return@LaunchedEffect
        val n = m.substringAfter(':', "").toIntOrNull() ?: 0
        val text = when {
            m.startsWith("cleanup_done:") -> resources.getQuantityString(R.plurals.drive_work_cleanup_done, n, n)
            m == "cleanup_left" -> resources.getString(R.string.drive_work_cleanup_left)
            else -> workText(context, m)
        }
        Toast.makeText(context, text, Toast.LENGTH_LONG).show()
        viewModel.clearMessage()
    }
    LaunchedEffect(done) { if (done) onBack() }

    Column(Modifier.fillMaxSize()) {
        GcSubpageBar(stringResource(R.string.drive_work_cleanup_title), onBack = onBack, backDescription = stringResource(R.string.drive_back))
        when (val s = state) {
            is DriveCleanupViewModel.State.Looking -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.drive_work_cleanup_looking, s.done, s.total), color = x.muted, style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(
                    progress = { if (s.total > 0) s.done.toFloat() / s.total else 0f },
                    modifier = Modifier.fillMaxWidth(),
                    color = x.blue,
                    trackColor = x.panel2,
                )
            }
            DriveCleanupViewModel.State.Learning -> GcCard(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.drive_work_cleanup_learning, learned.progress, DriveWorkLogic.MIN_EXAMPLES), style = MaterialTheme.typography.bodyMedium)
            }
            is DriveCleanupViewModel.State.Ready -> {
                val list = s.list
                val likely = list.count { it.preselected }
                GcCard(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        if (list.isEmpty()) stringResource(R.string.drive_work_cleanup_empty) else pluralStringResource(R.plurals.drive_work_cleanup_head, likely, likely),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (list.isNotEmpty()) Text(stringResource(R.string.drive_work_cleanup_text), style = MaterialTheme.typography.bodySmall, color = x.muted)
                }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 96.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    DriveWorkLogic.byMonth(list).forEach { (month, group) ->
                        item(key = "m-$month", span = { GridItemSpan(maxLineSpan) }) {
                            val ids = group.map { it.node.id }
                            val all = ids.all { it in selected }
                            val title = monthName(month) ?: stringResource(R.string.drive_photos_undated)
                            val desc = stringResource(if (all) R.string.drive_sel_none_of else R.string.drive_sel_all_of, title)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f).padding(start = 4.dp).semantics { heading() })
                                TextButton(onClick = { viewModel.allOrNone(ids) }, modifier = Modifier.heightIn(min = 44.dp).semantics { contentDescription = desc }) {
                                    Text(stringResource(if (all) R.string.drive_sel_none else R.string.drive_sel_all), color = x.accentText, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                        items(group, key = { "p-${it.node.id}" }) { sug ->
                            SuggestionTile(sug.node, sug.percent, sug.node.id in selected) { viewModel.toggle(sug.node.id) }
                        }
                    }
                }
                val n = list.count { it.node.id in selected }
                Column(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(stringResource(R.string.drive_work_cleanup_target, workLabel + if (settings.subfolders) " › …" else ""), style = MaterialTheme.typography.bodySmall, color = x.muted)
                    Button(
                        onClick = viewModel::go,
                        enabled = !busy && list.isNotEmpty() && settings.active,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = x.blue, contentColor = Color(0xFF0D1015)),
                    ) {
                        Text(
                            if (n == 0) stringResource(R.string.drive_work_cleanup_nothing) else pluralStringResource(R.plurals.drive_work_cleanup_go, n, n, workName),
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SuggestionTile(node: DNode, percent: Int, ticked: Boolean, onToggle: () -> Unit) {
    val look = kindLook(DriveLogic.kind(node))
    val picture = rememberDrivePreview(node)
    val label = stringResource(R.string.drive_work_cleanup_tile, node.name, percent)
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(6.dp))
            .background(if (ticked) GateControlTheme.extraColors.accentBg else Color.Transparent)
            .toggleable(value = ticked, role = Role.Checkbox, onValueChange = { onToggle() })
            .semantics { contentDescription = label },
    ) {
        Box(
            Modifier.matchParentSize().padding(if (ticked) 8.dp else 0.dp).clip(RoundedCornerShape(if (ticked) 10.dp else 6.dp)).background(look.bg),
            contentAlignment = Alignment.Center,
        ) {
            if (picture != null) Image(picture, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
            else Icon(look.icon, contentDescription = null, tint = look.fg, modifier = Modifier.size(24.dp))
        }
        SelectCheck(ticked, Modifier.align(Alignment.TopStart).padding(6.dp), onImage = true)
        Text(
            stringResource(R.string.drive_work_cleanup_percent, percent),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
