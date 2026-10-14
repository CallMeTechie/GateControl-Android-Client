package com.gatecontrol.android.ui.familie

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.familie.FamilieDest
import com.gatecontrol.android.familie.FamilieProfile
import com.gatecontrol.android.familie.money.AllowanceAccount
import com.gatecontrol.android.familie.money.AllowanceBooking
import com.gatecontrol.android.familie.money.AllowanceRule
import com.gatecontrol.android.familie.money.AllowanceRuleDraft
import com.gatecontrol.android.familie.money.ConvertRequest
import com.gatecontrol.android.familie.money.Money
import com.gatecontrol.android.familie.money.StarRate
import com.gatecontrol.android.ui.components.GcBanner
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcChevron
import com.gatecontrol.android.ui.components.GcFilterChip
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcListRow
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.components.GcRowDivider
import com.gatecontrol.android.ui.components.GcSegmented
import com.gatecontrol.android.ui.components.GcSwitchRow
import com.gatecontrol.android.ui.components.GcTextField
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.theme.GateControlTheme
import java.time.LocalDate

private sealed interface AllowanceDialog {
    data class Book(val childId: Int) : AllowanceDialog
    data class Rule(val childId: Int) : AllowanceDialog
    data class Convert(val childId: Int) : AllowanceDialog
}

