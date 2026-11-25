package com.gatecontrol.android.ui.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.media.MediaTitle
import com.gatecontrol.android.ui.components.GcChevron
import com.gatecontrol.android.ui.components.GcIconSquare
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcListRow
import com.gatecontrol.android.ui.theme.GateControlTheme

/**
 * The card "Weiterschauen" on Start (mockup 2): a tap plays on from where it was left, a long press opens the detail
 * page, "Alle" the Medien tab. Nothing while no source is set up, nothing is left half-way or the card is off.
 */
@Composable
fun MediaStartCard(onDetail: (MediaTitle) -> Unit, onAll: () -> Unit, viewModel: MediaStartViewModel = hiltViewModel()) {
    val resume by viewModel.resume.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    if (resume.isEmpty()) return
    CompositionLocalProvider(LocalMediaPictures provides viewModel.pictures) {
        Column(Modifier.fillMaxWidth().testTag(START_CARD_TAG)) {
            MediaRowHead(stringResource(R.string.media_row_resume), end = stringResource(R.string.media_all), onEnd = onAll, horizontal = 4.dp, top = 0.dp)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(resume, key = { "${it.sourceId}:${it.id}" }) { t ->
                    MediaResumeCard(t, onPlay = { viewModel.play(t) }, onDetail = { onDetail(t) }, width = 180.dp)
                }
            }
        }
    }
}

/**
 * Takes the queues of the media pages ([com.gatecontrol.android.media.MediaPlayRequests]) and plays them
 * ([com.gatecontrol.android.media.MediaPlayback]: the phone's player). Once in the app's navigation.
 */
@Composable
fun MediaPlayHost(viewModel: MediaPlayHostViewModel = hiltViewModel()) {
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.requests.queues.collect { q -> viewModel.requests.playback.play(context, q) }
    }
}

/** "Medien von der NAS einrichten": the way in from the Drive overview and the settings before any source exists. */
@Composable
fun MediaSetupRow(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val x = GateControlTheme.extraColors
    GcListRow(
        title = stringResource(R.string.media_setup_entry),
        description = stringResource(R.string.media_setup_entry_desc),
        onClick = onClick,
        modifier = modifier,
        leading = { GcIconSquare(background = x.accentBg) { Icon(GcIcons.Film, contentDescription = null, tint = x.accentText, modifier = Modifier.size(22.dp)) } },
        trailing = { GcChevron() },
    )
}

/** For the setting's row: "Medien › Quellen" once a source exists. */
@Composable
fun MediaSourcesRow(count: Int, onClick: () -> Unit) {
    val x = GateControlTheme.extraColors
    GcListRow(
        title = stringResource(R.string.media_sources_title),
        description = androidx.compose.ui.res.pluralStringResource(R.plurals.media_sources_count, count, count),
        onClick = onClick,
        leading = { Icon(GcIcons.Film, contentDescription = null, tint = x.accentText, modifier = Modifier.size(22.dp)) },
        trailing = { GcChevron() },
    )
}

internal const val START_CARD_TAG = "media-start-card"
