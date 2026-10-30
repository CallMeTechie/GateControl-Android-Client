package com.gatecontrol.android.ui.drive

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.R
import com.gatecontrol.android.drive.DNode
import com.gatecontrol.android.drive.DResult
import com.gatecontrol.android.drive.DriveApi
import com.gatecontrol.android.drive.DriveKi
import com.gatecontrol.android.drive.DriveLive
import com.gatecontrol.android.drive.DriveTransfers
import com.gatecontrol.android.drive.backup.DrivePhotoLabelsWork
import com.gatecontrol.android.ui.components.GcBanner
import com.gatecontrol.android.ui.components.GcFilterChip
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcSubpageBar
import com.gatecontrol.android.ui.components.GcTextField
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.theme.GateControlTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * "Fotos nach Inhalt finden" (Drive 1.14, D3, feature `photoSearch`): photos by the words of what is in them — found on
 * the phones that backed them up ([com.gatecontrol.android.drive.backup.DrivePhotoLabels]); the chips add the hits'
 * most frequent terms. Opening it asks for the labels still missing (in the background, under the backup's conditions).
 */
@HiltViewModel
class DrivePhotoSearchViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val api: DriveApi,
    private val transfers: DriveTransfers,
    val live: DriveLive,
) : ViewModel() {

    /** How many in all and the hits' terms (from the first page). */
    data class Meta(val total: Int?, val terms: List<String>)

    private var term = ""
    private val _meta = MutableStateFlow(Meta(null, emptyList()))
    val meta: StateFlow<Meta> = _meta.asStateFlow()

    private val pager = PhotoPager(viewModelScope) { before ->
        api.photos(before = before, q = term).also { r ->
            if (before == null && r is DResult.Ok) _meta.value = Meta(r.value.total, r.value.terms)
        }
    }
    val photos: StateFlow<PhotoPages> = pager.state

    private val _active = MutableStateFlow(false)
    /** A search is typed (else nothing shows). */
    val active: StateFlow<Boolean> = _active.asStateFlow()
    private var job: Job? = null

    init {
        DrivePhotoLabelsWork.request(context)
    }

    fun search(q: String) {
        job?.cancel()
        val t = q.trim().take(com.gatecontrol.android.drive.DriveJson.PHOTO_Q_MAX)
        if (t.length < 2) {
            term = ""
            _active.value = false
            _meta.value = Meta(null, emptyList())
            return
        }
        job = viewModelScope.launch {
            delay(300)
            term = t
            _active.value = true
            pager.reload()
        }
    }

    fun more() = pager.more()

    fun retry() = pager.retry()

    suspend fun fetch(node: DNode): DResult<File> = transfers.fetch(node)
}

@Composable
fun DrivePhotoSearchScreen(onBack: () -> Unit, viewModel: DrivePhotoSearchViewModel = hiltViewModel()) {
    val photos by viewModel.photos.collectAsStateWithLifecycle()
    val meta by viewModel.meta.collectAsStateWithLifecycle()
    val active by viewModel.active.collectAsStateWithLifecycle()
    val x = GateControlTheme.extraColors
    var q by rememberSaveable { mutableStateOf("") }
    var viewing by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(Unit) { if (q.isNotBlank()) viewModel.search(q) }
    val set: (String) -> Unit = { q = it; viewModel.search(it) }
    Column(Modifier.fillMaxSize()) {
        GcSubpageBar(stringResource(R.string.drive_ki_photo_search), onBack = onBack, backDescription = stringResource(R.string.drive_back))
        Column(Modifier.padding(horizontal = 16.dp).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            GcTextField(
                value = q,
                onValueChange = { set(it.take(com.gatecontrol.android.drive.DriveJson.PHOTO_Q_MAX)) },
                label = stringResource(R.string.drive_ki_photo_search_hint),
                placeholder = stringResource(R.string.drive_ki_photo_search_example),
            )
            val chips = DriveKi.chips(q, if (active) meta.terms else emptyList())
            if (chips.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(chips, key = { it.term.lowercase() + it.on }) { c ->
                        GcFilterChip(
                            if (c.on) c.term else "+ " + c.term,
                            selected = c.on,
                            onClick = { set(if (c.on) DriveKi.withoutTerm(q, c.term) else DriveKi.withTerm(q, c.term)) },
                        )
                    }
                }
            }
            if (active && photos.loaded) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    val n = meta.total ?: photos.items.size
                    Text(
                        pluralStringResource(R.plurals.drive_ki_photos_found, n, n),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(stringResource(R.string.drive_ki_photo_labels_device), style = MaterialTheme.typography.bodySmall, color = x.muted)
                }
            }
        }
        Box(Modifier.weight(1f).padding(top = 8.dp)) {
            if (active) {
                PhotoGrid(
                    pages = photos,
                    selected = emptySet(),
                    onOpen = { viewing = it },
                    onToggle = {},
                    onMore = viewModel::more,
                    onRetry = viewModel::retry,
                    empty = stringResource(R.string.drive_ki_photo_search_none),
                )
            }
        }
        GcBanner(tone = GcTone.Info, icon = GcIcons.Info, modifier = Modifier.navigationBarsPadding().padding(12.dp)) {
            Text(stringResource(R.string.drive_ki_photo_search_note), style = MaterialTheme.typography.bodySmall)
        }
    }
    viewing?.let { start ->
        PhotoViewer(items = photos.items, start = start, fetch = viewModel::fetch, onMore = viewModel::more, onClose = { viewing = null })
    }
}
