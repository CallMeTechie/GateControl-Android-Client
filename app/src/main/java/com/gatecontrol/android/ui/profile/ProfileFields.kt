package com.gatecontrol.android.ui.profile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.R
import com.gatecontrol.android.chat.ChatNewChat
import com.gatecontrol.android.profile.PBirthday
import com.gatecontrol.android.profile.PEmail
import com.gatecontrol.android.profile.PPhone
import com.gatecontrol.android.profile.ProfileDetails
import com.gatecontrol.android.profile.ProfileEdit
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcChevron
import com.gatecontrol.android.ui.components.GcChip
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.components.GcRowDivider
import com.gatecontrol.android.ui.components.GcSectionLabel
import com.gatecontrol.android.ui.components.GcSegmented
import com.gatecontrol.android.ui.components.GcSwitch
import com.gatecontrol.android.ui.components.GcSwitchRow
import com.gatecontrol.android.ui.components.GcTextField
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.components.SecureScreenEffect
import com.gatecontrol.android.ui.familie.FamilieDateField
import com.gatecontrol.android.ui.familie.appLocale
import com.gatecontrol.android.ui.theme.GateControlTheme
import java.time.Month
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/** Taps of the profile's groups. */
class ProfileFieldActions(
    val edit: (ProfileEditor) -> Unit,
    val addPhone: () -> Unit,
    val editPhone: (Long) -> Unit,
    val email: (PEmail) -> Unit,
    val recognition: (byWhatsapp: Boolean?, byPhone: Boolean?) -> Unit,
    /** Bridges 7a: „An meinem verknüpften Signal“. */
    val recognitionSignal: (Boolean) -> Unit = {},
    /** Bridges 7b: „An meinem verknüpften Telegram“. */
    val recognitionTelegram: (Boolean) -> Unit = {},
    /** „Wer sieht dein Bild?“ — the sheet with the four levels. */
    val avatarVisibility: () -> Unit = {},
)

// ─── pure texts (unit-tested) ────────────────────────────────────

/** Pure parts of the page's texts. */
object ProfileFieldText {
    /** „14. März“ / „March 14“, with the year when [withYear] and known. */
    fun birthday(b: PBirthday, locale: Locale, withYear: Boolean): String {
        val month = Month.of(b.month).getDisplayName(TextStyle.FULL, locale)
        val year = b.year?.takeIf { withYear }
        return if (locale.language == "de") "${b.day}. $month" + (year?.let { " $it" } ?: "")
        else "$month ${b.day}" + (year?.let { ", $it" } ?: "")
    }

    /** A time zone the device knows (IANA), else null. */
    fun validZone(s: String?): String? = s?.trim()?.takeIf { it.isNotEmpty() && it.length <= 64 }?.let { z ->
        runCatching { ZoneId.of(z) }.getOrNull()?.takeIf { it.id == z && '/' in z }?.id
    }

    /** A number as the person reads it: `+49 151 00000000`. */
    fun number(e164: String): String = ChatNewChat.number(e164).display ?: e164

    /** A number for TalkBack: the digits one by one. */
    fun numberSpoken(e164: String): String = e164.toCharArray().joinToString(" ")
}

@Composable
private fun kindLabel(kind: String): String = stringResource(
    when (kind) {
        "mobile" -> R.string.profile_kind_mobile
        "work" -> R.string.profile_kind_work
        "home" -> R.string.profile_kind_home
        else -> R.string.profile_kind_other
    },
)

@Composable
internal fun visLabel(level: String, short: Boolean = true): String = stringResource(
    when (level) {
        ProfileDetails.VIS_NONE -> R.string.profile_vis_none
        ProfileDetails.VIS_SELF -> R.string.profile_vis_self
        ProfileDetails.VIS_FAMILY -> R.string.profile_vis_family
        else -> if (short) R.string.profile_vis_all_short else R.string.profile_vis_all
    },
)

