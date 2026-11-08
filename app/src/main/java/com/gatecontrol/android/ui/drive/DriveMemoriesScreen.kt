package com.gatecontrol.android.ui.drive

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.R
import com.gatecontrol.android.drive.DMemoryCollection
import com.gatecontrol.android.drive.DMemoryList
import com.gatecontrol.android.drive.DNode
import com.gatecontrol.android.drive.DResult
import com.gatecontrol.android.drive.DriveApi
import com.gatecontrol.android.drive.DriveMemoriesLogic
import com.gatecontrol.android.drive.DriveTransfers
import com.gatecontrol.android.ui.components.GcSubpageBar
import com.gatecontrol.android.ui.theme.GateControlTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * The page „Erinnerungen“ (Drive 1.26, feature `memoryCollections`): `GET memories` — the collections of the private
 * photos by section; one opened shows its photos (`GET memories/photos`) in the photo grid, a tap the viewer.
 */
@HiltViewModel
class DriveMemoriesViewModel @Inject constructor(
    private val api: DriveApi,
    private val transfers: DriveTransfers,
) : ViewModel() {
    private val _state = MutableStateFlow<DriveLoad<DMemoryList>>(DriveLoad.Loading)
    val state: StateFlow<DriveLoad<DMemoryList>> = _state.asStateFlow()
    private val _open = MutableStateFlow<DMemoryCollection?>(null)
    /** The collection shown (null: the list). */
    val open: StateFlow<DMemoryCollection?> = _open.asStateFlow()

    private val pager = PhotoPager(viewModelScope) { before ->
        val c = _open.value ?: return@PhotoPager DResult.Failed("not_found", 404)
        api.memoryPhotos(c.id, before = before)
    }
    val photos: StateFlow<PhotoPages> = pager.state

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            val r = api.memoryCollections()
            if (r is DResult.Ok || _state.value !is DriveLoad.Ready) _state.value = r.toLoad { it }
        }
    }

    fun open(c: DMemoryCollection) {
        _open.value = c
        pager.reload()
    }

    fun close() {
        _open.value = null
    }

    fun more() = pager.more()

    fun retry() = pager.retry()

    suspend fun fetch(node: DNode): DResult<File> = transfers.fetch(node)
}

/** The page „Erinnerungen“; back closes an opened collection first. */
@Composable
fun DriveMemoriesScreen(onBack: () -> Unit, viewModel: DriveMemoriesViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val open by viewModel.open.collectAsStateWithLifecycle()
    val photos by viewModel.photos.collectAsStateWithLifecycle()
    var viewing by remember { mutableStateOf<Int?>(null) }
    BackHandler(enabled = open != null) { viewModel.close() }
    Column(Modifier.fillMaxSize()) {
        val title = open?.let { memoryTitle(it) } ?: stringResource(R.string.drive_mem_title)
        GcSubpageBar(title, onBack = { if (open != null) viewModel.close() else onBack() }, backDescription = stringResource(R.string.drive_back))
        val c = open
        if (c != null) {
            PhotoGrid(
                pages = photos,
                selected = emptySet(),
                onOpen = { viewing = it },
                onToggle = {},
                onMore = viewModel::more,
                onRetry = viewModel::retry,
                empty = stringResource(R.string.drive_mem_empty),
                header = {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(memoryLine(c), style = MaterialTheme.typography.bodySmall, color = GateControlTheme.extraColors.muted, modifier = Modifier.padding(bottom = 8.dp))
                    }
                },
            )
        } else {
            when (val s = state) {
                DriveLoad.Loading -> DriveLoading()
                is DriveLoad.Error -> DriveErrorBox(s.code, onRetry = viewModel::load)
                is DriveLoad.Ready -> MemoriesList(s.value, onOpen = viewModel::open)
            }
        }
    }
    LaunchedEffect(open?.id) { viewing = null }
    viewing?.let { start ->
        PhotoViewer(items = photos.items, start = start, fetch = viewModel::fetch, onMore = viewModel::more, onClose = { viewing = null })
    }
}

