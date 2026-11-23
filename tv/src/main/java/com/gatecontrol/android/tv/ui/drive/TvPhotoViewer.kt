package com.gatecontrol.android.tv.ui.drive

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.gatecontrol.android.drive.DNode
import com.gatecontrol.android.tv.R
import com.gatecontrol.android.tv.drive.TvDriveLogic
import com.gatecontrol.android.tv.drive.TvDriveMedia
import com.gatecontrol.android.tv.player.TvPhotoAction
import com.gatecontrol.android.tv.player.TvPhotoKeys
import com.gatecontrol.android.tv.player.TvPlayerKeys
import com.gatecontrol.android.tv.ui.components.TvPill
import com.gatecontrol.android.tv.ui.components.TvPillTone
import kotlinx.coroutines.delay

/** How long a picture of the slide show stays (the photo frame has its own interval). */
private const val SLIDE_MS = 6_000L

/**
 * A picture of [items] over the whole screen: ◀ ▶ the previous and next one, OK / ⏯ the slide show, Zurück closes.
 * The name and "3 / 12" fade after a few seconds. [document]: a document's preview (no list, no slide show).
 */
@Composable
fun TvPhotoViewer(
    items: List<DNode>,
    at: Int,
    media: TvDriveMedia,
    onAt: (Int) -> Unit,
    onClose: () -> Unit,
    document: Boolean = false,
) {
    val node = items[at]
    var picture by remember(node.id) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(node.id) { mutableStateOf(false) }
    var slideshow by remember { mutableStateOf(false) }
    var shown by remember { mutableIntStateOf(0) }
    var infoVisible by remember { mutableStateOf(true) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(node.id) {
        picture = media.peek(node, large = true)
        if (picture == null) picture = media.picture(node)
        failed = picture == null
        // the next one in the background, so ▶ shows it at once
        items.getOrNull(at + 1)?.let { media.picture(it) }
    }
    LaunchedEffect(shown) {
        infoVisible = true
        delay(TvPlayerKeys.CONTROLS_HIDE_MS)
        infoVisible = false
    }
    LaunchedEffect(slideshow, at) {
        if (!slideshow) return@LaunchedEffect
        delay(SLIDE_MS)
        if (at < items.size - 1) onAt(at + 1) else slideshow = false
    }
    LaunchedEffect(Unit) { focus.requestSoon() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (TvPhotoKeys.action(e.nativeKeyEvent.keyCode)) {
                    TvPhotoAction.PREVIOUS -> onAt(TvDriveLogic.step(at, -1, items.size)).also { shown++ }
                    TvPhotoAction.NEXT -> onAt(TvDriveLogic.step(at, 1, items.size)).also { shown++ }
                    TvPhotoAction.SLIDESHOW -> if (!document) {
                        slideshow = !slideshow
                        shown++
                    }
                    TvPhotoAction.BACK -> onClose()
                    null -> return@onKeyEvent false
                }
                true
            },
    ) {
        picture?.let {
            Image(it.asImageBitmap(), contentDescription = node.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
        if (picture == null) {
            Text(
                stringResource(if (failed) R.string.tv_viewer_failed else R.string.tv_drive_loading),
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        if (infoVisible) {
            Row(
                Modifier.align(Alignment.TopStart).padding(24.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TvPill(node.name, TvPillTone.Neutral, dot = false)
                if (items.size > 1) TvPill("${at + 1} / ${items.size}", TvPillTone.Neutral, dot = false)
                if (document) TvPill(stringResource(R.string.tv_viewer_preview_only), TvPillTone.Info, dot = false)
                if (slideshow) TvPill(stringResource(R.string.tv_viewer_slideshow), TvPillTone.Accent)
            }
        }
    }
}
