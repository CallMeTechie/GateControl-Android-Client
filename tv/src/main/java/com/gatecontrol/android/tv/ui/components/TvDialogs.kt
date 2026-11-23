package com.gatecontrol.android.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.gatecontrol.android.tv.R
import com.gatecontrol.android.tv.security.TvPinCheck
import com.gatecontrol.android.tv.security.TvPinHash
import com.gatecontrol.android.tv.ui.theme.GateControlTvTheme
import kotlinx.coroutines.launch

/** A modal panel of the TV: the focus stays inside, Zurück closes it. */
@Composable
fun TvDialog(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val colors = GateControlTvTheme.colors
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.width(420.dp).background(colors.panel, RoundedCornerShape(12.dp)).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) { content() }
    }
}

/** "Wirklich?" before an action the plugin wants confirmed. */
@Composable
fun TvConfirmDialog(title: String, text: String?, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val colors = GateControlTvTheme.colors
    val first = remember { FocusRequester() }
    TvDialog(onDismiss) {
        Text(title, style = MaterialTheme.typography.headlineSmall, color = colors.text)
        if (text != null) Text(text, style = MaterialTheme.typography.bodyMedium, color = colors.muted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            TvButton(confirm, onClick = onConfirm, style = TvButtonStyle.Primary, modifier = Modifier.focusRequester(first))
            TvButton(stringResource(R.string.tv_cancel), onClick = onDismiss)
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

/**
 * Asks for the TV PIN (OK on the field opens the number keyboard). [check] is the store's; wrong PINs count, after
 * five the entry is locked for a while (the text says until when).
 */
@Composable
fun TvPinDialog(title: String, check: suspend (String) -> TvPinCheck, onPassed: () -> Unit, onDismiss: () -> Unit) {
    val colors = GateControlTvTheme.colors
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    val field = remember { FocusRequester() }
    val wrongText = stringResource(R.string.tv_pin_wrong)
    val lockedText = stringResource(R.string.tv_pin_locked)
    fun submit() {
        if (checking || !TvPinHash.isValid(pin)) return
        checking = true
        scope.launch {
            val result = check(pin)
            checking = false
            pin = ""
            when (result) {
                TvPinCheck.Ok -> onPassed()
                is TvPinCheck.Wrong -> error = if (result.lockedUntil > 0) lockedText.format(minutesLeft(result.lockedUntil)) else wrongText.format(result.attemptsLeft)
                is TvPinCheck.Locked -> error = lockedText.format(minutesLeft(result.until))
            }
        }
    }
    TvDialog(onDismiss) {
        Text(title, style = MaterialTheme.typography.headlineSmall, color = colors.text)
        PinField(pin, { pin = it }, stringResource(R.string.tv_pin_label), field, onDone = ::submit)
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.warn) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TvButton(stringResource(R.string.tv_pin_ok), onClick = ::submit, style = TvButtonStyle.Primary, enabled = !checking && TvPinHash.isValid(pin))
            TvButton(stringResource(R.string.tv_cancel), onClick = onDismiss)
        }
    }
    LaunchedEffect(Unit) { runCatching { field.requestFocus() } }
}

/** Sets a new PIN: twice the same 4 to 8 digits. */
@Composable
fun TvNewPinDialog(onSet: (String) -> Unit, onDismiss: () -> Unit) {
    val colors = GateControlTvTheme.colors
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val field = remember { FocusRequester() }
    val mismatch = stringResource(R.string.tv_pin_mismatch)
    val invalid = stringResource(R.string.tv_pin_invalid)
    fun submit() {
        error = when {
            !TvPinHash.isValid(first) -> invalid
            first != second -> mismatch
            else -> null
        }
        if (error == null) onSet(first)
    }
    TvDialog(onDismiss) {
        Text(stringResource(R.string.tv_pin_new_title), style = MaterialTheme.typography.headlineSmall, color = colors.text)
        Text(stringResource(R.string.tv_pin_new_desc), style = MaterialTheme.typography.bodySmall, color = colors.muted)
        PinField(first, { first = it }, stringResource(R.string.tv_pin_new_label), field, onDone = {})
        PinField(second, { second = it }, stringResource(R.string.tv_pin_repeat_label), null, onDone = ::submit)
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.warn) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TvButton(stringResource(R.string.tv_pin_save), onClick = ::submit, style = TvButtonStyle.Primary)
            TvButton(stringResource(R.string.tv_cancel), onClick = onDismiss)
        }
    }
    LaunchedEffect(Unit) { runCatching { field.requestFocus() } }
}

@Composable
private fun PinField(value: String, onChange: (String) -> Unit, label: String, focus: FocusRequester?, onDone: () -> Unit) {
    TvTextInput(
        value = value,
        onValueChange = { v -> onChange(v.filter { it in '0'..'9' }.take(TvPinHash.MAX_LENGTH)) },
        label = label,
        mono = true,
        password = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        fieldModifier = if (focus != null) Modifier.focusRequester(focus) else Modifier,
    )
}

private fun minutesLeft(until: Long): Int = (((until - System.currentTimeMillis()) + 59_999) / 60_000).toInt().coerceAtLeast(1)