/** A level of „Wer sieht dein Bild?“: Alle (short) / Alle auf diesem Server, Kontakte, Familie, Nur ich. */
@Composable
internal fun avatarVisLabel(level: String, short: Boolean = false): String = stringResource(
    when (level) {
        ProfileDetails.VIS_CONTACTS -> R.string.profile_avatar_vis_contacts
        ProfileDetails.VIS_FAMILY -> R.string.profile_avatar_vis_family
        ProfileDetails.VIS_SELF -> R.string.profile_avatar_vis_self
        else -> if (short) R.string.profile_avatar_vis_all_short else R.string.profile_avatar_vis_all
    },
)

@Composable
private fun stateLabel(state: String): String = stringResource(
    when (state) {
        PPhone.STATE_WHATSAPP -> R.string.profile_state_whatsapp
        PPhone.STATE_SIGNAL -> R.string.profile_state_signal
        PPhone.STATE_TELEGRAM -> R.string.profile_state_telegram
        PPhone.STATE_ADMIN -> R.string.profile_state_admin
        PPhone.STATE_DIRECTORY -> R.string.profile_state_directory
        else -> R.string.profile_state_unconfirmed
    },
)

@Composable
private fun languageLabel(code: String?): String = when (code?.substringBefore('-')) {
    null -> stringResource(R.string.profile_field_empty)
    "de" -> "Deutsch"
    "en" -> "English"
    else -> Locale.forLanguageTag(code).let { it.getDisplayLanguage(it).replaceFirstChar { c -> c.titlecase(it) } }.ifBlank { code }
}

/** Who sees a row: Niemand / Nur ich (grey), Familie (blue), Alle (green). */
@Composable
private fun VisChip(level: String) {
    val label = visLabel(level)
    val desc = stringResource(R.string.profile_vis_desc, visLabel(level, short = false))
    GcChip(
        label,
        tone = when (level) {
            ProfileDetails.VIS_ALL -> GcTone.Ok
            ProfileDetails.VIS_FAMILY -> GcTone.Info
            else -> GcTone.Neutral
        },
        small = true,
        modifier = Modifier.semantics { contentDescription = desc },
    )
}

@Composable
private fun Group(label: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        GcSectionLabel(label, modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
        GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.Top, content = content)
    }
}

/** A row of a group (`.fr`): icon, the field's name small, its value, the chips below, a chevron or the lock. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FieldRow(
    icon: ImageVector,
    label: String,
    value: String,
    onClick: (() -> Unit)?,
    locked: Boolean = false,
    valueDescription: String? = null,
    chips: (@Composable () -> Unit)? = null,
) {
    val extra = GateControlTheme.extraColors
    val editDesc = stringResource(R.string.profile_edit_desc, label)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .then(if (onClick != null && !locked) Modifier.clickable(role = Role.Button, onClickLabel = editDesc, onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = extra.muted)
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = if (valueDescription != null) Modifier.semantics { contentDescription = valueDescription } else Modifier,
            )
            if (locked) Text(stringResource(R.string.profile_locked), style = MaterialTheme.typography.bodySmall, color = extra.muted)
            if (chips != null) {
                FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { chips() }
            }
        }
        when {
            locked -> Icon(GcIcons.Lock, contentDescription = null, tint = extra.faint, modifier = Modifier.size(20.dp))
            onClick != null -> GcChevron()
        }
    }
}

@Composable
private fun AddRow(text: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(GcIcons.Plus, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    }
}

/**
 * The groups of the profile under the picture: „Über dich“, „Telefonnummern“ (when the server allows them), „E-Mail“,
 * „Geburtstag“, „Sprache und Zeitzone“ and „Erkennung“. Each row shows who sees it.
 */