/**
 * Taschengeld (mockup AppAllowance): the balance card, stars that can be
 * turned into money ("Umwandeln", parents confirm), the last bookings;
 * parents switch between children, book money in or out, set the rule
 * and decide convert requests. Bookkeeping only — nothing is paid.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AllowanceScreen(
    onBack: () -> Unit,
    onOpen: (FamilieDest) -> Unit,
    initialChildId: Int? = null,
    viewModel: AllowanceViewModel = hiltViewModel(),
) {
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val payload by viewModel.data.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    var selected by rememberSaveable { mutableStateOf(initialChildId) }
    var dialog by remember { mutableStateOf<AllowanceDialog?>(null) }
    FamilieToasts(viewModel)

    val p = profile
    val parent = p?.me?.isParent == true
    val data = payload
    val childId = when {
        p == null -> null
        !parent -> p.me.userId
        else -> selected?.takeIf { id -> p.children.any { it.userId == id } } ?: p.children.firstOrNull()?.userId
    }
    val account = data?.account(childId) ?: if (!parent) data?.accounts?.firstOrNull() else null
    val title = when {
        p == null -> stringResource(R.string.familie_allowance_title)
        !parent -> stringResource(R.string.familie_allowance_mine)
        childId != null -> stringResource(R.string.familie_allowance_title_of, memberName(p, childId))
        else -> stringResource(R.string.familie_allowance_title)
    }

    FamiliePageFrame(title = title, onBack = onBack, refreshing = busy && data != null, onRefresh = { viewModel.refresh() }) {
        when {
            data == null && error != null -> FamilieErrorBanner(error, onRetry = { viewModel.refresh() })
            data == null || p == null -> FamilieLoading()
            else -> {
                if (error != null) FamilieErrorBanner(error)
                if (parent && p.children.size > 1) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        p.children.forEach { c ->
                            GcFilterChip(text = c.name, selected = c.userId == childId, onClick = { selected = c.userId })
                        }
                    }
                }
                if (parent) {
                    val others = data.pending.filter { it.childId != childId }
                    if (others.isNotEmpty()) RequestsCard(p, others, viewModel)
                }
                when {
                    parent && p.children.isEmpty() -> FamilieEmpty(GcIcons.Wallet, stringResource(R.string.familie_allowance_no_children))
                    account == null -> FamilieEmpty(GcIcons.Wallet, stringResource(if (parent) R.string.familie_allowance_no_account_parent else R.string.familie_allowance_none))
                    else -> {
                        BalanceCard(account)
                        if (parent && account.pending.isNotEmpty()) RequestsCard(p, account.pending, viewModel)
                        StarsCard(account, parent, p.modules.stars, onConvert = { dialog = AllowanceDialog.Convert(account.childId) })
                        if (parent) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                GcPrimaryButton(
                                    text = stringResource(R.string.familie_allowance_book),
                                    onClick = { dialog = AllowanceDialog.Book(account.childId) },
                                    icon = GcIcons.Plus,
                                    modifier = Modifier.weight(1f),
                                )
                                GcOutlineButton(
                                    text = stringResource(R.string.familie_allowance_rule),
                                    onClick = { dialog = AllowanceDialog.Rule(account.childId) },
                                    icon = GcIcons.Sliders,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                        BookingsCard(account.bookings, p, onDelete = if (parent) ({ b -> viewModel.deleteBooking(account.childId, b.id) }) else null)
                        if (!parent && p.modules.wishes) {
                            GcCard(contentPadding = PaddingValues(0.dp), verticalArrangement = Arrangement.Top) {
                                GcListRow(
                                    title = stringResource(R.string.familie_wishes_mine),
                                    description = stringResource(R.string.familie_wishes_surprise),
                                    onClick = { onOpen(FamilieDest.Wishes()) },
                                    leading = { androidx.compose.material3.Icon(GcIcons.Gift, contentDescription = null, tint = GateControlTheme.extraColors.accentText, modifier = Modifier.size(22.dp)) },
                                    trailing = { GcChevron() },
                                )
                            }
                        }
                    }
                }
                GcBanner(tone = GcTone.Info, icon = GcIcons.Wallet) {
                    Text(stringResource(R.string.familie_money_bookkeeping), style = MaterialTheme.typography.bodySmall, color = GateControlTheme.extraColors.muted)
                }
            }
        }
    }

    when (val d = dialog) {
        is AllowanceDialog.Book -> BookingDialog(
            childName = memberName(p, d.childId),
            onDismiss = { dialog = null },
            onSave = { cents, note, spend ->
                viewModel.book(d.childId, cents, note, spend)
                dialog = null
            },
        )
        is AllowanceDialog.Rule -> RuleDialog(
            childName = memberName(p, d.childId),
            account = data?.account(d.childId),
            onDismiss = { dialog = null },
            onSave = { draft ->
                viewModel.saveRule(d.childId, draft)
                dialog = null
            },
        )
        is AllowanceDialog.Convert -> {
            val acc = data?.account(d.childId)
            val rate = acc?.starRate
            if (acc != null && rate != null) {
                ConvertDialog(
                    available = acc.convertibleStars ?: 0,
                    rate = rate,
                    onDismiss = { dialog = null },
                    onConvert = { stars ->
                        viewModel.convert(d.childId, stars)
                        dialog = null
                    },
                )
            }
        }
        null -> Unit
    }
}

/** Green balance card of the mockup; a negative balance shows red. */
@Composable
private fun BalanceCard(account: AllowanceAccount) {
    val negative = account.balanceCents < 0
    val bg = if (negative) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val fg = if (negative) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary
    Surface(color = bg, contentColor = fg, shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.familie_allowance_balance), style = MaterialTheme.typography.bodyMedium, color = fg.copy(alpha = 0.8f))
            Text(
                money(account.balanceCents),
                style = MaterialTheme.typography.headlineLarge.copy(fontSize = 40.sp, fontWeight = FontWeight.ExtraBold),
                color = fg,
            )
            Text(nextPayoutLine(account), style = MaterialTheme.typography.bodyMedium, color = fg.copy(alpha = 0.8f))
        }
    }
}

@Composable
private fun nextPayoutLine(account: AllowanceAccount): String {
    val rule = account.rule
    return when {
        rule == null || (!rule.active && rule.amountCents == 0) -> stringResource(R.string.familie_allowance_no_rule)
        !rule.active -> stringResource(R.string.familie_allowance_paused)
        else -> {
            val next = account.nextPayout
            val day = next?.let { n ->
                FamilieFormat.parseDate(n)?.let { payoutDay(it) } ?: FamilieFormat.parseInstant(n)?.let { relativeStamp(n) }
            } ?: ruleText(rule)
            stringResource(R.string.familie_allowance_next, day, money(rule.amountCents))
        }
    }
}

/** "Samstag" within a week, "heute" / "morgen", else the date. */
@Composable
private fun payoutDay(date: LocalDate): String {
    val n = FamilieFormat.daysFrom(LocalDate.now(), date)
    return when (n) {
        0L -> stringResource(R.string.familie_today)
        1L -> stringResource(R.string.familie_tomorrow)
        in 2L..6L -> date.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, appLocale())
        else -> FamilieFormat.day(date, appLocale())
    }
}

