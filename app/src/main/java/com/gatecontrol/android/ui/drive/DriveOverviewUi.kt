package com.gatecontrol.android.ui.drive

import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.drive.DActivity
import com.gatecontrol.android.drive.DArea
import com.gatecontrol.android.drive.DDeadline
import com.gatecontrol.android.drive.DMemoryCard
import com.gatecontrol.android.drive.DOverview
import com.gatecontrol.android.drive.DOverviewPeople
import com.gatecontrol.android.drive.DOverviewPlaces
import com.gatecontrol.android.drive.DOverviewStorage
import com.gatecontrol.android.drive.DRecent
import com.gatecontrol.android.drive.DTodo
import com.gatecontrol.android.drive.DriveLogic
import com.gatecontrol.android.drive.DriveOverviewLogic
import com.gatecontrol.android.drive.DriveOverviewLogic.Section
import com.gatecontrol.android.drive.DrivePlacesLogic
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcIconSquare
import com.gatecontrol.android.ui.familie.OsmAttribution
import com.gatecontrol.android.ui.theme.GateControlTheme
import java.time.LocalDate
import java.time.ZoneId

/** Where the overview's rows lead (Drive 1.18); null: not offered here. */
internal data class OverviewActions(
    val search: () -> Unit,
    val backup: () -> Unit,
    val storage: (() -> Unit)?,
    val deadline: (DDeadline) -> Unit,
    val deadlines: () -> Unit,
    val review: () -> Unit,
    val toFile: (() -> Unit)?,
    val file: (Long) -> Unit,
    val folder: (Long) -> Unit,
    val files: () -> Unit,
    val photos: () -> Unit,
    val memory: (DMemoryCard) -> Unit,
    val people: () -> Unit,
    val person: (Long) -> Unit,
    val who: () -> Unit,
    val map: () -> Unit,
    val place: (String) -> Unit,
)

/** The small map of "Orte": a still picture this big (the mockup's 120 × 84, wide enough for the attribution). */
private val MAP_W = 168.dp
private val MAP_H = 100.dp

/**
 * The overview page of the Drive tab (Drive 1.18, mockup "Übersicht"): the search over everything, the backup and
 * the storage, then "Zu erledigen", "Zuletzt geöffnet", "Erinnerungen", "Personen", "Orte" and "Neu in …" — each only
 * when it has something.
 */
@Composable
internal fun DriveOverviewContent(viewModel: DriveOverviewViewModel, areas: List<DArea>, actions: OverviewActions, naming: (Long) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val viewing by viewModel.viewing.collectAsStateWithLifecycle()
    val config by viewModel.backup.config.collectAsStateWithLifecycle()
    val stats by viewModel.backup.stats.collectAsStateWithLifecycle()
    val status by viewModel.backup.status.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.start()
        viewModel.load()
        onPauseOrDispose { }
    }
    val backup = DriveOverviewLogic.backup(config.enabled, status.running, status.done, status.total, stats.lastRunAt, stats.error)
    when (val s = state) {
        DriveLoad.Loading -> DriveLoading()
        is DriveLoad.Error -> DriveErrorBox(s.code, onRetry = viewModel::load)
        is DriveLoad.Ready -> OverviewPage(
            overview = s.value,
            backup = backup,
            areas = areas,
            actions = actions.copy(memory = viewModel::openMemory, who = { viewModel.whoIsThat(naming) }),
        )
    }
    viewing?.let { items ->
        PhotoViewer(items = items, start = 0, fetch = viewModel::fetch, onMore = null, onClose = viewModel::closeMemory)
    }
}

/**
 * The overview itself (no view model: tested as it is). The small map of "Orte" is never a map in the list: the list
 * shows its still picture ([PhotoMapStills]); until there is one, a map of the same size draws it behind the list.
 */
