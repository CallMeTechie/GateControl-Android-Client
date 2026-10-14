package com.gatecontrol.android.ui.familie

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.R
import com.gatecontrol.android.familie.emergency.EmergencyContact
import com.gatecontrol.android.familie.emergency.EmergencyInsurance
import com.gatecontrol.android.familie.emergency.EmergencyMember
import com.gatecontrol.android.familie.emergency.EmergencyMemberDraft
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcFilterChip
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcTextField
import com.gatecontrol.android.ui.theme.GateControlTheme

/** Keeps a contact list across rotation (label, phone, note triples). */
private val ContactsSaver = Saver<MutableState<List<EmergencyContact>>, ArrayList<String>>(
    save = { state -> ArrayList(state.value.flatMap { listOf(it.label, it.phone, it.note) }) },
    restore = { flat -> mutableStateOf(flat.chunked(3).filter { it.size == 3 }.map { EmergencyContact(it[0], it[1], it[2]) }) },
)

/** Contacts that can be sent: every row with a number needs a label; empty rows are dropped. */
private fun List<EmergencyContact>.sendable(): List<EmergencyContact>? {
    val rows = filter { it.label.isNotBlank() || it.phone.isNotBlank() || it.note.isNotBlank() }
    return if (rows.all { it.label.isNotBlank() && EmergencyContact.PHONE_RE.matches(it.phone.trim()) }) rows else null
}

/** Edit one member's card: blood type, weight, allergies, medication, conditions, notes, insurance, own numbers. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EmergencyMemberDialog(
    member: EmergencyMember,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSave: (EmergencyMemberDraft) -> Unit,
) {
    val extra = GateControlTheme.extraColors
    var blood by rememberSaveable { mutableStateOf(member.bloodType) }
    var weight by rememberSaveable { mutableStateOf(member.weightKg?.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() }.orEmpty()) }
    var allergies by rememberSaveable { mutableStateOf(member.allergies) }
    var medications by rememberSaveable { mutableStateOf(member.medications) }
    var conditions by rememberSaveable { mutableStateOf(member.conditions) }
    var notes by rememberSaveable { mutableStateOf(member.notes) }
    var insName by rememberSaveable { mutableStateOf(member.insurance?.name.orEmpty()) }
    var insNumber by rememberSaveable { mutableStateOf(member.insurance?.number.orEmpty()) }
    var contacts by rememberSaveable(saver = ContactsSaver) { mutableStateOf(member.contacts) }

    val weightKg = weight.replace(',', '.').trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()
    val weightOk = weight.isBlank() || (weightKg != null && weightKg > 0 && weightKg < 500)
    val sendable = contacts.sendable()

    FamilieFormDialog(
        title = stringResource(R.string.familie_emergency_edit_title, member.name),
        onDismiss = onDismiss,
        saveEnabled = !saving && weightOk && sendable != null,
        onSave = {
            onSave(
                EmergencyMemberDraft(
                    bloodType = blood,
                    weightKg = weightKg,
                    allergies = allergies,
                    medications = medications,
                    conditions = conditions,
                    notes = notes,
                    insurance = EmergencyInsurance(insName, insNumber).takeIf { insName.isNotBlank() || insNumber.isNotBlank() },
                    contacts = sendable.orEmpty(),
                ),
            )
        },
    ) {
        Text(stringResource(R.string.familie_emergency_blood), style = MaterialTheme.typography.titleSmall, color = extra.muted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            GcFilterChip(text = stringResource(R.string.familie_emergency_unknown), selected = blood == null, onClick = { blood = null })
            EmergencyMember.BLOOD_TYPES.forEach { t -> GcFilterChip(text = t, selected = blood == t, onClick = { blood = t }) }
        }
        GcTextField(
            value = weight,
            onValueChange = { v -> weight = v.filter { it.isDigit() || it == ',' || it == '.' }.take(6) },
            label = stringResource(R.string.familie_emergency_weight_label),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            error = if (weightOk) null else stringResource(R.string.familie_emergency_weight_invalid),
        )
        GcTextField(value = allergies, onValueChange = { allergies = it.take(EmergencyMember.MAX_TEXT) }, label = stringResource(R.string.familie_emergency_allergies), placeholder = stringResource(R.string.familie_emergency_allergies_hint), singleLine = false)
        GcTextField(value = medications, onValueChange = { medications = it.take(EmergencyMember.MAX_TEXT) }, label = stringResource(R.string.familie_emergency_medications), singleLine = false)
        GcTextField(value = conditions, onValueChange = { conditions = it.take(EmergencyMember.MAX_TEXT) }, label = stringResource(R.string.familie_emergency_conditions), singleLine = false)
        GcTextField(value = notes, onValueChange = { notes = it.take(EmergencyMember.MAX_NOTES) }, label = stringResource(R.string.familie_emergency_notes), singleLine = false)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GcTextField(value = insName, onValueChange = { insName = it.take(EmergencyInsurance.MAX_NAME) }, label = stringResource(R.string.familie_emergency_insurance), modifier = Modifier.weight(1f))
            GcTextField(value = insNumber, onValueChange = { insNumber = it.take(EmergencyInsurance.MAX_NUMBER) }, label = stringResource(R.string.familie_emergency_insurance_number_label), modifier = Modifier.weight(1f))
        }
        Text(stringResource(R.string.familie_emergency_member_contacts, member.name), style = MaterialTheme.typography.titleSmall, color = extra.muted)
        ContactsEditor(contacts, EmergencyMember.MAX_CONTACTS) { contacts = it }
    }
}

/** Edit the family's important numbers (parents). */
@Composable
fun EmergencyContactsDialog(
    contacts: List<EmergencyContact>,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSave: (List<EmergencyContact>) -> Unit,
) {
    var list by rememberSaveable(saver = ContactsSaver) { mutableStateOf(contacts) }
    val sendable = list.sendable()
    FamilieFormDialog(
        title = stringResource(R.string.familie_emergency_family_contacts),
        onDismiss = onDismiss,
        saveEnabled = !saving && sendable != null,
        onSave = { onSave(sendable.orEmpty()) },
    ) {
        Text(stringResource(R.string.familie_emergency_family_contacts_hint), style = MaterialTheme.typography.bodySmall, color = GateControlTheme.extraColors.muted)
        ContactsEditor(list, com.gatecontrol.android.familie.emergency.EmergencyCard.MAX_FAMILY_CONTACTS) { list = it }
    }
}

