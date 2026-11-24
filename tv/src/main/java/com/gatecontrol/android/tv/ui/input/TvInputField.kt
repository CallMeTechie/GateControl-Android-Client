package com.gatecontrol.android.tv.ui.input

import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.gatecontrol.android.tv.ui.home.TvIcons
import com.gatecontrol.android.tv.ui.theme.GateControlTvTheme

/**
 * How the input fields of the TV talk to the system keyboard (and through it to the dictation of Fire OS):
 * [CLASSIC] a plain Android [EditText] embedded with AndroidView — the reports of dictation on Fire TV are about such
 * fields; [COMPOSE] Compose's own BasicTextField. Not tested at the stick yet, so it is chosen in one place:
 * [TvInput.DEFAULT] (tests set [LocalTvInputVariant]).
 */
enum class TvInputVariant { CLASSIC, COMPOSE }

object TvInput {
    /** The one place that decides the input field of GateControl TV (see [TvInputVariant]). */
    val DEFAULT: TvInputVariant = TvInputVariant.CLASSIC
}

/** The variant of every [TvInputField] below; the app keeps [TvInput.DEFAULT], tests take [TvInputVariant.COMPOSE]. */
val LocalTvInputVariant = compositionLocalOf { TvInput.DEFAULT }

/** Einstellungen › Eingabe „Diktat-Hinweise ausblenden“ off: the hints on the microphone button show (default). */
val LocalTvDictationHints = compositionLocalOf { true }

/**
 * Opens the system keyboard on a [TvInputField] from a button („Weiter sprechen“, „Bearbeiten“): the field takes the
 * focus, the cursor goes to the end. Nothing happens while no field is attached.
 */
class TvInputHandle {
    internal var opener: (() -> Unit)? = null

    fun open() {
        opener?.invoke()
    }
}

/**
 * A text field for the remote (look of the mockup's `.ta` / input): focused with the D-pad, OK opens the system
 * keyboard of Fire OS, then the microphone button dictates into it. [onDone]: „Fertig“ on the keyboard.
 * [minHeight] > 0 makes it a multi-line area (answers), else one line.
 */
@Composable
fun TvInputField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    maxLength: Int = Int.MAX_VALUE,
    minHeight: Dp = 0.dp,
    onDone: () -> Unit = {},
    handle: TvInputHandle? = null,
    /** On the field itself (focus requests); [modifier] is on the column with the label. */
    fieldModifier: Modifier = Modifier,
    showLabel: Boolean = true,
) {
    val colors = GateControlTvTheme.colors
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (showLabel) Text(label, style = MaterialTheme.typography.labelMedium, color = colors.muted)
        when (LocalTvInputVariant.current) {
            TvInputVariant.CLASSIC -> ClassicField(value, onValueChange, label, placeholder, maxLength, minHeight, onDone, handle, fieldModifier)
            TvInputVariant.COMPOSE -> ComposeField(value, onValueChange, label, placeholder, maxLength, minHeight, onDone, handle, fieldModifier)
        }
    }
}

/** The look of every field: panel, line, 8 dp corners; the focus ring of the TV. */
@Composable
private fun Modifier.frame(focused: Boolean, minHeight: Dp): Modifier {
    val colors = GateControlTvTheme.colors
    val shape = RoundedCornerShape(8.dp)
    return this
        .fillMaxWidth()
        .heightIn(min = minHeight)
        .background(if (minHeight > 0.dp) colors.panel2 else colors.panel, shape)
        .border(if (focused) BorderStroke(2.dp, colors.focus) else BorderStroke(1.dp, colors.line2), shape)
        .padding(horizontal = 12.dp, vertical = 9.dp)
}

