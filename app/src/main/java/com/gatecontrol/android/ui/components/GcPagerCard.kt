package com.gatecontrol.android.ui.components

import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.R
import com.gatecontrol.android.ui.theme.GateControlTheme

/**
 * Card whose content slides sideways between pages ([pageKeys]) while the
 * frame stays put; small dots below show the page. [title] labels the
 * current page in a small section label above it; null = no header, the
 * content starts at the top. A long press on a page makes it the favourite
 * ([onSetFavorite], "Als Standard festgelegt"): the page shown first —
 * unless [onLongPress] is given, which then runs instead (with
 * [onLongPressLabel] for accessibility), also with a single page.
 * [favoriteKey] null = not loaded yet; once known it is shown unless the
 * person already swiped.
 */
@Composable
fun GcPagerCard(
    pageKeys: List<String>,
    favoriteKey: String?,
    onSetFavorite: (String) -> Unit,
    modifier: Modifier = Modifier,
    title: ((String) -> String)? = null,
    onLongPress: ((String) -> Unit)? = null,
    onLongPressLabel: String? = null,
    page: @Composable (String) -> Unit,
) {
    val extra = GateControlTheme.extraColors
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val keys by rememberUpdatedState(pageKeys)
    val pagerState = rememberPagerState(initialPage = pageKeys.indexOf(favoriteKey).coerceAtLeast(0)) { keys.size }
    var userMoved by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(pagerState) {
        pagerState.interactionSource.interactions.collect { if (it is DragInteraction.Start) userMoved = true }
    }
    // The favourite first: once it is known and its page exists (it may arrive later).
    LaunchedEffect(favoriteKey, pageKeys) {
        if (userMoved || favoriteKey == null) return@LaunchedEffect
        val index = pageKeys.indexOf(favoriteKey)
        if (index >= 0 && index != pagerState.currentPage) pagerState.scrollToPage(index)
    }

    val current = pageKeys.getOrNull(pagerState.currentPage.coerceIn(0, (pageKeys.size - 1).coerceAtLeast(0)))
    val confirm = stringResource(R.string.pager_favorite_set)
    val favoriteAction = stringResource(R.string.pager_favorite_action)
    val multiple = pageKeys.size > 1
    // The favourite only makes sense with several pages; a custom action always.
    val longPress: ((String) -> Unit)? = onLongPress ?: if (multiple) {
        { key ->
            onSetFavorite(key)
            Toast.makeText(context, confirm, Toast.LENGTH_SHORT).show()
        }
    } else {
        null
    }
    val longPressLabel = onLongPressLabel.takeIf { onLongPress != null } ?: favoriteAction
    val pressed: (String) -> Unit = { key ->
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        longPress?.invoke(key)
    }
    val currentPressed by rememberUpdatedState(pressed)

    GcCard(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (title != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                GcSectionLabel(
                    text = current?.let(title).orEmpty(),
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (multiple && current != null && current == favoriteKey) {
                    Icon(
                        GcIcons.StarFilled,
                        contentDescription = stringResource(R.string.pager_favorite_desc),
                        tint = extra.warn,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
        }
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth(),
            pageSpacing = 16.dp,
            verticalAlignment = Alignment.Top,
            key = { index -> pageKeys.getOrElse(index) { index } },
        ) { index ->
            val key = pageKeys.getOrNull(index) ?: return@HorizontalPager
            Box(
                Modifier
                    .fillMaxWidth()
                    .then(
                        if (longPress != null) {
                            Modifier
                                .pointerInput(key) { detectTapGestures(onLongPress = { currentPressed(key) }) }
                                .semantics { onLongClick(label = longPressLabel) { pressed(key); true } }
                        } else {
                            Modifier
                        },
                    ),
            ) { page(key) }
        }
        if (multiple) {
            PageDots(count = pageKeys.size, current = pagerState.currentPage, modifier = Modifier.align(Alignment.CenterHorizontally))
        }
    }
}

/** Small page dots; the current one is a short pill. */
@Composable
fun PageDots(count: Int, current: Int, modifier: Modifier = Modifier) {
    val extra = GateControlTheme.extraColors
    val description = stringResource(R.string.pager_page_desc, current + 1, count)
    Row(
        modifier.semantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { i ->
            val selected = i == current
            val width by animateDpAsState(if (selected) 16.dp else 6.dp, label = "dot")
            val color by animateColorAsState(if (selected) extra.accentText else extra.border2, label = "dotColor")
            Box(
                Modifier
                    .height(6.dp)
                    .width(width)
                    .clip(RoundedCornerShape(3.dp))
                    .background(color),
            )
        }
    }
}
