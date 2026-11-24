package com.gatecontrol.android.ui.media

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.media.MediaTitle
import com.gatecontrol.android.ui.components.GcSubpageBar
import com.gatecontrol.android.ui.components.GcTextField
import com.gatecontrol.android.ui.theme.GateControlTheme

/** "In allen Quellen suchen" (mockup 3): films and series of each Jellyfin server and each share. */
@Composable
fun MediaSearchScreen(onBack: () -> Unit, onOpen: (MediaTitle) -> Unit, viewModel: MediaSearchViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    CompositionLocalProvider(LocalMediaPictures provides viewModel.pictures) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            GcSubpageBar(title = stringResource(R.string.media_search_title), onBack = onBack, backDescription = stringResource(R.string.common_back))
            GcTextField(
                value = ui.query,
                onValueChange = viewModel::setQuery,
                label = stringResource(R.string.media_search_label),
                placeholder = stringResource(R.string.media_search_hint),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).focusRequester(focus),
            )
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 104.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (ui.searching) {
                    item(key = "searching", span = { GridItemSpan(maxLineSpan) }) {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) }
                    }
                }
                val results = ui.results
                if (results != null && results.isEmpty() && !ui.searching) {
                    item(key = "none", span = { GridItemSpan(maxLineSpan) }) {
                        Text(stringResource(R.string.media_search_none), style = MaterialTheme.typography.bodyMedium, color = GateControlTheme.extraColors.muted)
                    }
                }
                items(results.orEmpty(), key = { "${it.sourceId}:${it.id}" }) { t -> MediaPoster(t, onClick = { onOpen(t) }) }
            }
        }
    }
}