@Composable
fun ProfileFieldGroups(d: ProfileDetails, a: ProfileFieldActions, saving: Boolean, modifier: Modifier = Modifier) {
    val empty = stringResource(R.string.profile_field_empty)
    val locale = appLocale()
    Column(modifier.testTag("profile-fields"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Group(stringResource(R.string.profile_group_about)) {
            // „Wer sieht dein Bild?“ (server with visibility.avatar): for all of GateControl
            d.avatarVisibility?.let { level ->
                FieldRow(GcIcons.Eye, stringResource(R.string.profile_avatar_vis_title), avatarVisLabel(level, short = true), a.avatarVisibility)
                GcRowDivider()
            }
            FieldRow(GcIcons.User, stringResource(R.string.profile_field_display_name), d.displayName, { a.edit(ProfileEditor.DISPLAY_NAME) }, locked = d.locked.displayName) {
                VisChip(ProfileDetails.VIS_ALL)
            }
            GcRowDivider()
            val names = listOfNotNull(d.firstName, d.lastName).joinToString(" ").ifBlank { empty }
            FieldRow(GcIcons.IdCard, stringResource(R.string.profile_field_names), names, { a.edit(ProfileEditor.NAMES) }) { VisChip(d.visibilityOf("firstName")) }
            GcRowDivider()
            FieldRow(GcIcons.Info, stringResource(R.string.profile_field_about), d.about ?: empty, { a.edit(ProfileEditor.ABOUT) }) { VisChip(d.visibilityOf("about")) }
        }
        if (d.features.phones) {
            Group(stringResource(R.string.profile_group_phones)) {
                d.phones.forEachIndexed { i, p ->
                    if (i > 0) GcRowDivider()
                    FieldRow(
                        GcIcons.Phone,
                        kindLabel(p.kind),
                        ProfileFieldText.number(p.number),
                        { a.editPhone(p.id) },
                        valueDescription = ProfileFieldText.numberSpoken(p.number),
                    ) {
                        GcChip(stateLabel(p.state), tone = if (p.confirmed) GcTone.Ok else GcTone.Neutral, small = true)
                        VisChip(p.visibility)
                        if (p.useForRecognition) GcChip(stringResource(R.string.profile_phone_recognise), tone = GcTone.Ok, small = true)
                    }
                }
                if (d.canAddPhone) {
                    if (d.phones.isNotEmpty()) GcRowDivider()
                    AddRow(stringResource(R.string.profile_phone_add), a.addPhone)
                }
            }
        }
        Group(stringResource(R.string.profile_group_email)) {
            FieldRow(GcIcons.Mail, stringResource(R.string.profile_email_main), d.email ?: empty, { a.edit(ProfileEditor.EMAIL) }, locked = d.locked.email) {
                VisChip(d.visibilityOf("email"))
            }
            d.emails.forEach { e ->
                GcRowDivider()
                FieldRow(GcIcons.Mail, stringResource(R.string.profile_email_more), e.address, { a.email(e) }) {
                    GcChip(
                        stringResource(if (e.confirmed) R.string.profile_email_state_confirmed else R.string.profile_state_unconfirmed),
                        tone = if (e.confirmed) GcTone.Ok else GcTone.Neutral,
                        small = true,
                    )
                    VisChip(d.visibilityOf("email"))
                }
            }
            if (d.canAddEmail) {
                GcRowDivider()
                AddRow(stringResource(R.string.profile_email_add)) { a.edit(ProfileEditor.ADD_EMAIL) }
            }
        }
        Group(stringResource(R.string.profile_group_birthday)) {
            val b = d.birthday
            val text = when {
                b == null -> empty
                b.yearHidden || b.year == null -> ProfileFieldText.birthday(b, locale, withYear = false) +
                    if (b.yearHidden && b.year != null) " · " + stringResource(R.string.profile_birthday_year_hidden) else ""
                else -> ProfileFieldText.birthday(b, locale, withYear = true)
            }
            FieldRow(GcIcons.Gift, stringResource(R.string.profile_group_birthday), text, { a.edit(ProfileEditor.BIRTHDAY) }) { VisChip(d.visibilityOf("birthday")) }
        }
        Group(stringResource(R.string.profile_group_language)) {
            val tz = d.timezone ?: stringResource(R.string.profile_tz_server, d.serverTimezone ?: "UTC")
            FieldRow(GcIcons.Globe, stringResource(R.string.profile_group_language), languageLabel(d.language) + " · " + tz, { a.edit(ProfileEditor.LANGUAGE) }) {
                VisChip(d.visibilityOf("language"))
            }
        }
        if (d.features.recognition) {
            Group(stringResource(R.string.profile_group_recognition)) {
                GcSwitchRow(
                    label = stringResource(R.string.profile_rec_whatsapp),
                    description = if (!d.recognition.whatsappLinked) stringResource(R.string.profile_rec_unlinked) else null,
                    checked = d.recognition.byWhatsapp,
                    onCheckedChange = { a.recognition(it, null) },
                    icon = GcIcons.Scan,
                    enabled = !saving,
                )
                // Bridges 7a: one line per further linked service (while it is linked or the consent is on)
                if (d.recognition.signalLinked || d.recognition.bySignal) {
                    GcRowDivider()
                    GcSwitchRow(
                        label = stringResource(R.string.profile_rec_signal),
                        description = stringResource(R.string.profile_rec_signal_sub),
                        checked = d.recognition.bySignal,
                        onCheckedChange = { a.recognitionSignal(it) },
                        icon = GcIcons.Scan,
                        enabled = !saving,
                    )
                }
                // Bridges 7b: Telegram likewise
                if (d.recognition.telegramLinked || d.recognition.byTelegram) {
                    GcRowDivider()
                    GcSwitchRow(
                        label = stringResource(R.string.profile_rec_telegram),
                        description = stringResource(R.string.profile_rec_signal_sub),
                        checked = d.recognition.byTelegram,
                        onCheckedChange = { a.recognitionTelegram(it) },
                        icon = GcIcons.Scan,
                        enabled = !saving,
                    )
                }
                if (d.features.phones) {
                    GcRowDivider()
                    val n = d.recognition.phonesForRecognition
                    GcSwitchRow(
                        label = stringResource(R.string.profile_rec_phones),
                        description = pluralStringResource(R.plurals.profile_rec_phones_n, n, n),
                        checked = d.recognition.byPhone,
                        onCheckedChange = { a.recognition(null, it) },
                        icon = GcIcons.Phone,
                        enabled = !saving,
                    )
                }
            }
            Text(
                stringResource(R.string.profile_rec_hint),
                style = MaterialTheme.typography.bodySmall,
                color = GateControlTheme.extraColors.faint,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

/** „Meine Daten“ at the page's foot: the JSON file through the system's save dialog. */
@Composable
fun ProfileDataGroup(exporting: Boolean, onExport: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        Group(stringResource(R.string.profile_group_data)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .clickable(enabled = !exporting, role = Role.Button, onClick = onExport)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Icon(GcIcons.Download, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.profile_export), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text(stringResource(R.string.profile_export_desc), style = MaterialTheme.typography.bodySmall, color = GateControlTheme.extraColors.muted)
                }
                if (exporting) androidx.compose.material3.CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else GcChevron()
            }
        }
    }
}

// ─── the sheet of a number ───────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneSheet(
    draft: PhoneDraft,
    d: ProfileDetails,
    saving: Boolean,
    refusal: ProfileEdit.Refused?,
    onChange: ((PhoneDraft) -> PhoneDraft) -> Unit,
    onSave: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        // the sheet is a window of its own: no screenshot of the number there either
        SecureScreenEffect()
        PhoneSheetContent(draft, d, saving, refusal, onChange, onSave, onRemove)
    }
}

