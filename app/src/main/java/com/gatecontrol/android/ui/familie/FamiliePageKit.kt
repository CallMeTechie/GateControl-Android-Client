package com.gatecontrol.android.ui.familie

import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.R
import com.gatecontrol.android.familie.FamilieProfile
import com.gatecontrol.android.familie.FamilieRepository
import com.gatecontrol.android.familie.FamilieResult
import com.gatecontrol.android.familie.errorCode
import com.gatecontrol.android.familie.money.Expense
import com.gatecontrol.android.familie.money.Money
import com.gatecontrol.android.familie.push.FamiliePushEvent
import com.gatecontrol.android.ui.components.GcSubpageBar
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Shared state of the Familie 1.2 screens: one payload loaded from the
 * server, its error, a busy flag, one-shot error codes and confirmations
 * of actions. Subclasses call [start] in their `init`.
 */
abstract class FamilieLoadViewModel<T>(protected val repository: FamilieRepository) : ViewModel() {

    val profile: StateFlow<FamilieProfile?> = repository.profile

    protected val _data = MutableStateFlow<T?>(null)
    val data: StateFlow<T?> = _data.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    /** Error code of the last load, null after a success. */
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    /** Error code of the last action, shown once. */
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _info = MutableStateFlow<Int?>(null)
    /** Confirmation (string resource) of the last action, shown once. */
    val info: StateFlow<Int?> = _info.asStateFlow()

    private var loadJob: Job? = null

    protected abstract suspend fun load(): FamilieResult<T>

    /** A push message that should reload the screen. */
    protected open fun refreshOn(event: FamiliePushEvent): Boolean = false

    protected fun start() {
        refresh()
        viewModelScope.launch { repository.events.collect { if (refreshOn(it)) refresh() } }
    }

    fun refresh(clear: Boolean = false) {
        loadJob?.cancel()
        if (clear) _data.value = null
        loadJob = viewModelScope.launch {
            _busy.value = true
            if (repository.profile.value == null) repository.refreshProfile()
            when (val r = load()) {
                is FamilieResult.Ok -> {
                    _data.value = r.value
                    _error.value = null
                }
                else -> _error.value = r.errorCode()
            }
            _busy.value = false
        }
    }

    /** Runs [call]; success: [info] and a reload, failure: the error once (and a reload). */
    protected fun act(@StringRes info: Int? = null, call: suspend () -> FamilieResult<*>) {
        viewModelScope.launch {
            val r = call()
            if (r is FamilieResult.Ok) {
                if (info != null) _info.value = info
            } else {
                _message.value = r.errorCode()
            }
            refresh()
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    fun clearInfo() {
        _info.value = null
    }
}

/** Shows the one-shot error and confirmation of [vm] as toasts. */
@Composable
fun FamilieToasts(vm: FamilieLoadViewModel<*>) {
    val message by vm.message.collectAsStateWithLifecycle()
    val info by vm.info.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val messageText = message?.let { familieErrorText(it) }
    val infoText = info?.let { stringResource(it) }
    LaunchedEffect(messageText) {
        if (messageText != null) {
            Toast.makeText(context, messageText, Toast.LENGTH_LONG).show()
            vm.clearMessage()
        }
    }
    LaunchedEffect(infoText) {
        if (infoText != null) {
            Toast.makeText(context, infoText, Toast.LENGTH_SHORT).show()
            vm.clearInfo()
        }
    }
}

/**
 * Frame of a Familie screen: sub page bar with [actions], pull to refresh,
 * scrolling column; [overlay] sits on top (e.g. a floating button).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FamiliePageFrame(
    title: String,
    onBack: () -> Unit,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    overlay: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        GcSubpageBar(title = title, onBack = onBack, backDescription = stringResource(R.string.common_back), actions = actions)
        Box(Modifier.fillMaxSize()) {
            PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    content = content,
                )
            }
            overlay()
        }
    }
}

/** Extended floating button (mockup AppExpenses "+ Ausgabe"). */
@Composable
fun FamilieFab(text: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(56.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shadowElevation = 6.dp,
    ) {
        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** "23,50 €" in the app language; [signed] "+ 5,00 €" / "− 8,50 €". */
@Composable
fun money(cents: Int, signed: Boolean = false): String = Money.format(cents, appLocale(), signed)

@StringRes
fun expenseCategoryRes(category: String): Int = when (category) {
    Expense.FOOD -> R.string.familie_expense_cat_food
    Expense.KIDS -> R.string.familie_expense_cat_kids
    Expense.HOUSEHOLD -> R.string.familie_expense_cat_household
    Expense.LEISURE -> R.string.familie_expense_cat_leisure
    Expense.MOBILITY -> R.string.familie_expense_cat_mobility
    else -> R.string.familie_expense_cat_other
}

/** Bar colours of the mockup (food green, kids blue, household orange, leisure violet). */
fun expenseCategoryColor(category: String): Color = when (category) {
    Expense.FOOD -> Color(0xFF0B7A55)
    Expense.KIDS -> Color(0xFF1D4ED8)
    Expense.HOUSEHOLD -> Color(0xFFE08A1E)
    Expense.LEISURE -> Color(0xFF8B5CF6)
    Expense.MOBILITY -> Color(0xFF0E7490)
    else -> Color(0xFF8A94A6)
}
