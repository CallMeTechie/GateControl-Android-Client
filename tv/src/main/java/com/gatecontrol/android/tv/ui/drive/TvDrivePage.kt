package com.gatecontrol.android.tv.ui.drive

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.gatecontrol.android.drive.DArea
import com.gatecontrol.android.drive.DNode
import com.gatecontrol.android.drive.DriveLogic
import com.gatecontrol.android.tv.R
import com.gatecontrol.android.tv.drive.TvDriveOpen
import com.gatecontrol.android.tv.drive.TvDriveProblem
import com.gatecontrol.android.tv.drive.TvDriveUi
import com.gatecontrol.android.tv.ui.components.TvButton
import com.gatecontrol.android.tv.ui.components.TvPill
import com.gatecontrol.android.tv.ui.components.TvPillTone
import com.gatecontrol.android.tv.ui.home.TvCard
import com.gatecontrol.android.tv.ui.home.TvIcons
import com.gatecontrol.android.tv.ui.theme.GateControlTvTheme

private val PictureBg = Color(0xFF0B0F14)
private const val COLUMNS = 5

/**
 * The page "Drive" of the rail: the areas as cards, then the folders and files of one (◀ ▶ ▲ ▼, OK opens, Zurück goes
 * up). Pictures and videos open the viewer, a document its preview; the TV only reads.
 */
@Composable
fun TvDrivePage(
    ui: TvDriveUi,
    previews: Map<Long, Bitmap>,
    onStart: () -> Unit,
    onArea: (DArea) -> Unit,
    onItem: (DNode) -> TvDriveOpen,
    onBack: () -> Boolean,
    onMore: () -> Unit,
    onPreview: (DNode) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GateControlTvTheme.colors
    LaunchedEffect(Unit) { onStart() }
    BackHandler(enabled = !ui.stack.atAreas) { onBack() }
    var hint by remember { mutableStateOf<TvDriveOpen?>(null) }
    LaunchedEffect(ui.stack) { hint = null }
    val first = remember { FocusRequester() }
    // a new level: the remote starts on its first card
    LaunchedEffect(ui.stack, ui.areas.isNotEmpty(), ui.items.isNotEmpty()) {
        if ((ui.stack.atAreas && ui.areas.isNotEmpty()) || (!ui.stack.atAreas && ui.items.isNotEmpty())) first.requestSoon()
    }

    Column(modifier.fillMaxSize().padding(end = 30.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.tv_nav_drive), style = MaterialTheme.typography.titleMedium, color = colors.text)
            val path = ui.stack.path()
            if (path.isNotEmpty()) {
                Text(
                    "› " + path.joinToString(" › "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            if (ui.loading) TvPill(stringResource(R.string.tv_drive_loading), TvPillTone.Info)
            hint?.let { TvPill(stringResource(if (it == TvDriveOpen.BLOCKED) R.string.tv_drive_blocked else R.string.tv_drive_cannot_open), TvPillTone.Warn) }
        }
        val problem = ui.problem
        when {
            problem != null -> Problem(problem, onRetry)
            ui.stack.atAreas -> Areas(ui.areas, onArea, first)
            ui.items.isEmpty() && !ui.loading -> Text(stringResource(R.string.tv_drive_empty), style = MaterialTheme.typography.bodyLarge, color = colors.muted)
            else -> Items(ui.items, previews, onItem = { n -> hint = onItem(n).takeIf { it == TvDriveOpen.BLOCKED || it == TvDriveOpen.NONE } }, onMore, onPreview, first, more = ui.next != null)
        }
    }
}

/**
 * Focus [this] as soon as its element is there: a lazy grid composes its cards a frame later, a request before that
 * fails. Gives up after a few frames.
 */
internal suspend fun FocusRequester.requestSoon() {
    repeat(FOCUS_FRAMES) {
        if (runCatching { requestFocus() }.getOrDefault(false)) return
        withFrameNanos { }
    }
}

private const val FOCUS_FRAMES = 5

/** Small helpers of the page, kept apart from the composables. */
internal object TvDriveLogicUi {
    fun isPicture(n: DNode): Boolean = !n.isDir && !n.encrypted && DriveLogic.kind(n) == DriveLogic.Kind.IMAGE

    /** The second line of a card: a video's length, a file's size, nothing for a folder. */
    fun detail(n: DNode): String? = when {
        n.isDir -> null
        DriveLogic.kind(n) == DriveLogic.Kind.VIDEO -> DriveLogic.durationText(n.durationMs) ?: DriveLogic.size(n.size)
        else -> DriveLogic.size(n.size)
    }
}

@Composable
private fun Problem(problem: TvDriveProblem, onRetry: () -> Unit) {
    val colors = GateControlTvTheme.colors
    val text = when (problem) {
        TvDriveProblem.OFFLINE -> R.string.tv_drive_offline
        TvDriveProblem.NOT_ON -> R.string.tv_drive_not_on
        TvDriveProblem.NOT_LICENSED -> R.string.tv_drive_not_licensed
        TvDriveProblem.FAILED -> R.string.tv_drive_failed
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(text), style = MaterialTheme.typography.bodyLarge, color = colors.muted)
        if (problem == TvDriveProblem.OFFLINE || problem == TvDriveProblem.FAILED) TvButton(stringResource(R.string.tv_drive_retry), onClick = onRetry)
    }
}

