package com.gatecontrol.android.ui.drive

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import com.gatecontrol.android.drive.DriveBulk
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.drive.DAlbum
import com.gatecontrol.android.drive.DArea
import com.gatecontrol.android.drive.DMe
import com.gatecontrol.android.drive.DMemories
import com.gatecontrol.android.drive.DNode
import com.gatecontrol.android.drive.DResult
import com.gatecontrol.android.drive.DriveLogic
import com.gatecontrol.android.drive.DrivePlacesLogic
import com.gatecontrol.android.drive.work.DriveWorkLogic
import com.gatecontrol.android.drive.work.DriveWorkPhotos
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcFilterChip
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcListRow
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.components.GcSectionLabel
import com.gatecontrol.android.ui.components.GcSegmented
import com.gatecontrol.android.ui.components.GcTextField
import com.gatecontrol.android.ui.theme.GateControlTheme
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** A message of the photo pages as a sentence (outside of composition): albums, else a Drive code. */
internal fun photosText(context: Context, code: String): String {
    val n = code.substringAfter(':', "").toIntOrNull() ?: 0
    return when {
        code.startsWith("photos_added:") -> context.resources.getQuantityString(R.plurals.drive_photos_added, n, n)
        code.startsWith("photos_removed:") -> context.resources.getQuantityString(R.plurals.drive_photos_removed, n, n)
        code == "album_created" -> context.getString(R.string.drive_album_created)
        code == "album_renamed" -> context.getString(R.string.drive_album_renamed)
        code == "album_deleted" -> context.getString(R.string.drive_album_deleted)
        code == "album_cover" -> context.getString(R.string.drive_album_cover_done)
        code == "album_frame_on" -> context.getString(R.string.drive_album_frame_on)
        code == "album_frame_off" -> context.getString(R.string.drive_album_frame_off)
        code == "work_undone" -> context.getString(R.string.drive_work_snack_undone)
        else -> workText(context, code)
    }
}

/** "Oktober 2026" of "2026-10". */
private fun monthTitle(month: String?): String? = month?.let {
    runCatching { YearMonth.parse(it).format(DateTimeFormatter.ofPattern("LLLL yyyy", Locale.getDefault())) }.getOrNull()
}

/** When a photo was taken, as the phone writes a date. */
internal fun photoDate(node: DNode): String? = (node.takenAt ?: node.mtime)?.let { iso ->
    runCatching {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withZone(ZoneId.systemDefault()).format(Instant.parse(iso))
    }.getOrNull()
}

