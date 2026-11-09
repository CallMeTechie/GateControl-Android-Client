package com.gatecontrol.android.ui.mail

import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.mail.MAddress
import com.gatecontrol.android.mail.MMe
import com.gatecontrol.android.mail.MSettings
import com.gatecontrol.android.mail.MSummary
import com.gatecontrol.android.mail.MailBulk
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcSubpageBar
import com.gatecontrol.android.ui.components.GcSwitchRow
import com.gatecontrol.android.ui.theme.GateControlTheme

/** The name of a swipe action in the settings ("Gelesen / Ungelesen", "Verschieben …"). */
@Composable
internal fun swipeName(action: String): String = stringResource(
    when (action) {
        MSettings.SWIPE_ARCHIVE -> R.string.mail_swipe_a_archive
        MSettings.SWIPE_DELETE -> R.string.mail_swipe_a_delete
        MSettings.SWIPE_READ -> R.string.mail_swipe_a_read
        MSettings.SWIPE_FLAG -> R.string.mail_swipe_a_flag
        MSettings.SWIPE_MOVE -> R.string.mail_swipe_a_move
        MSettings.SWIPE_JUNK -> R.string.mail_swipe_a_junk
        else -> R.string.mail_swipe_a_none
    },
)

/**
 * "Mail-Einstellungen › Wischen" (Mail 1.13 mockup 4): a preview of a mail being swiped with the person's choice, for
 * each way the actions (seven with `swipeV2`, else the old four) with their icon and color, and "Vor dem Löschen
 * fragen". Kept on the server: it holds on every device.
 */
@Composable
fun MailSwipeScreen(onBack: () -> Unit, viewModel: MailSettingsViewModel = hiltViewModel()) {
    val me by viewModel.me.collectAsStateWithLifecycle()
    val toastText by viewModel.toast.collectAsStateWithLifecycle()
    val context = mailContext()
    LaunchedEffect(toastText) {
        toastText?.let {
            toast(context, it)
            viewModel.toastShown()
        }
    }
    Column(Modifier.fillMaxSize()) {
        GcSubpageBar(title = stringResource(R.string.mail_settings_swipe), onBack = onBack, backDescription = stringResource(R.string.mail_back))
        val m = me
        if (m == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Column
        }
        MailSwipeContent(
            me = m,
            onLeft = { viewModel.put(swipeLeft = it) },
            onRight = { viewModel.put(swipeRight = it) },
            onConfirm = { viewModel.put(confirmDelete = it) },
            modifier = Modifier.weight(1f),
        )
    }
}

/** The page's content (without the bar), for the screen and its tests. */
@Composable
internal fun MailSwipeContent(
    me: MMe,
    onLeft: (String) -> Unit,
    onRight: (String) -> Unit,
    onConfirm: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val x = GateControlTheme.extraColors
    val options = MailBulk.swipeOptions(me)
    val v2 = me.has(MMe.FEATURE_SWIPE_V2)
    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SwipePreview(left = me.settings.swipeLeft, right = me.settings.swipeRight)
        Text(stringResource(R.string.mail_swipe_preview), style = MaterialTheme.typography.bodySmall, color = x.muted)
        SideLabel(stringResource(R.string.mail_swipe_left_title), leftArrow = true)
        SwipeChoices(options, selected = me.settings.swipeLeft, onSelect = onLeft)
        SideLabel(stringResource(R.string.mail_swipe_right_title), leftArrow = false)
        SwipeChoices(options, selected = me.settings.swipeRight, onSelect = onRight)
        if (v2) {
            GcCard(contentPadding = PaddingValues(vertical = 4.dp)) {
                GcSwitchRow(
                    label = stringResource(R.string.mail_settings_confirm),
                    description = stringResource(R.string.mail_settings_confirm_text),
                    checked = me.settings.confirmDelete,
                    onCheckedChange = onConfirm,
                )
            }
        }
        Text(
            stringResource(if (v2) R.string.mail_swipe_note else R.string.mail_swipe_more_later),
            style = MaterialTheme.typography.bodySmall,
            color = x.muted,
        )
    }
}

