package com.gatecontrol.android.ui.profile

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.gatecontrol.android.profile.AvatarWizard
import com.gatecontrol.android.profile.AvatarWizardStep
import com.gatecontrol.android.profile.CropRect
import com.gatecontrol.android.profile.MyProfile
import com.gatecontrol.android.profile.ProfileSupport
import com.gatecontrol.android.ui.components.GcAvatar
import com.gatecontrol.android.ui.components.GcBanner
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.components.GcSubpageBar
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.components.avatarColor
import com.gatecontrol.android.ui.familie.FamilieConfirmDialog
import com.gatecontrol.android.ui.theme.GateControlTheme

/**
 * Own profile (a tap on the own avatar on the start tab and in the
 * Familie tab, also Settings → Profil). Top right the gear to the
 * settings and the light/dark switch (until 1.35 in the start tab's header): the large avatar with its ring at the top — a tap
 * opens the system photo picker, the "X" badge removes the picture (after
 * a short question), the palette badge changes only the ring colour. A
 * picked photo opens the full-screen wizard ([AvatarWizard]: crop → ring
 * colour → "Fertig"). Below: the name and, with the Familie presence
 * module, "Anwesenheit teilen". Red is not offered: it means away. The
 * camera is not offered: the app declares CAMERA for the QR scanner, so a
 * camera intent would need that permission at runtime.
 */
