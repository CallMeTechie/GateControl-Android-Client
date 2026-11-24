package com.gatecontrol.android.tv.ui.input

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.gatecontrol.android.tv.R
import com.gatecontrol.android.tv.input.TvComposerLogic
import com.gatecontrol.android.tv.input.TvHandoff
import com.gatecontrol.android.tv.input.TvSnippet
import com.gatecontrol.android.tv.input.TvSnippetKind
import com.gatecontrol.android.tv.input.TvSnippetSource
import com.gatecontrol.android.tv.ui.components.TvButton
import com.gatecontrol.android.tv.ui.components.TvButtonStyle
import com.gatecontrol.android.tv.ui.components.TvConfirmDialog
import com.gatecontrol.android.tv.ui.drive.requestSoon
import com.gatecontrol.android.tv.ui.gatedrop.TvSendDialog
import com.gatecontrol.android.tv.ui.home.TvIcons
import com.gatecontrol.android.tv.ui.theme.GateControlTvTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * The input building block of GateControl TV (stage 3d, mockup „Antworten“ and „So senden?“): a field with the system
 * keyboard (dictation of Fire OS with the microphone button), the hint how, the column of text building blocks and
 * then always the big preview before [onSend] — the sending itself is the caller's (`true`: sent, the composer
 * closes; `false`: „Nicht gesendet“, the draft stays). Zurück asks „Entwurf verwerfen?“; a draft survives a trip to
 * Alexa (the app went to the background), coming back shows the hint again.
 *
 * [smooth]: the preview smooths punctuation (first word capital, a full stop) — for messages, not for list entries.
 * [header]: above the field (mail: Empfänger, Betreff). [previewMeta]: the line under „So senden?“ („an Familie · …“).
 */
