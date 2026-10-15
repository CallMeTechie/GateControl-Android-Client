package com.gatecontrol.android.ui.profile

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.profile.AvatarCrop
import com.gatecontrol.android.profile.AvatarPalette
import com.gatecontrol.android.profile.AvatarRing
import com.gatecontrol.android.profile.CropRect
import com.gatecontrol.android.profile.ProfileSupport
import com.gatecontrol.android.ui.components.GcAvatar
import com.gatecontrol.android.ui.components.GcBanner
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.components.GcSectionLabel
import com.gatecontrol.android.ui.components.GcSubpageBar
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.components.avatarColor
import com.gatecontrol.android.ui.theme.GateControlTheme

/**
 * Settings → Profil (also the own avatar on the start tab): profile
 * picture (system photo picker, square crop, 256 px) and favourite colour
 * — the ring colour while at home. Red is not offered: it means away.
 * The camera is not offered: the app declares CAMERA for the QR scanner,
 * so a camera intent would need that permission at runtime.
 */
@Composable
fun ProfileScreen(onBack: () -> Unit, viewModel: ProfileViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val cropSource by viewModel.cropSource.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val extra = GateControlTheme.extraColors
    val context = LocalContext.current
    val resources = LocalResources.current

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { res ->
            if (res != 0) android.widget.Toast.makeText(context, resources.getString(res), android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> viewModel.onPicked(uri) }

    cropSource?.let { bitmap ->
        AvatarCropDialog(
            bitmap = bitmap,
            saving = busy == ProfileBusy.UPLOAD,
            onConfirm = viewModel::confirmCrop,
            onDismiss = viewModel::cancelCrop,
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        GcSubpageBar(title = stringResource(R.string.profile_title), onBack = onBack, backDescription = stringResource(R.string.common_back))
        val profile = state.profile
        when {
            state.support == ProfileSupport.UNSUPPORTED || state.support == ProfileSupport.NO_USER -> {
                GcBanner(tone = GcTone.Info, modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text(
                        stringResource(if (state.support == ProfileSupport.NO_USER) R.string.profile_no_user else R.string.profile_unsupported),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            profile == null -> {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    if (refreshing) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    } else {
                        Text(stringResource(R.string.profile_error_offline), style = MaterialTheme.typography.bodyMedium, color = extra.muted)
                    }
                }
            }
            else -> {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    GcAvatar(
                        url = profile.avatarUrl,
                        initials = profile.initials,
                        colorHex = profile.colorHex,
                        // Preview: the ring as the others see it while I am at home.
                        ring = AvatarRing.Home(profile.colorHex),
                        size = 128.dp,
                        contentDescription = stringResource(R.string.profile_avatar_desc, profile.name.ifBlank { "?" }),
                    )
                    if (profile.name.isNotBlank()) {
                        Text(profile.name, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
                    }
                    Row(
                        Modifier.widthIn(max = 480.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        GcPrimaryButton(
                            text = stringResource(R.string.profile_photo_pick),
                            onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                            enabled = busy == null,
                            loading = busy == ProfileBusy.LOADING_PHOTO || busy == ProfileBusy.UPLOAD,
                            icon = GcIcons.Photo,
                            modifier = Modifier.weight(1f),
                        )
                        if (profile.avatarUrl != null) {
                            GcOutlineButton(
                                text = stringResource(R.string.profile_photo_remove),
                                onClick = viewModel::removeAvatar,
                                enabled = busy == null,
                                loading = busy == ProfileBusy.REMOVE,
                                icon = GcIcons.Trash,
                                fillWidth = false,
                            )
                        }
                    }
                }

                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    GcSectionLabel(stringResource(R.string.profile_color_title), Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
                    GcCard(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            AvatarPalette.selectable(profile.palette).forEach { c ->
                                ColorSwatch(
                                    hex = c.hex,
                                    label = colorLabel(c.key),
                                    selected = c.key == profile.color,
                                    enabled = busy == null,
                                    onClick = { viewModel.setColor(c.key) },
                                )
                            }
                        }
                        Text(stringResource(R.string.profile_color_help), style = MaterialTheme.typography.bodySmall, color = extra.muted)
                    }
                }
                Text(
                    stringResource(R.string.profile_privacy_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = extra.faint,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
        }
    }
}

@Composable
private fun ColorSwatch(hex: String, label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val color = avatarColor(hex) ?: return
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = label }
            .border(if (selected) 3.dp else 0.dp, if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent, CircleShape)
            .padding(if (selected) 5.dp else 2.dp)
            .clip(CircleShape)
            .background(color),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(GcIcons.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun colorLabel(key: String): String = stringResource(
    when (key) {
        "blue" -> R.string.profile_color_blue
        "teal" -> R.string.profile_color_teal
        "green" -> R.string.profile_color_green
        "lime" -> R.string.profile_color_lime
        "yellow" -> R.string.profile_color_yellow
        "orange" -> R.string.profile_color_orange
        "pink" -> R.string.profile_color_pink
        "purple" -> R.string.profile_color_purple
        "indigo" -> R.string.profile_color_indigo
        "slate" -> R.string.profile_color_slate
        else -> R.string.profile_color_other
    },
)

/**
 * Simple crop: the photo covers a square frame; pinch to zoom, drag to
 * move. The circle shows what the avatar will look like.
 */
@Composable
private fun AvatarCropDialog(bitmap: Bitmap, saving: Boolean, onConfirm: (CropRect) -> Unit, onDismiss: () -> Unit) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    var zoom by remember(bitmap) { mutableFloatStateOf(1f) }
    var offsetX by remember(bitmap) { mutableFloatStateOf(0f) }
    var offsetY by remember(bitmap) { mutableFloatStateOf(0f) }
    var framePx by remember(bitmap) { mutableFloatStateOf(0f) }
    val density = LocalDensity.current

    Dialog(onDismissRequest = { if (!saving) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize().background(Color(0xF2000000)).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            Text(
                stringResource(R.string.profile_crop_title),
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
            )
            Text(
                stringResource(R.string.profile_crop_hint),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.75f),
                textAlign = TextAlign.Center,
            )
            BoxWithConstraints(Modifier.widthIn(max = 420.dp).fillMaxWidth()) {
                val side = maxWidth
                val frame = with(density) { side.toPx() }
                framePx = frame
                val base = AvatarCrop.baseScale(bitmap.width, bitmap.height, frame)
                val imgW = with(density) { (bitmap.width * base).toDp() }
                val imgH = with(density) { (bitmap.height * base).toDp() }
                Box(
                    Modifier
                        .size(side)
                        .clipToBounds()
                        .pointerInput(bitmap, frame) {
                            detectTransformGestures { _, pan, gestureZoom, _ ->
                                zoom = AvatarCrop.clampZoom(zoom * gestureZoom)
                                val (x, y) = AvatarCrop.clampOffset(bitmap.width, bitmap.height, frame, zoom, offsetX + pan.x, offsetY + pan.y)
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
                    // What stays visible as the round avatar.
                    Box(Modifier.fillMaxSize().border(2.dp, Color.White.copy(alpha = 0.9f), CircleShape))
                    Box(Modifier.fillMaxSize().border(1.dp, Color.White.copy(alpha = 0.35f)))
                }
            }
            Row(Modifier.widthIn(max = 420.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GcOutlineButton(
                    text = stringResource(R.string.common_cancel),
                    onClick = onDismiss,
                    enabled = !saving,
                    modifier = Modifier.weight(1f),
                )
                GcPrimaryButton(
                    text = stringResource(R.string.profile_crop_use),
                    onClick = {
                        if (framePx > 0f) onConfirm(AvatarCrop.sourceRect(bitmap.width, bitmap.height, framePx, zoom, offsetX, offsetY))
                    },
                    loading = saving,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** Leading avatar of the "Profil" row in the settings. */
@Composable
fun ProfileRowAvatar(ui: MyAvatarUi) {
    GcAvatar(
        url = ui.avatarUrl,
        initials = ui.initials ?: "?",
        colorHex = ui.colorHex,
        ring = ui.ring,
        size = 44.dp,
    )
}
