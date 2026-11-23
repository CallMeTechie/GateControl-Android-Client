package com.gatecontrol.android.tv.ui.cameras

import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Border
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.SelectableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.gatecontrol.android.door.HomeCamera
import com.gatecontrol.android.tv.R
import com.gatecontrol.android.tv.cameras.TvLiveUi
import com.gatecontrol.android.tv.ui.components.TvPill
import com.gatecontrol.android.tv.ui.components.TvPillTone
import com.gatecontrol.android.tv.ui.theme.GateControlTvTheme

private val PictureBg = Color(0xFF0B0F14)

/**
 * The page "Kameras": the home cameras on the left (▲ ▼, OK shows one), the chosen one live on the right — HLS over
 * Media3 in the VPN or the server's LAN like the phone app, else a still every few seconds.
 */
@Composable
fun TvCamerasPage(
    cameras: List<HomeCamera>,
    live: TvLiveUi,
    sourceFactory: () -> DataSource.Factory?,
    onShow: (String) -> Unit,
    onLeave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GateControlTvTheme.colors
    LaunchedEffect(cameras.isNotEmpty()) { if (cameras.isNotEmpty() && live.cameraId == null) onShow(cameras.first().id) }
    DisposableEffect(Unit) { onDispose { onLeave() } }
    if (cameras.isEmpty()) {
        Text(stringResource(R.string.tv_cameras_none), style = MaterialTheme.typography.bodyLarge, color = colors.muted)
        return
    }
    Row(modifier.fillMaxSize().padding(end = 30.dp, bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(Modifier.width(170.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            for (cam in cameras) CameraItem(cam, selected = cam.id == live.cameraId, onClick = { onShow(cam.id) })
        }
        val name = cameras.firstOrNull { it.id == live.cameraId }?.name.orEmpty()
        TvLivePicture(live, sourceFactory, name, Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(10.dp)))
    }
}

@Composable
private fun CameraItem(camera: HomeCamera, selected: Boolean, onClick: () -> Unit) {
    val colors = GateControlTvTheme.colors
    val shape = RoundedCornerShape(7.dp)
    val ring = Border(BorderStroke(2.dp, colors.focus), shape = shape)
    Surface(
        selected = selected,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(34.dp),
        shape = SelectableSurfaceDefaults.shape(shape),
        colors = SelectableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = colors.muted,
            focusedContainerColor = colors.panel2,
            focusedContentColor = colors.text,
            selectedContainerColor = colors.panel2,
            selectedContentColor = colors.text,
            focusedSelectedContainerColor = colors.panel2,
            focusedSelectedContentColor = colors.text,
        ),
        border = SelectableSurfaceDefaults.border(focusedBorder = ring, focusedSelectedBorder = ring),
        scale = SelectableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(camera.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (!camera.online) Text(stringResource(R.string.tv_camera_offline), style = MaterialTheme.typography.bodySmall, color = colors.warn)
        }
    }
}

/** The live picture: the video once its first frame is there, else the newest still; "LIVE" and the name on top. */
@OptIn(UnstableApi::class)
@Composable
fun TvLivePicture(live: TvLiveUi, sourceFactory: () -> DataSource.Factory?, name: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val hls = live.hlsUrl
    var showingVideo by remember(live.cameraId) { mutableStateOf(false) }
    val player = remember(live.cameraId, hls != null) {
        if (hls == null) null
        else sourceFactory()?.let { factory ->
            ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(factory)).build().apply { volume = 0f }
        }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                showingVideo = true
            }
        }
        player?.addListener(listener)
        onDispose {
            player?.removeListener(listener)
            player?.release()
        }
    }
    // The first list: start; a renewed token: the data source reads the newest one by itself.
    LaunchedEffect(player) {
        val url = live.hlsUrl ?: return@LaunchedEffect
        player?.setMediaItem(MediaItem.fromUri(url))
        player?.prepare()
        player?.playWhenReady = true
    }
    Box(modifier.background(PictureBg)) {
        if (player != null) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                        setKeepContentOnPlayerReset(true)
                    }
                },
                update = { it.player = player },
                onRelease = { it.player = null },
                modifier = Modifier.fillMaxSize(),
            )
        }
        val still = live.still
        if (!showingVideo && still != null) {
            Image(still.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
        if (!showingVideo && still == null) {
            Text(
                stringResource(if (live.failed) R.string.tv_camera_failed else R.string.tv_camera_connecting),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        Row(Modifier.align(Alignment.TopStart).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (showingVideo) TvPill(stringResource(R.string.tv_camera_live), TvPillTone.Warn)
            else if (still != null && hls == null) TvPill(stringResource(R.string.tv_camera_still_every, live.every), TvPillTone.Neutral, dot = false)
            if (name.isNotEmpty()) TvPill(name, TvPillTone.Neutral, dot = false)
        }
    }
}
