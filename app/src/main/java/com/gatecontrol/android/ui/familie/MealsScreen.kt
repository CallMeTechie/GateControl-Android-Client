package com.gatecontrol.android.ui.familie

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.R
import com.gatecontrol.android.familie.FamilieApi
import com.gatecontrol.android.familie.FamilieJson12
import com.gatecontrol.android.familie.FamilieProfile
import com.gatecontrol.android.familie.FamilieRepository
import com.gatecontrol.android.familie.FamilieResult
import com.gatecontrol.android.familie.meals.Meal
import com.gatecontrol.android.familie.meals.MealDay
import com.gatecontrol.android.familie.meals.MealWeek
import com.gatecontrol.android.familie.push.FamiliePushEvent
import com.gatecontrol.android.familie.shopping.ShoppingList
import com.gatecontrol.android.familie.shopping.ShoppingLogic
import com.gatecontrol.android.familie.shopping.ShoppingRepository
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcFilterChip
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcRowDivider
import com.gatecontrol.android.ui.components.GcTextField
import com.gatecontrol.android.ui.theme.GateControlTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.format.TextStyle
import javax.inject.Inject

/** Essensplan: one ISO week at a time. */
@HiltViewModel
class MealsViewModel @Inject constructor(
    private val api: FamilieApi,
    repository: FamilieRepository,
    private val shopping: ShoppingRepository,
) : FamilieLoadViewModel<MealWeek>(repository) {

    private val _week = MutableStateFlow(MealWeek.keyOf(LocalDate.now()))
    val week: StateFlow<String> = _week.asStateFlow()

    /** Shopping lists for "Zutaten auf die Liste" (as the device has them). */
    val lists: StateFlow<List<ShoppingList>> = shopping.data.map { ShoppingLogic.view(it).lists }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ShoppingLogic.view(shopping.data.value).lists)

    init {
        start()
    }

    fun show(week: String?) {
        if (week == null || !MealWeek.isKey(week) || week == _week.value) return
        _week.value = week
        refresh(clear = true)
    }

    fun shift(weeks: Long) = show(MealWeek.shift(_week.value, weeks))

    override suspend fun load(): FamilieResult<MealWeek> = api.meals(_week.value)

    override fun refreshOn(event: FamiliePushEvent): Boolean = event is FamiliePushEvent.Other && event.topicKey == "meals"

    fun save(date: String, slot: String, meal: Meal) = act(R.string.familie_saved) { api.putMeal(date, slot, meal) }

    fun delete(date: String, slot: String) = act { api.deleteMeal(date, slot) }

    private val _added = MutableStateFlow<com.gatecontrol.android.familie.money.ShoppingAdded?>(null)
    /** Result of the last "Zutaten auf die Liste", shown once. */
    val added: StateFlow<com.gatecontrol.android.familie.money.ShoppingAdded?> = _added.asStateFlow()

    /** The ingredients become open items of [listId]; the list is synced afterwards. */
    fun toShopping(date: String, slot: String, listId: String) = act {
        api.mealToShopping(date, slot, listId).also {
            if (it is FamilieResult.Ok) {
                _added.value = it.value
                shopping.syncAsync()
            }
        }
    }

    fun clearAdded() {
        _added.value = null
    }
}

private data class MealEdit(val date: String, val slot: String, val meal: Meal?)

/**
 * Essensplan (no mockup — Familie style): Monday … Sunday with lunch and
 * dinner, week by week; edit a meal (title, note, who cooks, ingredients),
 * "Zutaten auf die Liste" puts the ingredients on a shopping list (the
 * server skips ones that are already open there).
 */