@Composable
internal fun OverviewPage(
    overview: DOverview,
    backup: DriveOverviewLogic.Backup?,
    areas: List<DArea>,
    actions: OverviewActions,
    listState: LazyListState = rememberLazyListState(),
) {
    val sections = DriveOverviewLogic.sections(overview, backup)
    val places = overview.places?.takeIf { Section.PLACES in sections }
    val stillKey = places?.let { overviewMapKey(it) }
    Box(Modifier.fillMaxSize()) {
        if (places != null && stillKey != null && PhotoMapStills[stillKey] == null) OverviewMapRenderer(places, stillKey)
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag(OVERVIEW_LIST_TAG),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            sections.forEach { section ->
                when (section) {
                    Section.SEARCH -> item(key = "search", contentType = "search") { OverviewSearch(actions.search) }
                    Section.STATUS -> item(key = "status", contentType = "status") { StatusRow(backup, overview.storage, actions) }
                    Section.TODO -> item(key = "todo", contentType = "todo") { TodoSection(overview.todo!!, areas, actions) }
                    Section.RECENT -> item(key = "recent", contentType = "recent") { RecentSection(overview.recent, areas, actions) }
                    Section.MEMORIES -> item(key = "memories", contentType = "memories") { MemoriesSection(overview.memories, actions) }
                    Section.PEOPLE -> item(key = "people", contentType = "people") { PeopleSection(overview.people!!, actions) }
                    Section.PLACES -> item(key = "places", contentType = "places") { PlacesSection(overview.places!!, stillKey, actions) }
                    Section.ACTIVITY -> item(key = "activity", contentType = "activity") { ActivitySection(overview.activity, actions) }
                }
            }
            if (sections.size == 1) {
                item(key = "empty") {
                    Text(
                        stringResource(R.string.drive_ov_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = GateControlTheme.extraColors.muted,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
                    )
                }
            }
        }
    }
}

internal const val OVERVIEW_LIST_TAG = "drive-overview"

/** What the still picture of the small map shows (places, bubble, size, colours): the key of [PhotoMapStills]. */
@Composable
private fun overviewMapKey(p: DOverviewPlaces): String? {
    val box = DriveOverviewLogic.mapBox(p) ?: return null
    val c = DriveOverviewLogic.mapCluster(p)
    val density = LocalDensity.current
    val w = with(density) { MAP_W.roundToPx() }
    val h = with(density) { MAP_H.roundToPx() }
    val scheme = MaterialTheme.colorScheme
    return "ov|$box|${c?.lat},${c?.lon},${c?.count}|${w}x$h|${scheme.primary.toArgb()},${scheme.surface.toArgb()},${scheme.background.toArgb()}"
}

/**
 * Draws the small map once, outside the list and as big as its picture (never 0 × 0), behind the list's background:
 * once its tiles are all there it becomes the still picture, and then it goes.
 */
@Composable
private fun OverviewMapRenderer(p: DOverviewPlaces, key: String) {
    Box(Modifier.size(MAP_W, MAP_H).clearAndSetSemantics { }) {
        PhotoMap(
            clusters = listOfNotNull(DriveOverviewLogic.mapCluster(p)),
            selected = null,
            fit = DriveOverviewLogic.mapBox(p),
            interactive = false,
            onCluster = {},
            onMoved = { _, _ -> },
            contentDescription = "",
            modifier = Modifier.size(MAP_W, MAP_H),
            stillKey = key,
            onStill = PhotoMapStills::put,
        )
    }
}

// ─── the sections ─────────────────────────────────────────────────

