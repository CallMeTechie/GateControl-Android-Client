package com.gatecontrol.android.tv.ui.home

import android.graphics.Bitmap
import android.text.format.DateUtils
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.gatecontrol.android.door.HomeCamera
import com.gatecontrol.android.drive.DNode
import com.gatecontrol.android.drive.DriveLogic
import com.gatecontrol.android.quickactions.QuickActionControlItem
import com.gatecontrol.android.tv.R
import com.gatecontrol.android.tv.gatedrop.TvDropEntry
import com.gatecontrol.android.tv.gatedrop.TvDropKind
import com.gatecontrol.android.tv.gatedrop.TvDropLogic
import com.gatecontrol.android.tv.ui.components.TvPill
import com.gatecontrol.android.tv.ui.components.TvPillTone
import com.gatecontrol.android.tv.home.TvRunNotice
import com.gatecontrol.android.tv.home.TvSetpoint
import com.gatecontrol.android.tv.home.TvTile
import com.gatecontrol.android.tv.ui.theme.GateControlTvTheme
import com.gatecontrol.android.tv.ui.theme.TV_FOCUS_SCALE

/** What the start page shows once paired. */
data class TvStartUi(
    val tiles: List<TvTile>,
    val busy: Set<String>,
    val cameras: List<HomeCamera>,
    val previews: Map<String, Bitmap>,
    val notice: TvRunNotice?,
    val loading: Boolean,
    /** "Zuletzt in Drive": the files opened last (Drive's overview), with their previews. */
    val drive: List<DNode> = emptyList(),
    val drivePreviews: Map<Long, Bitmap> = emptyMap(),
    /** "GateDrop-Eingang": the sends kept on the TV, newest first. */
    val drops: List<TvDropEntry> = emptyList(),
)

/**
 * The rows of the start page (mockup "1 · Start"): Schnellaktionen as tiles from the server's start layout, the
 * cameras with their newest still, Drive still a placeholder (stage 2). D-pad: ◀ ▶ within a row, ▲ ▼ between rows,
 * OK runs. A heating's tile: OK on its "Ziel" opens the target, ◀ ▶ set it in steps, OK or Zurück send it.
 */