/** "jeden Samstag" / "am 1. des Monats". */
@Composable
private fun ruleText(rule: AllowanceRule): String =
    if (rule.interval == AllowanceRule.MONTHLY) {
        stringResource(R.string.familie_allowance_rule_day, rule.dayOfMonth ?: 1)
    } else {
        stringResource(R.string.familie_allowance_rule_every, weekdayNames()[((rule.weekday ?: 6) - 1).coerceIn(0, 6)])
    }

/** "12 Sterne · 10 Sterne = 1 €" with "Umwandeln" (child) — mockup's star row. */
@Composable
private fun StarsCard(account: AllowanceAccount, parent: Boolean, starsModule: Boolean, onConvert: () -> Unit) {
    val rate = account.starRate ?: return
    if (!starsModule) return
    val extra = GateControlTheme.extraColors
    val stars = account.convertibleStars ?: 0
    val pending = account.pending.firstOrNull()
    GcCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(extra.warnBg),
                contentAlignment = Alignment.Center,
            ) { Text("★", color = extra.warn, style = MaterialTheme.typography.titleLarge) }
            Column(Modifier.weight(1f)) {
                Text(
                    pluralStringResource(R.plurals.familie_allowance_stars_have, stars, stars) + " · " +
                        pluralStringResource(R.plurals.familie_allowance_rate, rate.stars, rate.stars, money(rate.cents)),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    when {
                        pending != null -> stringResource(R.string.familie_allowance_pending, pending.stars, money(pending.cents ?: Money.starsValue(pending.stars, rate)))
                        parent -> stringResource(R.string.familie_allowance_parents_decide)
                        else -> stringResource(R.string.familie_allowance_parents_confirm)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = extra.muted,
                )
            }
            if (!parent) {
                GcOutlineButton(
                    text = stringResource(R.string.familie_allowance_convert),
                    onClick = onConvert,
                    enabled = pending == null && Money.convertSteps(stars, rate).isNotEmpty(),
                    fillWidth = false,
                    minHeight = 38.dp,
                )
            }
        }
    }
}

@Composable
private fun RequestsCard(p: FamilieProfile, requests: List<ConvertRequest>, viewModel: AllowanceViewModel) {
    GcCard(borderColor = GateControlTheme.extraColors.warn.copy(alpha = 0.5f)) {
        Text(stringResource(R.string.familie_allowance_requests), style = MaterialTheme.typography.titleMedium)
        requests.forEach { r ->
            Text(
                stringResource(R.string.familie_allowance_request_line, memberName(p, r.childId), r.stars, r.cents?.let { money(it) } ?: "–"),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GcPrimaryButton(text = stringResource(R.string.familie_action_accept), onClick = { viewModel.decide(r.id, true) }, modifier = Modifier.weight(1f), minHeight = 40.dp)
                GcOutlineButton(text = stringResource(R.string.familie_action_decline), onClick = { viewModel.decide(r.id, false) }, modifier = Modifier.weight(1f), minHeight = 40.dp)
            }
        }
    }
}

