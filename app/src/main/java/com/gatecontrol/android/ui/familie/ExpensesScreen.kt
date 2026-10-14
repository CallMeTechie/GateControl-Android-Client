package com.gatecontrol.android.ui.familie

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.familie.FamilieApi
import com.gatecontrol.android.familie.FamilieProfile
import com.gatecontrol.android.familie.FamilieRepository
import com.gatecontrol.android.familie.FamilieResult
import com.gatecontrol.android.familie.FamilieRole
import com.gatecontrol.android.familie.money.Expense
import com.gatecontrol.android.familie.money.ExpenseDraft
import com.gatecontrol.android.familie.money.ExpenseMonth
import com.gatecontrol.android.familie.money.Money
import com.gatecontrol.android.familie.push.FamiliePushEvent
import com.gatecontrol.android.familie.shopping.ShoppingRepository
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcChip
import com.gatecontrol.android.ui.components.GcFilterChip
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcRowDivider
import com.gatecontrol.android.ui.components.GcSectionLabel
import com.gatecontrol.android.ui.components.GcSwitchRow
import com.gatecontrol.android.ui.components.GcTextField
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.theme.GateControlTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import javax.inject.Inject

/** Haushaltsbuch (parents): one month at a time. */
@HiltViewModel
class ExpensesViewModel @Inject constructor(
    private val api: FamilieApi,
    repository: FamilieRepository,
    private val shopping: ShoppingRepository,
) : FamilieLoadViewModel<ExpenseMonth>(repository) {

    private val _month = MutableStateFlow(YearMonth.now().toString())
    val month: StateFlow<String> = _month.asStateFlow()

    init {
        start()
    }

    override suspend fun load(): FamilieResult<ExpenseMonth> = api.expenses(_month.value)

    override fun refreshOn(event: FamiliePushEvent): Boolean = event is FamiliePushEvent.Other && event.topicKey == "expenses"

    fun shift(months: Long) {
        val current = runCatching { YearMonth.parse(_month.value) }.getOrDefault(YearMonth.now())
        val next = current.plusMonths(months)
        if (next.isAfter(YearMonth.now())) return
        _month.value = next.toString()
        refresh(clear = true)
    }

    /** Name of a shopping list ("Einkauf erfassen?" from that list). */
    fun listName(listId: String): String? = com.gatecontrol.android.familie.shopping.ShoppingLogic.view(shopping.data.value).lists.firstOrNull { it.id == listId }?.name

    fun save(id: Int?, draft: ExpenseDraft) = act(R.string.familie_saved) { if (id == null) api.createExpense(draft) else api.updateExpense(id, draft) }

    fun delete(id: Int) = act { api.deleteExpense(id) }

    fun settle() = act(R.string.familie_expenses_settled_done) { api.settleExpenses(_month.value) }
}

/**
 * Haushaltsbuch (mockup AppExpenses, parents only): the month's total, a
 * bar per category, the settlement between the parents with "Erledigt"
 * (a record — nothing is paid), the expenses, "+ Ausgabe". [fromListId]
 * opens a new expense for that shopping list ("Einkauf erfassen?").
 */