@Composable
fun TvStartRows(
    ui: TvStartUi,
    onTile: (TvTile) -> Unit,
    onSetpoint: (TvTile.Device, QuickActionControlItem, Double) -> Unit,
    onCamera: (HomeCamera) -> Unit,
    modifier: Modifier = Modifier,
    onDrive: (DNode) -> Unit = {},
    onDrivePreview: (DNode) -> Unit = {},
    onDrop: (TvDropEntry) -> Unit = {},
    /** The card's picture of a send (its first picture, small); null: none. */
    dropCover: suspend (TvDropEntry) -> Bitmap? = { null },
) {
    val colors = GateControlTvTheme.colors
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        RowTitle(stringResource(R.string.tv_row_quick_actions), ui.notice?.let { noticeText(it) })
        if (ui.tiles.isEmpty()) {
            Text(
                stringResource(if (ui.loading) R.string.tv_tiles_loading else R.string.tv_tiles_none),
                style = MaterialTheme.typography.bodySmall,
                color = colors.muted,
                modifier = Modifier.padding(top = 9.dp, bottom = 14.dp),
            )
        } else {
            LazyRow(
                contentPadding = PaddingValues(start = 2.dp, top = 9.dp, end = 30.dp, bottom = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                itemsIndexed(ui.tiles, key = { _, t -> t.key }) { _, tile ->
                    when (tile) {
                        is TvTile.Action -> ActionTile(tile, busy = tile.item.key in ui.busy, onClick = { onTile(tile) })
                        is TvTile.Device -> DeviceTile(tile, busy = tile.power?.key in ui.busy, onClick = { onTile(tile) }, onSetpoint = onSetpoint)
                    }
                }
            }
        }
        if (ui.cameras.isNotEmpty()) {
            RowTitle(stringResource(R.string.tv_row_cameras), stringResource(R.string.tv_cameras_count, ui.cameras.count { it.online }))
            LazyRow(
                contentPadding = PaddingValues(start = 2.dp, top = 9.dp, end = 30.dp, bottom = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                itemsIndexed(ui.cameras, key = { _, c -> c.id }) { _, cam ->
                    CameraCard(cam, ui.previews[cam.id], onClick = { onCamera(cam) })
                }
            }
        }
        if (ui.drive.isNotEmpty()) {
            RowTitle(stringResource(R.string.tv_row_drive), null)
            LazyRow(
                contentPadding = PaddingValues(start = 2.dp, top = 9.dp, end = 30.dp, bottom = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                itemsIndexed(ui.drive, key = { _, n -> n.id }) { _, node ->
                    LaunchedEffect(node.id) { onDrivePreview(node) }
                    DriveCard(node, ui.drivePreviews[node.id], onClick = { onDrive(node) })
                }
            }
        }
        if (ui.drops.isNotEmpty()) {
            val fresh = TvDropLogic.fresh(ui.drops)
            RowTitle(stringResource(R.string.tv_row_gatedrop), if (fresh > 0) pluralStringResource(R.plurals.tv_drop_new_count, fresh, fresh) else null)
            LazyRow(
                contentPadding = PaddingValues(start = 2.dp, top = 9.dp, end = 30.dp, bottom = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                itemsIndexed(ui.drops.take(START_DROPS), key = { _, e -> e.sendId }) { _, e ->
                    DropCard(e, dropCover, onClick = { onDrop(e) })
                }
            }
        }
    }
}

@Composable
private fun noticeText(n: TvRunNotice): String = when {
    n.message != null -> "${n.title}: ${n.message}"
    n.ok -> stringResource(R.string.tv_run_ok, n.title)
    n.reason == com.gatecontrol.android.quickactions.QuickActionResult.TIMEOUT -> stringResource(R.string.tv_run_timeout, n.title)
    n.reason == com.gatecontrol.android.quickactions.QuickActionResult.BUSY -> stringResource(R.string.tv_run_busy)
    else -> stringResource(R.string.tv_run_failed, n.title)
}

@Composable
private fun RowTitle(title: String, extra: String?) {
    val colors = GateControlTvTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = colors.text)
        if (extra != null) Text(extra, style = MaterialTheme.typography.bodySmall, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A focusable card with the TV focus (ring, glow, zoom). [on]: a switched-on device (green). */
@Composable
internal fun TvCard(
    width: Dp,
    height: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    on: Boolean = false,
    description: String? = null,
    content: @Composable () -> Unit,
) {
    val colors = GateControlTvTheme.colors
    val shape = RoundedCornerShape(9.dp)
    Surface(
        onClick = onClick,
        modifier = modifier.size(width, height).then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (on) colors.accentBg else colors.panel,
            contentColor = colors.text,
            focusedContainerColor = if (on) colors.accentBg else colors.panel2,
            focusedContentColor = colors.text,
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, if (on) colors.accent.copy(alpha = 0.5f) else colors.line), shape = shape),
            focusedBorder = Border(BorderStroke(2.dp, colors.focus), shape = shape),
        ),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = Glow(elevationColor = colors.focusGlow, elevation = 16.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = TV_FOCUS_SCALE),
    ) { content() }
}

private val TileWidth = 118.dp
private val TileHeight = 74.dp

@Composable
private fun ActionTile(tile: TvTile.Action, busy: Boolean, onClick: () -> Unit) {
    val colors = GateControlTvTheme.colors
    val item = tile.item
    val subtitle = when {
        busy -> stringResource(R.string.tv_tile_running)
        item.toggle && item.state == "on" -> stringResource(R.string.tv_tile_on)
        item.toggle && item.state == "off" -> stringResource(R.string.tv_tile_off)
        else -> item.subtitle
    }
    TvCard(TileWidth, TileHeight, onClick = { if (!busy) onClick() }, on = item.isOn, description = listOfNotNull(item.title, subtitle).joinToString(", ")) {
        Column(Modifier.fillMaxSize().padding(9.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Icon(
                TvIcons.fromPath(item.displayIcon, if (tile.door) TvIcons.Door else TvIcons.Bolt),
                contentDescription = null,
                tint = if (item.isOn) colors.accentText else colors.muted,
                modifier = Modifier.size(16.dp),
            )
            Column {
                Text(item.title, style = MaterialTheme.typography.labelMedium, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * A device with a widget: main value (e.g. 21,5°), name, status; the power action on OK. With a target ("Ziel 22°")
 * a second small card beside it takes ◀ ▶ while open.
 */
@Composable
private fun DeviceTile(
    tile: TvTile.Device,
    busy: Boolean,
    onClick: () -> Unit,
    onSetpoint: (TvTile.Device, QuickActionControlItem, Double) -> Unit,
) {
    val colors = GateControlTvTheme.colors
    val widget = tile.group.widget
    val on = tile.power?.isOn == true || widget?.state == "on"
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TvCard(TileWidth, TileHeight, onClick = { if (!busy) onClick() }, on = on, description = listOfNotNull(tile.title, widget?.value, widget?.status).joinToString(", ")) {
            Column(Modifier.fillMaxSize().padding(9.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(TvIcons.fromPath(tile.group.icon ?: widget?.icon), null, tint = if (on) colors.accentText else colors.muted, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.weight(1f))
                    val value = widget?.value
                    if (value != null) {
                        Text(listOfNotNull(value, widget.unit).joinToString(""), style = MaterialTheme.typography.titleSmall, color = colors.text)
                    }
                }
                Column {
                    Text(tile.title, style = MaterialTheme.typography.labelMedium, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val status = if (busy) stringResource(R.string.tv_tile_running) else widget?.status ?: widget?.label
                    if (status != null) Text(status, style = MaterialTheme.typography.bodySmall, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        tile.setpoint?.let { control -> SetpointCard(tile, control, onSetpoint) }
    }
}

/** "Ziel 22,0 °C": OK opens it, ◀ ▶ change it by one step, OK or Zurück close it and send the value. */
@Composable
private fun SetpointCard(tile: TvTile.Device, control: QuickActionControlItem, onSetpoint: (TvTile.Device, QuickActionControlItem, Double) -> Unit) {
    val colors = GateControlTvTheme.colors
    var editing by remember(control.id) { mutableStateOf(false) }
    var value by remember(control.id, control.number) { mutableDoubleStateOf(control.number) }
    fun close() {
        editing = false
        if (TvSetpoint.changed(control, value)) onSetpoint(tile, control, value)
    }
    val label = control.label ?: stringResource(R.string.tv_setpoint)
    TvCard(
        width = 74.dp,
        height = TileHeight,
        onClick = { if (editing) close() else editing = true },
        on = editing,
        description = "$label ${control.format(value)}",
        modifier = Modifier
            .onFocusChanged { if (!it.isFocused && editing) close() }
            .onPreviewKeyEvent { e ->
                if (!editing || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionLeft -> { value = TvSetpoint.moved(control, value, -1); true }
                    Key.DirectionRight -> { value = TvSetpoint.moved(control, value, 1); true }
                    Key.Back, Key.Escape -> { close(); true }
                    else -> false
                }
            },
    ) {
        Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text(if (editing) "◀ $label ▶" else label, style = MaterialTheme.typography.bodySmall, color = if (editing) colors.accentText else colors.muted, maxLines = 1)
            Text(control.format(value), style = MaterialTheme.typography.titleSmall, color = colors.text, maxLines = 1)
        }
    }
}

@Composable
private fun DriveCard(node: DNode, preview: Bitmap?, onClick: () -> Unit) {
    val colors = GateControlTvTheme.colors
    TvCard(126.dp, 71.dp, onClick = onClick, description = node.name) {
        Box(Modifier.fillMaxSize().background(if (preview != null) Color(0xFF0B0F14) else colors.panel)) {
            if (preview != null) {
                Image(preview.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                val icon = when (DriveLogic.kind(node)) {
                    DriveLogic.Kind.FOLDER -> TvIcons.Folder
                    DriveLogic.Kind.IMAGE -> TvIcons.Photo
                    DriveLogic.Kind.VIDEO -> TvIcons.Play
                    else -> TvIcons.File
                }
                Icon(icon, contentDescription = null, tint = colors.muted, modifier = Modifier.align(Alignment.Center).padding(bottom = 14.dp).size(20.dp))
            }
            Box(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(node.name, style = MaterialTheme.typography.labelMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private const val START_DROPS = 12

/** A send in "GateDrop-Eingang" (mockup 1): its picture or kind, "3 Fotos" / its name, "NEU", "von Mila · vor 5 Min.". */
@Composable
private fun DropCard(e: TvDropEntry, cover: suspend (TvDropEntry) -> Bitmap?, onClick: () -> Unit) {
    val colors = GateControlTvTheme.colors
    var picture by remember(e.sendId) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(e.sendId) { if (TvDropLogic.cover(e) != null) picture = cover(e) }
    val summary = TvDropLogic.summary(e)
    val title = when (summary.kind) {
        TvDropKind.PHOTOS -> pluralStringResource(R.plurals.tv_drop_photos, summary.count, summary.count)
        TvDropKind.VIDEOS -> pluralStringResource(R.plurals.tv_drop_videos, summary.count, summary.count)
        TvDropKind.MEDIA -> pluralStringResource(R.plurals.tv_drop_media, summary.count, summary.count)
        TvDropKind.FILES -> pluralStringResource(R.plurals.tv_drop_files, summary.count, summary.count)
        TvDropKind.FILE, TvDropKind.TEXT, TvDropKind.LINK -> summary.name ?: stringResource(R.string.tv_drop_message)
    }
    val from = stringResource(
        R.string.tv_drop_card_from,
        e.from ?: stringResource(R.string.tv_drop_someone),
        DateUtils.getRelativeTimeSpanString(e.at).toString(),
    )
    TvCard(170.dp, 58.dp, onClick = onClick, description = "$title, $from") {
        Row(Modifier.fillMaxSize().padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val square = Modifier.size(42.dp).background(if (picture != null) Color(0xFF0B0F14) else colors.infoBg, RoundedCornerShape(6.dp))
            Box(square, contentAlignment = Alignment.Center) {
                val p = picture
                if (p != null) {
                    Image(p.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    val icon = when (summary.kind) {
                        TvDropKind.PHOTOS -> TvIcons.Photo
                        TvDropKind.VIDEOS, TvDropKind.MEDIA -> TvIcons.Play
                        else -> TvIcons.File
                    }
                    Icon(icon, contentDescription = null, tint = colors.muted, modifier = Modifier.size(18.dp))
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(title, style = MaterialTheme.typography.labelMedium, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (!e.seen) TvPill(stringResource(R.string.tv_drop_badge_new), TvPillTone.Accent, dot = false)
                }
                Text(from, style = MaterialTheme.typography.bodySmall, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun CameraCard(camera: HomeCamera, preview: Bitmap?, onClick: () -> Unit) {
    val colors = GateControlTvTheme.colors
    TvCard(155.dp, 87.dp, onClick = onClick, description = camera.name) {
        Box(Modifier.fillMaxSize().background(Color(0xFF0B0F14))) {
            if (preview != null) {
                Image(preview.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            Box(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(camera.name, style = MaterialTheme.typography.labelMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (!camera.online) {
                Text(
                    stringResource(R.string.tv_camera_offline),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.warn,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                )
            }
        }
    }
}
