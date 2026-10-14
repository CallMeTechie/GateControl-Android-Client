package com.gatecontrol.android.ui.familie

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.familie.FamilieApi
import com.gatecontrol.android.familie.FamilieProfile
import com.gatecontrol.android.familie.emergency.EmergencyCard
import com.gatecontrol.android.familie.emergency.EmergencyContact
import com.gatecontrol.android.familie.emergency.EmergencyMember
import com.gatecontrol.android.familie.emergency.EmergencyState
import com.gatecontrol.android.ui.components.GcBanner
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcFilterChip
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcRowDivider
import com.gatecontrol.android.ui.components.GcSubpageBar
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.theme.GateControlTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private sealed interface EmergencyForm {
    data class Member(val member: EmergencyMember) : EmergencyForm

    data object Contacts : EmergencyForm
}

/**
 * Notfall-Karte (mockup AppEmergency): one card per member (allergies,
 * blood type, medication, conditions), the member's and the family's
 * numbers with dial buttons, the insurance number masked until tapped and
 * a fixed "Notruf 112". Works offline from the encrypted copy ("Stand: …").
 * Edit: my own card; parents every card and the family numbers.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmergencyScreen(onBack: () -> Unit, initialUserId: Int? = null, viewModel: EmergencyViewModel = hiltViewModel()) {
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val saved by viewModel.saved.collectAsStateWithLifecycle()
    val saving by viewModel.saving.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var selected by rememberSaveable { mutableStateOf(initialUserId) }
    var form by remember { mutableStateOf<EmergencyForm?>(null) }
    val messageText = message?.let { familieErrorText(it) }

    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    LaunchedEffect(messageText) {
        if (messageText != null) {
            Toast.makeText(context, messageText, Toast.LENGTH_LONG).show()
            viewModel.clearMessage()
        }
    }
    LaunchedEffect(saved) { if (saved > 0) form = null }

    val card = state.card
    val p = profile
    val shownId = selected?.takeIf { id -> card?.member(id) != null } ?: p?.me?.userId?.takeIf { card?.member(it) != null } ?: card?.members?.firstOrNull()?.userId
    val member = card?.member(shownId)

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        GcSubpageBar(title = stringResource(R.string.familie_emergency_title), onBack = onBack, backDescription = stringResource(R.string.common_back)) {
            if (state.offlineCopy) OfflineChip()
        }
        PullToRefreshBox(isRefreshing = state.loading && card != null, onRefresh = viewModel::refresh, modifier = Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (card != null && card.members.size > 1) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        card.members.forEach { m -> GcFilterChip(text = m.name, selected = m.userId == shownId, onClick = { selected = m.userId }) }
                    }
                }
                if (state.stale && card != null) StaleBanner(state)
                when {
                    card == null && (state.loading || state.error == null) -> FamilieLoading()
                    card == null -> {
                        FamilieErrorBanner(state.error, onRetry = viewModel::refresh)
                        if (state.error == FamilieApi.CODE_OFFLINE) {
                            Text(stringResource(R.string.familie_emergency_no_data), style = MaterialTheme.typography.bodySmall, color = GateControlTheme.extraColors.muted)
                        }
                    }
                    else -> {
                        if (member != null) {
                            MemberCard(member, p, onEdit = if (canEdit(p, member.userId)) ({ form = EmergencyForm.Member(member) }) else null)
                        }
                        ContactsCard(context, card, member, onEditFamily = if (p?.me?.isParent == true) ({ form = EmergencyForm.Contacts }) else null)
                    }
                }
                EmergencyCallButton(context)
                Text(
                    stringResource(R.string.familie_emergency_footer),
                    style = MaterialTheme.typography.bodySmall,
                    color = GateControlTheme.extraColors.muted,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }

    when (val f = form) {
        is EmergencyForm.Member -> EmergencyMemberDialog(
            member = f.member,
            saving = saving,
            onDismiss = { form = null },
            onSave = { draft -> viewModel.saveMember(f.member.userId, draft) },
        )
        EmergencyForm.Contacts -> EmergencyContactsDialog(
            contacts = card?.contacts.orEmpty(),
            saving = saving,
            onDismiss = { form = null },
            onSave = viewModel::saveContacts,
        )
        null -> Unit
    }
}

private fun canEdit(p: FamilieProfile?, userId: Int): Boolean = p != null && (p.me.isParent || p.me.userId == userId)

@Composable
private fun OfflineChip() {
    val extra = GateControlTheme.extraColors
    Row(
        Modifier
            .padding(end = 8.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, extra.border, RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(GcIcons.Download, contentDescription = null, tint = extra.muted, modifier = Modifier.size(14.dp))
        Text(stringResource(R.string.familie_emergency_offline), style = MaterialTheme.typography.labelMedium, color = extra.muted)
    }
}

@Composable
private fun StaleBanner(state: EmergencyState) {
    val at = state.savedAtMs?.let {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(appLocale())
            .format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()))
    } ?: "–"
    GcBanner(tone = GcTone.Warn, icon = GcIcons.Clock) {
        Column {
            Text(stringResource(R.string.familie_emergency_as_of, at), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            if (state.error == FamilieApi.CODE_OFFLINE) {
                Text(stringResource(R.string.familie_emergency_offline_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
private fun MemberCard(m: EmergencyMember, p: FamilieProfile?, onEdit: (() -> Unit)?) {
    val extra = GateControlTheme.extraColors
    val color = p?.everyone?.firstOrNull { it.userId == m.userId }?.color
    GcCard(borderColor = MaterialTheme.colorScheme.error.copy(alpha = 0.35f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FamilieAvatar(m.name, color, size = 48.dp)
            Column(Modifier.weight(1f)) {
                Text(m.name, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold), color = MaterialTheme.colorScheme.onSurface)
                val born = FamilieFormat.parseDate(m.birthdate)?.let {
                    stringResource(R.string.familie_emergency_born, DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(appLocale()).format(it))
                }
                val line = listOfNotNull(
                    born,
                    m.age?.let { pluralStringResource(R.plurals.familie_emergency_years, it, it) },
                    m.weightKg?.let { stringResource(R.string.familie_emergency_weight, formatKg(it)) },
                ).joinToString(" · ")
                if (line.isNotEmpty()) Text(line, style = MaterialTheme.typography.bodySmall, color = extra.muted)
            }
        }
        if (m.isEmpty) {
            Text(stringResource(R.string.familie_emergency_empty, m.name), style = MaterialTheme.typography.bodyMedium, color = extra.muted)
        } else {
            val none = stringResource(R.string.familie_emergency_none)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                InfoBox(stringResource(R.string.familie_emergency_allergies), m.allergies.ifBlank { none }, alert = m.allergies.isNotBlank(), modifier = Modifier.weight(1f))
                InfoBox(stringResource(R.string.familie_emergency_blood), m.bloodType ?: stringResource(R.string.familie_emergency_unknown), modifier = Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                InfoBox(stringResource(R.string.familie_emergency_medications), m.medications.ifBlank { none }, modifier = Modifier.weight(1f))
                InfoBox(stringResource(R.string.familie_emergency_conditions), m.conditions.ifBlank { none }, modifier = Modifier.weight(1f))
            }
            if (m.notes.isNotBlank()) InfoBox(stringResource(R.string.familie_emergency_notes), m.notes, modifier = Modifier.fillMaxWidth())
        }
        if (m.updatedAt != null) {
            val who = m.updatedByName ?: m.updatedBy?.let { memberName(p, it) }
            Text(
                if (who != null) stringResource(R.string.familie_emergency_updated_by, relativeStamp(m.updatedAt), who)
                else stringResource(R.string.familie_emergency_updated, relativeStamp(m.updatedAt)),
                style = MaterialTheme.typography.labelSmall,
                color = extra.faint,
            )
        }
        if (onEdit != null) GcOutlineButton(text = stringResource(R.string.familie_emergency_edit), onClick = onEdit, icon = GcIcons.Edit)
    }
}

/** "32" / "32,5" without trailing zeros. */
private fun formatKg(kg: Double): String =
    if (kg % 1.0 == 0.0) kg.toLong().toString() else String.format(java.util.Locale.getDefault(), "%.1f", kg)