/** "Dateien" | "Fotos" on top of the Drive tab (only when Drive has photos). */
@Composable
internal fun DriveModeSwitch(photos: Boolean, onPhotos: (Boolean) -> Unit) {
    GcSegmented(
        options = listOf(false to stringResource(R.string.drive_mode_files), true to stringResource(R.string.drive_mode_photos)),
        selected = photos,
        onSelect = onPhotos,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/** "N ausgewählt" of the photos, with the hint of what can be done (work photos apart: "Arbeit" / "Privat"). */
@Composable
internal fun PhotosSelectionHead(viewModel: DrivePhotosViewModel) {
    val me by viewModel.live.me.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val timeline by viewModel.timeline.collectAsStateWithLifecycle()
    val workSettings by viewModel.workSettings.collectAsStateWithLifecycle()
    val localReview by viewModel.localReview.collectAsStateWithLifecycle()
    val chosen = remember(selected, timeline.items) { timeline.items.filter { it.id in selected } }
    val line = when {
        DriveWorkLogic.reviewBar(chosen, localReview) -> stringResource(R.string.drive_work_sel_review)
        workSettings.active || viewModel.work.serverClasses(me) -> stringResource(R.string.drive_work_sel_normal)
        else -> stringResource(R.string.drive_photos_sel_hint)
    }
    SelectionHead(count = selected.size, line = line, allSelected = false, onClose = viewModel::clearSelection, onAll = null)
}

/**
 * The photos of Drive (Drive 1.7): the timeline by month with "Vor einem Jahr" on top, loading on while scrolling, and
 * the albums. Long press ticks photos for an album; a tap shows them full screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DrivePhotosContent(
    viewModel: DrivePhotosViewModel,
    topBar: @Composable () -> Unit,
    onAlbum: (String) -> Unit,
    onCleanup: () -> Unit = {},
    onFile: ((Long) -> Unit)? = null,
    /** Drive 1.16: where the search, the map, a place and the people lead (null: not shown). */
    places: PhotosPlacesNav? = null,
    /** The page shows its own selection head in place of [topBar] (false: the Drive tab shows it, [PhotosSelectionHead]). */
    ownHead: Boolean = true,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val me by viewModel.live.me.collectAsStateWithLifecycle()
    val timeline by viewModel.timeline.collectAsStateWithLifecycle()
    val months by viewModel.months.collectAsStateWithLifecycle()
    val memories by viewModel.memories.collectAsStateWithLifecycle()
    val albums by viewModel.albums.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val hasAlbums = me?.has(DMe.FEATURE_ALBUMS) == true
    // Drive 1.10 (`photosFolder`): the timeline of the camera uploads only
    val hasCamera = me?.has(DMe.FEATURE_PHOTOS_FOLDER) == true
    val cameraOnly by viewModel.cameraOnly.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf("timeline") }
    /** Full screen: the timeline (null) or the memories of so many years ago, from that photo on. */
    var viewing by remember { mutableStateOf<Pair<Int?, Int>?>(null) }
    var choosing by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf<Boolean?>(null) }

    // Drive 1.13: work photos apart (Alle | Privat | Arbeit | Prüfen, the review mark, Arbeit / Privat in front)
    val cls by viewModel.cls.collectAsStateWithLifecycle()
    val classes by viewModel.classes.collectAsStateWithLifecycle()
    val workSettings by viewModel.workSettings.collectAsStateWithLifecycle()
    val localReview by viewModel.localReview.collectAsStateWithLifecycle()
    val learned by viewModel.learned.collectAsStateWithLifecycle()
    val suggested by viewModel.work.suggested.collectAsStateWithLifecycle()
    val workSnack by viewModel.workSnack.collectAsStateWithLifecycle()
    val autoSorted by viewModel.work.autoSorted.collectAsStateWithLifecycle()
    val oldPrivate by viewModel.work.oldPrivate.collectAsStateWithLifecycle()
    val serverClasses = viewModel.work.serverClasses(me)
    val workOn = workSettings.active || serverClasses
    var moreOpen by remember { mutableStateOf(false) }

    // Drive 1.16: the people and the places above the timeline
    val header: DrivePlacesHeaderViewModel = androidx.hilt.navigation.compose.hiltViewModel(key = "drive-places")
    val barPeople by header.people.collectAsStateWithLifecycle()
    val topPlaces by header.places.collectAsStateWithLifecycle()
    val placeClusters by header.clusters.collectAsStateWithLifecycle()
    val peopleOn = places != null && me?.has(DMe.FEATURE_PHOTO_PEOPLE) == true
    val placesOn = places != null && me?.has(DMe.FEATURE_PHOTO_PLACES) == true
    var naming by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(me?.features) { if (places != null) header.load(me) }

    LaunchedEffect(Unit) {
        viewModel.start()
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
        viewModel.lookForSuggestions(unmetered = cm != null && !cm.isActiveNetworkMetered)
    }
    LaunchedEffect(me?.features) { viewModel.featuresChanged() }
    LaunchedEffect(tab) { if (tab == "albums" && albums == null) viewModel.loadAlbums() }
    LaunchedEffect(message) {
        message?.let {
            Toast.makeText(context, photosText(context, it), Toast.LENGTH_SHORT).show()
            viewModel.clearMessage()
        }
    }

    val selecting = selected.isNotEmpty()
    val running by viewModel.ops.running.collectAsStateWithLifecycle()
    val busy by viewModel.bulk.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    // back ends the selection first
    BackHandler(enabled = selecting) { viewModel.clearSelection() }
    val chosen = remember(selected, timeline.items) { timeline.items.filter { it.id in selected } }
    // without Drive 1.13 "Prüfen" shows this phone's marks among the photos loaded
    val shown = if (!serverClasses && cls == "review") timeline.copy(items = timeline.items.filter { it.id in localReview }) else timeline
    val reviewCount = if (serverClasses) classes?.review ?: 0 else localReview.size
    val reviewBar = DriveWorkLogic.reviewBar(chosen, localReview)
    val workSnackLearns = stringResource(R.string.drive_work_snack_learns)
    val resources = LocalResources.current
    val undoLabel = stringResource(R.string.drive_snack_undo)
    LaunchedEffect(workSnack) {
        val w = workSnack ?: return@LaunchedEffect
        viewModel.workSnackShown()
        val r = resources
        val target = workSettings.folderLabel ?: r.getString(R.string.drive_work_work_default)
        val line = when (w.to) {
            DriveWorkPhotos.Target.WORK -> r.getQuantityString(R.plurals.drive_work_snack_work, w.count, w.count, target)
            DriveWorkPhotos.Target.PRIVATE -> r.getQuantityString(R.plurals.drive_work_snack_private, w.count, w.count)
            DriveWorkPhotos.Target.REVIEW -> r.getQuantityString(R.plurals.drive_work_snack_review, w.count, w.count)
        }
        val text = if (w.to == DriveWorkPhotos.Target.REVIEW) line else line + "\n" + workSnackLearns
        val result = snackbar.showSnackbar(text, actionLabel = if (w.undo != null) undoLabel else null, withDismissAction = w.undo == null, duration = SnackbarDuration.Long)
        if (result == SnackbarResult.ActionPerformed) w.undo?.let(viewModel::undoWork)
    }
    // marked photos the phone sorted alone ("Selbst einsortieren, wenn sicher"), now or since the last time
    LaunchedEffect(autoSorted) {
        val a = autoSorted ?: return@LaunchedEffect
        viewModel.autoSortedShown()
        val text = resources.getQuantityString(R.plurals.drive_work_auto_sorted, a.count, a.count)
        val result = snackbar.showSnackbar(text, actionLabel = if (a.undo != null) undoLabel else null, withDismissAction = a.undo == null, duration = SnackbarDuration.Long)
        if (result == SnackbarResult.ActionPerformed) a.undo?.let(viewModel::undoAutoSorted)
    }
    val where = DriveBulk.Where(
        writable = { n -> me?.areas?.firstOrNull { it.id == n.areaId }?.writable == true },
        child = { n -> me?.areas?.firstOrNull { it.id == n.areaId }?.via == "parent" },
    )

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        // ticking changes the title row only (as high either way); everything below stays, dimmed — the grid must not
        // move under the finger of the long press
        if (ownHead) SelectionSwap(selecting, bar = topBar, selection = { PhotosSelectionHead(viewModel) }) else topBar()
        if (places != null && (placesOn || peopleOn)) PhotosSearchField(onClick = places.search, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp).inert(selecting))
        if (workOn) {
            val why = if (serverClasses) null else stringResource(R.string.drive_work_needs_1130)
            val reviewDesc = stringResource(R.string.drive_work_review_count, reviewCount)
            Row(Modifier.inert(selecting).horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 0.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                val onTimeline = tab == "timeline" || !hasAlbums
                WorkChip(stringResource(R.string.drive_work_all), selected = onTimeline && cls == null, onClick = { tab = "timeline"; viewModel.setClass(null) })
                WorkChip(stringResource(R.string.drive_work_private), selected = onTimeline && cls == "private", why = why, onClick = { tab = "timeline"; viewModel.setClass("private") })
                WorkChip(stringResource(R.string.drive_work_work), selected = onTimeline && cls == "work", why = why, onClick = { tab = "timeline"; viewModel.setClass("work") })
                WorkChip(
                    stringResource(R.string.drive_work_review),
                    selected = onTimeline && cls == "review",
                    count = reviewCount,
                    description = reviewDesc,
                    onClick = { tab = "timeline"; viewModel.setClass("review") },
                )
                if (hasAlbums) GcFilterChip(stringResource(R.string.drive_photos_albums), selected = tab == "albums", onClick = { tab = "albums" }, modifier = Modifier.padding(start = 4.dp))
                if (hasCamera && onTimeline) {
                    GcFilterChip(stringResource(R.string.drive_photos_camera_only), selected = cameraOnly, onClick = { viewModel.setCameraOnly(!cameraOnly) }, modifier = Modifier.padding(start = 8.dp))
                }
            }
        } else if (hasAlbums || hasCamera) {
            Row(Modifier.inert(selecting).horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (hasAlbums) {
                    GcFilterChip(stringResource(R.string.drive_photos_timeline), selected = tab == "timeline", onClick = { tab = "timeline" })
                    GcFilterChip(stringResource(R.string.drive_photos_albums), selected = tab == "albums", onClick = { tab = "albums" })
                }
                // a filter of the timeline (the albums are the albums)
                if (hasCamera && (tab == "timeline" || !hasAlbums)) {
                    GcFilterChip(
                        stringResource(R.string.drive_photos_camera_only),
                        selected = cameraOnly,
                        onClick = { viewModel.setCameraOnly(!cameraOnly) },
                    )
                }
            }
        }
        if (busy != null) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BulkBusyCard(busy, viewModel.bulk::cancelBusy)
            }
        }
        if (workOn && tab != "albums") Column(Modifier.inert(selecting)) {
            if (reviewCount > 0) {
                WorkBand(
                    pluralStringResource(R.plurals.drive_work_banner, reviewCount, reviewCount),
                    button = if (cls != "review") stringResource(R.string.drive_work_banner_show) else null,
                    warn = true,
                    onClick = { viewModel.setClass("review") },
                )
            }
            // "Prüfen" while the AI still learns: how far, and what helps
            if (cls == "review" && reviewCount > 0 && workSettings.active && !learned.ready) {
                WorkBand(
                    stringResource(R.string.drive_work_learning_band, learned.progress, DriveWorkLogic.MIN_EXAMPLES, learned.work, learned.private),
                    button = null,
                    warn = false,
                    onClick = null,
                )
            }
            // old photos nothing could place went private without the mark: "Aufräumen" suggests later
            if (workSettings.active && oldPrivate > 0 && cls != "review") {
                WorkBand(
                    pluralStringResource(R.plurals.drive_work_old_private, oldPrivate, oldPrivate),
                    button = stringResource(if (learned.usable) R.string.drive_work_cleanup_open else R.string.drive_work_old_ok),
                    warn = false,
                    onClick = {
                        viewModel.work.clearOldPrivate()
                        if (learned.usable) onCleanup()
                    },
                )
            }
            if (workSettings.active && learned.usable && suggested > 0 && cls != "review") {
                WorkBand(pluralStringResource(R.plurals.drive_work_cleanup_hint, suggested, suggested), button = stringResource(R.string.drive_work_cleanup_open), warn = false, onClick = onCleanup)
            }
        }
        Box(Modifier.weight(1f)) {
            if (tab == "albums" && hasAlbums) {
                AlbumGrid(albums, onAlbum = { onAlbum(it.id) }, onNew = { creating = false }, onRetry = viewModel::loadAlbums)
            } else {
                PhotoGrid(
                    pages = shown,
                    selected = selected,
                    months = if (shown !== timeline) emptyMap() else months,
                    badge = if (workOn) ({ n -> TileBadge(review = DriveWorkLogic.isReview(n, localReview), work = n.work && cls == null) }) else null,
                    onOpen = { i -> viewing = null to i },
                    onToggle = viewModel::toggle,
                    onMore = viewModel::more,
                    onRetry = viewModel::refresh,
                    empty = stringResource(R.string.drive_photos_empty),
                    // the header stays while ticking (else the photos below it would move up)
                    header = ({
                        // Drive 1.16: "Personen" with "Wer ist das?", "Orte" with the map — on "Alle" only
                        if (places != null && cls == null) {
                            val (named, unnamed) = DrivePlacesLogic.bar(barPeople?.people.orEmpty())
                            if (peopleOn && (named.isNotEmpty() || unnamed != null)) {
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    Column {
                                        PhotosSectionHead(stringResource(R.string.drive_pp_people), stringResource(R.string.drive_pp_all), places.people)
                                        PeopleBar(named, unnamed, onPerson = { places.person(it.id) }, onWho = { naming = it.id })
                                    }
                                }
                            }
                            if (placesOn && topPlaces.isNotEmpty()) {
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    Column(Modifier.padding(bottom = 4.dp)) {
                                        PhotosSectionHead(stringResource(R.string.drive_pp_places), stringResource(R.string.drive_pp_map), places.map)
                                        PlacesCard(topPlaces, placeClusters, onMap = places.map, onPlace = { places.place(it.key) })
                                    }
                                }
                            }
                        }
                        if (memories.isNotEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                MemoriesRow(memories, onOpen = { m -> viewing = m.yearsAgo to 0 })
                            }
                        }
                    }),
                    onSelect = viewModel.bulk::set,
                )
            }
        }
        if (selecting) {
            val send = BarAction(GcIcons.Share, stringResource(R.string.drive_bar_send), viewModel.bulk.can(DriveBulk.Action.SEND, chosen, where)) { viewModel.bulk.send() }
            val delete = BarAction(GcIcons.Trash, stringResource(R.string.drive_bar_delete), viewModel.bulk.can(DriveBulk.Action.DELETE, chosen, where), danger = true) { viewModel.bulk.delete() }
            val more = BarAction(GcIcons.MoreHorizontal, stringResource(R.string.drive_bar_more), DriveBulk.Can.YES) { moreOpen = true }
            if (reviewBar) {
                // every photo ticked is to be reviewed: "Arbeit" and "Privat" in front
                ReviewAssignBar(
                    hint = DriveWorkLogic.hint(chosen.map { DriveWorkLogic.hintOf(it, localReview) }),
                    onWork = { viewModel.assign(DriveWorkPhotos.Target.WORK) },
                    onPrivate = { viewModel.assign(DriveWorkPhotos.Target.PRIVATE) },
                    small = listOf(send, delete, more),
                )
            } else {
                SelectionActionBar(
                    listOf(
                        send,
                        BarAction(GcIcons.Photo, stringResource(R.string.drive_bar_album), viewModel.bulk.can(DriveBulk.Action.ALBUM, chosen, where)) { viewModel.loadAlbums(); choosing = true },
                        BarAction(GcIcons.FolderMove, stringResource(R.string.drive_bar_move), viewModel.bulk.can(DriveBulk.Action.MOVE, chosen, where)) { viewModel.bulk.openTarget(DriveBulk.Mode.MOVE) },
                        delete,
                        more,
                    ),
                )
            }
        }
    }
    // jobs on several items: the ring of the round button (a tap shows them one by one)
    if (!selecting) {
        JobsFab(
            jobs = running,
            onCancel = { id -> scope.launch { viewModel.ops.cancel(id) } },
            modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 16.dp, bottom = 16.dp),
        )
    }
    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = if (selecting) 88.dp else if (running.isNotEmpty()) 84.dp else 16.dp))
    }
    DriveBulkHost(viewModel.bulk, snackbar)
    if (moreOpen && chosen.isNotEmpty()) {
        PhotosMoreSheet(
            nodes = chosen,
            viewModel = viewModel,
            where = where,
            workOn = workOn,
            workLabel = workSettings.folderLabel ?: stringResource(R.string.drive_work_work_default),
            workFolderSet = workSettings.active,
            localReview = localReview,
            onFile = onFile,
            onDismiss = { moreOpen = false },
        )
    }

    viewing?.let { (years, start) ->
        PhotoViewer(
            items = if (years == null) timeline.items else memories.firstOrNull { it.yearsAgo == years }?.items.orEmpty(),
            start = start,
            fetch = viewModel::fetch,
            onMore = if (years == null) viewModel::more else null,
            onClose = { viewing = null },
        )
    }
    if (choosing) {
        ModalBottomSheet(onDismissRequest = { choosing = false }) {
            AlbumChooser(
                albums = (albums as? DriveLoad.Ready)?.value?.filter { it.canEdit },
                onAlbum = { a -> choosing = false; viewModel.addSelectedTo(a) },
                onNew = { choosing = false; creating = true },
            )
        }
    }
    naming?.let { id ->
        NamePersonSheet(
            personId = id,
            onDismiss = { naming = null; header.load(me) },
            onNext = {
                val next = DrivePlacesLogic.groups(barPeople?.people.orEmpty()).unnamed.firstOrNull { it.id != id }
                naming = next?.id
                if (next == null) header.load(me)
            },
        )
    }
    creating?.let { withSelected ->
        AlbumNameDialog(
            title = stringResource(R.string.drive_album_new),
            initial = "",
            confirm = stringResource(R.string.drive_create),
            areas = viewModel.albumAreas(),
            onConfirm = { name, area -> creating = null; viewModel.createAlbum(name, area, withSelected) },
            onDismiss = { creating = null },
        )
    }
}

