package com.gatecontrol.android.ui.media

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gatecontrol.android.R
import com.gatecontrol.android.media.MediaLink
import com.gatecontrol.android.media.MediaLogic
import com.gatecontrol.android.media.MediaPictures
import com.gatecontrol.android.media.MediaProblem
import com.gatecontrol.android.media.MediaQuality
import com.gatecontrol.android.media.MediaTitle
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.theme.GateControlTheme

/** The posters of the media pages: provided by each page from its view model. */
val LocalMediaPictures = compositionLocalOf<MediaPictures?> { null }

/** [title]'s poster or backdrop once loaded; null meanwhile and when it has none. */
@Composable
fun rememberMediaPicture(title: MediaTitle, backdrop: Boolean = false): ImageBitmap? {
    val pictures = LocalMediaPictures.current ?: return null
    val key = pictures.key(title, backdrop) ?: return null
    val picture by produceState(pictures.cached(title, backdrop)?.asImageBitmap(), key) {
        if (value == null) value = pictures.load(title, backdrop)?.asImageBitmap()
    }
    return picture
}

/** The tile behind a poster without a picture: a calm colour of its own per title (the mockup's placeholders). */
@Composable
private fun placeholder(title: MediaTitle): Color {
    val x = GateControlTheme.extraColors
    val tones = listOf(x.blueBg, x.accentBg, x.proBg, x.warnBg, x.panel2)
    return tones[(title.name.hashCode() and 0x7fffffff) % tones.size]
}

/** "noch 1:06" / "noch 18 Min." of a title left half-way. */
@Composable
fun leftText(ms: Long): String = MediaLogic.hoursMinutes(ms)?.let { stringResource(R.string.media_left_hours, it) }
    ?: stringResource(R.string.media_left_minutes, MediaLogic.minutes(ms))

/** The second line of a card: "ab 42:10", "S2 · F4", "2025", "3 Staffeln". */
@Composable
fun subline(t: MediaTitle, resume: Boolean = false): String? = when {
    t.kind == MediaTitle.Kind.EPISODE && t.season != null && t.episode != null -> stringResource(R.string.media_season_episode_short, t.season!!, t.episode!!)
    resume && t.positionMs > 0 -> stringResource(R.string.media_from, com.gatecontrol.android.media.MediaPhoneLogic.clock(t.positionMs))
    t.kind == MediaTitle.Kind.SERIES && t.childCount != null -> pluralStringResource(R.plurals.media_seasons, t.childCount!!, t.childCount!!)
    t.year != null -> t.year.toString()
    else -> null
}

/** A small mark on a picture ("NAS", "NEU"). */
@Composable
fun MediaMark(text: String, modifier: Modifier = Modifier, accent: Boolean = false) {
    Box(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (accent) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(text, color = if (accent) MaterialTheme.colorScheme.onPrimary else Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** The green bar of a title begun. */
@Composable
fun MediaProgressBar(progress: Float, modifier: Modifier = Modifier) {
    val x = GateControlTheme.extraColors
    Box(modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(x.panel2)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(MaterialTheme.colorScheme.primary))
    }
}

/** A poster (2:3) with its name below: the grids and the rows "Zuletzt hinzugefügt", "Serien". */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaPoster(
    title: MediaTitle,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp? = null,
    onLongClick: (() -> Unit)? = null,
    new: Boolean = false,
) {
    val x = GateControlTheme.extraColors
    val picture = rememberMediaPicture(title)
    Column(
        (if (width != null) modifier.width(width) else modifier)
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(role = Role.Button, onClick = onClick, onLongClick = onLongClick),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp)).background(placeholder(title))) {
            if (picture != null) {
                Image(picture, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(GcIcons.Film, contentDescription = null, tint = x.muted, modifier = Modifier.align(Alignment.Center).size(28.dp))
            }
            Row(Modifier.align(Alignment.TopStart).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (new) MediaMark(stringResource(R.string.media_mark_new), accent = true)
                if (title.fromShare) MediaMark(stringResource(R.string.media_mark_nas))
            }
            if (title.played) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(GcIcons.Check, contentDescription = stringResource(R.string.media_played), tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(14.dp))
                }
            }
            if (title.resumable) MediaProgressBar(title.progress, Modifier.align(Alignment.BottomCenter).padding(6.dp))
        }
        Column(Modifier.padding(horizontal = 2.dp)) {
            Text(title.name, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subline(title)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = x.muted, maxLines = 1) }
        }
    }
}

