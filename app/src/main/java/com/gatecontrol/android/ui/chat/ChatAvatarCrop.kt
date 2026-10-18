package com.gatecontrol.android.ui.chat

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.gatecontrol.android.R
import com.gatecontrol.android.profile.AvatarCrop
import com.gatecontrol.android.profile.CropRect
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcPrimaryButton

/**
 * Square crop of a new group picture (the same simple crop as the profile
 * picture, [AvatarCrop]): the photo covers the frame, pinch to zoom, drag
 * to move; the circle shows what the group avatar will show.
 */
@Composable
fun ChatAvatarCropDialog(source: Bitmap, saving: Boolean, onDone: (CropRect) -> Unit, onCancel: () -> Unit) {
    var zoom by remember(source) { mutableFloatStateOf(1f) }
    var offsetX by remember(source) { mutableFloatStateOf(0f) }
    var offsetY by remember(source) { mutableFloatStateOf(0f) }
    var framePx by remember(source) { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val image = remember(source) { source.asImageBitmap() }

    Dialog(
        onDismissRequest = { if (!saving) onCancel() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Column(
            Modifier.fillMaxSize().background(Color(0xF2000000)).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GcIconButton(GcIcons.Close, stringResource(R.string.chat_cancel), onClick = onCancel, tint = Color.White, enabled = !saving)
                Text(
                    stringResource(R.string.chat_group_picture_crop),
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    modifier = Modifier.weight(1f),
                )
            }
            Column(
                Modifier.weight(1f).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            ) {
                Text(
                    stringResource(R.string.chat_group_picture_crop_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.75f),
                    textAlign = TextAlign.Center,
                )
                BoxWithConstraints(Modifier.widthIn(max = 420.dp).fillMaxWidth()) {
                    val side = maxWidth
                    val frame = with(density) { side.toPx() }
                    framePx = frame
                    val base = AvatarCrop.baseScale(source.width, source.height, frame)
                    val imgW = with(density) { (source.width * base).toDp() }
                    val imgH = with(density) { (source.height * base).toDp() }
                    Box(
                        Modifier
                            .size(side)
                            .clipToBounds()
                            .pointerInput(source, frame) {
                                detectTransformGestures { _, pan, gestureZoom, _ ->
                                    if (saving) return@detectTransformGestures
                                    zoom = AvatarCrop.clampZoom(zoom * gestureZoom)
                                    val (x, y) = AvatarCrop.clampOffset(source.width, source.height, frame, zoom, offsetX + pan.x, offsetY + pan.y)
                                    offsetX = x
                                    offsetY = y
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            image,
                            contentDescription = null,
                            contentScale = ContentScale.FillBounds,
                            modifier = Modifier
                                .requiredSize(imgW, imgH)
                                .graphicsLayer {
                                    scaleX = zoom
                                    scaleY = zoom
                                    translationX = offsetX
                                    translationY = offsetY
                                },
                        )
                        Box(Modifier.fillMaxSize().border(2.dp, Color.White.copy(alpha = 0.9f), CircleShape))
                        Box(Modifier.fillMaxSize().border(1.dp, Color.White.copy(alpha = 0.35f)))
                    }
                }
            }
            Row(Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GcOutlineButton(text = stringResource(R.string.chat_cancel), onClick = onCancel, enabled = !saving, modifier = Modifier.weight(1f))
                GcPrimaryButton(
                    text = stringResource(R.string.chat_save),
                    onClick = { if (framePx > 0f) onDone(AvatarCrop.sourceRect(source.width, source.height, framePx, zoom, offsetX, offsetY)) },
                    loading = saving,
                    enabled = !saving,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
