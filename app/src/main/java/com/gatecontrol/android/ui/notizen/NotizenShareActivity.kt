package com.gatecontrol.android.ui.notizen

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.R
import com.gatecontrol.android.chat.ChatShareRules
import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.familie.PluginDirectory
import com.gatecontrol.android.notizen.NArea
import com.gatecontrol.android.notizen.NResult
import com.gatecontrol.android.notizen.NotizenJson
import com.gatecontrol.android.notizen.NotizenRepository
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.components.GcSegmented
import com.gatecontrol.android.ui.theme.GateControlTheme
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

/** What a share brings and what becomes of it (pure parts unit-tested in [NotizenShare]). */
object NotizenShare {
    /** The shared text and subject → title and the rest (a link alone: the subject or the link is the title). */
    fun split(subject: String?, text: String?): Pair<String, String> {
        val t = text?.trim().orEmpty()
        val s = subject?.trim()?.takeIf { it.isNotEmpty() }
        if (s != null) return s.take(300) to t
        val first = t.substringBefore('\n').trim()
        return first.take(300) to t.substringAfter('\n', "").trim()
    }
}

@HiltViewModel
class NotizenShareViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: NotizenRepository,
) : ViewModel() {
    data class Ui(
        val subject: String? = null,
        val text: String? = null,
        val files: List<Uri> = emptyList(),
        val note: Boolean = true,
        val area: String = NArea.PRIVATE,
        val busy: Boolean = false,
        val done: Boolean = false,
        val error: String? = null,
    )

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui.asStateFlow()

    fun start(subject: String?, text: String?, files: List<Uri>) {
        _ui.update { it.copy(subject = subject, text = text, files = files, note = files.isNotEmpty() || (text?.length ?: 0) > 120) }
        viewModelScope.launch {
            repo.meOrCached()
            _ui.update { it.copy(area = repo.currentArea()) }
        }
    }

    fun setNote(v: Boolean) = _ui.update { it.copy(note = v) }
    fun setArea(a: String) = _ui.update { it.copy(area = NArea.of(a)) }

    fun save() {
        val u = _ui.value
        if (u.busy) return
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, error = null) }
            val (title, rest) = NotizenShare.split(u.subject, u.text)
            val fallback = context.getString(R.string.notizen_share_title_files)
            // only text: it may wait for a connection like any new note or task (plugin 1.1.0)
            if (u.files.isEmpty()) {
                val body = if (u.note) {
                    NotizenJson.noteBody(null, u.area, title.ifEmpty { fallback }, rest.ifEmpty { if (u.subject != null) u.text.orEmpty() else "" })
                } else {
                    NotizenJson.taskBody(title.ifEmpty { fallback }, area = u.area, note = rest.takeIf { it.isNotEmpty() })
                }
                val r = if (u.note) repo.createNote(body).code() else repo.createTask(body).code()
                if (r != null) { fail(r); return@launch }
                _ui.update { it.copy(busy = false, done = true) }
                return@launch
            }
            val (kind, id) = if (u.note) {
                val r = repo.api.createNote(NotizenJson.noteBody(null, u.area, title.ifEmpty { fallback }, rest.ifEmpty { if (u.subject != null) u.text.orEmpty() else "" }))
                if (r !is NResult.Ok) { fail(r.code()); return@launch }
                "note" to r.value.summary.id
            } else {
                val r = repo.api.createTask(NotizenJson.taskBody(title.ifEmpty { fallback }, area = u.area, note = rest.takeIf { it.isNotEmpty() }))
                if (r !is NResult.Ok) { fail(r.code()); return@launch }
                "task" to r.value.id
            }
            val me = repo.meOrCached()
            val max = (me?.attachMB ?: 25) * 1024L * 1024L
            var body = ""
            for (f in u.files) {
                val picked = withContext(Dispatchers.IO) { readPicked(context, f, max) } ?: continue
                val up = repo.api.upload(picked.first, picked.second)
                if (up !is NResult.Ok) continue
                val a = repo.api.attach(NotizenJson.attachBody(kind, id, up.value, picked.third, null)).valueOrNull() ?: continue
                if (kind == "note") body += (if (picked.second.startsWith("image/")) "!" else "") + "[" + picked.third.replace(Regex("[\\[\\]()]"), "") + "](att:${a.id})\n"
            }
            if (kind == "note" && body.isNotEmpty()) {
                val n = repo.api.note(id).valueOrNull()?.first
                val text = listOf(n?.body.orEmpty(), body.trim()).filter { it.isNotBlank() }.joinToString("\n\n")
                repo.api.patchNote(id, com.google.gson.JsonObject().apply { addProperty("body", text) })
            }
            repo.touched("notes", "tasks")
            _ui.update { it.copy(busy = false, done = true) }
        }
    }

    private fun fail(code: String?) = _ui.update { it.copy(busy = false, error = code ?: "failed") }
}