@Composable
fun ExpensesScreen(onBack: () -> Unit, fromListId: String? = null, viewModel: ExpensesViewModel = hiltViewModel()) {
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val data by viewModel.data.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val month by viewModel.month.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Expense?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var prefill by rememberSaveable { mutableStateOf<String?>(null) }
    var handledList by rememberSaveable { mutableStateOf(false) }
    FamilieToasts(viewModel)

    LaunchedEffect(fromListId) {
        if (fromListId != null && !handledList) {
            handledList = true
            prefill = viewModel.listName(fromListId)
            creating = true
        }
    }

    val p = profile
    val ym = runCatching { YearMonth.parse(month) }.getOrDefault(YearMonth.now())
    val locale = appLocale()
    val monthName = ym.month.getDisplayName(TextStyle.FULL_STANDALONE, locale).replaceFirstChar { it.titlecase(locale) } +
        if (ym.year != YearMonth.now().year) " ${ym.year}" else ""

    FamiliePageFrame(
        title = stringResource(R.string.familie_expenses_title),
        onBack = onBack,
        refreshing = busy && data != null,
        onRefresh = { viewModel.refresh() },
        actions = {
            GcIconButton(GcIcons.ChevronLeft, contentDescription = stringResource(R.string.familie_month_prev), onClick = { viewModel.shift(-1) }, size = 40.dp)
            Text(monthName, style = MaterialTheme.typography.titleSmall, color = GateControlTheme.extraColors.muted)
            GcIconButton(
                GcIcons.ChevronRight,
                contentDescription = stringResource(R.string.familie_month_next),
                onClick = { viewModel.shift(1) },
                size = 40.dp,
                enabled = ym.isBefore(YearMonth.now()),
                tint = if (ym.isBefore(YearMonth.now())) GateControlTheme.extraColors.muted else GateControlTheme.extraColors.faint,
            )
        },
        overlay = {
            if (p?.me?.isParent == true) {
                FamilieFab(
                    text = stringResource(R.string.familie_expense_add),
                    icon = GcIcons.Plus,
                    onClick = { creating = true },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 18.dp, bottom = 24.dp),
                )
            }
        },
    ) {
        val m = data
        when {
            p != null && !p.me.isParent -> FamilieEmpty(GcIcons.Receipt, stringResource(R.string.familie_error_parents_only))
            m == null && error != null -> FamilieErrorBanner(error, onRetry = { viewModel.refresh() })
            m == null || p == null -> FamilieLoading()
            else -> {
                if (error != null) FamilieErrorBanner(error)
                SummaryCard(m)
                SettlementCard(p, m, onSettle = viewModel::settle)
                if (m.expenses.isEmpty()) {
                    FamilieEmpty(GcIcons.Receipt, stringResource(R.string.familie_expenses_none))
                } else {
                    GcSectionLabel(stringResource(R.string.familie_expenses_recent), Modifier.padding(start = 4.dp, top = 4.dp))
                    GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.Top) {
                        m.expenses.forEachIndexed { i, e ->
                            if (i > 0) GcRowDivider()
                            ExpenseRow(p, e, onClick = { editing = e })
                        }
                    }
                }
            }
        }
    }

    val e = editing
    if (p != null && p.me.isParent && (creating || e != null)) {
        ExpenseDialog(
            profile = p,
            expense = e,
            prefillNote = prefill,
            onDismiss = {
                creating = false
                editing = null
                prefill = null
            },
            onSave = { draft ->
                viewModel.save(e?.id, draft)
                creating = false
                editing = null
                prefill = null
            },
            onDelete = e?.let { x ->
                {
                    viewModel.delete(x.id)
                    editing = null
                }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SummaryCard(m: ExpenseMonth) {
    val extra = GateControlTheme.extraColors
    val locale = appLocale()
    GcCard(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(money(m.totalCents), style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.ExtraBold, fontSize = 30.sp))
            Text(stringResource(R.string.familie_expenses_spent), style = MaterialTheme.typography.bodyMedium, color = extra.muted, modifier = Modifier.padding(bottom = 4.dp))
        }
        val shares = Money.shares(m.byCategory)
        if (shares.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp))) {
                shares.forEach { (cat, w) ->
                    Box(Modifier.weight(w.coerceAtLeast(0.01f)).height(10.dp).background(expenseCategoryColor(cat)))
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                shares.forEach { (cat, _) ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(expenseCategoryColor(cat)))
                        Text(
                            stringResource(expenseCategoryRes(cat)) + " " + Money.short(((m.byCategory[cat] ?: 0) + 50) / 100 * 100, locale),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

/** "Ausgleich: Marc → Anna 48,20 €" with "Erledigt" (mockup), one line per transfer with 3+ parents. */
@Composable
private fun SettlementCard(p: FamilieProfile, m: ExpenseMonth, onSettle: () -> Unit) {
    val extra = GateControlTheme.extraColors
    if (m.transfers.isEmpty()) {
        if (m.expenses.any { it.shared }) {
            GcChip(stringResource(R.string.familie_expenses_even), tone = GcTone.Ok)
        }
        return
    }
    GcCard(color = extra.warnBg, borderColor = extra.warn.copy(alpha = 0.35f), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                m.transfers.forEach { t ->
                    Text(
                        stringResource(R.string.familie_expenses_transfer, memberName(p, t.from), memberName(p, t.to), money(t.cents)),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                val single = m.transfers.singleOrNull()
                Text(
                    when {
                        m.settled -> stringResource(R.string.familie_expenses_settled)
                        single != null -> stringResource(R.string.familie_expenses_paid_more, memberName(p, single.to))
                        else -> stringResource(R.string.familie_expenses_transfers_hint)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = extra.warn,
                )
            }
            if (m.settled) {
                GcChip(stringResource(R.string.familie_expenses_done_chip), tone = GcTone.Ok, small = true)
            } else {
                GcOutlineButton(text = stringResource(R.string.familie_expenses_settle), onClick = onSettle, fillWidth = false, minHeight = 38.dp)
            }
        }
    }
}

@Composable
private fun ExpenseRow(p: FamilieProfile, e: Expense, onClick: () -> Unit) {
    val extra = GateControlTheme.extraColors
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FamilieAvatar(memberName(p, e.paidBy), p.member(e.paidBy)?.color ?: p.me.color.takeIf { e.paidBy == p.me.userId }, size = 36.dp)
        Column(Modifier.weight(1f)) {
            Text(e.note.ifBlank { stringResource(expenseCategoryRes(e.category)) }, style = MaterialTheme.typography.titleSmall)
            Text(
                listOfNotNull(
                    relativeDay(e.date),
                    stringResource(expenseCategoryRes(e.category)),
                    if (!e.shared) stringResource(R.string.familie_expense_private) else null,
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = extra.muted,
            )
        }
        Text(money(e.cents), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExpenseDialog(
    profile: FamilieProfile,
    expense: Expense?,
    prefillNote: String?,
    onDismiss: () -> Unit,
    onSave: (ExpenseDraft) -> Unit,
    onDelete: (() -> Unit)?,
) {
    val locale = appLocale()
    val parents = profile.everyone.filter { it.role == FamilieRole.PARENT }
    var amount by rememberSaveable { mutableStateOf(expense?.cents?.let { Money.input(it, locale) }.orEmpty()) }
    var category by rememberSaveable { mutableStateOf(expense?.category ?: Expense.FOOD) }
    var paidBy by rememberSaveable { mutableIntStateOf(expense?.paidBy ?: profile.me.userId) }
    var date by rememberSaveable { mutableStateOf(expense?.date ?: LocalDate.now().toString()) }
    var note by rememberSaveable { mutableStateOf(expense?.note ?: prefillNote.orEmpty()) }
    var shared by rememberSaveable { mutableStateOf(expense?.shared ?: true) }
    val cents = Money.parseCents(amount)?.takeIf { it in 1..Expense.MAX_CENTS }
    val extra = GateControlTheme.extraColors
    FamilieFormDialog(
        title = stringResource(if (expense == null) R.string.familie_expense_new else R.string.familie_expense_edit),
        onDismiss = onDismiss,
        saveEnabled = cents != null,
        onSave = { if (cents != null) onSave(ExpenseDraft(cents, category, paidBy, date, note, shared)) },
        onDelete = onDelete,
    ) {
        AmountField(amount, { amount = it })
        Text(stringResource(R.string.familie_expense_category), style = MaterialTheme.typography.titleSmall, color = extra.muted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Expense.CATEGORIES.forEach { c -> GcFilterChip(text = stringResource(expenseCategoryRes(c)), selected = c == category, onClick = { category = c }) }
        }
        if (parents.size > 1) {
            Text(stringResource(R.string.familie_expense_paid_by), style = MaterialTheme.typography.titleSmall, color = extra.muted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                parents.forEach { m -> GcFilterChip(text = m.name, selected = m.userId == paidBy, onClick = { paidBy = m.userId }) }
            }
        }
        FamilieDateField(label = stringResource(R.string.familie_expense_date), value = date, onValueChange = { v -> if (v != null) date = v })
        GcTextField(
            value = note,
            onValueChange = { note = it.take(Expense.MAX_NOTE) },
            label = stringResource(R.string.familie_expense_note),
            placeholder = stringResource(R.string.familie_expense_note_hint),
        )
        GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.Top) {
            GcSwitchRow(
                label = stringResource(R.string.familie_expense_shared),
                description = stringResource(R.string.familie_expense_shared_desc),
                checked = shared,
                onCheckedChange = { shared = it },
                icon = GcIcons.Family,
            )
        }
    }
}