@Composable
fun TvComposer(
    title: String,
    onSend: suspend (String) -> Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = stringResource(R.string.tv_composer_label),
    sendLabel: String = stringResource(R.string.tv_composer_send),
    previewMeta: String? = null,
    initial: String = "",
    smooth: Boolean = true,
    maxLength: Int = TvComposerLogic.MAX_TEXT,
    sources: List<TvSnippetSource> = emptyList(),
    handoff: TvHandoff? = null,
    header: @Composable ColumnScope.() -> Unit = {},
) {
    val colors = GateControlTvTheme.colors
    val scope = rememberCoroutineScope()
    var draft by rememberSaveable { mutableStateOf(initial.take(maxLength)) }
    var preview by rememberSaveable { mutableStateOf(false) }
    var askDiscard by rememberSaveable { mutableStateOf(false) }
    var handing by rememberSaveable { mutableStateOf(false) }
    var away by rememberSaveable { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var snippets by remember { mutableStateOf<List<TvSnippet>>(emptyList()) }
    val handle = remember { TvInputHandle() }
    val field = remember { FocusRequester() }

    // the app went to the background while writing (Alexa took the microphone button): the draft stays, a hint
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        var left = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> left = true
                Lifecycle.Event.ON_START -> if (left) {
                    left = false
                    away = true
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(sources) {
        snippets = sources.flatMap { source ->
            try {
                source.snippets()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
    // „Weiter sprechen“ / „Bearbeiten“: the keyboard once the preview is gone
    var reopen by remember { mutableStateOf(false) }
    LaunchedEffect(preview, reopen) {
        if (preview) return@LaunchedEffect
        field.requestSoon()
        if (reopen) {
            withFrameNanos { }
            handle.open()
            reopen = false
        }
    }

    fun openKeyboard(more: Boolean) {
        if (more) draft = TvComposerLogic.continued(draft)
        preview = false
        failed = false
        away = false
        reopen = true
    }
    fun toPreview() {
        if (draft.isNotBlank()) {
            failed = false
            preview = true
        }
    }
    fun send() {
        if (sending) return
        val text = if (smooth) TvComposerLogic.smooth(draft) else draft.trim()
        if (text.isEmpty()) return
        sending = true
        failed = false
        scope.launch {
            val ok = try {
                onSend(text)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            sending = false
            if (ok) {
                draft = ""
                preview = false
                onClose()
            } else {
                failed = true
            }
        }
    }

    BackHandler(enabled = !preview) { if (draft.isBlank()) onClose() else askDiscard = true }

    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, color = colors.text)
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                header()
                TvInputField(
                    value = draft,
                    onValueChange = {
                        draft = it
                        away = false
                    },
                    label = label,
                    showLabel = false,
                    maxLength = maxLength,
                    minHeight = 110.dp,
                    onDone = ::toPreview,
                    handle = handle,
                    fieldModifier = Modifier.focusRequester(field),
                )
                Text(
                    stringResource(R.string.tv_composer_count, draft.length),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.faint,
                    modifier = Modifier.align(Alignment.End),
                )
                if (away && LocalTvDictationHints.current) {
                    Text(
                        stringResource(R.string.tv_composer_back_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.warn,
                        modifier = Modifier.fillMaxWidth().background(colors.warnBg, RoundedCornerShape(7.dp)).padding(horizontal = 9.dp, vertical = 6.dp),
                    )
                }
                TvDictationHint(stringResource(R.string.tv_composer_mic_hint), boxed = true)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TvButton(stringResource(R.string.tv_composer_to_preview), onClick = ::toPreview, style = TvButtonStyle.Primary, enabled = draft.isNotBlank())
                    if (handoff != null) {
                        TvButton(stringResource(R.string.tv_composer_handoff), onClick = { handing = true }, icon = TvIcons.Phone, enabled = draft.isNotBlank())
                    }
                    TvButton(stringResource(R.string.tv_composer_discard), onClick = {
                        draft = ""
                        onClose()
                    })
                }
            }
            SnippetColumn(snippets, onPick = { draft = TvComposerLogic.insert(draft, it.text).take(maxLength) })
        }
    }

    if (preview) {
        TvComposerPreview(
            text = if (smooth) TvComposerLogic.smooth(draft) else draft.trim(),
            meta = previewMeta,
            sendLabel = sendLabel,
            sending = sending,
            failed = failed,
            onSend = ::send,
            onMore = { openKeyboard(more = true) },
            onEdit = { openKeyboard(more = false) },
            onHandoff = handoff?.let { { handing = true } },
            onDiscard = {
                draft = ""
                preview = false
                onClose()
            },
        )
    }
    if (askDiscard) {
        TvConfirmDialog(
            title = stringResource(R.string.tv_composer_discard_title),
            text = stringResource(R.string.tv_composer_discard_text),
            confirm = stringResource(R.string.tv_composer_discard),
            onConfirm = {
                askDiscard = false
                draft = ""
                onClose()
            },
            onDismiss = { askDiscard = false },
        )
    }
    if (handing && handoff != null) {
        val text = draft.trim()
        TvSendDialog(
            name = text,
            title = stringResource(R.string.tv_composer_handoff_title),
            targets = handoff.targets,
            send = { handoff.send(it, text) },
            onDismiss = { handing = false },
        )
    }
}

/** The column „Textbausteine“ (mockup `.snips`): OK puts one into the draft. */
@Composable
private fun SnippetColumn(snippets: List<TvSnippet>, onPick: (TvSnippet) -> Unit) {
    val colors = GateControlTvTheme.colors
    Column(Modifier.width(240.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.tv_composer_snippets), style = MaterialTheme.typography.titleMedium, color = colors.text)
        if (snippets.isEmpty()) {
            Text(stringResource(R.string.tv_composer_snippets_none), style = MaterialTheme.typography.bodySmall, color = colors.muted)
        } else {
            LazyColumn(Modifier.weight(1f, fill = false), contentPadding = PaddingValues(4.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                itemsIndexed(snippets) { _, s -> SnippetCard(s, onClick = { onPick(s) }) }
            }
            Text(stringResource(R.string.tv_composer_snippets_more), style = MaterialTheme.typography.labelSmall, color = colors.faint)
        }
        // the fallback when dictation does not work (always there)
        Text(stringResource(R.string.tv_composer_fallback), style = MaterialTheme.typography.labelSmall, color = colors.faint)
    }
}

@Composable
private fun SnippetCard(snippet: TvSnippet, onClick: () -> Unit) {
    val colors = GateControlTvTheme.colors
    val shape = RoundedCornerShape(7.dp)
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = colors.panel,
            contentColor = colors.text,
            focusedContainerColor = colors.panel2,
            focusedContentColor = colors.text,
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, colors.line), shape = shape),
            focusedBorder = Border(BorderStroke(2.dp, colors.focus), shape = shape),
        ),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = Glow(elevationColor = colors.focusGlow, elevation = 8.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 5.dp)) {
            Text(
                stringResource(
                    when (snippet.kind) {
                        TvSnippetKind.OWN -> R.string.tv_composer_snippet_own
                        TvSnippetKind.AI -> R.string.tv_composer_snippet_ai
                        TvSnippetKind.SUGGESTION -> R.string.tv_composer_snippet_suggestion
                    },
                ).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = if (snippet.kind == TvSnippetKind.OWN) colors.faint else colors.info,
            )
            Text(snippet.text, style = MaterialTheme.typography.bodySmall, color = colors.text, maxLines = 3)
        }
    }
}

