package com.gatecontrol.android.ui.infocard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
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
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcDot
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIconSquare
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcPagerCard
import com.gatecontrol.android.ui.theme.GateControlTheme
import kotlinx.coroutines.delay

/** How often the tiles are asked for while the start tab is visible. */
private const val REFRESH_MS = 60_000L

/**
 * "Info" card of the start tab (below the quick actions): the contents the
 * person chose (today: the plugins' start tiles) slide sideways, a long
 * press makes one the slide shown first, the pencil opens the picker
 * ([onEdit]). Without any choice only "Inhalte auswählen"; hidden on older
 * servers and when there are no tiles. Refreshed when the tab becomes
 * visible and every 60 s while it is.
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
    val title = stringResource(R.string.info_card_title)

    when (val v = view) {
        InfoCardView.Hidden -> Unit
        InfoCardView.Choose -> GcCard(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            TextButton(onClick = onEdit) {
                Icon(GcIcons.Plus, contentDescription = null, tint = extra.accentText, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.info_card_choose), color = extra.accentText, style = MaterialTheme.typography.labelLarge)
            }
        }
        is InfoCardView.Slides -> {
            val slides = v.slides
            val selection by viewModel.selection.collectAsStateWithLifecycle()
            GcPagerCard(
                pageKeys = slides.map { it.id },
                // The favourite once the choice is read, else the first slide.
                favoriteKey = selection?.let { s -> s.favorite ?: slides.first().id },
                onSetFavorite = viewModel::setFavorite,
                title = { title },
                modifier = modifier,
                headerActions = {
                    GcIconButton(
                        icon = GcIcons.Edit,
                        contentDescription = stringResource(R.string.info_card_edit),
                        onClick = onEdit,
                        size = 36.dp,
                        iconSize = 18.dp,
                    )
                },
            ) { id ->
                when (val slide = slides.firstOrNull { it.id == id }) {
                    is InfoSlide.Tile -> TileSlide(slide.tile)
                    null -> Unit
                }
            }
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
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                tile.value ?: "—",
                style = MaterialTheme.typography.displayMedium,
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