/**
 * Rows of label / phone / note with remove buttons; "Nummer hinzufügen" and
 * label suggestions (no numbers are suggested — none we could get wrong).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ContactsEditor(contacts: List<EmergencyContact>, max: Int, onChange: (List<EmergencyContact>) -> Unit) {
    val extra = GateControlTheme.extraColors
    fun update(i: Int, c: EmergencyContact) = onChange(contacts.toMutableList().also { it[i] = c })
    contacts.forEachIndexed { i, c ->
        GcCard(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GcTextField(
                    value = c.label,
                    onValueChange = { update(i, c.copy(label = it.take(EmergencyContact.MAX_LABEL))) },
                    label = stringResource(R.string.familie_emergency_contact_label),
                    modifier = Modifier.weight(1f),
                )
                GcIconButton(
                    GcIcons.Trash,
                    contentDescription = stringResource(R.string.familie_emergency_contact_remove_cd, c.label),
                    onClick = { onChange(contacts.filterIndexed { j, _ -> j != i }) },
                    modifier = Modifier.padding(top = 26.dp),
                )
            }
            GcTextField(
                value = c.phone,
                onValueChange = { update(i, c.copy(phone = EmergencyContact.cleanPhone(it))) },
                label = stringResource(R.string.familie_emergency_contact_phone),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                error = if (c.phone.isNotBlank() && c.label.isBlank()) stringResource(R.string.familie_emergency_contact_needs_label) else null,
            )
            GcTextField(
                value = c.note,
                onValueChange = { update(i, c.copy(note = it.take(EmergencyContact.MAX_NOTE))) },
                label = stringResource(R.string.familie_emergency_contact_note),
                placeholder = stringResource(R.string.familie_emergency_contact_note_hint),
            )
        }
    }
    if (contacts.size < max) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GcOutlineButton(
                text = stringResource(R.string.familie_emergency_contact_add),
                onClick = { onChange(contacts + EmergencyContact("", "", "")) },
                icon = GcIcons.Plus,
            )
            Text(stringResource(R.string.familie_emergency_contact_suggest), style = MaterialTheme.typography.bodySmall, color = extra.muted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                stringArrayResource(R.array.familie_emergency_label_suggestions)
                    .filter { label -> contacts.none { it.label == label } }
                    .forEach { label -> GcFilterChip(text = label, selected = false, onClick = { onChange(contacts + EmergencyContact(label, "", "")) }) }
            }
        }
    }
}
