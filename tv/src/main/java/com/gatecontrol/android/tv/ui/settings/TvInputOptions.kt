package com.gatecontrol.android.tv.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.gatecontrol.android.tv.R
import com.gatecontrol.android.tv.input.TvComposerLogic
import com.gatecontrol.android.tv.ui.components.TvButton
import com.gatecontrol.android.tv.ui.components.TvButtonStyle
import com.gatecontrol.android.tv.ui.components.TvDialog
import com.gatecontrol.android.tv.ui.components.TvOptionRow
import com.gatecontrol.android.tv.ui.components.TvRowEnd
import com.gatecontrol.android.tv.ui.drive.requestSoon
import com.gatecontrol.android.tv.ui.input.TvComposerDialog
import com.gatecontrol.android.tv.ui.theme.GateControlTvTheme

/**
 * Einstellungen › Eingabe (stage 3d): the own text building blocks (new with the composer, move, delete), the
 * switch „Diktat-Hinweise ausblenden“, what Amazon does with dictation and the ways without it.
 */
@Composable
fun TvInputOptions(
    snippets: List<String>,
    onSnippets: (List<String>) -> Unit,
    hints: Boolean,
    onHints: (Boolean) -> Unit,
) {
    val colors = GateControlTvTheme.colors
    var adding by rememberSaveable { mutableStateOf(false) }
    var chosen by rememberSaveable { mutableStateOf<Int?>(null) }
    val full = snippets.size >= TvComposerLogic.MAX_SNIPPETS

    Text(stringResource(R.string.tv_input_snippets), style = MaterialTheme.typography.titleMedium, color = colors.text)
    Text(stringResource(R.string.tv_input_snippets_desc), style = MaterialTheme.typography.bodySmall, color = colors.muted)
    if (snippets.isEmpty()) Text(stringResource(R.string.tv_input_snippet_none), style = MaterialTheme.typography.bodySmall, color = colors.faint)
    snippets.forEachIndexed { i, s ->
        TvOptionRow(
            title = s,
            description = stringResource(R.string.tv_input_snippet_position, i + 1, snippets.size),
            end = TvRowEnd.Arrow,
            onClick = { chosen = i },
        )
    }
    TvOptionRow(
        title = stringResource(R.string.tv_input_snippet_new),
        description = if (full) stringResource(R.string.tv_input_snippet_full, TvComposerLogic.MAX_SNIPPETS) else stringResource(R.string.tv_input_snippet_new_desc),
        end = TvRowEnd.Arrow,
        onClick = { if (!full) adding = true },
    )
    TvOptionRow(
        title = stringResource(R.string.tv_input_hints_off),
        description = stringResource(R.string.tv_input_hints_off_desc),
        end = TvRowEnd.Switch(!hints),
        onClick = { onHints(!hints) },
    )
    Info(stringResource(R.string.tv_input_privacy_title), stringResource(R.string.tv_input_privacy), warn = true)
    Info(stringResource(R.string.tv_input_fallback_title), stringResource(R.string.tv_input_fallback), warn = false)

    if (adding) {
        TvComposerDialog(
            title = stringResource(R.string.tv_input_snippet_new),
            sendLabel = stringResource(R.string.tv_input_snippet_save),
            // a building block goes into answers that are smoothed when sent
            smooth = false,
            maxLength = TvComposerLogic.MAX_SNIPPET,
            onSend = {
                onSnippets(snippets + it)
                true
            },
            onClose = { adding = false },
        )
    }
    chosen?.let { i ->
        val text = snippets.getOrNull(i)
        if (text == null) {
            chosen = null
        } else {
            SnippetDialog(
                text = text,
                first = i == 0,
                last = i == snippets.lastIndex,
                onMove = { by ->
                    onSnippets(TvComposerLogic.move(snippets, i, by))
                    chosen = (i + by).coerceIn(0, snippets.lastIndex)
                },
                onDelete = {
                    onSnippets(snippets.filterIndexed { j, _ -> j != i })
                    chosen = null
                },
                onDismiss = { chosen = null },
            )
        }
    }
}

@Composable
private fun Info(title: String, text: String, warn: Boolean) {
    val colors = GateControlTvTheme.colors
    Column(
        Modifier.fillMaxWidth().background(if (warn) colors.warnBg else colors.panel2, RoundedCornerShape(9.dp)).padding(horizontal = 12.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = if (warn) colors.warn else colors.text)
        Text(text, style = MaterialTheme.typography.bodySmall, color = colors.muted)
    }
}

@Composable
private fun SnippetDialog(text: String, first: Boolean, last: Boolean, onMove: (Int) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    val colors = GateControlTvTheme.colors
    val focus = remember { FocusRequester() }
    TvDialog(onDismiss) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = colors.text)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            TvButton(stringResource(R.string.tv_input_snippet_up), onClick = { onMove(-1) }, enabled = !first)
            TvButton(stringResource(R.string.tv_input_snippet_down), onClick = { onMove(1) }, enabled = !last)
            TvButton(stringResource(R.string.tv_input_snippet_delete), onClick = onDelete, style = TvButtonStyle.Warn)
        }
        TvButton(stringResource(R.string.tv_cancel), onClick = onDismiss, modifier = Modifier.focusRequester(focus))
    }
    LaunchedEffect(Unit) { focus.requestSoon() }
}