/**
 * „So senden?“ (Frage 16 a: always): the whole text large, numbers and times yellow. Senden / Weiter sprechen /
 * Bearbeiten / Am Handy weiter / Verwerfen; Zurück is „Bearbeiten“.
 */
@Composable
fun TvComposerPreview(
    text: String,
    meta: String?,
    sendLabel: String,
    sending: Boolean,
    failed: Boolean,
    onSend: () -> Unit,
    onMore: () -> Unit,
    onEdit: () -> Unit,
    onHandoff: (() -> Unit)?,
    onDiscard: () -> Unit,
) {
    val colors = GateControlTvTheme.colors
    val first = remember { FocusRequester() }
    Dialog(onDismissRequest = { if (!sending) onEdit() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.width(620.dp).background(colors.panel, RoundedCornerShape(14.dp)).padding(horizontal = 22.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.tv_composer_preview_title), style = MaterialTheme.typography.headlineMedium, color = colors.text)
            if (meta != null) Text(meta, style = MaterialTheme.typography.bodySmall, color = colors.muted)
            Text(
                marked(text, colors.warnBg, colors.warn),
                style = MaterialTheme.typography.headlineSmall,
                color = colors.text,
                modifier = Modifier.fillMaxWidth().background(colors.panel2, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
            )
            when {
                sending -> Text(stringResource(R.string.tv_composer_sending), style = MaterialTheme.typography.bodyMedium, color = colors.muted)
                failed -> Text(stringResource(R.string.tv_composer_failed), style = MaterialTheme.typography.bodyMedium, color = colors.warn)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TvButton(sendLabel, onClick = onSend, style = TvButtonStyle.Primary, enabled = !sending, modifier = Modifier.focusRequester(first))
                TvButton(stringResource(R.string.tv_composer_more), onClick = onMore, icon = TvIcons.Mic, enabled = !sending)
                TvButton(stringResource(R.string.tv_composer_edit), onClick = onEdit, enabled = !sending)
                if (onHandoff != null) TvButton(stringResource(R.string.tv_composer_handoff_short), onClick = onHandoff, icon = TvIcons.Phone, enabled = !sending)
                TvButton(stringResource(R.string.tv_composer_discard), onClick = onDiscard, enabled = !sending)
            }
        }
    }
    LaunchedEffect(Unit) { first.requestSoon() }
}

/** [text] with its numbers and times on [bg] in [fg] (mockup `mark`). */
internal fun marked(text: String, bg: androidx.compose.ui.graphics.Color, fg: androidx.compose.ui.graphics.Color): AnnotatedString = buildAnnotatedString {
    append(text)
    for (r in TvComposerLogic.marks(text)) addStyle(SpanStyle(background = bg, color = fg), r.first, r.last + 1)
}

/**
 * [TvComposer] over the whole screen (Einstellungen › Neuer Textbaustein, later the answers of chats and mails when
 * they open from a list).
 */
@Composable
fun TvComposerDialog(
    title: String,
    onSend: suspend (String) -> Boolean,
    onClose: () -> Unit,
    label: String = stringResource(R.string.tv_composer_label),
    sendLabel: String = stringResource(R.string.tv_composer_send),
    previewMeta: String? = null,
    initial: String = "",
    smooth: Boolean = true,
    maxLength: Int = TvComposerLogic.MAX_TEXT,
    sources: List<TvSnippetSource> = emptyList(),
    handoff: TvHandoff? = null,
    /** Above the field (a chat: the message the answer refers to, the way it goes). */
    header: @Composable ColumnScope.() -> Unit = {},
) {
    val colors = GateControlTvTheme.colors
    Dialog(onDismissRequest = {}, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = false, dismissOnClickOutside = false)) {
        TvComposer(
            title = title,
            onSend = onSend,
            onClose = onClose,
            label = label,
            sendLabel = sendLabel,
            previewMeta = previewMeta,
            initial = initial,
            smooth = smooth,
            maxLength = maxLength,
            sources = sources,
            handoff = handoff,
            header = header,
            modifier = Modifier.fillMaxSize().background(colors.bg).padding(horizontal = 36.dp, vertical = 24.dp),
        )
    }
}