/** "Vor einem Jahr", "Vor 3 Jahren": a card per year with the day's photos. */
@Composable
private fun MemoriesRow(memories: List<DMemories>, onOpen: (DMemories) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 8.dp)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(memories, key = { it.yearsAgo }) { m ->
                val cover = m.items.first()
                val picture = rememberDrivePreview(cover, large = true) ?: rememberDrivePreview(cover)
                Box(
                    Modifier
                        .width(220.dp)
                        .height(150.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(GateControlTheme.extraColors.blueBg)
                        .clickable(role = Role.Button) { onOpen(m) },
                ) {
                    if (picture != null) Image(picture, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f)))
                    Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
                        Text(
                            if (m.yearsAgo <= 0) stringResource(R.string.drive_photos_memories) else pluralStringResource(R.plurals.drive_photos_years_ago, m.yearsAgo, m.yearsAgo),
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                        )
                        Text(pluralStringResource(R.plurals.drive_photos_count, m.items.size, m.items.size), style = MaterialTheme.typography.bodySmall, color = Color.White)
                    }
                }
            }
        }
    }
}

/**
 * Photos as tiles, by month when [months] is given, loading the next page near the end. A tap opens one ([onOpen]
 * with its index), a long press — or a tap while some are ticked — ticks it. With [onSelect] a long press and a swipe
 * over the photos tick several at once (or untick, when the first one was ticked), and each month has "Alle" / "Keine"
 * while ticking.
 */
