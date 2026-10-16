package com.gatecontrol.android.ui.infocard

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.infocard.InfoCardView
import com.gatecontrol.android.infocard.InfoSlide
import com.gatecontrol.android.infocard.InfoTile
import com.gatecontrol.android.infocard.TileState
import com.gatecontrol.android.quickactions.QuickActionIcons
import com.gatecontrol.android.quickactions.QuickActionReadingItem
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcChevron
import com.gatecontrol.android.ui.components.GcDot
import com.gatecontrol.android.ui.components.GcIconSquare
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcListRow
import com.gatecontrol.android.ui.components.GcPagerCard
import com.gatecontrol.android.ui.components.GcRowDivider
import com.gatecontrol.android.ui.theme.GateControlTheme
import kotlinx.coroutines.delay

/** How often the tiles are asked for while the start tab is visible. */
private const val REFRESH_MS = 60_000L

/**
 * Info card of the start tab (below the quick actions): the contents the
 * person chose (today: the plugins' start tiles) slide sideways — no
 * heading, no buttons. A long press on the card opens a small menu from
 * below: show this page first, or the picker ([onEdit]). Without any choice
 * only "Inhalte auswählen"; hidden on older servers and when there are no
 * tiles. Refreshed when the tab becomes visible and every 60 s while it is.
 */
@Composable
fun InfoCard(
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: InfoCardViewModel = hiltViewModel(),
) {
    val view by viewModel.view.collectAsStateWithLifecycle()
    val extra = GateControlTheme.extraColors
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                viewModel.refresh()
                delay(REFRESH_MS)
            }
        }
    }

    when (val v = view) {
        InfoCardView.Hidden -> Unit
        InfoCardView.Choose -> GcCard(modifier = modifier, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
            TextButton(onClick = onEdit) {
                Icon(GcIcons.Plus, contentDescription = null, tint = extra.accentText, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.info_card_choose), color = extra.accentText, style = MaterialTheme.typography.labelLarge)
            }
        }
        is InfoCardView.Slides -> {
            val slides = v.slides
            val selection by viewModel.selection.collectAsStateWithLifecycle()
            // The favourite once the choice is read, else the first slide.
            val favorite = selection?.let { s -> s.favorite ?: slides.first().id }
            // The slide whose long-press menu is open.
            var menuFor by rememberSaveable { mutableStateOf<String?>(null) }
            GcPagerCard(
                pageKeys = slides.map { it.id },
                favoriteKey = favorite,
                onSetFavorite = viewModel::setFavorite,
                modifier = modifier,
                onLongPress = { menuFor = it },
                onLongPressLabel = stringResource(R.string.info_card_edit),
            ) { id ->
                when (val slide = slides.firstOrNull { it.id == id }) {
                    is InfoSlide.Tile -> TileSlide(slide.tile)
                    null -> Unit
                }
            }
            val open = menuFor
            val index = slides.indexOfFirst { it.id == open }
            if (open != null && index >= 0) {
                InfoCardMenu(
                    slide = slides[index],
                    page = index + 1,
                    pageCount = slides.size,
                    isFavorite = open == favorite,
                    onShowFirst = { viewModel.setFavorite(open); menuFor = null },
                    onPick = { menuFor = null; onEdit() },
                    onDismiss = { menuFor = null },
                )
            }
        }
    }
}