@Composable
private fun BookingsCard(bookings: List<AllowanceBooking>, p: FamilieProfile, onDelete: ((AllowanceBooking) -> Unit)?) {
    var confirm by remember { mutableStateOf<AllowanceBooking?>(null) }
    val extra = GateControlTheme.extraColors
    GcCard(contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(R.string.familie_allowance_bookings), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        if (bookings.isEmpty()) {
            Text(stringResource(R.string.familie_allowance_no_bookings), style = MaterialTheme.typography.bodyMedium, color = extra.muted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        }
        bookings.take(50).forEachIndexed { i, b ->
            if (i > 0) GcRowDivider()
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(b.note.ifBlank { stringResource(bookingKindRes(b.kind)) }, style = MaterialTheme.typography.bodyLarge)
                    val by = b.by?.takeIf { b.kind == AllowanceBooking.KIND_MANUAL || b.kind == AllowanceBooking.KIND_SPEND }?.let { memberName(p, it) }
                    Text(listOfNotNull(relativeStamp(b.at), by).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = extra.muted)
                }
                Text(
                    money(b.cents, signed = true),
                    style = MaterialTheme.typography.titleSmall,
                    color = if (b.cents > 0) extra.accentText else if (b.cents < 0) MaterialTheme.colorScheme.onSurface else extra.muted,
                )
                // Parents may take back a manual booking or a spending (not payouts or stars).
                if (onDelete != null && (b.kind == AllowanceBooking.KIND_MANUAL || b.kind == AllowanceBooking.KIND_SPEND)) {
                    com.gatecontrol.android.ui.components.GcIconButton(
                        GcIcons.Trash,
                        contentDescription = stringResource(R.string.familie_allowance_booking_delete),
                        onClick = { confirm = b },
                        size = 40.dp,
                        iconSize = 16.dp,
                    )
                }
            }
        }
    }
    val c = confirm
    if (c != null && onDelete != null) {
        FamilieConfirmDialog(
            title = stringResource(R.string.familie_allowance_booking_delete),
            text = stringResource(R.string.familie_allowance_booking_delete_text, c.note.ifBlank { stringResource(bookingKindRes(c.kind)) }, money(c.cents, signed = true)),
            confirmLabel = stringResource(R.string.familie_delete),
            destructive = true,
            onConfirm = {
                onDelete(c)
                confirm = null
            },
            onDismiss = { confirm = null },
        )
    }
}

private fun bookingKindRes(kind: String): Int = when (kind) {
    AllowanceBooking.KIND_ALLOWANCE -> R.string.familie_allowance_kind_allowance
    AllowanceBooking.KIND_STARS -> R.string.familie_allowance_kind_stars
    AllowanceBooking.KIND_SPEND -> R.string.familie_allowance_kind_spend
    else -> R.string.familie_allowance_kind_manual
}

@Composable
private fun BookingDialog(childName: String, onDismiss: () -> Unit, onSave: (Int, String, Boolean) -> Unit) {
    var spend by rememberSaveable { mutableStateOf(false) }
    var amount by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    val cents = Money.parseCents(amount)?.takeIf { it > 0 }
    FamilieFormDialog(
        title = stringResource(R.string.familie_allowance_book_title, childName),
        onDismiss = onDismiss,
        saveEnabled = cents != null,
        onSave = { if (cents != null) onSave(cents, note, spend) },
    ) {
        GcSegmented(
            options = listOf(false to stringResource(R.string.familie_allowance_book_in), true to stringResource(R.string.familie_allowance_book_out)),
            selected = spend,
            onSelect = { spend = it },
        )
        AmountField(amount, { amount = it })
        GcTextField(
            value = note,
            onValueChange = { note = it.take(AllowanceBooking.MAX_NOTE) },
            label = stringResource(R.string.familie_allowance_note),
            placeholder = stringResource(R.string.familie_allowance_note_hint),
        )
    }
}

