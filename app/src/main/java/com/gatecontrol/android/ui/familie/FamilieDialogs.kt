package com.gatecontrol.android.ui.familie

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.R
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcTextField
import com.gatecontrol.android.ui.theme.GateControlTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

/** A field of [FamilieInputDialog]. */
data class DialogField(val label: String, val initial: String = "", val maxLength: Int = 120, val placeholder: String? = null)

/**
 * Small dialog with one or more text fields (new list, rename, edit an
 * entry). [onDelete] adds a delete button.
 */
@Composable
fun FamilieInputDialog(
    title: String,
    fields: List<DialogField>,
    onConfirm: (List<String>) -> Unit,
    onDismiss: () -> Unit,
    confirmLabel: String = stringResource(R.string.familie_save),
    onDelete: (() -> Unit)? = null,
) {
    var values by rememberSaveable { mutableStateOf(fields.map { it.initial }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.extraLarge,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                fields.forEachIndexed { i, f ->
                    GcTextField(
                        value = values[i],
                        onValueChange = { v -> values = values.toMutableList().also { it[i] = v.take(f.maxLength) } },
                        label = f.label,
                        placeholder = f.placeholder,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(values) }, enabled = values.firstOrNull()?.isNotBlank() == true) { Text(confirmLabel) }
        },
        dismissButton = {
            if (onDelete != null) {
                TextButton(onClick = onDelete) { Text(stringResource(R.string.familie_delete), color = MaterialTheme.colorScheme.error) }
            }
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

/** Yes/no question before something that cannot be undone. */
@Composable
fun FamilieConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.extraLarge,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = { Text(text, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

/** Read-only field that opens a date picker; [value] is `YYYY-MM-DD` or null. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FamilieDateField(
    label: String,
    value: String?,
    onValueChange: (String?) -> Unit,
    modifier: Modifier = Modifier,
    clearable: Boolean = false,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val locale = appLocale()
    val shown = FamilieFormat.parseDate(value)?.let { FamilieFormat.day(it, locale) + " " + it.year } ?: stringResource(R.string.familie_none)
    PickerField(label, shown, modifier, onClick = { open = true }, onClear = if (clearable && value != null) ({ onValueChange(null) }) else null)
    if (open) {
        val initial = (FamilieFormat.parseDate(value) ?: LocalDate.now()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val state = rememberDatePickerState(initialSelectedDateMillis = initial)
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { ms ->
                        onValueChange(Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate().toString())
                    }
                    open = false
                }) { Text(stringResource(R.string.familie_ok)) }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.common_cancel)) } },
        ) { DatePicker(state = state) }
    }
}

/** Read-only field that opens a time picker; [value] is `HH:MM` or null. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FamilieTimeField(
    label: String,
    value: String?,
    onValueChange: (String?) -> Unit,
    modifier: Modifier = Modifier,
    clearable: Boolean = true,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    PickerField(
        label,
        value ?: stringResource(R.string.familie_none),
        modifier,
        onClick = { open = true },
        onClear = if (clearable && value != null) ({ onValueChange(null) }) else null,
    )
    if (open) {
        val parts = value?.split(':')
        val state = rememberTimePickerState(
            initialHour = parts?.getOrNull(0)?.toIntOrNull() ?: 18,
            initialMinute = parts?.getOrNull(1)?.toIntOrNull() ?: 0,
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { open = false },
            containerColor = MaterialTheme.colorScheme.surface,
            confirmButton = {
                TextButton(onClick = {
                    onValueChange(String.format(Locale.ROOT, "%02d:%02d", state.hour, state.minute))
                    open = false
                }) { Text(stringResource(R.string.familie_ok)) }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.common_cancel)) } },
            text = { TimePicker(state = state) },
        )
    }
}

@Composable
private fun PickerField(label: String, shown: String, modifier: Modifier, onClick: () -> Unit, onClear: (() -> Unit)?) {
    val extra = GateControlTheme.extraColors
    val shape = RoundedCornerShape(14.dp)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall, color = extra.muted)
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .clip(shape)
                .border(1.dp, extra.border2, shape)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(start = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(shown, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            if (onClear != null) {
                GcIconButton(GcIcons.Close, contentDescription = stringResource(R.string.familie_clear), onClick = onClear, size = 40.dp, iconSize = 18.dp)
            } else {
                Icon(GcIcons.Calendar, contentDescription = null, tint = extra.faint, modifier = Modifier.padding(end = 10.dp).size(18.dp))
            }
        }
    }
}

/**
 * Full-screen form (create / edit a task, date, place): close, title,
 * save in the top bar, scrollable content, optional delete at the end.
 */
@Composable
fun FamilieFormDialog(
    title: String,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    saveEnabled: Boolean,
    onDelete: (() -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        androidx.compose.material3.Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column {
                Row(
                    Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GcIconButton(GcIcons.Close, contentDescription = stringResource(R.string.familie_close), onClick = onDismiss, tint = MaterialTheme.colorScheme.onBackground)
                    Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f).padding(start = 4.dp))
                    TextButton(onClick = onSave, enabled = saveEnabled) { Text(stringResource(R.string.familie_save)) }
                }
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(androidx.compose.foundation.rememberScrollState())
                        .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    content()
                    if (onDelete != null) {
                        TextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.familie_delete), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}