@Composable
private fun MemoriesList(list: DMemoryList, onOpen: (DMemoryCollection) -> Unit) {
    val x = GateControlTheme.extraColors
    val sections = remember(list) { DriveMemoriesLogic.sections(list.collections) }
    if (sections.isEmpty()) {
        Text(
            stringResource(R.string.drive_mem_none),
            style = MaterialTheme.typography.bodyMedium,
            color = x.muted,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
        )
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 156.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }, key = "note") {
            Text(stringResource(R.string.drive_mem_private), style = MaterialTheme.typography.bodySmall, color = x.muted)
        }
        sections.forEach { sec ->
            item(span = { GridItemSpan(maxLineSpan) }, key = "head-${sec.kind}") {
                Text(
                    sectionTitle(sec.kind),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 12.dp).semantics { heading() },
                )
            }
            items(sec.items, key = { it.id }) { c -> MemoryCard(c) { onOpen(c) } }
        }
    }
}

/** A card: the cover, the title and the line below over it (as the overview's „Erinnerungen“). */
@Composable
private fun MemoryCard(c: DMemoryCollection, onClick: () -> Unit) {
    val picture = c.cover?.let { rememberDrivePreview(it, large = true) ?: rememberDrivePreview(it) }
    Box(
        Modifier.fillMaxWidth().aspectRatio(1.2f).clip(RoundedCornerShape(16.dp)).background(GateControlTheme.extraColors.blueBg)
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        if (picture != null) Image(picture, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.28f)))
        Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(memoryTitle(c), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(memoryLine(c), style = MaterialTheme.typography.bodySmall, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun sectionTitle(kind: String): String = stringResource(
    when (kind) {
        DriveMemoriesLogic.DAY -> R.string.drive_mem_sec_day
        DriveMemoriesLogic.TRIP -> R.string.drive_mem_sec_trip
        DriveMemoriesLogic.PERSON -> R.string.drive_mem_sec_person
        DriveMemoriesLogic.MONTH -> R.string.drive_mem_sec_month
        DriveMemoriesLogic.YEAR -> R.string.drive_mem_sec_year
        else -> R.string.drive_mem_sec_place
    },
)

@Composable
private fun locale(): java.util.Locale = LocalConfiguration.current.locales[0]

@Composable
internal fun memoryTitle(c: DMemoryCollection): String {
    val t = DriveMemoriesLogic.text(c, locale())
    return when (t.title) {
        DriveMemoriesLogic.Title.YEARS_AGO -> pluralStringResource(R.plurals.drive_ov_memory_today, t.number ?: 1, t.number ?: 1)
        DriveMemoriesLogic.Title.PLACES -> t.name ?: stringResource(R.string.drive_mem_trip)
        DriveMemoriesLogic.Title.PERSON -> stringResource(R.string.drive_mem_with, t.name.orEmpty())
        DriveMemoriesLogic.Title.MONTH_YEARS_AGO -> pluralStringResource(R.plurals.drive_mem_month_years, t.number ?: 1, t.number ?: 1)
        DriveMemoriesLogic.Title.YEAR -> stringResource(R.string.drive_mem_year, (t.number ?: 0).toString())
        DriveMemoriesLogic.Title.PLACE -> t.name.orEmpty()
    }
}

/** "3 Tage · Juli 2025 · 87 Fotos", "26. Oktober 2025 · 12 Fotos", "12 Fotos". */
@Composable
internal fun memoryLine(c: DMemoryCollection): String {
    val t = DriveMemoriesLogic.text(c, locale())
    return listOfNotNull(
        t.days?.let { pluralStringResource(R.plurals.drive_mem_days, it, it) },
        t.`when`,
        pluralStringResource(R.plurals.drive_photos_count, c.count, c.count),
    ).joinToString(" · ")
}