@Composable
internal fun PhotoGrid(
    pages: PhotoPages,
    selected: Set<Long>,
    onOpen: (Int) -> Unit,
    onToggle: (DNode) -> Unit,
    onMore: () -> Unit,
    onRetry: () -> Unit,
    empty: String,
    months: Map<String, Int>? = null,
    header: (LazyGridScope.() -> Unit)? = null,
    onSelect: ((Set<Long>) -> Unit)? = null,
    /** Drive 1.13: the review mark and the work bag of a tile. */
    badge: ((DNode) -> TileBadge)? = null,
    /** Drive 1.16: own headings (a place's days, a person's years) instead of the months. */
    sections: List<PhotoSection>? = null,
) {
    val state = rememberLazyGridState()
    val nearEnd by remember {
        derivedStateOf {
            val info = state.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 24
        }
    }
    LaunchedEffect(nearEnd, pages.items.size, pages.next) { if (nearEnd && !pages.end) onMore() }
    val index = remember(pages.items) { pages.items.withIndex().associate { it.value.id to it.index } }
    val ids = remember(pages.items) { pages.items.map { it.id } }
    val x = GateControlTheme.extraColors
    val selecting = selected.isNotEmpty()
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val current by rememberUpdatedState(selected)
    val drag = if (onSelect == null) {
        Modifier
    } else {
        Modifier.pointerInput(ids) {
            /** The photo under [pos] (its index in [ids]); -1 for none. */
            fun indexAt(pos: Offset): Int {
                val hit = state.layoutInfo.visibleItemsInfo.firstOrNull { info ->
                    val k = info.key as? String ?: return@firstOrNull false
                    k.startsWith("p-") && pos.x >= info.offset.x && pos.x < info.offset.x + info.size.width && pos.y >= info.offset.y && pos.y < info.offset.y + info.size.height
                } ?: return -1
                val id = (hit.key as String).removePrefix("p-").toLongOrNull() ?: return -1
                return index[id] ?: -1
            }
            var anchor = -1
            var base: Set<Long> = emptySet()
            var select = true
            detectDragGesturesAfterLongPress(
                onDragStart = { pos ->
                    val i = indexAt(pos)
                    if (i >= 0) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        anchor = i
                        base = current
                        select = ids[i] !in base
                        onSelect(DriveBulk.dragRange(base, ids, anchor, i, select))
                    }
                },
                onDrag = { change, _ ->
                    if (anchor >= 0) {
                        change.consume()
                        val i = indexAt(change.position)
                        if (i >= 0) onSelect(DriveBulk.dragRange(base, ids, anchor, i, select))
                        // near the edges the grid scrolls on
                        val edge = 64.dp.toPx()
                        val y = change.position.y
                        if (y < edge) scope.launch { state.scrollBy(-(edge - y) / 3) } else if (y > size.height - edge) scope.launch { state.scrollBy((y - (size.height - edge)) / 3) }
                    }
                },
                onDragEnd = { anchor = -1 },
                onDragCancel = { anchor = -1 },
            )
        }
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 96.dp),
        state = state,
        // bottom: room for the selection bar
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxSize().then(drag),
    ) {
        header?.invoke(this)
        val groups: List<Pair<String?, List<DNode>>> = sections?.map { it.key to it.items }
            ?: if (months != null) DriveLogic.byMonth(pages.items) else listOf(null to pages.items)
        groups.forEachIndexed { g, (month, list) ->
            if (months != null || sections != null) {
                item(key = "m-$g-$month", span = { GridItemSpan(maxLineSpan) }) {
                    val section = sections?.getOrNull(g)
                    val count = month?.let { months?.get(it) }
                    val title = section?.title ?: monthTitle(month) ?: stringResource(R.string.drive_photos_undated)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                        Text(
                            if (count != null && count > 0) "$title · ${pluralStringResource(R.plurals.drive_photos_count, count, count)}" else title,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.weight(1f).padding(top = 8.dp, bottom = 4.dp, start = 4.dp).semantics { heading() },
                        )
                        section?.trailing?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = x.muted, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp, end = 4.dp)) }
                        if (selecting && onSelect != null) {
                            val monthIds = list.map { it.id }
                            val all = DriveBulk.allSelected(selected, monthIds)
                            val desc = stringResource(if (all) R.string.drive_sel_none_of else R.string.drive_sel_all_of, title)
                            TextButton(onClick = { onSelect(DriveBulk.allOrNone(selected, monthIds)) }, modifier = Modifier.heightIn(min = 44.dp).semantics { contentDescription = desc }) {
                                Text(stringResource(if (all) R.string.drive_sel_none else R.string.drive_sel_all), color = x.accentText, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
            items(list, key = { "p-${it.id}" }) { n ->
                PhotoTile(
                    node = n,
                    selecting = selecting,
                    ticked = n.id in selected,
                    onClick = { if (selected.isNotEmpty()) onToggle(n) else onOpen(index[n.id] ?: 0) },
                    onLongClick = { onToggle(n) },
                    // with a swipe to tick the grid takes the long press itself
                    longPressGesture = onSelect == null,
                    badge = badge?.invoke(n),
                )
            }
        }
        item(key = "footer", span = { GridItemSpan(maxLineSpan) }) {
            when {
                pages.loading -> DriveLoading()
                pages.error != null && !pages.loaded -> DriveErrorBox(pages.error, onRetry = onRetry)
                pages.error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(stringResource(R.string.drive_photos_more_error), color = x.muted)
                    TextButton(onClick = onMore) { Text(stringResource(R.string.drive_retry)) }
                }
                pages.loaded && pages.items.isEmpty() -> Text(empty, color = x.muted, modifier = Modifier.padding(vertical = 24.dp, horizontal = 4.dp))
            }
        }
    }
}