@Composable
private fun SideLabel(text: String, leftArrow: Boolean) {
    val x = GateControlTheme.extraColors
    Row(Modifier.padding(top = 4.dp).semantics(mergeDescendants = true) { heading() }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (leftArrow) Icon(GcIcons.Back, contentDescription = null, tint = x.faint, modifier = Modifier.size(14.dp))
        Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = x.faint)
        if (!leftArrow) Icon(GcIcons.Back, contentDescription = null, tint = x.faint, modifier = Modifier.size(14.dp).graphicsLayer { scaleX = -1f })
    }
}

/** One way's actions as radio rows: the icon in a square of its color, the name, the round mark. */
@Composable
private fun SwipeChoices(options: List<String>, selected: String, onSelect: (String) -> Unit) {
    val x = GateControlTheme.extraColors
    GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        Column(Modifier.selectableGroup()) {
            options.forEach { a ->
                val on = a == selected
                val (icon, color) = swipeLook(a)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .selectable(selected = on, role = Role.RadioButton) { if (!on) onSelect(a) }
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(19.dp))
                    }
                    Text(swipeName(a), style = MaterialTheme.typography.bodyLarge, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.weight(1f))
                    Box(
                        Modifier.size(20.dp).clip(CircleShape).border(2.dp, if (on) MaterialTheme.colorScheme.primary else x.muted, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (on) Box(Modifier.size(10.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                    }
                }
            }
        }
    }
}

/**
 * The preview (mockup 4): a mail that is swiped to the left and to the right in turn, behind it the color, icon and
 * name of the action of that way. With the animations off (also in tests) it stands swiped to the left.
 */
@Composable
private fun SwipePreview(left: String, right: String) {
    val x = GateControlTheme.extraColors
    val desc = stringResource(R.string.mail_swipe_preview_desc)
    val transition = rememberInfiniteTransition(label = "swipe-preview")
    val shift by transition.animateFloat(
        initialValue = -PREVIEW_SHIFT,
        targetValue = -PREVIEW_SHIFT,
        animationSpec = InfiniteRepeatableSpec(
            keyframes {
                durationMillis = 6000
                -PREVIEW_SHIFT at 0 using LinearEasing
                -PREVIEW_SHIFT at 1500
                0f at 2400
                0f at 3000
                PREVIEW_SHIFT at 3900
                PREVIEW_SHIFT at 5400
                -PREVIEW_SHIFT at 6000
            },
            repeatMode = RepeatMode.Restart,
        ),
        label = "shift",
    )
    val sample = previewMail()
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, x.border, RoundedCornerShape(16.dp))
            .clearAndSetSemantics { contentDescription = desc },
    ) {
        Row(Modifier.matchParentSize()) {
            // swiped to the right: the right way's action shows on the left, and the other way round
            PreviewSide(right, alignEnd = false, modifier = Modifier.weight(1f))
            PreviewSide(left, alignEnd = true, modifier = Modifier.weight(1f))
        }
        Box(
            Modifier
                .graphicsLayer { translationX = shift * size.width }
                .background(MaterialTheme.colorScheme.background),
        ) {
            MailRow(item = sample, account = null, onClick = {}, onLongClick = {})
        }
    }
}

@Composable
private fun PreviewSide(action: String, alignEnd: Boolean, modifier: Modifier) {
    val (icon, color) = swipeLook(action)
    val none = MailBulk.swipe(action) == null
    Row(
        modifier.fillMaxHeight().background(color.copy(alpha = if (none) 0.08f else 0.18f)).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (alignEnd) Arrangement.End else Arrangement.Start,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        Text(swipeName(action), color = color, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.padding(start = 8.dp))
    }
}

/** The mail of the preview (made up, in the app's language). */
@Composable
private fun previewMail(): MSummary = MSummary(
    id = -1,
    accountId = -1,
    folderId = null,
    threadId = null,
    threadCount = 1,
    from = MAddress(stringResource(R.string.mail_swipe_sample_from), "lena@example.org"),
    to = emptyList(),
    subject = stringResource(R.string.mail_swipe_sample_subject),
    snippet = stringResource(R.string.mail_swipe_sample_snippet),
    date = System.currentTimeMillis(),
    unread = true,
    flagged = false,
    answered = false,
    hasAttachments = true,
    tab = MSummary.TAB_RELEVANT,
    savedToDrive = false,
    aiSummary = null,
)

private const val PREVIEW_SHIFT = 0.48f
