package com.gatecontrol.android.ui.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.R
import com.gatecontrol.android.media.server.MediaConvertHint
import com.gatecontrol.android.media.server.MediaConvertLogic
import com.gatecontrol.android.ui.components.GcBanner
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.theme.GateControlTheme
import java.time.ZoneId

/** The title and the text of [hint] ([problem]: what the device does not play, „H.264 10-Bit (High 10)“). */
@Composable
internal fun convertTexts(hint: MediaConvertHint, problem: String?): Pair<String, String> = when (hint) {
    MediaConvertHint.Offer -> stringResource(R.string.media_convert_offer_title) to stringResource(R.string.media_convert_offer_text, problem ?: "")
    MediaConvertHint.Requested -> stringResource(R.string.media_convert_requested_title) to stringResource(R.string.media_convert_requested_text)
    is MediaConvertHint.Queued -> {
        @Suppress("DEPRECATION")
        val locale = LocalConfiguration.current.locale
        val clock = MediaConvertLogic.startClock(hint.startsAt, System.currentTimeMillis(), ZoneId.systemDefault(), locale)
        val parts = listOfNotNull(
            hint.position?.let { stringResource(R.string.media_convert_queued_place, it) },
            clock?.let { stringResource(R.string.media_convert_queued_from, it) },
        )
        val after = stringResource(R.string.media_convert_queued_after)
        stringResource(R.string.media_convert_queued_title) to (if (parts.isEmpty()) after else parts.joinToString(", ") + ". " + after)
    }
    is MediaConvertHint.Running ->
        (hint.percent?.let { stringResource(R.string.media_convert_running_title_percent, it) } ?: stringResource(R.string.media_convert_running_title)) to
            stringResource(R.string.media_convert_running_text)
}

/**
 * „Videos umwandeln“ before playing (mockup pictures 5 and 6): the phone does not play the file's picture itself.
 * Tonight (with the place and the start), being converted (with the bar), or „Für heute Nacht vormerken“. Playing
 * stays possible: the buttons below are untouched.
 */
@Composable
internal fun MediaConvertCard(
    hint: MediaConvertHint,
    problem: String?,
    busy: Boolean,
    failed: Boolean,
    onRequest: () -> Unit,
    modifier: Modifier = Modifier,
    /** „Andere Folge“ (an episode) or „Anderer Film“; null: not offered. */
    onOther: (() -> Unit)? = null,
    episode: Boolean = true,
) {
    val x = GateControlTheme.extraColors
    val (title, text) = convertTexts(hint, problem)
    GcBanner(
        modifier = modifier.testTag(CONVERT_TAG),
        tone = GcTone.Warn,
        icon = if (hint is MediaConvertHint.Running) GcIcons.Clock else GcIcons.Moon,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Text(text, style = MaterialTheme.typography.bodySmall, color = x.muted)
            val percent = (hint as? MediaConvertHint.Running)?.percent
            if (percent != null) {
                MediaProgressBar(percent / 100f, Modifier.padding(top = 2.dp))
            }
            // „Andere Folge“ beside „Für heute Nacht vormerken“ (mockup picture 5): another title instead of a stuttering one
            if (hint == MediaConvertHint.Offer || onOther != null) {
                // one under the other: side by side the long label would break into three lines on a phone
                Column(Modifier.padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (onOther != null) {
                        GcOutlineButton(
                            stringResource(if (episode) R.string.media_convert_other_episode else R.string.media_convert_other_film),
                            onClick = onOther,
                            minHeight = 40.dp,
                            modifier = Modifier.testTag(CONVERT_OTHER_TAG),
                        )
                    }
                    if (hint == MediaConvertHint.Offer) {
                        GcOutlineButton(
                            stringResource(R.string.media_convert_request),
                            onClick = onRequest,
                            loading = busy,
                            enabled = !busy,
                            icon = GcIcons.Moon,
                            minHeight = 40.dp,
                            modifier = Modifier.testTag(CONVERT_REQUEST_TAG),
                        )
                    }
                }
            }
            if (failed) Text(stringResource(R.string.media_convert_failed), style = MaterialTheme.typography.bodySmall, color = x.warn)
        }
    }
}

internal const val CONVERT_TAG = "media-convert"
internal const val CONVERT_REQUEST_TAG = "media-convert-request"
internal const val CONVERT_OTHER_TAG = "media-convert-other"
