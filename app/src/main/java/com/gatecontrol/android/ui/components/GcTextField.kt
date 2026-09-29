package com.gatecontrol.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.ui.theme.GateControlTheme
import com.gatecontrol.android.ui.theme.MonoFontFamily

/**
 * Input with the label above the field (`.inp` + `<label>`): 52 dp high,
 * 14 dp corners, page background, blue focus ring.
 */
@Composable
fun GcTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    hint: String? = null,
    error: String? = null,
    mono: Boolean = false,
    prefix: String? = null,
    singleLine: Boolean = true,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    val extra = GateControlTheme.extraColors
    val scheme = MaterialTheme.colorScheme
    val textStyle = MaterialTheme.typography.bodyLarge.let { if (mono) it.copy(fontFamily = MonoFontFamily) else it }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall, color = extra.muted)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            textStyle = textStyle,
            placeholder = placeholder?.let { { Text(it, style = textStyle, color = extra.faint) } },
            prefix = prefix?.let { { Text(it, style = textStyle, color = extra.faint) } },
            trailingIcon = trailingIcon,
            singleLine = singleLine,
            enabled = enabled,
            readOnly = readOnly,
            isError = error != null,
            visualTransformation = visualTransformation,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = scheme.background,
                unfocusedContainerColor = scheme.background,
                disabledContainerColor = scheme.background,
                errorContainerColor = scheme.background,
                focusedBorderColor = extra.blue,
                unfocusedBorderColor = extra.border2,
                disabledBorderColor = extra.border,
                errorBorderColor = scheme.error,
                cursorColor = extra.blue,
                focusedTextColor = scheme.onSurface,
                unfocusedTextColor = scheme.onSurface,
            ),
        )
        when {
            error != null -> Text(error, style = MaterialTheme.typography.bodySmall, color = scheme.error)
            hint != null -> Text(hint, style = MaterialTheme.typography.bodySmall, color = extra.faint)
        }
    }
}
