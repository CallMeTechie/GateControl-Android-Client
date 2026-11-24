package com.gatecontrol.android.tv.ui.input

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/** Robolectric's Compose tests type into Compose fields only: the pages with [TvInputVariant.COMPOSE]. */
@Composable
fun ComposeField(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalTvInputVariant provides TvInputVariant.COMPOSE, content = content)
}