/** A heading of the overview with a number or a link on the right ("Alle", "Karte"). */
@Composable
private fun OverviewHead(title: String, count: Int? = null, link: String? = null, onLink: (() -> Unit)? = null) {
    val x = GateControlTheme.extraColors
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 16.dp, bottom = 4.dp).heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).semantics { heading() })
        if (count != null) Text(count.toString(), style = MaterialTheme.typography.bodySmall, color = x.muted, modifier = Modifier.padding(end = 12.dp))
        if (link != null && onLink != null) {
            TextButton(onClick = onLink, modifier = Modifier.heightIn(min = 44.dp)) {
                Text(link, color = x.accentText, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** "Dateien, Fotos, Personen, Orte": a tap opens the search. */
@Composable
private fun OverviewSearch(onClick: () -> Unit) {
    val x = GateControlTheme.extraColors
    Row(
        Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp).fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(14.dp)).background(x.panel2)
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.drive_ov_search), onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(GcIcons.Search, contentDescription = null, tint = x.muted, modifier = Modifier.size(18.dp))
        Text(stringResource(R.string.drive_ov_search_hint), color = x.muted, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** "Fotos gesichert · vor 4 Min." and the storage with its bar, side by side. */
@Composable
private fun StatusRow(backup: DriveOverviewLogic.Backup?, storage: DOverviewStorage?, actions: OverviewActions) {
    val x = GateControlTheme.extraColors
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (backup != null) {
            val ok = backup is DriveOverviewLogic.Backup.Saved || backup is DriveOverviewLogic.Backup.Running
            Row(
                Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp)).background(if (ok) x.accentBg else x.warnBg)
                    .clickable(role = Role.Button, onClick = actions.backup).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(if (ok) GcIcons.Check else GcIcons.Upload, contentDescription = null, tint = if (ok) x.accentText else x.warn, modifier = Modifier.size(16.dp))
                Text(backupText(backup), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (storage != null) {
            val used = DriveLogic.size(storage.used)
            val text = storage.total?.let { stringResource(R.string.drive_ov_storage, used, DriveLogic.size(it)) } ?: stringResource(R.string.drive_ov_storage_used, used)
            val desc = stringResource(R.string.drive_ov_storage_desc, listOfNotNull(text, storage.target).joinToString(" · "))
            Column(
                Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp)).background(x.panel2)
                    .then(if (actions.storage != null) Modifier.clickable(role = Role.Button, onClick = actions.storage) else Modifier)
                    .semantics(mergeDescendants = true) { contentDescription = desc }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
            ) {
                Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                DriveOverviewLogic.fill(storage)?.let { f ->
                    LinearProgressIndicator(
                        progress = { f },
                        color = x.blue,
                        trackColor = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                    )
                }
            }
        }
    }
}

@Composable
private fun backupText(b: DriveOverviewLogic.Backup): String = when (b) {
    is DriveOverviewLogic.Backup.Running ->
        if (b.total > 0) stringResource(R.string.drive_ov_backup_running, b.done, b.total) else stringResource(R.string.drive_ov_backup_running_start)
    is DriveOverviewLogic.Backup.Saved -> stringResource(
        R.string.drive_ov_backup_saved,
        DateUtils.getRelativeTimeSpanString(b.at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString(),
    )
    DriveOverviewLogic.Backup.Waiting -> stringResource(R.string.drive_ov_backup_waiting)
    is DriveOverviewLogic.Backup.Stopped -> stringResource(R.string.drive_ov_backup_stopped)
}

/** "Zu erledigen": the next deadlines, the photos to review, the mail attachments to file. */
@Composable
private fun TodoSection(t: DTodo, areas: List<DArea>, actions: OverviewActions) {
    val x = GateControlTheme.extraColors
    val today = remember { LocalDate.now() }
    OverviewHead(stringResource(R.string.drive_ov_todo), count = DriveOverviewLogic.todoCount(t))
    OverviewCard {
        val rows = buildList<@Composable (Boolean) -> Unit> {
            t.deadlines.forEach { d ->
                add { last ->
                    val file = d.node?.let { nodeTitle(it) }
                    val area = d.node?.areaId?.let { id -> areas.firstOrNull { it.id == id }?.label }
                    TodoRow(
                        icon = { Icon(GcIcons.Calendar, contentDescription = null, tint = x.warn, modifier = Modifier.size(18.dp)) },
                        tint = x.warnBg,
                        title = d.label ?: file ?: stringResource(deadlineLabel(d.kind)),
                        line = listOfNotNull(deadlineWhen(DriveLogic.daysUntil(d.date, today)), area).joinToString(" · "),
                        last = last,
                        onClick = { actions.deadline(d) },
                    )
                }
            }
            if (t.deadlinesTotal > t.deadlines.size) {
                val more = t.deadlinesTotal - t.deadlines.size
                add { last ->
                    TodoRow(
                        icon = { Icon(GcIcons.Calendar, contentDescription = null, tint = x.muted, modifier = Modifier.size(18.dp)) },
                        tint = x.panel2,
                        title = pluralStringResource(R.plurals.drive_ov_more_deadlines, more, more),
                        line = null,
                        last = last,
                        onClick = actions.deadlines,
                    )
                }
            }
            if (t.review > 0) {
                add { last ->
                    TodoRow(
                        icon = { Text("?", color = x.warn, fontWeight = FontWeight.Bold, fontSize = 16.sp) },
                        tint = x.warnBg,
                        title = pluralStringResource(R.plurals.drive_ov_review, t.review, t.review),
                        line = stringResource(R.string.drive_ov_review_hint),
                        last = last,
                        onClick = actions.review,
                    )
                }
            }
            val toFile = t.toFile ?: 0
            if (toFile > 0 && actions.toFile != null) {
                add { last ->
                    TodoRow(
                        icon = { Icon(GcIcons.Mail, contentDescription = null, tint = x.blue, modifier = Modifier.size(18.dp)) },
                        tint = x.blueBg,
                        title = pluralStringResource(R.plurals.drive_ov_to_file, toFile, toFile),
                        line = t.toFileHint?.let { stringResource(R.string.drive_ov_to_file_hint, it) },
                        last = last,
                        onClick = actions.toFile,
                    )
                }
            }
        }
        rows.forEachIndexed { i, row -> row(i == rows.lastIndex) }
    }
}

@Composable
private fun OverviewCard(content: @Composable () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface)) { content() }
}

