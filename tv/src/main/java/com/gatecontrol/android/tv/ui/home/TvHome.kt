package com.gatecontrol.android.tv.ui.home

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.text.format.DateFormat
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.SelectableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.gatecontrol.android.tv.R
import com.gatecontrol.android.tv.ui.theme.GateControlTvTheme
import com.gatecontrol.android.tv.ui.theme.TV_FOCUS_SCALE
import java.util.Date

/** The pages of the rail on the left (mockup "GateControl TV", start page). */
internal enum class TvDestination(@param:StringRes val label: Int, val icon: ImageVector) {
    Start(R.string.tv_nav_start, TvIcons.Home),
    Cameras(R.string.tv_nav_cameras, TvIcons.Cameras),
    Drive(R.string.tv_nav_drive, TvIcons.Drive),
    GateDrop(R.string.tv_nav_gatedrop, TvIcons.GateDrop),
    Family(R.string.tv_nav_family, TvIcons.Family),
    Settings(R.string.tv_nav_settings, TvIcons.Settings),
}

/** One placeholder row of the start page: its title, how many cards and their size. */
internal data class PlaceholderRow(@param:StringRes val title: Int, val count: Int, val width: Dp, val height: Dp)

/**
 * Rows of the start page in the order of the mockup. Stage 0 only shows empty, focusable cards; quick actions
 * (server start layout), cameras and Drive fill them from stage 1 on.
 */
internal val StartRows = listOf(
    PlaceholderRow(R.string.tv_row_quick_actions, count = 6, width = 104.dp, height = 70.dp),
    PlaceholderRow(R.string.tv_row_cameras, count = 4, width = 155.dp, height = 87.dp),
    PlaceholderRow(R.string.tv_row_drive, count = 5, width = 112.dp, height = 63.dp),
)

// Layout of the mockup, which measures in u = 1/96 of the screen width: 10 dp on a 960 dp wide TV (1080p).
private val RailWidth = 56.dp
private val ContentStart = 75.dp

/** Start screen of GateControl TV: rail on the left, status bar on top, rows of cards. */
@Composable
fun TvHome(modifier: Modifier = Modifier) {
    var current by rememberSaveable { mutableStateOf(TvDestination.Start) }
    val colors = GateControlTvTheme.colors
    val startFocus = remember { FocusRequester() }

    Box(modifier.fillMaxSize().background(colors.bg)) {
        Column(Modifier.fillMaxSize().padding(start = ContentStart)) {
            TopBar(Modifier.padding(top = 18.dp, end = 30.dp))
            Spacer(Modifier.height(20.dp))
            if (current == TvDestination.Start) {
                StartPage()
            } else {
                Text(
                    text = stringResource(R.string.tv_page_soon, stringResource(current.label)),
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.muted,
                )
            }
        }
        Rail(
            current = current,
            onSelect = { current = it },
            startFocus = startFocus,
            modifier = Modifier.align(Alignment.CenterStart),
        )
    }
    // The remote starts on the rail's current page, as in the mockup.
    LaunchedEffect(Unit) { startFocus.requestFocus() }
}

@Composable
private fun Rail(
    current: TvDestination,
    onSelect: (TvDestination) -> Unit,
    startFocus: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val colors = GateControlTvTheme.colors
    Column(
        modifier
            .width(RailWidth)
            .fillMaxHeight()
            .background(colors.rail)
            .drawBehind {
                drawLine(colors.line, Offset(size.width, 0f), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
            }
            .padding(top = 52.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        for (destination in TvDestination.entries) {
            val label = stringResource(destination.label)
            val shape = RoundedCornerShape(9.dp)
            val ring = Border(BorderStroke(2.dp, colors.focus), shape = shape)
            Surface(
                selected = destination == current,
                onClick = { onSelect(destination) },
                modifier = Modifier
                    .size(32.dp)
                    .semantics { contentDescription = label }
                    .then(if (destination == TvDestination.Start) Modifier.focusRequester(startFocus) else Modifier),
                shape = SelectableSurfaceDefaults.shape(shape),
                colors = SelectableSurfaceDefaults.colors(
                    containerColor = Color.Transparent,
                    contentColor = colors.faint,
                    focusedContainerColor = colors.panel2,
                    focusedContentColor = colors.text,
                    selectedContainerColor = colors.accentBg,
                    selectedContentColor = colors.accentText,
                    focusedSelectedContainerColor = colors.accentBg,
                    focusedSelectedContentColor = colors.accentText,
                ),
                border = SelectableSurfaceDefaults.border(focusedBorder = ring, focusedSelectedBorder = ring),
                scale = SelectableSurfaceDefaults.scale(focusedScale = 1f),
            ) {
                Icon(
                    imageVector = destination.icon,
                    contentDescription = null,
                    modifier = Modifier.align(Alignment.Center).size(15.dp),
                )
            }
        }
    }
}

@Composable
private fun TopBar(modifier: Modifier = Modifier) {
    val colors = GateControlTvTheme.colors
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier.size(22.dp).background(colors.accent, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(TvIcons.Mark, contentDescription = null, tint = colors.onAccent, modifier = Modifier.size(14.dp))
        }
        Text(stringResource(R.string.tv_brand), style = MaterialTheme.typography.titleMedium, color = colors.text)
        // Stage 1: "VPN verbunden · Zuhause" and the device name once the TV is paired.
        Pill(stringResource(R.string.tv_status_not_paired))
        Spacer(Modifier.weight(1f))
        Clock()
    }
}

@Composable
private fun Pill(text: String) {
    val colors = GateControlTvTheme.colors
    Row(
        Modifier.height(22.dp).background(colors.panel2, RoundedCornerShape(6.dp)).padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(6.dp).background(colors.muted, CircleShape))
        Text(text, style = MaterialTheme.typography.labelMedium, color = colors.muted)
    }
}

/** Time of day in the system's 12/24-hour format, updated by the minute tick (no coroutine loop). */
@Composable
private fun Clock() {
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                now = System.currentTimeMillis()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        // Protected broadcasts only the system sends: exported is safe and needs no extra permission on Android 9.
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
    val text = remember(now) { DateFormat.getTimeFormat(context).format(Date(now)) }
    Text(text, style = MaterialTheme.typography.headlineSmall, color = GateControlTvTheme.colors.muted)
}

@Composable
private fun StartPage() {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        for (row in StartRows) {
            Text(
                text = stringResource(row.title),
                style = MaterialTheme.typography.titleMedium,
                color = GateControlTvTheme.colors.text,
            )
            LazyRow(
                // Room for the zoom and glow of a focused card
                contentPadding = PaddingValues(start = 2.dp, top = 9.dp, end = 30.dp, bottom = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                items(row.count) { PlaceholderCard(row.width, row.height) }
            }
        }
    }
}

/** An empty card of the start page: focusable with the TV focus (ring, glow, zoom), no action yet. */
@Composable
private fun PlaceholderCard(width: Dp, height: Dp) {
    val colors = GateControlTvTheme.colors
    val shape = RoundedCornerShape(9.dp)
    Surface(
        onClick = {},
        modifier = Modifier.size(width, height),
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = colors.panel,
            contentColor = colors.faint,
            focusedContainerColor = colors.panel2,
            focusedContentColor = colors.muted,
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, colors.line), shape = shape),
            focusedBorder = Border(BorderStroke(2.dp, colors.focus), shape = shape),
        ),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = Glow(elevationColor = colors.focusGlow, elevation = 16.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = TV_FOCUS_SCALE),
    ) {
        Text(
            text = stringResource(R.string.tv_placeholder),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.align(Alignment.BottomStart).padding(9.dp),
        )
    }
}
