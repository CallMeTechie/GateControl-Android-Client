package com.gatecontrol.android.ui.chat.status

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.chat.ChatPerson
import com.gatecontrol.android.chat.ChatStatus
import com.gatecontrol.android.chat.ChatStatusLogic
import com.gatecontrol.android.ui.chat.ChatPersonAvatar
import com.gatecontrol.android.ui.chat.ChatText
import com.gatecontrol.android.ui.chat.PickChip
import com.gatecontrol.android.ui.chat.TimeOnlyDialog
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.components.GcSecondaryButton
import com.gatecontrol.android.ui.theme.GateControlTheme
import com.gatecontrol.android.ui.theme.MonoFontFamily
import java.time.LocalTime
import java.time.ZoneId

/** Texts of a status ("Im Homeoffice · bis 17:00"). */
object StatusText {
    fun preset(ctx: Context, preset: String): String? = when (preset) {
        ChatStatus.PRESET_HOMEOFFICE -> ctx.getString(R.string.chat_status_preset_homeoffice)
        ChatStatus.PRESET_AWAY -> ctx.getString(R.string.chat_status_preset_away)
        ChatStatus.PRESET_DND -> ctx.getString(R.string.chat_status_preset_dnd)
        ChatStatus.PRESET_AUTO_PLACE -> ctx.getString(R.string.chat_status_preset_auto_place)
        else -> null
    }

    /** The status as one line; null when there is none (or it ended). */
    fun line(ctx: Context, s: ChatStatus?, nowMs: Long = System.currentTimeMillis()): String? {
        if (s == null || !ChatStatusLogic.active(s, nowMs)) return null
        val main = s.place.takeIf { s.preset == ChatStatus.PRESET_AUTO_PLACE } ?: s.text ?: preset(ctx, s.preset) ?: return null
        val extra = when {
            s.preset == ChatStatus.PRESET_AWAY && s.eta != null -> ctx.getString(R.string.chat_status_eta, ChatText.clock(ctx, s.eta))
            s.until != null -> ctx.getString(R.string.chat_status_until_time, ChatText.clock(ctx, s.until))
            else -> null
        }
        return listOfNotNull(main, s.destLabel?.takeIf { s.preset == ChatStatus.PRESET_AWAY }?.let { "→ $it" }, extra).joinToString(" · ")
    }
}

private data class PresetLook(val preset: String, val icon: ImageVector, val tint: Color, val bg: Color, val label: Int, val desc: Int)