/** The sheet's content: Art, Nummer, „Wer sieht sie?“, „Zum Erkennen nutzen“, Speichern (and „Nummer entfernen“). */
@Composable
fun PhoneSheetContent(
    draft: PhoneDraft,
    d: ProfileDetails,
    saving: Boolean,
    refusal: ProfileEdit.Refused?,
    onChange: ((PhoneDraft) -> PhoneDraft) -> Unit,
    onSave: () -> Unit,
    onRemove: () -> Unit,
) {
    val extra = GateControlTheme.extraColors
    var touched by rememberSaveable(draft.id) { mutableStateOf(draft.id != null) }
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(bottom = 16.dp)
            .testTag("phone-sheet"),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(stringResource(R.string.profile_phone_title), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.profile_phone_kind), style = MaterialTheme.typography.titleSmall, color = extra.muted)
            GcSegmented(d.kinds.map { it to kindLabel(it) }, draft.kind, { k -> onChange { it.copy(kind = k) } })
        }
        val invalid = touched && draft.input.isNotBlank() && draft.e164 == null
        val serverNumber = refusal?.takeIf { it.field == "number" || it.code == "DUPLICATE" }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            GcTextField(
                value = draft.input,
                onValueChange = { v -> touched = true; onChange { it.copy(input = v.take(32)) } },
                label = stringResource(R.string.profile_phone_number),
                placeholder = "+49 151 …",
                hint = stringResource(R.string.profile_phone_hint),
                error = when {
                    serverNumber != null -> serverNumber.message ?: stringResource(ProfileFieldsViewModel.messageOf(serverNumber.code))
                    invalid -> stringResource(R.string.profile_phone_invalid)
                    else -> null
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                enabled = !saving,
            )
            draft.state?.let { s -> GcChip(stateLabel(s), tone = if (s != PPhone.STATE_UNCONFIRMED) GcTone.Ok else GcTone.Neutral, small = true) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.profile_vis_question), style = MaterialTheme.typography.titleSmall, color = extra.muted)
            Column(Modifier.selectableGroup()) {
                d.phoneLevels(draft.visibility).forEach { level ->
                    VisibilityOption(
                        label = visLabel(level, short = false),
                        description = when (level) {
                            ProfileDetails.VIS_NONE -> stringResource(R.string.profile_vis_none_desc)
                            ProfileDetails.VIS_FAMILY -> stringResource(R.string.profile_vis_family_desc)
                            else -> null
                        },
                        selected = draft.visibility == level,
                        enabled = !saving,
                    ) { onChange { it.copy(visibility = level) } }
                }
            }
        }
        if (d.features.recognition) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .toggleable(value = draft.useForRecognition, enabled = !saving, role = Role.Switch, onValueChange = { on -> onChange { it.copy(useForRecognition = on) } })
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.profile_phone_use), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text(stringResource(R.string.profile_phone_use_desc), style = MaterialTheme.typography.bodySmall, color = extra.muted)
                }
                GcSwitch(checked = draft.useForRecognition, onCheckedChange = null, enabled = !saving)
            }
        }
        refusal?.takeIf { it !== serverNumber }?.let { r ->
            Text(r.message ?: stringResource(ProfileFieldsViewModel.messageOf(r.code)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        GcPrimaryButton(
            text = stringResource(R.string.profile_save),
            onClick = { touched = true; onSave() },
            enabled = !saving && draft.e164 != null,
            loading = saving,
        )
        if (draft.id != null) {
            GcOutlineButton(text = stringResource(R.string.profile_phone_remove), onClick = onRemove, enabled = !saving)
        }
    }
}

@Composable
private fun VisibilityOption(label: String, description: String?, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = GateControlTheme.extraColors.muted) }
        }
    }
}

