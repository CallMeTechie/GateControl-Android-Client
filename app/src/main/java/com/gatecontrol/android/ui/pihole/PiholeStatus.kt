package com.gatecontrol.android.ui.pihole

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.gatecontrol.android.R

/** Maps a Pi-hole blocking state to its localized label. Shared by the screen + home card. */
@Composable
internal fun piholeStatusLabel(state: String): String = when (state) {
    "enabled" -> stringResource(R.string.pihole_status_enabled)
    "disabled" -> stringResource(R.string.pihole_status_disabled)
    "partial" -> stringResource(R.string.pihole_status_partial)
    else -> stringResource(R.string.pihole_status_unknown)
}
