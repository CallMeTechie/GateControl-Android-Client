package com.gatecontrol.android.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gatecontrol.android.profile.AvatarLoader
import com.gatecontrol.android.profile.AvatarPalette
import com.gatecontrol.android.profile.AvatarRing
import com.gatecontrol.android.ui.theme.GateControlTheme
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface AvatarLoaderEntryPoint {
    fun avatarLoader(): AvatarLoader
}

/** Compose colour of `#RRGGBB`, null when invalid. */
fun avatarColor(hex: String?): Color? = AvatarPalette.argb(hex)?.let { Color(it) }

/**
 * The picture at [url] (`/avatars/…` of the own server), loaded through
 * [AvatarLoader]; null while loading, without picture, offline or broken.
 */
@Composable
fun rememberAvatarBitmap(url: String?, sizePx: Int): ImageBitmap? {
    if (url == null || LocalInspectionMode.current) return null
    val context = LocalContext.current.applicationContext
    val loader = remember(context) {
        runCatching { EntryPointAccessors.fromApplication(context, AvatarLoaderEntryPoint::class.java).avatarLoader() }.getOrNull()
    } ?: return null
    val bitmap by produceState(loader.peek(url, sizePx)?.asImageBitmap(), url, sizePx) {
        if (value == null) value = loader.load(url, sizePx)?.asImageBitmap()
    }
    return bitmap
}

/**
 * Avatar of a person everywhere in the app: the picture (or the initials
 * in white on the favourite colour) inside a presence ring — at home the
 * favourite colour, away red, unknown / not shared a thin neutral border.
 * [size] is the whole avatar including the ring. [image] shows a picture
 * that is not on the server yet (the wizard's preview) instead of [url].
 */
@Composable
fun GcAvatar(
    url: String?,
    initials: String,
    colorHex: String?,
    ring: AvatarRing,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    contentDescription: String? = null,
    image: ImageBitmap? = null,
    /** Colour of the initials on [colorHex] (default white). */
    initialsColor: Color? = null,
) {
    val extra = GateControlTheme.extraColors
    val fill = avatarColor(colorHex)
    val ringWidth = if (size >= 72.dp) 3.dp else 2.dp
    val gap = if (size >= 72.dp) 3.dp else 2.dp
    val ringColor: Color? = when (ring) {
        is AvatarRing.Home -> avatarColor(ring.colorHex) ?: MaterialTheme.colorScheme.primary
        AvatarRing.Away -> avatarColor(AvatarPalette.AWAY_HEX)
        AvatarRing.None -> null
    }
    val px = with(LocalDensity.current) { size.roundToPx() }
    val bitmap = image ?: rememberAvatarBitmap(url, px)
    Box(
        modifier = modifier
            .size(size)
            .then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier)
            .then(
                if (ringColor != null) {
                    Modifier.border(ringWidth, ringColor, CircleShape).padding(ringWidth + gap)
                } else {
                    Modifier.border(1.dp, extra.border2, CircleShape).padding(1.dp)
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        val inner = Modifier.fillMaxSize().clip(CircleShape)
        if (bitmap != null) {
            Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = inner)
        } else {
            Box(
                inner.background(fill ?: extra.accentBg),
                contentAlignment = Alignment.Center,
            ) {
                val textSize = (size.value * if (initials.length > 2) 0.28f else 0.36f)
                Text(
                    initials,
                    color = if (fill != null) initialsColor ?: Color.White else extra.accentText,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.ExtraBold, fontSize = textSize.sp),
                    maxLines = 1,
                )
            }
        }
    }
}
