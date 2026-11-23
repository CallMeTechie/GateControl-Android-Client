package com.gatecontrol.android.tv.ui.doorbell

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.datasource.DataSource
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.gatecontrol.android.tv.R
import com.gatecontrol.android.tv.cameras.TvLiveUi
import com.gatecontrol.android.tv.doorbell.DoorbellRules
import com.gatecontrol.android.tv.doorbell.OpenStep
import com.gatecontrol.android.tv.doorbell.TvDoorbellUi
import com.gatecontrol.android.tv.security.TvPinCheck
import com.gatecontrol.android.tv.ui.cameras.TvLivePicture
import com.gatecontrol.android.tv.ui.components.TvButton
import com.gatecontrol.android.tv.ui.components.TvButtonStyle
import com.gatecontrol.android.tv.ui.components.TvConfirmDialog
import com.gatecontrol.android.tv.ui.components.TvPinDialog
import com.gatecontrol.android.tv.ui.theme.GateControlTvTheme
import kotlinx.coroutines.delay

/**
 * The doorbell's window (mockup "3"): picture (live or the ring's still), "Es klingelt – <Kamera>", "Groß ansehen",
 * "Tür öffnen" (only when this person may open the door belonging to the bell), "Ausblenden". A bar runs down for
 * 30 s; any key keeps the window.
 */
@Composable
fun TvDoorbellOverlay(
    ui: TvDoorbellUi,
    live: TvLiveUi,
    sourceFactory: () -> DataSource.Factory?,
    fullscreen: Boolean,
    onToggleSize: () -> Unit,
    onOpen: () -> Unit,
    onConfirm: () -> Unit,
    onPinPassed: () -> Unit,
    checkPin: suspend (String) -> TvPinCheck,
    onCancelStep: () -> Unit,
    onClose: () -> Unit,
) {
    val colors = GateControlTvTheme.colors
    var touched by remember { mutableStateOf(false) }
    var left by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(touched) {
        if (touched) return@LaunchedEffect
        val steps = 100
        for (i in steps downTo 0) {
            left = i / steps.toFloat()
            delay(DoorbellRules.AUTO_HIDE_MS / steps)
        }
        onClose()
    }
    val first = remember { FocusRequester() }
    Box(
        Modifier.fillMaxSize().onPreviewKeyEvent {
            touched = true
            false
        },
    ) {
        Column(
            (if (fullscreen) Modifier.fillMaxSize().padding(36.dp) else Modifier.align(Alignment.BottomEnd).padding(28.dp).width(380.dp))
                .background(colors.panel, RoundedCornerShape(12.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val pictureModifier = if (fullscreen) Modifier.weight(1f).fillMaxWidth() else Modifier.fillMaxWidth().aspectRatio(16f / 9f)
            Box(pictureModifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFF0B0F14))) {
                if (live.cameraId != null) {
                    TvLivePicture(live, sourceFactory, name = "", modifier = Modifier.fillMaxSize())
                } else {
                    ui.picture?.let { Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
                }
            }
            Text(ui.title.ifBlank { stringResource(R.string.tv_bell_title) }, style = MaterialTheme.typography.titleMedium, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (ui.body.isNotBlank()) Text(ui.body, style = MaterialTheme.typography.bodySmall, color = colors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            ui.result?.let { Text(resultText(it, ui.resultOk), style = MaterialTheme.typography.bodySmall, color = if (ui.resultOk) colors.accentText else colors.warn) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (ui.lock != null && ui.step != OpenStep.Done) {
                    TvButton(
                        stringResource(if (ui.step == OpenStep.Running) R.string.tv_bell_opening else R.string.tv_bell_open),
                        onClick = onOpen,
                        style = TvButtonStyle.Primary,
                        enabled = ui.step != OpenStep.Running,
                    )
                }
                TvButton(
                    stringResource(if (fullscreen) R.string.tv_bell_smaller else R.string.tv_bell_bigger),
                    onClick = onToggleSize,
                    modifier = if (ui.lock == null) Modifier.focusRequester(first) else Modifier,
                )
                TvButton(stringResource(R.string.tv_bell_hide), onClick = onClose, modifier = if (ui.lock != null) Modifier.focusRequester(first) else Modifier)
            }
            if (!touched) {
                Box(Modifier.fillMaxWidth().height(3.dp).background(colors.line, RoundedCornerShape(2.dp))) {
                    Box(Modifier.fillMaxWidth(left).height(3.dp).background(colors.accent, RoundedCornerShape(2.dp)))
                }
            }
        }
    }
    LaunchedEffect(ui.lock != null) { runCatching { first.requestFocus() } }
    when (ui.step) {
        OpenStep.Confirm -> TvConfirmDialog(
            title = stringResource(R.string.tv_bell_confirm_title, ui.lock?.name.orEmpty()),
            text = null,
            confirm = stringResource(R.string.tv_bell_open),
            onConfirm = onConfirm,
            onDismiss = onCancelStep,
        )
        OpenStep.Pin -> TvPinDialog(stringResource(R.string.tv_pin_for_door), checkPin, onPinPassed, onCancelStep)
        else -> Unit
    }
}

@Composable
private fun resultText(result: String, ok: Boolean): String = when {
    ok && result.isBlank() -> stringResource(R.string.tv_bell_opened)
    ok -> result
    result == com.gatecontrol.android.door.DoorApi.CODE_OFFLINE -> stringResource(R.string.tv_bell_offline)
    result.matches(Regex("^[a-z_0-9]+$")) -> stringResource(R.string.tv_bell_failed)
    else -> result
}