// ─── „Wer sieht dein Bild?“ ──────────────────────────────────────

/** The sheet of „Wer sieht dein Bild?“: four levels, a choice saves at once; the bridges never get the picture. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AvatarVisibilitySheet(current: String, saving: Boolean, refusal: ProfileEdit.Refused?, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        AvatarVisibilitySheetContent(current, saving, refusal, onPick)
    }
}

@Composable
fun AvatarVisibilitySheetContent(current: String, saving: Boolean, refusal: ProfileEdit.Refused?, onPick: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(bottom = 16.dp)
            .testTag("avatar-visibility-sheet"),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(stringResource(R.string.profile_avatar_vis_title), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
        Column(Modifier.selectableGroup()) {
            ProfileDetails.AVATAR_LEVELS.forEach { level ->
                VisibilityOption(
                    label = avatarVisLabel(level),
                    description = when (level) {
                        ProfileDetails.VIS_CONTACTS -> stringResource(R.string.profile_avatar_vis_contacts_desc)
                        ProfileDetails.VIS_SELF -> stringResource(R.string.profile_avatar_vis_self_desc)
                        else -> null
                    },
                    selected = current == level,
                    enabled = !saving,
                ) { if (level != current) onPick(level) }
            }
        }
        refusal?.let { r ->
            Text(r.message ?: stringResource(ProfileFieldsViewModel.messageOf(r.code)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Text(stringResource(R.string.profile_avatar_vis_bridges), style = MaterialTheme.typography.bodySmall, color = GateControlTheme.extraColors.muted)
    }
}

// ─── dialogs ─────────────────────────────────────────────────────

/** The question after the first number. */
@Composable
fun ProfileConsentDialog(onAnswer: (Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        containerColor = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.extraLarge,
        icon = { Icon(GcIcons.Scan, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text(stringResource(R.string.profile_consent_title), style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.profile_consent_text), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.profile_consent_hint), style = MaterialTheme.typography.bodySmall, color = GateControlTheme.extraColors.muted)
            }
        },
        confirmButton = { TextButton(onClick = { onAnswer(true) }) { Text(stringResource(R.string.profile_consent_yes)) } },
        dismissButton = { TextButton(onClick = { onAnswer(false) }) { Text(stringResource(R.string.profile_consent_no)) } },
    )
}