/**
 * Share target „Als Notiz oder Aufgabe“: a text, a link or files from any app become a note or a task of Notizen &
 * Aufgaben. Exported for the share sheet, switched on only while the server offers the plugin ([setEnabled]). Reads
 * only content:// URIs of other apps (ChatShareRules), never file:// or the app's own providers.
 */
@AndroidEntryPoint
class NotizenShareActivity : ComponentActivity() {

    @Inject lateinit var setupRepository: SetupRepository
    @Inject lateinit var pluginDirectory: PluginDirectory
    @Inject lateinit var settingsRepository: SettingsRepository

    private val share: NotizenShareViewModel by viewModels()

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!setupRepository.isConfigured() || !pluginDirectory.state.value.notizen) {
            Toast.makeText(this, R.string.notizen_share_unavailable, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val action = intent.action
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) {
            finish()
            return
        }
        if (savedInstanceState == null) {
            val uris: List<Uri> = if (action == Intent.ACTION_SEND_MULTIPLE) {
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            } else {
                listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            }
            val files = uris.filter { ChatShareRules.acceptable(it, packageName) }.take(MAX_FILES)
            val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() }?.take(100_000)
            val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.takeIf { it.isNotBlank() }
            if (files.isEmpty() && text == null) {
                Toast.makeText(this, R.string.notizen_share_nothing, Toast.LENGTH_LONG).show()
                finish()
                return
            }
            share.start(subject, text, files)
        }
        setContent {
            val theme by settingsRepository.getTheme().collectAsStateWithLifecycle(initialValue = "system")
            val dark = when (theme) {
                "dark" -> true
                "light" -> false
                else -> isSystemInDarkTheme()
            }
            GateControlTheme(darkTheme = dark) {
                val ui by share.ui.collectAsStateWithLifecycle()
                LaunchedEffect(ui.done) {
                    if (ui.done) {
                        Toast.makeText(this@NotizenShareActivity, if (ui.note) R.string.notizen_msg_note_saved else R.string.notizen_msg_task_saved, Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }
                ModalBottomSheet(onDismissRequest = { finish() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(stringResource(R.string.notizen_share_target_label), style = MaterialTheme.typography.headlineSmall)
                        GcSegmented(listOf(true to stringResource(R.string.notizen_quick_note), false to stringResource(R.string.notizen_quick_task)), ui.note, share::setNote)
                        val (title, _) = NotizenShare.split(ui.subject, ui.text)
                        if (title.isNotEmpty()) Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (ui.files.isNotEmpty()) Text(stringResource(R.string.notizen_share_files, ui.files.size), style = MaterialTheme.typography.bodyMedium, color = GateControlTheme.extraColors.muted)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.notizen_area), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            NAreaPill(ui.area, share::setArea)
                        }
                        ui.error?.let { Text(messageText(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        GcPrimaryButton(stringResource(R.string.notizen_save), share::save, loading = ui.busy)
                        GcOutlineButton(stringResource(R.string.notizen_cancel), { finish() })
                    }
                }
            }
        }
    }

    companion object {
        private const val MAX_FILES = 10

        /** On while the server offers Notizen & Aufgaben: only then „Als Notiz oder Aufgabe“ shows up in the share sheet. */
        fun setEnabled(context: Context, enabled: Boolean) {
            runCatching {
                val component = ComponentName(context, NotizenShareActivity::class.java)
                val want = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                if (context.packageManager.getComponentEnabledSetting(component) != want) {
                    context.packageManager.setComponentEnabledSetting(component, want, PackageManager.DONT_KILL_APP)
                }
            }.onFailure { Timber.w("Notizen: share target not switched (%s)", it.javaClass.simpleName) }
        }
    }
}