@Composable
private fun TodoRow(icon: @Composable () -> Unit, tint: Color, title: String, line: String?, last: Boolean, onClick: () -> Unit) {
    val x = GateControlTheme.extraColors
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GcIconSquare(size = 36.dp, background = tint) { icon() }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (!line.isNullOrEmpty()) Text(line, style = MaterialTheme.typography.bodySmall, color = x.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (!last) Box(Modifier.fillMaxWidth().height(1.dp).background(x.border))
    }
}

/** "Zuletzt geöffnet": the files as small cards side by side. */
@Composable
private fun RecentSection(recent: List<DRecent>, areas: List<DArea>, actions: OverviewActions) {
    val x = GateControlTheme.extraColors
    OverviewHead(stringResource(R.string.drive_ov_recent), link = stringResource(R.string.drive_pp_all), onLink = actions.files)
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(recent, key = { it.node.id }) { r ->
            val at = DriveOverviewLogic.millis(r.openedAt ?: r.changedAt ?: r.node.mtime)
            val ago = at?.let { DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString() }
            val area = r.areaLabel ?: areas.firstOrNull { it.id == r.node.areaId }?.label
            Column(
                Modifier.width(150.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surface)
                    .clickable(role = Role.Button) { actions.file(r.node.id) }.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                NodeIcon(r.node, size = 40)
                Text(nodeTitle(r.node), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.heightIn(min = 36.dp))
                Text(listOfNotNull(ago, area).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = x.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** "Erinnerungen": "Heute vor 2 Jahren", "Vor einem Jahr" with the cover photo; a tap shows the photos. */
@Composable
private fun MemoriesSection(memories: List<DMemoryCard>, actions: OverviewActions) {
    OverviewHead(stringResource(R.string.drive_ov_memories), link = stringResource(R.string.drive_pp_all), onLink = actions.photos)
    val today = remember { LocalDate.now() }
    val zone = remember { ZoneId.systemDefault() }
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(memories, key = { "${it.yearsAgo}-${it.cover.id}" }) { m ->
            val picture = rememberDrivePreview(m.cover, large = true) ?: rememberDrivePreview(m.cover)
            val label = DriveOverviewLogic.memoryLabel(m, today, zone)
            val title = when {
                label.years <= 0 -> stringResource(R.string.drive_photos_memories)
                label.today -> pluralStringResource(R.plurals.drive_ov_memory_today, label.years, label.years)
                else -> pluralStringResource(R.plurals.drive_photos_years_ago, label.years, label.years)
            }
            val line = listOfNotNull(pluralStringResource(R.plurals.drive_photos_count, m.count, m.count), m.cover.geo?.place?.name).joinToString(" · ")
            Box(
                Modifier.width(200.dp).height(130.dp).clip(RoundedCornerShape(16.dp)).background(GateControlTheme.extraColors.blueBg)
                    .clickable(role = Role.Button) { actions.memory(m) },
            ) {
                if (picture != null) Image(picture, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.28f)))
                Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(line, style = MaterialTheme.typography.bodySmall, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** "Personen": the named faces, then "Wer ist das?" while someone has no name. */
@Composable
private fun PeopleSection(p: DOverviewPeople, actions: OverviewActions) {
    val x = GateControlTheme.extraColors
    OverviewHead(stringResource(R.string.drive_pp_people), link = stringResource(R.string.drive_pp_all), onLink = actions.people)
    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        items(p.people, key = { it.id }) { person ->
            Column(
                Modifier.width(68.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button) { actions.person(person.id) }.padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PersonFace(person, 52.dp)
                Text(person.name.orEmpty(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (p.unnamed > 0) {
            item(key = "who") {
                Column(
                    Modifier.width(76.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = actions.who).padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    WhoCircle(52.dp)
                    Text(stringResource(R.string.drive_pp_who), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = x.warn, maxLines = 1)
                }
            }
        }
    }
}

/**
 * "Orte": the small map as its still picture (a tap opens the map) and the places with most photos. Until the
 * picture is there the item shows the bubble on an empty map; no map is ever made in here.
 */
@Composable
private fun PlacesSection(p: DOverviewPlaces, stillKey: String?, actions: OverviewActions) {
    val x = GateControlTheme.extraColors
    OverviewHead(stringResource(R.string.drive_pp_places), link = stringResource(R.string.drive_pp_map), onLink = actions.map)
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val desc = stringResource(R.string.drive_pp_map_open)
        Box(
            Modifier.size(MAP_W, MAP_H).clip(RoundedCornerShape(14.dp)).background(x.panel2)
                .clickable(role = Role.Button, onClickLabel = desc, onClick = actions.map).semantics { contentDescription = desc },
        ) {
            val still = stillKey?.let { PhotoMapStills[it] }
            if (still != null) {
                Image(still, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
                OsmAttribution(Modifier.align(Alignment.BottomEnd))
            } else {
                val total = DriveOverviewLogic.mapCluster(p)?.count ?: p.total
                Box(
                    Modifier.align(Alignment.Center).heightIn(min = 28.dp).clip(RoundedCornerShape(99.dp)).background(MaterialTheme.colorScheme.primary).padding(horizontal = 10.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(countText(total), color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium) }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            p.top.forEach { place ->
                Box(
                    Modifier.heightIn(min = 36.dp).clip(RoundedCornerShape(99.dp)).border(1.dp, x.border2, RoundedCornerShape(99.dp))
                        .clickable(role = Role.Button) { actions.place(place.key) }.padding(horizontal = 12.dp, vertical = 6.dp),
                    contentAlignment = Alignment.CenterStart,
                ) { Text("${place.name} · ${countText(place.count)}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

/** "Neu in Familie": what the others added lately, a row per person, folder and hour. */
@Composable
private fun ActivitySection(activity: List<DActivity>, actions: OverviewActions) {
    val x = GateControlTheme.extraColors
    val area = activity.mapNotNull { it.areaLabel }.distinct().singleOrNull()
    OverviewHead(
        if (area != null) stringResource(R.string.drive_ov_activity_in, area) else stringResource(R.string.drive_ov_activity_shared),
        link = stringResource(R.string.drive_pp_all),
        onLink = actions.files,
    )
    OverviewCard {
        activity.forEachIndexed { i, a ->
            val open: (() -> Unit)? = when {
                a.folderId != null -> ({ actions.folder(a.folderId) })
                a.sample != null -> ({ actions.file(a.sample.id) })
                else -> null
            }
            Column {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).then(if (open != null) Modifier.clickable(role = Role.Button, onClick = open) else Modifier)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val name = a.userName ?: stringResource(R.string.drive_ov_someone)
                    Box(Modifier.size(36.dp).clip(CircleShape).background(personColor((a.userId ?: 0).toLong())), contentAlignment = Alignment.Center) {
                        Text(DrivePlacesLogic.initials(name), color = Color(0xFF0D1015), fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(boldName(activityText(a, name), name), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val where = listOfNotNull(a.areaLabel, DriveOverviewLogic.folderName(a.folderPath)).distinct().joinToString(" › ").ifEmpty { null }
                        val ago = DriveOverviewLogic.millis(a.at)?.let {
                            DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()
                        }
                        val line = listOfNotNull(where, ago).joinToString(" · ")
                        if (line.isNotEmpty()) Text(line, style = MaterialTheme.typography.bodySmall, color = x.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    a.sample?.let { NodeIcon(it, size = 40) }
                }
                if (i < activity.lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(x.border))
            }
        }
    }
}

/** "Mama hat 14 Fotos hinzugefügt", "Papa hat „Zeugnis Lea.pdf“ abgelegt", "Tim hat 3 Dateien geändert". */
@Composable
private fun activityText(a: DActivity, name: String): String {
    val one = a.sample?.takeIf { a.count == 1 }?.let { nodeTitle(it) }
    return when (a.kind) {
        DriveOverviewLogic.ADDED_PHOTOS -> pluralStringResource(R.plurals.drive_ov_act_photos, a.count, name, a.count)
        DriveOverviewLogic.ADDED_FILES ->
            if (one != null) stringResource(R.string.drive_ov_act_file, name, one) else pluralStringResource(R.plurals.drive_ov_act_files, a.count, name, a.count)
        else ->
            if (one != null) stringResource(R.string.drive_ov_act_changed_one, name, one) else pluralStringResource(R.plurals.drive_ov_act_changed, a.count, name, a.count)
    }
}

/** [text] with [name] in bold (where it stands, in any language). */
private fun boldName(text: String, name: String): AnnotatedString = buildAnnotatedString {
    append(text)
    val at = text.indexOf(name)
    if (at >= 0 && name.isNotEmpty()) addStyle(SpanStyle(fontWeight = FontWeight.Bold), at, at + name.length)
}