@Composable
fun MealsScreen(onBack: () -> Unit, initialWeek: String? = null, viewModel: MealsViewModel = hiltViewModel()) {
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val data by viewModel.data.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val week by viewModel.week.collectAsStateWithLifecycle()
    val lists by viewModel.lists.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<MealEdit?>(null) }
    var toList by remember { mutableStateOf<MealEdit?>(null) }
    FamilieToasts(viewModel)
    androidx.compose.runtime.LaunchedEffect(initialWeek) { viewModel.show(initialWeek) }
    val added by viewModel.added.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val addedText = added?.let { a ->
        if (a.skipped > 0) pluralStringResource(R.plurals.familie_meal_to_list_added_skipped, a.added, a.added, a.skipped)
        else pluralStringResource(R.plurals.familie_meal_to_list_added, a.added, a.added)
    }
    androidx.compose.runtime.LaunchedEffect(addedText) {
        if (addedText != null) {
            android.widget.Toast.makeText(context, addedText, android.widget.Toast.LENGTH_LONG).show()
            viewModel.clearAdded()
        }
    }

    val p = profile
    val weekNo = week.substringAfter("-W").toIntOrNull() ?: 0
    val shoppingOn = p?.modules?.shopping == true

    FamiliePageFrame(
        title = stringResource(R.string.familie_meals_title),
        onBack = onBack,
        refreshing = busy && data != null,
        onRefresh = { viewModel.refresh() },
        actions = {
            GcIconButton(GcIcons.ChevronLeft, contentDescription = stringResource(R.string.familie_week_prev), onClick = { viewModel.shift(-1) }, size = 40.dp)
            Text(stringResource(R.string.familie_meals_week, weekNo), style = MaterialTheme.typography.titleSmall, color = GateControlTheme.extraColors.muted)
            GcIconButton(GcIcons.ChevronRight, contentDescription = stringResource(R.string.familie_week_next), onClick = { viewModel.shift(1) }, size = 40.dp)
        },
    ) {
        val w = data
        when {
            w == null && error != null -> FamilieErrorBanner(error, onRetry = { viewModel.refresh() })
            w == null || p == null -> FamilieLoading()
            else -> {
                if (error != null) FamilieErrorBanner(error)
                if (week != MealWeek.keyOf(LocalDate.now())) {
                    GcOutlineButton(text = stringResource(R.string.familie_meals_this_week), onClick = { viewModel.show(MealWeek.keyOf(LocalDate.now())) }, icon = GcIcons.Calendar)
                }
                GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.Top) {
                    w.days.forEachIndexed { i, day ->
                        if (i > 0) GcRowDivider()
                        DayRow(p, day, onEdit = { slot -> editing = MealEdit(day.date, slot, day.meal(slot)) })
                    }
                }
            }
        }
    }

    val e = editing
    if (e != null && p != null) {
        MealDialog(
            profile = p,
            edit = e,
            canShop = shoppingOn && e.meal?.ingredients?.isNotEmpty() == true && lists.isNotEmpty(),
            onDismiss = { editing = null },
            onSave = { meal ->
                viewModel.save(e.date, e.slot, meal)
                editing = null
            },
            onDelete = if (e.meal != null) ({
                viewModel.delete(e.date, e.slot)
                editing = null
            }) else null,
            onToList = {
                editing = null
                if (lists.size == 1) viewModel.toShopping(e.date, e.slot, lists[0].id) else toList = e
            },
        )
    }
    val t = toList
    if (t != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { toList = null },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.extraLarge,
            title = { Text(stringResource(R.string.familie_meal_to_list), style = MaterialTheme.typography.titleLarge) },
            text = {
                Column {
                    lists.forEach { l ->
                        Text(
                            l.name,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) {
                                viewModel.toShopping(t.date, t.slot, l.id)
                                toList = null
                            }.padding(vertical = 14.dp),
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { androidx.compose.material3.TextButton(onClick = { toList = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

@Composable
private fun DayRow(p: FamilieProfile, day: MealDay, onEdit: (String) -> Unit) {
    val extra = GateControlTheme.extraColors
    val date = FamilieFormat.parseDate(day.date)
    val today = date == LocalDate.now()
    val locale = appLocale()
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.width(56.dp).padding(top = 6.dp)) {
            Text(
                date?.dayOfWeek?.getDisplayName(TextStyle.SHORT, locale) ?: day.date,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = if (today) FontWeight.ExtraBold else FontWeight.SemiBold),
                color = if (today) extra.accentText else MaterialTheme.colorScheme.onSurface,
            )
            Text(date?.dayOfMonth?.toString().orEmpty() + ".", style = MaterialTheme.typography.labelSmall, color = extra.muted)
        }
        Column(Modifier.weight(1f)) {
            MealWeek.SLOTS.forEach { slot ->
                val meal = day.meal(slot)
                Row(
                    Modifier.fillMaxWidth().clickable(role = Role.Button) { onEdit(slot) }.padding(vertical = 6.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        stringResource(if (slot == MealWeek.LUNCH) R.string.familie_meal_lunch else R.string.familie_meal_dinner),
                        style = MaterialTheme.typography.labelMedium,
                        color = extra.muted,
                        modifier = Modifier.width(64.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            meal?.title ?: "–",
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (meal == null) extra.faint else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val meta = listOfNotNull(
                            meal?.cook?.let { stringResource(R.string.familie_meal_cooks, memberName(p, it)) },
                            meal?.ingredients?.takeIf { it.isNotEmpty() }?.let { pluralStringResource(R.plurals.familie_meal_ingredients_count, it.size, it.size) },
                        ).joinToString(" · ")
                        if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.bodySmall, color = extra.muted)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MealDialog(
    profile: FamilieProfile,
    edit: MealEdit,
    canShop: Boolean,
    onDismiss: () -> Unit,
    onSave: (Meal) -> Unit,
    onDelete: (() -> Unit)?,
    onToList: () -> Unit,
) {
    val m = edit.meal
    var title by rememberSaveable { mutableStateOf(m?.title.orEmpty()) }
    var note by rememberSaveable { mutableStateOf(m?.note.orEmpty()) }
    var cook by rememberSaveable { mutableStateOf(m?.cook) }
    var ingredients by rememberSaveable { mutableStateOf(m?.ingredients?.joinToString("\n").orEmpty()) }
    val date = FamilieFormat.parseDate(edit.date)
    val slotName = stringResource(if (edit.slot == MealWeek.LUNCH) R.string.familie_meal_lunch else R.string.familie_meal_dinner)
    FamilieFormDialog(
        title = (date?.let { FamilieFormat.day(it, appLocale()) } ?: edit.date) + " · " + slotName,
        onDismiss = onDismiss,
        saveEnabled = title.isNotBlank(),
        onSave = { onSave(Meal(title.trim(), note.trim(), cook, FamilieJson12.ingredientLines(ingredients))) },
        onDelete = onDelete,
    ) {
        GcTextField(value = title, onValueChange = { title = it.take(Meal.MAX_TITLE) }, label = stringResource(R.string.familie_meal_title), placeholder = stringResource(R.string.familie_meal_title_hint))
        Text(stringResource(R.string.familie_meal_cook), style = MaterialTheme.typography.titleSmall, color = GateControlTheme.extraColors.muted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GcFilterChip(text = stringResource(R.string.familie_meal_cook_none), selected = cook == null, onClick = { cook = null })
            profile.everyone.forEach { mem -> GcFilterChip(text = mem.name, selected = cook == mem.userId, onClick = { cook = mem.userId }) }
        }
        GcTextField(
            value = ingredients,
            onValueChange = { ingredients = it.take(2_000) },
            label = stringResource(R.string.familie_meal_ingredients),
            hint = stringResource(R.string.familie_meal_ingredients_hint),
            singleLine = false,
        )
        GcTextField(value = note, onValueChange = { note = it.take(Meal.MAX_NOTE) }, label = stringResource(R.string.familie_meal_note), singleLine = false)
        if (canShop) {
            GcOutlineButton(text = stringResource(R.string.familie_meal_to_list), onClick = onToList, icon = GcIcons.Cart)
        }
    }
}