/** A heading of [PhotoGrid] with its photos (Drive 1.16: a place's day with its district, a person's year). */
internal data class PhotoSection(val key: String, val title: String, val trailing: String?, val items: List<DNode>)

/**
 * A photo's tile: the tick top left while ticking (the ticked tile inset), the play mark of a video. [longPressGesture]
 * false: the grid takes the long press (a swipe ticks several), TalkBack still has "Auswählen".
 */
/** What a tile shows of the work photos: the yellow question mark ([review]), the blue bag ([work]). */
internal data class TileBadge(val review: Boolean, val work: Boolean)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhotoTile(
    node: DNode,
    selecting: Boolean,
    ticked: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    longPressGesture: Boolean = true,
    badge: TileBadge? = null,
) {
    val look = kindLook(DriveLogic.kind(node))
    val picture = rememberDrivePreview(node)
    val haptic = LocalHapticFeedback.current
    val selectLabel = stringResource(R.string.drive_photos_select)
    val clickLabel = if (selecting) stringResource(if (ticked) R.string.drive_sel_deselect else R.string.drive_sel_select) else null
    val state = stringResource(if (ticked) R.string.drive_sel_selected else R.string.drive_sel_not_selected)
    val label = listOfNotNull(
        node.name,
        photoDate(node),
        if (badge?.review == true) stringResource(R.string.drive_work_tile_review) else null,
        if (badge?.work == true) stringResource(R.string.drive_work_tile_work) else null,
    ).joinToString(", ")
    Box(
        Modifier
            .aspectRatio(1f)
            .then(
                if (longPressGesture) {
                    Modifier.combinedClickable(
                        role = Role.Button,
                        onClickLabel = clickLabel,
                        onClick = onClick,
                        onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onLongClick() },
                        onLongClickLabel = selectLabel,
                    )
                } else {
                    Modifier
                        .clickable(role = Role.Button, onClickLabel = clickLabel, onClick = onClick)
                        .semantics { onLongClick(selectLabel) { onLongClick(); true } }
                },
            )
            .semantics {
                contentDescription = label
                if (selecting) { selected = ticked; stateDescription = state }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .matchParentSize()
                .padding(if (ticked) 8.dp else 0.dp)
                .clip(RoundedCornerShape(if (ticked) 10.dp else 6.dp))
                .background(look.bg),
            contentAlignment = Alignment.Center,
        ) {
            if (picture != null) {
                Image(picture, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
            } else {
                Icon(look.icon, contentDescription = null, tint = look.fg, modifier = Modifier.size(24.dp))
            }
            if (DriveLogic.kind(node) == DriveLogic.Kind.VIDEO) VideoMark(24)
        }
        if (selecting) SelectCheck(ticked, Modifier.align(Alignment.TopStart).padding(6.dp), onImage = true)
        if (badge?.review == true) ReviewMark(Modifier.align(Alignment.TopEnd).padding(6.dp))
        if (badge?.work == true) WorkBag(Modifier.align(Alignment.BottomStart).padding(6.dp))
    }
}