/**
 * Menu from below after a long press on the Info card: the pressed slide
 * as its head, "Diese Seite zuerst zeigen" (off when it already is) and
 * "Inhalte und Reihenfolge wählen" (the picker).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InfoCardMenu(
    slide: InfoSlide,
    page: Int,
    pageCount: Int,
    isFavorite: Boolean,
    onShowFirst: () -> Unit,
    onPick: () -> Unit,
    onDismiss: () -> Unit,
) {
    val extra = GateControlTheme.extraColors
    val context = LocalContext.current
    val confirm = stringResource(R.string.pager_favorite_set)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(Modifier.padding(bottom = 24.dp)) {
            when (slide) {
                is InfoSlide.Tile -> {
                    val tile = slide.tile
                    val (tint, background) = stateColors(tile.state)
                    val icon = remember(tile.icon) { QuickActionIcons.vectorOrNull(tile.icon) ?: GcIcons.Activity }
                    Row(
                        Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        GcIconSquare(size = 36.dp, background = background) {
                            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
                        }
                        Column {
                            Text(tile.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOfNotNull(tile.pluginName, stringResource(R.string.pager_page_desc, page, pageCount)).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = extra.muted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            GcListRow(
                title = stringResource(R.string.info_card_menu_first),
                description = if (isFavorite) stringResource(R.string.info_card_menu_first_done) else null,
                onClick = if (isFavorite) null else {
                    {
                        onShowFirst()
                        Toast.makeText(context, confirm, Toast.LENGTH_SHORT).show()
                    }
                },
                leading = {
                    Icon(
                        if (isFavorite) GcIcons.StarFilled else GcIcons.Star,
                        contentDescription = null,
                        tint = extra.warn,
                        modifier = Modifier.size(22.dp),
                    )
                },
            )
            GcRowDivider(Modifier.padding(horizontal = 16.dp))
            GcListRow(
                title = stringResource(R.string.info_card_menu_pick),
                onClick = onPick,
                leading = { Icon(GcIcons.Sliders, contentDescription = null, tint = extra.muted, modifier = Modifier.size(22.dp)) },
                trailing = { GcChevron() },
            )
        }
    }
}

/** One plugin tile: icon, title and plugin, the big value with its unit; the state colours the icon. */
@Composable
private fun TileSlide(tile: InfoTile) {
    val extra = GateControlTheme.extraColors
    val (tint, background) = stateColors(tile.state)
    val icon = remember(tile.icon) { QuickActionIcons.vectorOrNull(tile.icon) ?: GcIcons.Activity }
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GcIconSquare(size = 44.dp, background = background) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    tile.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(tile.pluginName, tile.section).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = extra.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (tile.state != null) GcDot(tint)
        }
        if (tile.items.isNotEmpty()) {
            TileItems(tile.items)
            return@Column
        }
        Row(verticalAlignment = Alignment.Bottom) {
            val value = tile.value ?: "—"
            Text(
                value,
                // A long text gets smaller instead of being cut off.
                style = if (value.length > 10) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.displayMedium,
                color = when (tile.state) {
                    TileState.WARN, TileState.CRIT -> tint
                    else -> MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (tile.unit != null && tile.value != null) {
                Text(
                    tile.unit,
                    style = MaterialTheme.typography.titleMedium,
                    color = extra.muted,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 6.dp, bottom = 6.dp),
                )
            }
        }
    }
}

/**
 * The tile's icon values in a row (core 1.172+): an icon (in its state's
 * colour) and a value with unit, e.g. battery 78 %, range 312 km; an icon
 * alone is a state symbol in a tinted circle, e.g. a green closed lock.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TileItems(items: List<QuickActionReadingItem>) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        items.forEach { item ->
            val icon = remember(item.icon) { item.icon?.let { QuickActionIcons.vectorOrNull(it) } }
            val (tint, background) = stateColors(TileState.fromWire(item.state))
            val description = listOfNotNull(item.label, item.value?.let { v -> item.unit?.let { "$v $it" } ?: v }).joinToString(": ")
            if (item.value == null && icon != null) {
                Box(
                    Modifier.size(38.dp).clip(CircleShape).background(background).semantics { contentDescription = description },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
                }
            } else {
                Row(
                    Modifier.semantics(mergeDescendants = true) { contentDescription = description },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (icon != null) {
                        Icon(icon, contentDescription = null, tint = if (item.state != null) tint else GateControlTheme.extraColors.muted, modifier = Modifier.size(22.dp))
                    }
                    Text(
                        listOfNotNull(item.value, item.unit).joinToString(" "),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** Accent of a tile's state: good green, warn amber, crit red, on the accent; off and none neutral. */
@Composable
internal fun stateColors(state: TileState?): Pair<Color, Color> {
    val extra = GateControlTheme.extraColors
    val scheme = MaterialTheme.colorScheme
    return when (state) {
        TileState.GOOD -> extra.accentText to extra.accentBg
        TileState.ON -> scheme.primary to extra.accentBg
        TileState.WARN -> extra.warn to extra.warnBg
        TileState.CRIT -> scheme.error to extra.errorBg
        TileState.OFF -> extra.muted to extra.panel2
        null -> scheme.onSurface to extra.panel2
    }
}
