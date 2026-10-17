package com.gatecontrol.android.ui.chat

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.R
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIcons
import kotlinx.coroutines.launch

/** A chat picture full screen: pinch to zoom, drag, double tap; share it to another app. */
@Composable
fun ChatImageViewer(mediaId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val store = rememberChatMediaStore()
    val scope = rememberCoroutineScope()
    var failed by remember { mutableStateOf(false) }
    val bitmap by produceState<ImageBitmap?>(null, mediaId) {
        value = store?.bitmap(mediaId, 2048)?.asImageBitmap()
        if (value == null) failed = true
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val transform = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 6f)
        offset = if (scale == 1f) Offset.Zero else offset + pan
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val bmp = bitmap
        when {
            bmp != null -> Image(
                bmp,
                contentDescription = stringResource(R.string.chat_kind_image),
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = {
                            scale = if (scale > 1f) 1f else 2.5f
                            offset = Offset.Zero
                        })
                    }
                    .transformable(transform)
                    .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
            )
            failed -> Text(
                stringResource(R.string.chat_err_download),
                color = Color.White,
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            )
            else -> CircularProgressIndicator(Modifier.align(Alignment.Center).size(36.dp), color = Color.White)
        }
        Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            GcIconButton(GcIcons.Back, stringResource(R.string.chat_back), onClick = onBack, tint = Color.White)
            Box(Modifier.weight(1f))
            GcIconButton(Icons.Outlined.Share, stringResource(R.string.chat_share_file), tint = Color.White, onClick = {
                scope.launch {
                    val f = store?.file(mediaId)
                    if (f == null) Toast.makeText(context, R.string.chat_err_download, Toast.LENGTH_LONG).show()
                    else openFile(context, f, "bild-$mediaId.webp".take(40), "image/*", share = true)
                }
            })
        }
    }
}