/** The albums as cards (cover, name, count, photo frame); the first card makes a new one. */
@Composable
private fun AlbumGrid(albums: DriveLoad<List<DAlbum>>?, onAlbum: (DAlbum) -> Unit, onNew: () -> Unit, onRetry: () -> Unit) {
    when (albums) {
        null, DriveLoad.Loading -> DriveLoading()
        is DriveLoad.Error -> DriveErrorBox(albums.code, onRetry = onRetry)
        is DriveLoad.Ready -> LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 150.dp),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "new") { NewAlbumCard(onNew) }
            items(albums.value, key = { it.id }) { a -> AlbumCard(a, onClick = { onAlbum(a) }) }
            if (albums.value.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(stringResource(R.string.drive_albums_empty), color = GateControlTheme.extraColors.muted, modifier = Modifier.padding(8.dp))
                }
            }
        }
    }
}

/** A stand-in node for an album's cover: its preview is fetched through the node ([rememberDrivePreview]). */
internal fun coverNode(a: DAlbum): DNode? = a.coverId?.let {
    DNode(
        id = it, areaId = a.areaId ?: "cover", parentId = null, name = a.name, isDir = false, size = 0, mime = "image/jpeg", mediaId = null,
        rev = 0, mtime = null, updatedAt = null, updatedBy = null, preview = true,
    )
}

@Composable
private fun AlbumCard(a: DAlbum, onClick: () -> Unit) {
    val x = GateControlTheme.extraColors
    val cover = coverNode(a)?.let { rememberDrivePreview(it) }
    Column(Modifier.clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface).clickable(role = Role.Button, onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).background(x.blueBg), contentAlignment = Alignment.Center) {
            if (cover != null) {
                Image(cover, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
            } else {
                Icon(GcIcons.Photo, contentDescription = null, tint = x.blue, modifier = Modifier.size(32.dp))
            }
            if (a.frame) {
                Text(
                    stringResource(R.string.drive_album_frame),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.5f)).padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(a.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(pluralStringResource(R.plurals.drive_photos_count, a.count, a.count), style = MaterialTheme.typography.bodySmall, color = x.muted)
        }
    }
}