/** Euro amount input ("5,50"); shows a hint while it cannot be read. */
@Composable
fun AmountField(value: String, onValueChange: (String) -> Unit, label: String = stringResource(R.string.familie_money_amount)) {
    GcTextField(
        value = value,
        onValueChange = { onValueChange(it.take(12)) },
        label = label,
        placeholder = "0,00",
        error = if (value.isNotBlank() && Money.parseCents(value) == null) stringResource(R.string.familie_money_amount_invalid) else null,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RuleDialog(childName: String, account: AllowanceAccount?, onDismiss: () -> Unit, onSave: (AllowanceRuleDraft) -> Unit) {
    val rule = account?.rule
    val locale = appLocale()
    var amount by rememberSaveable { mutableStateOf(rule?.amountCents?.let { Money.input(it, locale) } ?: "") }
    var interval by rememberSaveable { mutableStateOf(rule?.interval ?: AllowanceRule.WEEKLY) }
    var weekday by rememberSaveable { mutableIntStateOf(rule?.weekday ?: 6) }
    var day by rememberSaveable { mutableIntStateOf(rule?.dayOfMonth ?: 1) }
    var active by rememberSaveable { mutableStateOf(rule?.active ?: true) }
    var starsOn by rememberSaveable { mutableStateOf(account?.starRate != null) }
    var starCount by rememberSaveable { mutableIntStateOf(account?.starRate?.stars ?: 10) }
    var starValue by rememberSaveable { mutableStateOf(account?.starRate?.cents?.let { Money.input(it, locale) } ?: Money.input(100, locale)) }
    val cents = Money.parseCents(amount)?.takeIf { it <= AllowanceRule.MAX_CENTS }
    val starCents = Money.parseCents(starValue)?.takeIf { it > 0 }
    val extra = GateControlTheme.extraColors

    FamilieFormDialog(
        title = stringResource(R.string.familie_allowance_rule_title, childName),
        onDismiss = onDismiss,
        saveEnabled = cents != null && (!starsOn || starCents != null),
        onSave = {
            if (cents != null) {
                onSave(
                    AllowanceRuleDraft(
                        amountCents = cents,
                        interval = interval,
                        weekday = weekday,
                        dayOfMonth = day,
                        active = active,
                        starRate = if (starsOn && starCents != null) StarRate(starCount, starCents) else null,
                    ),
                )
            }
        },
    ) {
        AmountField(amount, { amount = it })
        GcSegmented(
            options = listOf(
                AllowanceRule.WEEKLY to stringResource(R.string.familie_allowance_rule_weekly),
                AllowanceRule.MONTHLY to stringResource(R.string.familie_allowance_rule_monthly),
            ),
            selected = interval,
            onSelect = { interval = it },
        )
        if (interval == AllowanceRule.WEEKLY) {
            Text(stringResource(R.string.familie_allowance_rule_weekday), style = MaterialTheme.typography.titleSmall, color = extra.muted)
            val names = weekdayNames()
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                (1..7).forEach { d -> GcFilterChip(text = names[d - 1], selected = d == weekday, onClick = { weekday = d }) }
            }
        } else {
            Text(stringResource(R.string.familie_allowance_rule_day, day), style = MaterialTheme.typography.titleSmall, color = extra.muted)
            Slider(value = day.toFloat(), onValueChange = { day = it.toInt().coerceIn(1, 28) }, valueRange = 1f..28f, steps = 26)
        }
        GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.Top) {
            GcSwitchRow(
                label = stringResource(R.string.familie_allowance_rule_active),
                description = stringResource(R.string.familie_allowance_rule_active_desc),
                checked = active,
                onCheckedChange = { active = it },
                icon = GcIcons.Wallet,
            )
            GcRowDivider()
            GcSwitchRow(
                label = stringResource(R.string.familie_allowance_rule_stars),
                description = stringResource(R.string.familie_allowance_rule_stars_desc),
                checked = starsOn,
                onCheckedChange = { starsOn = it },
                icon = GcIcons.Star,
            )
        }
        if (starsOn) {
            Text(pluralStringResource(R.plurals.familie_allowance_stars_have, starCount, starCount), style = MaterialTheme.typography.titleSmall, color = extra.muted)
            Slider(value = starCount.toFloat(), onValueChange = { starCount = it.toInt().coerceIn(1, 50) }, valueRange = 1f..50f, steps = 48)
            AmountField(starValue, { starValue = it }, label = stringResource(R.string.familie_allowance_rule_stars_value))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConvertDialog(available: Int, rate: StarRate, onDismiss: () -> Unit, onConvert: (Int) -> Unit) {
    val steps = Money.convertSteps(available, rate)
    var stars by rememberSaveable { mutableIntStateOf(steps.firstOrNull() ?: 0) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.extraLarge,
        title = { Text(stringResource(R.string.familie_allowance_convert_title), style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.familie_allowance_convert_pick), style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    steps.forEach { s ->
                        GcFilterChip(
                            text = stringResource(R.string.familie_allowance_convert_option, s, money(Money.starsValue(s, rate))),
                            selected = s == stars,
                            onClick = { stars = s },
                        )
                    }
                }
                Text(stringResource(R.string.familie_allowance_parents_confirm), style = MaterialTheme.typography.bodySmall, color = GateControlTheme.extraColors.muted)
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { onConvert(stars) }, enabled = stars > 0) { Text(stringResource(R.string.familie_allowance_convert)) }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}