@Composable
private fun InfoBox(label: String, value: String, modifier: Modifier = Modifier, alert: Boolean = false) {
    val extra = GateControlTheme.extraColors
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (alert) extra.errorBg else extra.panel2)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            label.uppercase(appLocale()),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = if (alert) MaterialTheme.colorScheme.error else extra.muted,
        )
        Text(value, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold), color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun ContactsCard(context: Context, card: EmergencyCard, member: EmergencyMember?, onEditFamily: (() -> Unit)?) {
    val extra = GateControlTheme.extraColors
    // The member's own numbers first, then the family's.
    val contacts = member?.contacts.orEmpty() + card.contacts
    val insurance = member?.insurance
    GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.Top) {
        contacts.forEachIndexed { i, c ->
            if (i > 0) GcRowDivider()
            ContactRow(context, c)
        }
        if (insurance != null) {
            if (contacts.isNotEmpty()) GcRowDivider()
            InsuranceRow(insurance.name, insurance.number)
        }
        if (contacts.isEmpty() && insurance == null) {
            Text(
                stringResource(R.string.familie_emergency_no_contacts),
                style = MaterialTheme.typography.bodyMedium,
                color = extra.muted,
                modifier = Modifier.padding(16.dp),
            )
        }
        if (onEditFamily != null) {
            GcRowDivider()
            Box(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                GcOutlineButton(text = stringResource(R.string.familie_emergency_edit_contacts), onClick = onEditFamily, icon = GcIcons.Edit)
            }
        }
    }
}