@Composable
private fun NewAlbumCard(onClick: () -> Unit) {
    val x = GateControlTheme.extraColors
    Column(
        Modifier.clip(RoundedCornerShape(16.dp)).border(1.dp, x.border2, RoundedCornerShape(16.dp)).clickable(role = Role.Button, onClick = onClick),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
            Icon(GcIcons.Plus, contentDescription = null, tint = x.accentText, modifier = Modifier.size(32.dp))
        }
        Text(stringResource(R.string.drive_album_new), style = MaterialTheme.typography.titleSmall, color = x.accentText, modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp))
    }
}

/** Which album the ticked photos go to (those the person may change), or a new one. */
@Composable
internal fun AlbumChooser(albums: List<DAlbum>?, onAlbum: (DAlbum) -> Unit, onNew: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        Text(stringResource(R.string.drive_album_choose), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        GcListRow(
            title = stringResource(R.string.drive_album_new),
            onClick = onNew,
            leading = { Icon(GcIcons.Plus, contentDescription = null, tint = GateControlTheme.extraColors.accentText) },
            titleColor = GateControlTheme.extraColors.accentText,
        )
        if (albums == null) DriveLoading()
        albums?.forEach { a ->
            GcListRow(
                title = a.name,
                description = pluralStringResource(R.plurals.drive_photos_count, a.count, a.count),
                onClick = { onAlbum(a) },
                leading = { Icon(GcIcons.Photo, contentDescription = null, tint = GateControlTheme.extraColors.muted) },
            )
        }
    }
}

/** Name of a new album (and, when there are several, the area it belongs to) or of a rename. */
@Composable
internal fun AlbumNameDialog(
    title: String,
    initial: String,
    confirm: String,
    areas: List<DArea>,
    onConfirm: (name: String, areaId: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    var area by remember { mutableStateOf(areas.firstOrNull { it.kind == "mine" }?.id ?: areas.firstOrNull()?.id) }
    val valid = value.isNotBlank() && value.trim().length <= DrivePhotosViewModel.NAME_MAX
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                GcTextField(value = value, onValueChange = { value = it.take(DrivePhotosViewModel.NAME_MAX) }, label = stringResource(R.string.drive_album_name))
                if (areas.size > 1) {
                    GcSectionLabel(stringResource(R.string.drive_album_area))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        areas.forEach { a -> GcFilterChip(a.label, selected = area == a.id, onClick = { area = a.id }) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(value.trim(), area.takeIf { areas.size > 1 }) }, enabled = valid) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.drive_cancel)) } },
    )
}

/**
 * Photos full screen, one after the other by swiping: the large preview (the small one meanwhile), name and date,
 * "Öffnen" and "Senden" as for any file of Drive. Near the end of [items] the next page loads ([onMore]).
 */
@Composable
internal fun PhotoViewer(
    items: List<DNode>,
    start: Int,
    fetch: suspend (DNode) -> DResult<File>,
    onMore: (() -> Unit)?,
    onClose: () -> Unit,
) {
    if (items.isEmpty()) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState(initialPage = start.coerceIn(0, items.size - 1)) { items.size }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(pager.currentPage, items.size) { if (onMore != null && pager.currentPage >= items.size - 5) onMore() }
    val act: (DNode, Boolean) -> Unit = { n, send ->
        if (!busy) {
            busy = true
            scope.launch {
                fetchThen(context, n, fetch, then = { f -> if (send) sendFile(context, n, f) else openFile(context, n, f) }) { code ->
                    Toast.makeText(context, driveText(context, code), Toast.LENGTH_LONG).show()
                }
                busy = false
            }
        }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), key = { items.getOrNull(it)?.id ?: it }) { page ->
                val n = items[page]
                val large = rememberDrivePreview(n, large = true)
                val small = rememberDrivePreview(n)
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val picture = large ?: small
                    if (picture != null) {
                        Image(picture, contentDescription = n.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                    } else if (n.preview) {
                        CircularProgressIndicator(color = Color.White)
                    } else {
                        Icon(kindLook(DriveLogic.kind(n)).icon, contentDescription = n.name, tint = Color.White, modifier = Modifier.size(56.dp))
                    }
                    if (DriveLogic.kind(n) == DriveLogic.Kind.VIDEO) {
                        Box(
                            Modifier.size(72.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)).clickable(role = Role.Button) { act(n, false) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(GcIcons.Play, contentDescription = stringResource(R.string.drive_open), tint = Color.White, modifier = Modifier.size(40.dp))
                        }
                    }
                }
            }
            val current = items.getOrNull(pager.currentPage)
            Row(
                Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.45f)).statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GcIconButton(GcIcons.Close, contentDescription = stringResource(R.string.drive_viewer_close), onClick = onClose, tint = Color.White)
                Column(Modifier.weight(1f)) {
                    Text(current?.name.orEmpty(), color = Color.White, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    current?.let { photoDate(it) }?.let { Text(it, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall) }
                }
                Text("${pager.currentPage + 1} / ${items.size}", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(end = 12.dp))
            }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.45f)).navigationBarsPadding()) {
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    current?.let { n ->
                        TextButton(onClick = { act(n, false) }) {
                            Icon(GcIcons.External, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.drive_open), color = Color.White)
                        }
                        TextButton(onClick = { act(n, true) }) {
                            Icon(GcIcons.Share, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.drive_send), color = Color.White)
                        }
                    }
                }
            }
        }
    }
}