/**
 * "Dein Status" (mockup Start – Status festlegen): text, presets, when it
 * ends, who sees it; links to the photo / text status and the profile.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun StatusSheet(
    onDismiss: () -> Unit,
    onOpenStoryComposer: (() -> Unit)?,
    onOpenProfile: (() -> Unit)?,
    onViewOwnStories: (() -> Unit)? = null,
    viewModel: StatusViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val people by viewModel.people.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val res = androidx.compose.ui.platform.LocalResources.current
    val extra = GateControlTheme.extraColors
    val scheme = MaterialTheme.colorScheme
    val own = ui.own
    var text by rememberSaveable(own) { mutableStateOf(own?.ownText ?: own?.text?.takeIf { own.preset != ChatStatus.PRESET_AUTO_PLACE }.orEmpty()) }
    var preset by rememberSaveable(own) { mutableStateOf(own?.preset ?: ChatStatus.PRESET_NONE) }
    var until by rememberSaveable(own) { mutableStateOf(if (own == null) ChatStatusLogic.UNTIL_NEVER else null) }
    var visibility by rememberSaveable(own) { mutableStateOf(own?.visibility?.takeIf { own.visibility in ChatStatus.VISIBILITIES } ?: viewModel.defaultVisibility()) }
    val chosen = remember(own) { mutableStateListOf<Long>().apply { addAll(own?.userIds.orEmpty()) } }
    var eta by rememberSaveable(own) { mutableStateOf(own?.eta?.let { com.gatecontrol.android.chat.ChatTimeline.epochMs(it) }) }
    var dest by rememberSaveable(own) { mutableStateOf(own?.destLabel.orEmpty()) }
    var pickPeople by remember { mutableStateOf(false) }
    var pickEta by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.notice.collect { n ->
            val msg = when (n) {
                is StatusNotice.Done -> res.getString(n.res)
                is StatusNotice.Error -> ChatText.actionError(ctx, n.code)
            }
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
        }
    }

    val presets = listOf(
        PresetLook(ChatStatus.PRESET_HOMEOFFICE, Icons.Outlined.Computer, extra.blue, extra.blueBg, R.string.chat_status_preset_homeoffice, 0),
        PresetLook(ChatStatus.PRESET_AWAY, Icons.Outlined.DirectionsCar, extra.warn, extra.warnBg, R.string.chat_status_preset_away, R.string.chat_status_preset_away_desc),
        PresetLook(ChatStatus.PRESET_DND, Icons.Outlined.Bedtime, extra.pro, extra.proBg, R.string.chat_status_preset_dnd, R.string.chat_status_preset_dnd_desc),
        PresetLook(ChatStatus.PRESET_AUTO_PLACE, Icons.Outlined.Place, extra.accentText, extra.accentBg, R.string.chat_status_preset_auto_place, R.string.chat_status_preset_auto_desc),
    )
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = scheme.surface, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.padding(horizontal = 16.dp).padding(bottom = 20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.chat_status_title), style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold), color = scheme.onSurface, modifier = Modifier.weight(1f))
                if (ui.storiesOn && onOpenStoryComposer != null) {
                    TextButton(onClick = onOpenStoryComposer) {
                        Icon(Icons.Outlined.PhotoCamera, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(" " + stringResource(R.string.chat_status_story_link), fontWeight = FontWeight.ExtraBold)
                    }
                }
            }
            // The own active stories: at a glance, with how many saw them, and a way to watch them.
            val myStories = ui.feed?.me?.stories.orEmpty()
            if (myStories.isNotEmpty() && onViewOwnStories != null) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(14.dp)).background(extra.accentBg)
                        .border(1.dp, scheme.primary, RoundedCornerShape(14.dp))
                        .clickable(role = Role.Button, onClick = onViewOwnStories).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    StoryAvatar(ui.profile?.user, StoryRing.OwnActive, 48.dp)
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.chat_status_my_stories_title), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), color = scheme.onSurface)
                        Text(
                            pluralStringResource(R.plurals.chat_status_my_stories, myStories.size, myStories.size) + " · " +
                                stringResource(R.string.chat_story_viewers, myStories.sumOf { it.viewerCount ?: 0 }),
                            style = MaterialTheme.typography.bodySmall,
                            color = extra.muted,
                        )
                    }
                    Text(stringResource(R.string.chat_status_my_stories_open), style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.ExtraBold), color = scheme.primary)
                }
            }
            // TEXT · field · n/60.
            Row(
                Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(14.dp)).background(scheme.background)
                    .border(1.dp, scheme.primary, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(stringResource(R.string.chat_status_text), style = MaterialTheme.typography.labelMedium.copy(fontFamily = MonoFontFamily, fontWeight = FontWeight.ExtraBold), color = scheme.primary)
                val label = stringResource(R.string.chat_status_text_label)
                BasicTextField(
                    value = text,
                    onValueChange = { text = it.replace('\n', ' ').take(ChatStatus.TEXT_MAX) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface, fontWeight = FontWeight.SemiBold),
                    cursorBrush = SolidColor(scheme.primary),
                    modifier = Modifier.weight(1f).semantics { contentDescription = label },
                )
                Text("${text.length}/${ChatStatus.TEXT_MAX}", style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily), color = extra.faint)
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                presets.forEach { p ->
                    val on = preset == p.preset
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(14.dp))
                            .background(if (on) extra.accentBg else scheme.background)
                            .border(1.dp, if (on) scheme.primary else extra.border, RoundedCornerShape(14.dp))
                            .clickable(role = Role.RadioButton) {
                                if (on) {
                                    preset = ChatStatus.PRESET_NONE
                                } else {
                                    preset = p.preset
                                    if (text.isBlank() && p.preset != ChatStatus.PRESET_AUTO_PLACE) text = res.getString(p.label)
                                }
                            }
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(p.bg), contentAlignment = Alignment.Center) {
                            Icon(p.icon, contentDescription = null, tint = p.tint, modifier = Modifier.size(18.dp))
                        }
                        Text(stringResource(p.label), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), color = scheme.onSurface, modifier = Modifier.weight(1f))
                        Text(
                            if (on) stringResource(R.string.chat_status_preset_active) else if (p.desc != 0) stringResource(p.desc) else "",
                            style = MaterialTheme.typography.labelMedium,
                            color = extra.muted,
                        )
                    }
                }
            }
            if (preset == ChatStatus.PRESET_AWAY) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = dest,
                        onValueChange = { dest = it.take(80) },
                        singleLine = true,
                        label = { Text(stringResource(R.string.chat_status_dest)) },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f),
                    )
                    PickChip(eta?.let { ChatText.clockMs(ctx, it) } ?: stringResource(R.string.chat_status_eta_pick), eta != null) { pickEta = true }
                }
            }
            Label(stringResource(R.string.chat_status_until))
            val nowTime = LocalTime.now(ZoneId.systemDefault())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ChatStatusLogic.UNTIL_CHOICES.filter { it != ChatStatusLogic.UNTIL_WORK || nowTime.isBefore(LocalTime.of(17, 0)) }.forEach { c ->
                    PickChip(
                        stringResource(
                            when (c) {
                                ChatStatusLogic.UNTIL_30M -> R.string.chat_status_until_30m
                                ChatStatusLogic.UNTIL_1H -> R.string.chat_status_until_1h
                                ChatStatusLogic.UNTIL_WORK -> R.string.chat_status_until_work
                                ChatStatusLogic.UNTIL_TODAY -> R.string.chat_status_until_today
                                else -> R.string.chat_status_until_never
                            },
                        ),
                        until == c,
                    ) { until = c }
                }
            }
            Label(stringResource(R.string.chat_status_visible))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                PickChip(stringResource(R.string.chat_status_vis_all), visibility == ChatStatus.VIS_ALL) { visibility = ChatStatus.VIS_ALL }
                PickChip(stringResource(R.string.chat_status_vis_family), visibility == ChatStatus.VIS_FAMILY) { visibility = ChatStatus.VIS_FAMILY }
                PickChip(
                    if (visibility == ChatStatus.VIS_CUSTOM && chosen.isNotEmpty()) stringResource(R.string.chat_status_vis_custom_n, chosen.size) else stringResource(R.string.chat_status_vis_custom),
                    visibility == ChatStatus.VIS_CUSTOM,
                ) {
                    viewModel.loadPeople()
                    pickPeople = true
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GcSecondaryButton(
                    text = stringResource(R.string.chat_status_clear),
                    onClick = { viewModel.clear(onDismiss) },
                    enabled = own != null && !ui.busy,
                    modifier = Modifier.weight(1f),
                )
                GcPrimaryButton(
                    text = stringResource(R.string.chat_status_save),
                    onClick = { viewModel.save(text, preset, until, visibility, chosen.toList(), eta, dest, onDismiss) },
                    enabled = !ui.busy && (visibility != ChatStatus.VIS_CUSTOM || chosen.isNotEmpty()),
                    loading = ui.busy,
                    modifier = Modifier.weight(1f),
                )
            }
            if (onOpenProfile != null) {
                TextButton(onClick = onOpenProfile, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(stringResource(R.string.chat_status_profile)) }
            }
        }
    }
    if (pickPeople) {
        PeoplePickerDialog(people, chosen.toList(), ui.profile?.user?.userId, onDone = { ids ->
            chosen.clear()
            chosen.addAll(ids)
            visibility = if (ids.isEmpty()) ChatStatus.VIS_ALL else ChatStatus.VIS_CUSTOM
            pickPeople = false
        }, onDismiss = { pickPeople = false })
    }
    if (pickEta) {
        TimeOnlyDialog(eta ?: (System.currentTimeMillis() + 30 * 60_000L), onPick = { ms ->
            eta = if (ms < System.currentTimeMillis()) ms + 86_400_000L else ms
            pickEta = false
        }, onDismiss = { pickPeople = false; pickEta = false })
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
        color = GateControlTheme.extraColors.faint,
    )
}

/** Who sees a status / story ("Ausgewählte …"). */
@Composable
fun PeoplePickerDialog(people: List<ChatPerson>, selected: List<Long>, myId: Long?, onDone: (List<Long>) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val picked = remember { mutableStateListOf<Long>().apply { addAll(selected) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_status_pick_people)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (people.isEmpty()) androidx.compose.material3.CircularProgressIndicator(Modifier.size(24.dp))
                people.filter { it.userId != myId }.forEach { p ->
                    val on = p.userId in picked
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(10.dp))
                            .clickable(role = Role.Checkbox) { if (on) picked.remove(p.userId) else picked.add(p.userId) }
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        ChatPersonAvatar(p, 36.dp)
                        Text(ChatText.name(ctx, p), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                        if (on) Icon(Icons.Outlined.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDone(picked.toList()) }) { Text(stringResource(R.string.chat_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.chat_cancel)) } },
    )
}