@Composable
private fun ContactRow(context: Context, c: EmergencyContact) {
    val extra = GateControlTheme.extraColors
    val callCd = stringResource(R.string.familie_emergency_call_cd, c.label)
    val noPhone = stringResource(R.string.familie_emergency_no_dialer)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(role = Role.Button) { dial(context, c.phone, noPhone) }
            .semantics { contentDescription = callCd + ", " + c.phone }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(c.label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(listOf(c.phone, c.note).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = extra.muted)
        }
        Box(Modifier.size(44.dp).clip(CircleShape).background(extra.accentBg), contentAlignment = Alignment.Center) {
            Icon(GcIcons.Call, contentDescription = null, tint = extra.accentText, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun InsuranceRow(name: String, number: String) {
    val extra = GateControlTheme.extraColors
    var revealed by rememberSaveable(number) { mutableStateOf(false) }
    val shown = if (revealed || number.length <= 4) number else "••••••" + number.takeLast(4)
    val cd = stringResource(if (revealed) R.string.familie_emergency_hide_cd else R.string.familie_emergency_reveal_cd)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .then(if (number.isNotEmpty()) Modifier.clickable(role = Role.Button, onClickLabel = cd) { revealed = !revealed } else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.familie_emergency_insurance), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(
                listOfNotNull(name.takeIf { it.isNotBlank() }, number.takeIf { it.isNotBlank() }?.let { stringResource(R.string.familie_emergency_insurance_number, shown) })
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = extra.muted,
            )
        }
        if (number.length > 4) Icon(GcIcons.Eye, contentDescription = null, tint = extra.faint, modifier = Modifier.size(20.dp))
    }
}

/** The fixed "Notruf 112" button (local, not from the server); opens the dialer. */
@Composable
private fun EmergencyCallButton(context: Context) {
    val noPhone = stringResource(R.string.familie_emergency_no_dialer)
    val cd = stringResource(R.string.familie_emergency_112_cd)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(EMERGENCY_RED)
            .clickable(role = Role.Button) { dial(context, "112", noPhone) }
            .semantics { contentDescription = cd },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
    ) {
        Icon(GcIcons.Call, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
        Text(stringResource(R.string.familie_emergency_112), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold), color = Color.White)
    }
}

/** The mockup's emergency red (#B91C1C), readable with white text in both themes. */
private val EMERGENCY_RED = Color(0xFFB91C1C)

/** The dialer with [phone] filled in (ACTION_DIAL: no CALL_PHONE permission, the user presses call). */
private fun dial(context: Context, phone: String, noDialer: String) {
    val number = phone.filter { it.isDigit() || it == '+' }
    if (number.isEmpty()) return
    try {
        context.startActivity(Intent(Intent.ACTION_DIAL, "tel:$number".toUri()))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, noDialer, Toast.LENGTH_LONG).show()
    }
}