/** A dialog of a field: its [content], the refusal of the server, Speichern / Abbrechen, [extra] (e.g. remove). */
@Composable
private fun FieldDialog(
    title: String,
    saving: Boolean,
    refusal: ProfileEdit.Refused?,
    canSave: Boolean,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    saveLabel: String = stringResource(R.string.profile_save),
    extra: Pair<String, () -> Unit>? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        containerColor = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.extraLarge,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                content()
                refusal?.let { r ->
                    Text(r.message ?: stringResource(ProfileFieldsViewModel.messageOf(r.code)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                extra?.let { (label, click) ->
                    TextButton(onClick = click, enabled = !saving) { Text(label, color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onSave, enabled = canSave && !saving) { Text(saveLabel) } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text(stringResource(R.string.common_cancel)) } },
    )
}

/** Nur ich · Familie · Alle (Familie only with a family). */
@Composable
private fun FieldVisibility(d: ProfileDetails, value: String, onChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.profile_vis_question), style = MaterialTheme.typography.titleSmall, color = GateControlTheme.extraColors.muted)
        GcSegmented(d.fieldLevels(value).map { it to visLabel(it) }, value, onChange)
    }
}

/** The dialog of [editor]. */
@Composable
fun ProfileEditorDialog(
    editor: ProfileEditor,
    d: ProfileDetails,
    saving: Boolean,
    refusal: ProfileEdit.Refused?,
    vm: ProfileFieldsViewModel,
) {
    val dismiss = vm::close
    when (editor) {
        ProfileEditor.DISPLAY_NAME -> {
            var name by rememberSaveable { mutableStateOf(d.displayName) }
            FieldDialog(stringResource(R.string.profile_field_display_name), saving, refusal, name.isNotBlank(), { vm.saveDisplayName(name) }, dismiss) {
                GcTextField(name, { name = it.take(d.limits.displayName) }, stringResource(R.string.profile_field_display_name), enabled = !saving)
            }
        }
        ProfileEditor.NAMES -> {
            var first by rememberSaveable { mutableStateOf(d.firstName.orEmpty()) }
            var last by rememberSaveable { mutableStateOf(d.lastName.orEmpty()) }
            var vis by rememberSaveable { mutableStateOf(d.visibilityOf("firstName")) }
            FieldDialog(stringResource(R.string.profile_field_names), saving, refusal, true, { vm.saveNames(first, last, vis) }, dismiss) {
                GcTextField(first, { first = it.take(d.limits.name) }, stringResource(R.string.profile_field_first), enabled = !saving)
                GcTextField(last, { last = it.take(d.limits.name) }, stringResource(R.string.profile_field_last), enabled = !saving)
                FieldVisibility(d, vis) { vis = it }
            }
        }
        ProfileEditor.ABOUT -> {
            var about by rememberSaveable { mutableStateOf(d.about.orEmpty()) }
            var vis by rememberSaveable { mutableStateOf(d.visibilityOf("about")) }
            FieldDialog(stringResource(R.string.profile_field_about), saving, refusal, true, { vm.saveAbout(about, vis) }, dismiss) {
                GcTextField(
                    about, { about = it.replace('\n', ' ').take(d.limits.about) }, stringResource(R.string.profile_field_about),
                    hint = stringResource(R.string.profile_field_count, about.length, d.limits.about), singleLine = false, enabled = !saving,
                )
                FieldVisibility(d, vis) { vis = it }
            }
        }
        ProfileEditor.EMAIL -> {
            var address by rememberSaveable { mutableStateOf(d.email.orEmpty()) }
            var vis by rememberSaveable { mutableStateOf(d.visibilityOf("email")) }
            FieldDialog(stringResource(R.string.profile_email_main), saving, refusal, true, { vm.saveEmail(address, vis) }, dismiss) {
                GcTextField(
                    address, { address = it.trim().take(254) }, stringResource(R.string.profile_email_address),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), enabled = !saving && !d.locked.email,
                )
                FieldVisibility(d, vis) { vis = it }
            }
        }
        ProfileEditor.ADD_EMAIL -> {
            var address by rememberSaveable { mutableStateOf("") }
            FieldDialog(stringResource(R.string.profile_email_add), saving, refusal, address.contains('@'), { vm.addEmail(address) }, dismiss) {
                GcTextField(
                    address, { address = it.trim().take(254) }, stringResource(R.string.profile_email_address),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), enabled = !saving,
                )
            }
        }
        ProfileEditor.BIRTHDAY -> {
            val b = d.birthday
            // the picker needs a year: a birthday without one keeps its own (it is sent without, unless a date is picked)
            var date by rememberSaveable { mutableStateOf(b?.takeIf { it.year != null }?.date) }
            var hide by rememberSaveable { mutableStateOf(b?.yearHidden ?: false) }
            var vis by rememberSaveable { mutableStateOf(d.visibilityOf("birthday")) }
            val picked = date?.let { s -> runCatching { java.time.LocalDate.parse(s) }.getOrNull() }
            val result = picked?.let { PBirthday(it.monthValue, it.dayOfMonth, it.year, hide) } ?: b?.copy(yearHidden = hide)
            FieldDialog(
                stringResource(R.string.profile_group_birthday), saving, refusal, result != null, { vm.saveBirthday(result, vis) }, dismiss,
                extra = if (b != null) stringResource(R.string.profile_birthday_remove) to { vm.saveBirthday(null, vis) } else null,
            ) {
                FamilieDateField(stringResource(R.string.profile_birthday_date), date, { date = it })
                if (picked == null && b != null) Text(ProfileFieldText.birthday(b, appLocale(), withYear = false), style = MaterialTheme.typography.bodyMedium)
                GcSwitchRow(
                    label = stringResource(R.string.profile_birthday_hide_year),
                    description = stringResource(R.string.profile_birthday_hide_year_desc),
                    checked = hide,
                    onCheckedChange = { hide = it },
                    enabled = !saving,
                )
                FieldVisibility(d, vis) { vis = it }
            }
        }
        ProfileEditor.LANGUAGE -> {
            var lang by rememberSaveable { mutableStateOf(d.language ?: "de") }
            val device = remember { ProfileFieldText.validZone(java.util.TimeZone.getDefault().id) }
            var tzMode by rememberSaveable {
                mutableStateOf(
                    when (d.timezone) {
                        null -> "server"
                        device -> "device"
                        else -> "other"
                    },
                )
            }
            var other by rememberSaveable { mutableStateOf(d.timezone?.takeIf { it != device }.orEmpty()) }
            var vis by rememberSaveable { mutableStateOf(d.visibilityOf("language")) }
            val tz = when (tzMode) {
                "server" -> null
                "device" -> device
                else -> ProfileFieldText.validZone(other)
            }
            val ok = tzMode != "other" || tz != null
            FieldDialog(stringResource(R.string.profile_group_language), saving, refusal, ok, { vm.saveLanguage(lang, tz, vis) }, dismiss) {
                Text(stringResource(R.string.profile_language), style = MaterialTheme.typography.titleSmall, color = GateControlTheme.extraColors.muted)
                GcSegmented(listOf("de" to "Deutsch", "en" to "English"), lang, { lang = it })
                Text(stringResource(R.string.profile_timezone), style = MaterialTheme.typography.titleSmall, color = GateControlTheme.extraColors.muted)
                Column(Modifier.selectableGroup()) {
                    VisibilityOption(stringResource(R.string.profile_tz_server, d.serverTimezone ?: "UTC"), null, tzMode == "server", !saving) { tzMode = "server" }
                    if (device != null && device != d.serverTimezone) {
                        VisibilityOption(stringResource(R.string.profile_tz_device, device), null, tzMode == "device", !saving) { tzMode = "device" }
                    }
                    VisibilityOption(stringResource(R.string.profile_tz_other), null, tzMode == "other", !saving) { tzMode = "other" }
                }
                if (tzMode == "other") {
                    GcTextField(
                        other, { other = it.trim().take(64) }, stringResource(R.string.profile_timezone),
                        placeholder = "Europe/Berlin", hint = stringResource(R.string.profile_tz_hint),
                        error = if (other.isNotEmpty() && tz == null) stringResource(R.string.profile_tz_invalid) else null, enabled = !saving,
                    )
                }
                FieldVisibility(d, vis) { vis = it }
            }
        }
    }
}

