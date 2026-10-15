package com.gatecontrol.android.ui.infocard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.infocard.InfoCardLogic
import com.gatecontrol.android.infocard.InfoSelection
import com.gatecontrol.android.infocard.InfoSlide
import com.gatecontrol.android.infocard.InfoTile
import com.gatecontrol.android.infocard.InfoTilesState
import com.gatecontrol.android.quickactions.QuickActionIcons
import com.gatecontrol.android.ui.components.GcBanner
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIconSquare
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcListRow
import com.gatecontrol.android.ui.components.GcSectionLabel
import com.gatecontrol.android.ui.components.GcSubpageBar
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.theme.GateControlTheme

/**
 * "Inhalte der Info-Karte": what the start tab's Info card shows. On top
 * the chosen contents in their order (arrows move them), below everything
 * available, grouped by plugin, with checkboxes. Stored per login.
 */
@Composable
fun InfoCardPickerScreen(onBack: () -> Unit, viewModel: InfoCardViewModel = hiltViewModel()) {
    val tiles by viewModel.tiles.collectAsStateWithLifecycle()
    val selectionOrNull by viewModel.selection.collectAsStateWithLifecycle()
    val selection = selectionOrNull ?: InfoSelection()
    val extra = GateControlTheme.extraColors

    LaunchedEffect(Unit) { viewModel.refresh() }

    val available = tiles.slides
    val chosen = InfoCardLogic.chosen(available, selection)
    val chosenIds = chosen.map { it.id }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            GcSubpageBar(
                title = stringResource(R.string.info_card_picker_title),
                onBack = onBack,
                backDescription = stringResource(R.string.common_back),
            )
        }
        item {
            Text(
                stringResource(R.string.info_card_picker_hint),
                style = MaterialTheme.typography.bodySmall,
                color = extra.muted,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        val notice = when {
            tiles.status == InfoTilesState.Status.UNSUPPORTED -> R.string.info_card_unsupported
            tiles.status == InfoTilesState.Status.FAILED && available.isEmpty() -> R.string.info_card_failed
            tiles.status == InfoTilesState.Status.LOADED && available.isEmpty() -> R.string.info_card_none
            else -> null
        }
        if (tiles.status == InfoTilesState.Status.UNKNOWN && available.isEmpty()) {
            item {
                Row(Modifier.fillMaxWidth().padding(32.dp), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        if (notice != null) {
            item {
                GcBanner(
                    tone = if (tiles.status == InfoTilesState.Status.FAILED) GcTone.Warn else GcTone.Info,
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    Text(stringResource(notice), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }

        // --- Chosen, in order ------------------------------------------------
        if (chosen.isNotEmpty()) {
            item(key = "h:chosen") {
                GcSectionLabel(stringResource(R.string.info_card_picker_chosen), Modifier.padding(start = 20.dp, top = 4.dp))
            }
            item(key = "c:chosen") {
                GcCard(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    contentPadding = PaddingValues(vertical = 6.dp),
                    verticalArrangement = Arrangement.Top,
                ) {
                    chosen.forEachIndexed { index, slide ->
                        when (slide) {
                            is InfoSlide.Tile -> GcListRow(
                                title = slide.tile.title,
                                description = slide.tile.pluginName,
                                leading = { TileIcon(slide.tile) },
                                trailing = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        GcIconButton(
                                            icon = GcIcons.ChevronUp,
                                            contentDescription = stringResource(R.string.info_card_move_up, slide.tile.title),
                                            onClick = { viewModel.move(slide.id, -1, chosenIds) },
                                            enabled = index > 0,
                                            tint = if (index > 0) extra.muted else extra.faint.copy(alpha = 0.4f),
                                            size = 40.dp,
                                            iconSize = 20.dp,
                                        )
                                        GcIconButton(
                                            icon = GcIcons.ChevronDown,
                                            contentDescription = stringResource(R.string.info_card_move_down, slide.tile.title),
                                            onClick = { viewModel.move(slide.id, 1, chosenIds) },
                                            enabled = index < chosen.lastIndex,
                                            tint = if (index < chosen.lastIndex) extra.muted else extra.faint.copy(alpha = 0.4f),
                                            size = 40.dp,
                                            iconSize = 20.dp,
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }

        // --- Everything available, by plugin ---------------------------------
        available.filterIsInstance<InfoSlide.Tile>().groupBy { it.tile.pluginName }.forEach { (plugin, slides) ->
            item(key = "h:$plugin") {
                GcSectionLabel(plugin, Modifier.padding(start = 20.dp, top = 4.dp))
            }
            item(key = "c:$plugin") {
                GcCard(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    contentPadding = PaddingValues(vertical = 6.dp),
                    verticalArrangement = Arrangement.Top,
                ) {
                    slides.forEach { slide ->
                        val checked = slide.id in selection.ids
                        val full = !checked && selection.ids.size >= InfoCardLogic.MAX_SELECTED
                        GcListRow(
                            title = slide.tile.title,
                            description = listOfNotNull(
                                slide.tile.section,
                                slide.tile.value?.let { v -> listOfNotNull(v, slide.tile.unit).joinToString(" ") },
                            ).joinToString(" · ").ifBlank { null },
                            onClick = if (full) null else { { viewModel.toggle(slide.id) } },
                            leading = { TileIcon(slide.tile) },
                            trailing = {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = null,
                                    enabled = !full,
                                    colors = CheckboxDefaults.colors(
                                        checkedColor = MaterialTheme.colorScheme.primary,
                                        checkmarkColor = MaterialTheme.colorScheme.onPrimary,
                                        uncheckedColor = extra.border2,
                                    ),
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TileIcon(tile: InfoTile) {
    val (tint, background) = stateColors(tile.state)
    val icon = remember(tile.icon) { QuickActionIcons.vectorOrNull(tile.icon) ?: GcIcons.Activity }
    GcIconSquare(size = 40.dp, background = background) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
    }
}