/** A card of "Weiterschauen" (16:9 with the time left, the bar): a tap plays on, a long press opens the detail page. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaResumeCard(title: MediaTitle, onPlay: () -> Unit, onDetail: () -> Unit, modifier: Modifier = Modifier, width: Dp = 200.dp) {
    val x = GateControlTheme.extraColors
    val picture = rememberMediaPicture(title, backdrop = true) ?: rememberMediaPicture(title)
    val name = if (title.kind == MediaTitle.Kind.EPISODE) title.seriesName ?: title.name else title.name
    Column(
        modifier.width(width).clip(RoundedCornerShape(14.dp))
            .combinedClickable(role = Role.Button, onClick = onPlay, onLongClick = onDetail, onLongClickLabel = stringResource(R.string.media_open_detail)),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp)).background(placeholder(title))) {
            if (picture != null) Image(picture, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(Modifier.align(Alignment.Center).size(40.dp).clip(RoundedCornerShape(20.dp)).background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                Icon(GcIcons.Play, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
            if (title.fromShare) MediaMark(stringResource(R.string.media_mark_nas), Modifier.align(Alignment.TopStart).padding(6.dp))
            title.leftMs?.let { MediaMark(leftText(it), Modifier.align(Alignment.TopEnd).padding(6.dp)) }
            MediaProgressBar(title.progress, Modifier.align(Alignment.BottomCenter).padding(horizontal = 8.dp, vertical = 6.dp))
        }
        Column(Modifier.padding(horizontal = 2.dp)) {
            Text(name, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subline(title, resume = true)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = x.muted, maxLines = 1) }
        }
    }
}

/** The head of a row: its name and, on the right, a count or "Alle". */
@Composable
fun MediaRowHead(text: String, modifier: Modifier = Modifier, end: String? = null, onEnd: (() -> Unit)? = null, horizontal: Dp = 16.dp, top: Dp = 18.dp) {
    val x = GateControlTheme.extraColors
    Row(modifier.fillMaxWidth().padding(horizontal = horizontal).padding(top = top, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        if (end != null) {
            val m = if (onEnd != null) Modifier.clip(RoundedCornerShape(8.dp)).combinedClickableCompat(onEnd).padding(horizontal = 6.dp, vertical = 4.dp) else Modifier
            Text(end, style = MaterialTheme.typography.labelLarge, color = if (onEnd != null) x.accentText else x.muted, modifier = m)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit): Modifier = combinedClickable(role = Role.Button, onClick = onClick)

/** "Zuhause · direkt" / "Unterwegs · 18 Mbit/s" / "Unterwegs". */
@Composable
fun linkText(link: MediaLink): String? = when (link.atHome) {
    true -> stringResource(R.string.media_link_home)
    false -> MediaLogic.bandwidth(link)?.let { stringResource(R.string.media_link_away_bw, it) } ?: stringResource(R.string.media_link_away)
    null -> null
}

/** Why a page shows nothing, with the way on. */
@Composable
fun MediaProblemCard(problem: MediaProblem, onRetry: () -> Unit, modifier: Modifier = Modifier, onSignIn: (() -> Unit)? = null) {
    GcCard(modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            stringResource(
                when (problem) {
                    MediaProblem.OFFLINE -> R.string.media_problem_offline
                    MediaProblem.SIGNED_OUT -> R.string.media_problem_signed_out
                    MediaProblem.FAILED -> R.string.media_problem_failed
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (problem == MediaProblem.SIGNED_OUT && onSignIn != null) {
            GcPrimaryButton(stringResource(R.string.media_sign_in_again), onClick = onSignIn)
        } else {
            GcOutlineButton(stringResource(R.string.media_retry), onClick = onRetry)
        }
    }
}

/** "1 Std. 48 Min." / "18 Min.". */
@Composable
fun runtimeText(ms: Long): String {
    val m = MediaLogic.minutes(ms)
    return if (m >= 60) stringResource(R.string.media_hours_minutes, m / 60, m % 60) else stringResource(R.string.media_minutes, m)
}

/** "4K HDR10" of a version's picture, through [MediaQuality.videoLabel]. */
fun videoChip(t: com.gatecontrol.android.media.MediaVersion?): String? = t?.video?.let { v ->
    listOfNotNull(MediaQuality.videoLabel(v)?.replace(" ", " · ")).firstOrNull()
}