@Composable
private fun Areas(areas: List<DArea>, onArea: (DArea) -> Unit, first: FocusRequester) {
    val colors = GateControlTvTheme.colors
    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        contentPadding = PaddingValues(start = 2.dp, top = 6.dp, bottom = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        itemsIndexed(areas, key = { _, a -> a.id }) { i, area ->
            TvCard(
                width = 150.dp,
                height = 74.dp,
                onClick = { onArea(area) },
                description = area.label,
                modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
            ) {
                Row(Modifier.fillMaxSize().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(TvIcons.Folder, contentDescription = null, tint = colors.accentText, modifier = Modifier.size(20.dp))
                    Column(Modifier.weight(1f)) {
                        Text(area.label, style = MaterialTheme.typography.titleSmall, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(areaKind(area), style = MaterialTheme.typography.bodySmall, color = colors.muted, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun areaKind(area: DArea): String = stringResource(
    when (area.kind) {
        "mine" -> R.string.tv_drive_area_mine
        "family" -> R.string.tv_drive_area_family
        "child" -> R.string.tv_drive_area_child
        else -> R.string.tv_drive_area_team
    },
)

@Composable
private fun Items(
    items: List<DNode>,
    previews: Map<Long, Bitmap>,
    onItem: (DNode) -> Unit,
    onMore: () -> Unit,
    onPreview: (DNode) -> Unit,
    first: FocusRequester,
    more: Boolean,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(COLUMNS),
        contentPadding = PaddingValues(start = 2.dp, top = 6.dp, bottom = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        itemsIndexed(items, key = { _, n -> n.id }) { i, node ->
            LaunchedEffect(node.id) { onPreview(node) }
            ItemCard(
                node,
                previews[node.id],
                onClick = { onItem(node) },
                modifier = (if (i == 0) Modifier.focusRequester(first) else Modifier)
                    .onFocusChanged { if (it.isFocused && more && i >= items.size - COLUMNS * 2) onMore() },
            )
        }
    }
}

@Composable
private fun ItemCard(node: DNode, preview: Bitmap?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = GateControlTvTheme.colors
    val kind = DriveLogic.kind(node)
    TvCard(width = 136.dp, height = 96.dp, onClick = onClick, description = node.name, modifier = modifier) {
        Box(Modifier.fillMaxSize()) {
            if (preview != null) {
                Image(preview.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().background(PictureBg))
            } else {
                val icon = when (kind) {
                    DriveLogic.Kind.FOLDER -> TvIcons.Folder
                    DriveLogic.Kind.IMAGE -> TvIcons.Photo
                    DriveLogic.Kind.VIDEO -> TvIcons.Play
                    else -> TvIcons.File
                }
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (kind == DriveLogic.Kind.FOLDER) colors.accentText else colors.muted,
                    modifier = Modifier.align(Alignment.Center).padding(bottom = 18.dp).size(26.dp),
                )
            }
            if (kind == DriveLogic.Kind.VIDEO && preview != null) {
                Box(
                    Modifier.align(Alignment.Center).size(26.dp).background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50)),
                    contentAlignment = Alignment.Center,
                ) { Icon(TvIcons.Play, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp)) }
            }
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(if (preview != null) Color.Black.copy(alpha = 0.5f) else Color.Transparent)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    node.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (preview != null) Color.White else colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                TvDriveLogicUi.detail(node)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = if (preview != null) Color.White.copy(alpha = 0.8f) else colors.muted, maxLines = 1)
                }
            }
        }
    }
}