/** Choosing photos to add to an album: the timeline, ticked by a tap, then "Hinzufügen". */
@Composable
internal fun PhotoPickerDialog(
    pages: PhotoPages,
    picked: Set<Long>,
    onPick: (DNode) -> Unit,
    onMore: () -> Unit,
    onRetry: () -> Unit,
    onAdd: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                GcIconButton(GcIcons.Close, contentDescription = stringResource(R.string.drive_cancel), onClick = onDismiss, tint = MaterialTheme.colorScheme.onBackground)
                Text(stringResource(R.string.drive_album_add), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            }
            Box(Modifier.weight(1f)) {
                PhotoGrid(
                    pages = pages,
                    selected = picked,
                    // a tap ticks: here every tap chooses
                    onOpen = { i -> pages.items.getOrNull(i)?.let(onPick) },
                    onToggle = onPick,
                    onMore = onMore,
                    onRetry = onRetry,
                    empty = stringResource(R.string.drive_photos_empty),
                )
            }
            Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
                GcPrimaryButton(pluralStringResource(R.plurals.drive_album_add_confirm, picked.size, picked.size), onClick = onAdd, enabled = picked.isNotEmpty())
            }
        }
    }
}

/** The album page's card about the photo frame (feature `frame`, only with the right to switch it, `canFrame`). */
@Composable
internal fun FrameCard(album: DAlbum, onFrame: (Boolean) -> Unit) {
    GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        com.gatecontrol.android.ui.components.GcSwitchRow(
            label = stringResource(R.string.drive_album_frame),
            description = stringResource(R.string.drive_album_frame_desc),
            checked = album.frame,
            onCheckedChange = onFrame,
        )
    }
}

/**
 * "Mehr" of the ticked photos: save on the phone, copy, one link, then — with work photos apart (Drive 1.13) — the
 * section "Zuordnung" ("Ist ein Arbeitsfoto", "Ist privat", "Zum Prüfen markieren"), and the details of one.
 */
@Composable
private fun PhotosMoreSheet(
    nodes: List<DNode>,
    viewModel: DrivePhotosViewModel,
    where: DriveBulk.Where,
    workOn: Boolean,
    workLabel: String,
    workFolderSet: Boolean,
    localReview: Map<Long, Int?>,
    onFile: ((Long) -> Unit)?,
    onDismiss: () -> Unit,
) {
    val bulk = viewModel.bulk
    val one = nodes.singleOrNull()
    val can: (DriveBulk.Action) -> DriveBulk.Can = { a -> bulk.can(a, nodes, where) }
    val multi = listOf(
        MoreItem(GcIcons.Download, stringResource(R.string.drive_more_save), stringResource(R.string.drive_more_save_desc), can(DriveBulk.Action.SAVE)) { bulk.save(nodes) },
        MoreItem(GcIcons.Copy, stringResource(R.string.drive_bar_copy), can = can(DriveBulk.Action.COPY)) { bulk.openTarget(DriveBulk.Mode.COPY, nodes) },
        MoreItem(
            GcIcons.Link,
            stringResource(R.string.drive_share_link),
            if (one != null) stringResource(R.string.drive_more_link_one) else pluralStringResource(R.plurals.drive_more_link, nodes.size, nodes.size),
            can(DriveBulk.Action.LINK),
        ) { bulk.openLink(nodes) },
    )
    val assign = if (!workOn) emptyList() else {
        val offer = DriveWorkLogic.offer(nodes, localReview)
        val no = DriveBulk.Can(false, null)
        listOf(
            MoreItem(
                GcIcons.Briefcase,
                stringResource(R.string.drive_work_more_work),
                when {
                    !workFolderSet -> stringResource(R.string.drive_work_more_no_folder)
                    !offer.work -> stringResource(R.string.drive_work_more_work_already)
                    else -> stringResource(R.string.drive_work_more_work_desc, workLabel)
                },
                if (workFolderSet && offer.work) can(DriveBulk.Action.MOVE) else no,
            ) { viewModel.assign(DriveWorkPhotos.Target.WORK, nodes) },
            MoreItem(
                GcIcons.House,
                stringResource(R.string.drive_work_more_private),
                stringResource(if (offer.private) R.string.drive_work_more_private_desc else R.string.drive_work_more_private_already),
                if (offer.private) can(DriveBulk.Action.MOVE) else no,
            ) { viewModel.assign(DriveWorkPhotos.Target.PRIVATE, nodes) },
            MoreItem(
                GcIcons.Alert,
                stringResource(R.string.drive_work_more_review),
                if (offer.review) null else stringResource(R.string.drive_work_more_review_already),
                if (offer.review) can(DriveBulk.Action.MOVE) else no,
            ) { viewModel.assign(DriveWorkPhotos.Target.REVIEW, nodes) },
        )
    }
    val state = one?.let {
        when {
            DriveWorkLogic.isReview(it, localReview) -> stringResource(R.string.drive_work_state_review)
            it.work -> stringResource(R.string.drive_work_state_work)
            else -> stringResource(R.string.drive_work_state_private)
        }
    }
    val details = if (one != null && onFile != null) listOf(MoreItem(GcIcons.Info, stringResource(R.string.drive_details)) { onFile(one.id) }) else emptyList()
    BulkMoreSheet(
        count = nodes.size,
        title = null,
        summary = if (one != null && workOn) "${one.name} · $state" else one?.name ?: summaryText(DriveBulk.summary(nodes)),
        quick = emptyList(),
        multi = multi,
        single = emptyList(),
        last = emptyList(),
        sections = listOfNotNull(
            if (assign.isNotEmpty()) stringResource(R.string.drive_work_more_section) to assign else null,
            if (details.isNotEmpty()) null to details else null,
        ),
        onDismiss = onDismiss,
    )
}