@Composable
fun ProfileScreen(
    onBack: () -> Unit,
    /** Gear top right: the settings (null inside the settings, e.g. their detail pane). */
    onOpenSettings: (() -> Unit)? = null,
    viewModel: ProfileViewModel = hiltViewModel(),
    presenceViewModel: ProfilePresenceViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val wizard by viewModel.wizard.collectAsStateWithLifecycle()
    val cropSource by viewModel.cropSource.collectAsStateWithLifecycle()
    val preview by viewModel.preview.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val extra = GateControlTheme.extraColors
    val context = LocalContext.current
    val resources = LocalResources.current
    var confirmRemove by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { res ->
            if (res != 0) android.widget.Toast.makeText(context, resources.getString(res), android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> viewModel.onPicked(uri) }

    val profile = state.profile
    val w = wizard
    if (w != null && profile != null) {
        AvatarWizardDialog(
            wizard = w,
            source = cropSource,
            preview = preview,
            profile = profile,
            saving = busy == ProfileBusy.SAVING,
            onNext = viewModel::wizardNext,
            onPick = viewModel::wizardPick,
            onFinish = viewModel::wizardFinish,
            onCancel = viewModel::wizardCancel,
        )
    }
    if (confirmRemove) {
        FamilieConfirmDialog(
            title = stringResource(R.string.profile_remove_title),
            text = stringResource(R.string.profile_remove_text),
            confirmLabel = stringResource(R.string.profile_photo_remove),
            destructive = true,
            onConfirm = {
                confirmRemove = false
                viewModel.removeAvatar()
            },
            onDismiss = { confirmRemove = false },
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
        GcSubpageBar(
            title = stringResource(R.string.profile_title),
            onBack = onBack,
            backDescription = stringResource(R.string.common_back),
            actions = {
                // The way to the settings (no tab since 1.35), then the light/dark switch.
                if (onOpenSettings != null) {
                    GcIconButton(
                        icon = GcIcons.Settings,
                        contentDescription = stringResource(R.string.settings_title),
                        onClick = onOpenSettings,
                    )
                }
                ThemeToggleButton()
            },
        )
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
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ProfileAvatarHeader(
                        url = profile.avatarUrl,
                        initials = profile.initials,
                        colorHex = profile.colorHex,
                        name = profile.name,
                        busy = busy,
                        onPick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        onRemove = { confirmRemove = true },
                        onColor = viewModel::startColorOnly,
                    )
                    if (profile.name.isNotBlank()) {
                        Text(profile.name, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
                    }
                    Text(
                        stringResource(R.string.profile_avatar_tap_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = extra.muted,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        ProfilePresenceSection(presenceViewModel, Modifier.padding(horizontal = 16.dp))
        if (profile != null && state.support == ProfileSupport.SUPPORTED) {
            Text(
                stringResource(R.string.profile_privacy_hint),
                style = MaterialTheme.typography.bodySmall,
                color = extra.faint,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
    }
}

/**
 * The large avatar (ring = the favourite colour, as the others see it at
 * home): tap → photo picker; "X" badge (with a picture) → remove; palette
 * badge → ring colour only.
 */
@Composable
private fun ProfileAvatarHeader(
    url: String?,
    initials: String,
    colorHex: String?,
    name: String,
    busy: ProfileBusy?,
    onPick: () -> Unit,
    onRemove: () -> Unit,
    onColor: () -> Unit,
) {
    val pickLabel = stringResource(R.string.profile_photo_pick)
    Box(Modifier.size(156.dp)) {
        GcAvatar(
            url = url,
            initials = initials,
            colorHex = colorHex,
            ring = AvatarRing.Home(colorHex),
            size = 136.dp,
            contentDescription = stringResource(R.string.profile_avatar_desc, name.ifBlank { "?" }),
            modifier = Modifier
                .align(Alignment.Center)
                .clip(CircleShape)
                .clickable(enabled = busy == null, role = Role.Button, onClickLabel = pickLabel, onClick = onPick),
        )
        if (busy == ProfileBusy.LOADING_PHOTO || busy == ProfileBusy.REMOVE) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.Center))
        }
        if (url != null) {
            AvatarBadge(
                icon = GcIcons.Close,
                contentDescription = stringResource(R.string.profile_photo_remove_desc),
                enabled = busy == null,
                onClick = onRemove,
                modifier = Modifier.align(Alignment.TopEnd),
            )
        }
        AvatarBadge(
            icon = GcIcons.Palette,
            contentDescription = stringResource(R.string.profile_color_change_desc),
            enabled = busy == null,
            onClick = onColor,
            modifier = Modifier.align(Alignment.BottomEnd),
        )
    }
}

/** Small round icon button on the avatar's edge. */
@Composable
private fun AvatarBadge(icon: ImageVector, contentDescription: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val extra = GateControlTheme.extraColors
    Box(
        modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, extra.border2, CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun ColorSwatch(hex: String, label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val color = avatarColor(hex) ?: return
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = label }
            .border(if (selected) 3.dp else 0.dp, if (selected) Color.White else Color.Transparent, CircleShape)
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
 * The full-screen wizard: step 1 the simple crop (the photo covers a
 * square frame; pinch to zoom, drag to move, the circle shows the avatar),
 * step 2 the ring colour with a live preview. Back / cancel discards.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AvatarWizardDialog(
    wizard: AvatarWizard,
    source: Bitmap?,
    preview: Bitmap?,
    profile: MyProfile,
    saving: Boolean,
    onNext: (CropRect) -> Unit,
    onPick: (String) -> Unit,
    onFinish: () -> Unit,
    onCancel: () -> Unit,
) {
    // The photo while cropping, null on the colour step.
    val src = source?.takeIf { wizard.step == AvatarWizardStep.CROP }
    var zoom by remember(source) { mutableFloatStateOf(1f) }
    var offsetX by remember(source) { mutableFloatStateOf(0f) }
    var offsetY by remember(source) { mutableFloatStateOf(0f) }
    var framePx by remember(source) { mutableFloatStateOf(0f) }
    val density = LocalDensity.current

    Dialog(
        onDismissRequest = { if (!saving) onCancel() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Column(
            Modifier.fillMaxSize().background(Color(0xF2000000)).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Top: cancel, title, step indicator.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GcIconButton(
                    icon = GcIcons.Close,
                    contentDescription = stringResource(R.string.common_cancel),
                    onClick = onCancel,
                    tint = Color.White,
                    enabled = !saving,
                )
                Text(
                    stringResource(if (src != null) R.string.profile_crop_title else R.string.profile_color_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    modifier = Modifier.weight(1f),
                )
                if (wizard.stepCount > 1) {
                    val stepDesc = stringResource(R.string.profile_wizard_step_desc, wizard.stepNumber, wizard.stepCount)
                    Text(
                        stringResource(R.string.profile_wizard_step, wizard.stepNumber, wizard.stepCount),
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White.copy(alpha = 0.75f),
                        modifier = Modifier.padding(end = 8.dp).semantics { contentDescription = stepDesc },
                    )
                }
            }
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            ) {
                if (src != null) {
                    Text(
                        stringResource(R.string.profile_crop_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.75f),
                        textAlign = TextAlign.Center,
                    )
                    val image = remember(src) { src.asImageBitmap() }
                    BoxWithConstraints(Modifier.widthIn(max = 420.dp).fillMaxWidth()) {
                        val side = maxWidth
                        val frame = with(density) { side.toPx() }
                        framePx = frame
                        val base = AvatarCrop.baseScale(src.width, src.height, frame)
                        val imgW = with(density) { (src.width * base).toDp() }
                        val imgH = with(density) { (src.height * base).toDp() }
                        Box(
                            Modifier
                                .size(side)
                                .clipToBounds()
                                .pointerInput(src, frame) {
                                    detectTransformGestures { _, pan, gestureZoom, _ ->
                                        zoom = AvatarCrop.clampZoom(zoom * gestureZoom)
                                        val (x, y) = AvatarCrop.clampOffset(src.width, src.height, frame, zoom, offsetX + pan.x, offsetY + pan.y)
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
                } else {
                    val hex = AvatarPalette.hexOf(wizard.colorKey, profile.palette) ?: profile.colorHex
                    val previewImage = remember(preview) { preview?.asImageBitmap() }
                    GcAvatar(
                        // A new photo: its crop (nothing from the server meanwhile); colour only: the current picture.
                        url = if (wizard.withPhoto) null else profile.avatarUrl,
                        image = previewImage,
                        initials = profile.initials,
                        colorHex = hex,
                        ring = AvatarRing.Home(hex),
                        size = 168.dp,
                    )
                    Text(
                        stringResource(R.string.profile_color_help),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.85f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.widthIn(max = 420.dp),
                    )
                    FlowRow(
                        Modifier.widthIn(max = 420.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        AvatarPalette.selectable(profile.palette).forEach { c ->
                            ColorSwatch(
                                hex = c.hex,
                                label = colorLabel(c.key),
                                selected = c.key == wizard.colorKey,
                                enabled = !saving,
                                onClick = { onPick(c.key) },
                            )
                        }
                    }
                }
            }
            Row(Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GcOutlineButton(
                    text = stringResource(R.string.common_cancel),
                    onClick = onCancel,
                    enabled = !saving,
                    modifier = Modifier.weight(1f),
                )
                if (src != null) {
                    GcPrimaryButton(
                        text = stringResource(R.string.profile_wizard_next),
                        onClick = {
                            if (framePx > 0f) onNext(AvatarCrop.sourceRect(src.width, src.height, framePx, zoom, offsetX, offsetY))
                        },
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    GcPrimaryButton(
                        text = stringResource(R.string.profile_wizard_done),
                        onClick = onFinish,
                        enabled = wizard.canFinish,
                        loading = saving,
                        modifier = Modifier.weight(1f),
                    )
                }
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