/** A further address: enter its code, send a new one, remove it. */
@Composable
fun ProfileEmailDialog(
    email: PEmail,
    d: ProfileDetails,
    saving: Boolean,
    refusal: ProfileEdit.Refused?,
    asking: Boolean,
    onCode: (String) -> Unit,
    onAsk: () -> Unit,
    onResend: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    var code by rememberSaveable(email.id) { mutableStateOf("") }
    val canConfirm = !email.confirmed && d.features.emailConfirm
    if (asking && canConfirm) {
        FieldDialog(
            stringResource(R.string.profile_email_code_title), saving, refusal, code.length == 6, { onCode(code) }, onDismiss,
            saveLabel = stringResource(R.string.profile_email_confirm),
            extra = stringResource(R.string.profile_email_remove) to onRemove,
        ) {
            Text(stringResource(R.string.profile_email_code_text, email.address), style = MaterialTheme.typography.bodyMedium)
            GcTextField(
                code, { v -> code = v.filter { it.isDigit() }.take(6) }, stringResource(R.string.profile_email_code), mono = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), enabled = !saving,
            )
            TextButton(onClick = onResend, enabled = !saving) { Text(stringResource(R.string.profile_email_resend)) }
        }
        return
    }
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        containerColor = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.extraLarge,
        title = { Text(email.address, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(if (email.confirmed) R.string.profile_email_state_confirmed else R.string.profile_state_unconfirmed),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (canConfirm) {
                    TextButton(onClick = onAsk, enabled = !saving) { Text(stringResource(R.string.profile_email_code_title)) }
                    TextButton(onClick = onResend, enabled = !saving) { Text(stringResource(R.string.profile_email_resend)) }
                }
                TextButton(onClick = onRemove, enabled = !saving) { Text(stringResource(R.string.profile_email_remove), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text(stringResource(R.string.common_done)) } },
    )
}