@Composable
private fun ComposeField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String?,
    maxLength: Int,
    minHeight: Dp,
    onDone: () -> Unit,
    handle: TvInputHandle?,
    fieldModifier: Modifier,
) {
    val colors = GateControlTvTheme.colors
    var focused by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val keyboard: SoftwareKeyboardController? = LocalSoftwareKeyboardController.current
    // the cursor at the end whenever the text changes from outside (a building block, „Weiter sprechen“)
    var field by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    if (field.text != value) field = TextFieldValue(value, TextRange(value.length))
    SideEffect {
        handle?.opener = {
            field = field.copy(selection = TextRange(field.text.length))
            runCatching { focus.requestFocus() }
            keyboard?.show()
        }
    }
    val style = MaterialTheme.typography.bodyLarge.copy(color = colors.text)
    BasicTextField(
        value = field,
        onValueChange = {
            val v = if (it.text.length > maxLength) it.copy(text = it.text.take(maxLength)) else it
            field = v
            if (v.text != value) onValueChange(v.text)
        },
        singleLine = minHeight <= 0.dp,
        textStyle = style,
        cursorBrush = SolidColor(colors.accent),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = fieldModifier
            .focusRequester(focus)
            .fillMaxWidth()
            .semantics { contentDescription = label }
            .onFocusChanged { focused = it.isFocused },
        decorationBox = { inner ->
            Box(Modifier.frame(focused, minHeight)) {
                if (value.isEmpty() && placeholder != null) Text(placeholder, style = style.copy(color = colors.faint))
                inner()
            }
        },
    )
}

/** A plain Android [EditText]: the system keyboard of Fire OS talks to it the classic way. */
@Composable
private fun ClassicField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String?,
    maxLength: Int,
    minHeight: Dp,
    onDone: () -> Unit,
    handle: TvInputHandle?,
    fieldModifier: Modifier,
) {
    val colors = GateControlTvTheme.colors
    var focused by remember { mutableStateOf(false) }
    val change by rememberUpdatedState(onValueChange)
    val done by rememberUpdatedState(onDone)
    val text = colors.text.toArgb()
    val hint = colors.faint.toArgb()
    val multi = minHeight > 0.dp
    val textSize = MaterialTheme.typography.bodyLarge.fontSize.value
    var view by remember { mutableStateOf<EditText?>(null) }
    SideEffect { handle?.opener = { view?.let(::openKeyboard) } }
    AndroidView(
        factory = { context ->
            EditText(context).apply {
                // Fire OS: TYPE_CLASS_TEXT with the flags of a message, „Fertig“ on the keyboard also for several lines
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                    (if (multi) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0)
                imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_FULLSCREEN
                if (multi) {
                    // a multi-line field still shows „Fertig“ instead of a return key
                    setRawInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
                    isSingleLine = false
                    setHorizontallyScrolling(false)
                    maxLines = 8
                    gravity = Gravity.TOP or Gravity.START
                } else {
                    isSingleLine = true
                }
                contentDescription = label
                setHint(placeholder)
                setTextColor(text)
                setHintTextColor(hint)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, textSize)
                background = null
                setPadding(0, 0, 0, 0)
                setText(value)
                setSelection(value.length)
                setOnFocusChangeListener { _, has -> focused = has }
                setOnEditorActionListener { _, action, event ->
                    val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
                    if (action == EditorInfo.IME_ACTION_DONE || (!multi && enter)) {
                        done()
                        true
                    } else {
                        false
                    }
                }
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                    override fun afterTextChanged(s: Editable?) {
                        val now = s?.toString().orEmpty()
                        if (now.length > maxLength) {
                            s?.delete(maxLength, now.length)
                            return
                        }
                        change(now)
                    }
                })
                view = this
            }
        },
        update = { v ->
            // the text changed from outside (a building block, „Weiter sprechen“, cleared): the cursor to the end
            if (v.text.toString() != value) {
                v.setText(value)
                v.setSelection(value.length)
            }
        },
        modifier = fieldModifier.frame(focused, minHeight),
    )
}

private fun openKeyboard(view: EditText) {
    view.requestFocus()
    view.setSelection(view.text.length)
    val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
    imm?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
}

/**
 * The dictation hint (mockup `.mic`): OK opens the keyboard, then hold the microphone button. Hidden with
 * „Diktat-Hinweise ausblenden“. [text] the line to show.
 */
@Composable
fun TvDictationHint(text: String, modifier: Modifier = Modifier, boxed: Boolean = false) {
    if (!LocalTvDictationHints.current) return
    val colors = GateControlTvTheme.colors
    val box = if (boxed) Modifier.fillMaxWidth().background(colors.infoBg, RoundedCornerShape(7.dp)).padding(horizontal = 9.dp, vertical = 6.dp) else Modifier
    Row(modifier.then(box), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (boxed) 8.dp else 6.dp)) {
        Icon(TvIcons.Mic, contentDescription = null, tint = if (boxed) colors.info else colors.muted, modifier = Modifier.size(if (boxed) 16.dp else 14.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = if (boxed) colors.text else colors.muted)
    }
}
