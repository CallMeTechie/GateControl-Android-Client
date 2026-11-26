package com.gatecontrol.android.ui.media

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.R
import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.media.CastListProblem
import com.gatecontrol.android.media.CastStartProblem
import com.gatecontrol.android.media.CastVolumeKeys
import com.gatecontrol.android.media.MediaPhoneLogic
import com.gatecontrol.android.media.MediaPlayRequests
import com.gatecontrol.android.media.MediaTitle
import com.gatecontrol.android.media.PhoneCast
import com.gatecontrol.android.media.cast.CastCmd
import com.gatecontrol.android.media.cast.CastProtocol
import com.gatecontrol.android.media.cast.CastRemote
import com.gatecontrol.android.media.cast.CastRemotePhase
import com.gatecontrol.android.media.cast.CastRemoteState
import com.gatecontrol.android.media.cast.CastTarget
import com.gatecontrol.android.media.cast.CastTrack
import com.gatecontrol.android.ui.components.GcFilterChip
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.theme.GateControlTheme
import com.gatecontrol.android.ui.theme.GcSystemBars
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject
import kotlin.math.roundToInt

/*
 * "Abspielen auf …" (stage 4, mockup 7 left, question 23a): the cast mark on the detail page and in the player opens
 * the sheet — this phone (ticked), the own devices with GateControl TV or GateControl for Windows with their online
 * state. Chosen, the device gets the source, the title and the place (PhoneCast) and the phone becomes its remote
 * (MediaCastActivity): pause and on, ±10 s and the place, sound and subtitles, stop.
 */

/** The sheet's list. */
data class MediaCastUi(
    val loading: Boolean = true,
    val targets: List<CastTarget> = emptyList(),
    val problem: CastListProblem? = null,
    /** The device being sent to now. */
    val sending: String? = null,
    /** The device a send failed for. */
    val failed: String? = null,
    /** The plugin refused it: too many messages from this phone (60 a minute, 600 an hour). */
    val rateLimited: Boolean = false,
)

@HiltViewModel
class MediaCastViewModel @Inject constructor(
    val cast: PhoneCast,
    private val requests: MediaPlayRequests,
) : ViewModel() {
    private val _ui = MutableStateFlow(MediaCastUi())
    val ui: StateFlow<MediaCastUi> = _ui.asStateFlow()

    val state: StateFlow<CastRemoteState?> = cast.state

    /** A key was refused a moment ago (too many messages). */
    val limited: StateFlow<Boolean> = cast.limited

    /** „Abspielen auf …“ is offered at all: the plugin allows it and an own TV or PC is there. */
    val available: StateFlow<Boolean> = cast.available

    init {
        cast.refreshTargets()
    }

    fun canCast(title: MediaTitle): Boolean = cast.canCast(title)

    fun load() {
        _ui.update { it.copy(loading = true, failed = null, rateLimited = false) }
        viewModelScope.launch {
            val (targets, problem) = cast.targets()
            _ui.update { it.copy(loading = false, targets = targets, problem = problem) }
        }
    }

    /** [title] from [positionMs] to [target]; [then] once it went out (the remote opens). */
    fun start(target: CastTarget, title: MediaTitle, positionMs: Long, audio: Int?, subtitle: Int?, then: () -> Unit) {
        if (_ui.value.sending != null) return
        _ui.update { it.copy(sending = target.id, failed = null, rateLimited = false) }
        viewModelScope.launch {
            val problem = cast.start(target, title, positionMs, audio, subtitle)
            _ui.update {
                it.copy(
                    sending = null,
                    failed = target.name.takeIf { problem == CastStartProblem.FAILED },
                    rateLimited = problem == CastStartProblem.RATE_LIMITED,
                )
            }
            if (problem == null) then()
        }
    }

    fun command(cmd: CastCmd, positionMs: Long? = null, deltaMs: Long? = null, track: String? = null) = cast.command(cmd, positionMs, deltaMs, track)

    fun setVolume(volume: Int) = cast.setVolume(volume)

    fun toggleMute() = cast.toggleMute()

    /** "Auf diesem Handy weiter ab …": the title of the session from where it stopped there. */
    fun goOnHere(positionMs: Long) {
        val t = cast.title ?: return
        viewModelScope.launch { requests.play(t.copy(positionMs = positionMs, played = false)) }
        cast.dismiss()
    }

    fun dismiss() = cast.dismiss()
}

/**
 * The sheet "Abspielen auf …": [title] from [positionMs] (the detail page's place or the player's, which pauses
 * first), [audio] / [subtitle] the Jellyfin streams chosen here. [onStarted]: sent, the remote opens.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaCastSheet(
    title: MediaTitle,
    positionMs: Long,
    audio: Int?,
    subtitle: Int?,
    onDismiss: () -> Unit,
    onStarted: () -> Unit,
    viewModel: MediaCastViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val running by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(Unit) { viewModel.load() }
    val x = GateControlTheme.extraColors
    val from = if (positionMs > 0) stringResource(R.string.media_cast_from, MediaPhoneLogic.clock(positionMs)) else stringResource(R.string.media_cast_from_start)
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp).testTag(CAST_SHEET_TAG)) {
            Text(
                stringResource(R.string.media_cast),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            val active = running?.takeIf { !it.phase.final }
            CastRow(GcIcons.Smartphone, stringResource(R.string.media_cast_this_phone), from, checked = active == null, onClick = onDismiss)
            active?.let { s ->
                CastRow(GcIcons.Cast, stringResource(R.string.media_cast_running, s.deviceName), stringResource(R.string.media_cast_open_remote), checked = true) {
                    MediaCastActivity.open(context)
                    onDismiss()
                }
            }
            ui.targets.forEach { t ->
                val app = stringResource(if (t.kind == CastTarget.Kind.TV) R.string.media_cast_tv else R.string.media_cast_pc)
                val note = when {
                    !t.online -> stringResource(R.string.media_cast_offline, app)
                    t.kind == CastTarget.Kind.TV -> stringResource(R.string.media_cast_starts_there, app, from)
                    else -> stringResource(R.string.media_cast_online, app)
                }
                CastRow(
                    if (t.kind == CastTarget.Kind.TV) GcIcons.Tv else GcIcons.Monitor,
                    t.name,
                    note,
                    checked = false,
                    enabled = t.online && ui.sending == null,
                    busy = ui.sending == t.id,
                    accent = true,
                ) {
                    viewModel.start(t, title, positionMs, audio, subtitle) {
                        MediaCastActivity.open(context)
                        onStarted()
                    }
                }
            }
            when {
                ui.loading -> Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) }
                ui.problem == CastListProblem.NOT_ALLOWED -> Note(stringResource(R.string.media_cast_not_allowed))
                ui.problem == CastListProblem.OFFLINE -> Note(stringResource(R.string.media_cast_offline_list))
                ui.targets.isEmpty() -> Note(stringResource(R.string.media_cast_none))
            }
            ui.failed?.let { Note(stringResource(R.string.media_cast_send_failed, it), error = true) }
            if (ui.rateLimited) Note(stringResource(R.string.media_cast_rate_limited), error = true)
            Text(stringResource(R.string.media_cast_hint), style = MaterialTheme.typography.bodySmall, color = x.muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        }
    }
}

@Composable
private fun Note(text: String, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (error) MaterialTheme.colorScheme.error else GateControlTheme.extraColors.muted,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
    )
}

@Composable
private fun CastRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    note: String?,
    checked: Boolean,
    enabled: Boolean = true,
    busy: Boolean = false,
    accent: Boolean = false,
    onClick: () -> Unit,
) {
    val x = GateControlTheme.extraColors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).alpha(if (enabled || busy) 1f else 0.55f)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, contentDescription = null, tint = if (checked) MaterialTheme.colorScheme.primary else if (accent) x.pro else x.muted, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = x.muted) }
        }
        when {
            busy -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            checked -> Icon(GcIcons.Check, contentDescription = stringResource(R.string.media_chosen), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        }
    }
}

/** The remote: its own window, so it stays when the player has gone; Zurück closes it, the playback goes on. */
@AndroidEntryPoint
class MediaCastActivity : ComponentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository

    @Inject lateinit var phoneCast: PhoneCast

    /** The volume keys change the volume on the other device while it tells it. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = CastVolumeKeys.handle(event, phoneCast) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = CastVolumeKeys.handle(event, phoneCast) || super.onKeyUp(keyCode, event)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val theme by settingsRepository.getTheme().collectAsStateWithLifecycle(initialValue = "system")
            val sysLang = rememberSaveable { Locale.getDefault().language }
            val locale by settingsRepository.getLocale().collectAsStateWithLifecycle(initialValue = if (sysLang == "de") "de" else "en")
            LaunchedEffect(locale) {
                val config = Configuration(resources.configuration).apply { setLocale(Locale.forLanguageTag(locale)) }
                @Suppress("DEPRECATION")
                resources.updateConfiguration(config, resources.displayMetrics)
            }
            val dark = when (theme) {
                "dark" -> true
                "light" -> false
                else -> isSystemInDarkTheme()
            }
            GcSystemBars(dark)
            GateControlTheme(darkTheme = dark) {
                MediaRemoteScreen(onClose = ::finish)
            }
        }
    }

    companion object {
        fun open(context: Context) {
            // into the app's own task, also from the player (its own task, which closes right after)
            context.startActivity(Intent(context, MediaCastActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
    }
}

/** What the remote shows: the device, the title, how it plays, the place, the keys, sound and subtitles, stop. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MediaRemoteScreen(onClose: () -> Unit, viewModel: MediaCastViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val s = state
    LaunchedEffect(s == null) { if (s == null) onClose() }
    if (s == null) return
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(s.phase) {
        while (true) {
            now = System.currentTimeMillis()
            delay(500)
        }
    }
    var dragging by remember { mutableStateOf<Float?>(null) }
    val position = CastRemote.position(s, now)
    val x = GateControlTheme.extraColors
    val live = !s.phase.final && s.phase != CastRemotePhase.CONNECTING
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).testTag(REMOTE_TAG),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GcIconButton(GcIcons.Back, contentDescription = stringResource(R.string.media_cast_close_remote), onClick = {
                viewModel.dismiss()
                onClose()
            })
            Text(stringResource(R.string.media_cast_remote), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(GcIcons.Cast, contentDescription = null, tint = x.pro, modifier = Modifier.size(22.dp))
            Text(stringResource(R.string.media_cast_running, s.deviceName), style = MaterialTheme.typography.titleSmall, color = x.muted)
        }
        Text(s.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Text(
            phaseText(s),
            style = MaterialTheme.typography.bodyMedium,
            color = if (s.phase == CastRemotePhase.FAILED || s.phase == CastRemotePhase.NO_ANSWER) MaterialTheme.colorScheme.error else x.muted,
            modifier = Modifier.testTag(REMOTE_STATE_TAG),
        )
        val limited by viewModel.limited.collectAsStateWithLifecycle()
        if (limited && !s.phase.final) Text(stringResource(R.string.media_cast_rate_limited), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        if (s.phase == CastRemotePhase.CONNECTING || s.phase == CastRemotePhase.LOADING) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        val duration = s.durationMs
        if (duration > 0) {
            Column {
                Slider(
                    value = dragging ?: (position.toFloat() / duration).coerceIn(0f, 1f),
                    onValueChange = { dragging = it },
                    onValueChangeFinished = {
                        dragging?.let { viewModel.command(CastCmd.SEEK, positionMs = (it * duration).toLong()) }
                        dragging = null
                    },
                    enabled = live,
                    modifier = Modifier.fillMaxWidth().testTag(REMOTE_SEEK_TAG),
                )
                Row(Modifier.fillMaxWidth()) {
                    Text(MediaPhoneLogic.clock(dragging?.let { (it * duration).toLong() } ?: position), style = MaterialTheme.typography.labelMedium, color = x.muted, modifier = Modifier.weight(1f))
                    Text(MediaPhoneLogic.clock(duration), style = MaterialTheme.typography.labelMedium, color = x.muted)
                }
            }
        } else if (position > 0) {
            Text(MediaPhoneLogic.clock(position), style = MaterialTheme.typography.labelMedium, color = x.muted)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
            GcIconButton(GcIcons.Back10, contentDescription = stringResource(R.string.media_player_back10), onClick = { viewModel.command(CastCmd.SEEK_BY, deltaMs = -SEEK_MS) }, enabled = live)
            val playing = s.phase == CastRemotePhase.PLAYING || s.phase == CastRemotePhase.BUFFERING
            GcIconButton(
                if (playing) GcIcons.Pause else GcIcons.Play,
                contentDescription = stringResource(if (playing) R.string.media_player_pause else R.string.media_player_play),
                onClick = { viewModel.command(if (playing) CastCmd.PAUSE else CastCmd.PLAY) },
                enabled = live,
                size = 64.dp,
                iconSize = 34.dp,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag(REMOTE_TOGGLE_TAG),
            )
            GcIconButton(GcIcons.Ahead10, contentDescription = stringResource(R.string.media_player_ahead10), onClick = { viewModel.command(CastCmd.SEEK_BY, deltaMs = SEEK_MS) }, enabled = live)
        }
        TrackChips(stringResource(R.string.media_cast_audio), s.audio.takeIf { it.size > 1 }.orEmpty(), live) { viewModel.command(CastCmd.AUDIO, track = it.id) }
        TrackChips(stringResource(R.string.media_cast_subtitles), s.subtitles, live) { viewModel.command(CastCmd.SUBTITLE, track = it.id) }
        s.volume?.takeIf { !s.phase.final }?.let { volume ->
            VolumeRow(volume, s.muted, live, onVolume = viewModel::setVolume, onMute = viewModel::toggleMute)
        }
        if (!s.phase.final) {
            GcOutlineButton(stringResource(R.string.media_cast_stop), onClick = { viewModel.command(CastCmd.STOP) }, icon = GcIcons.Stop, modifier = Modifier.testTag(REMOTE_STOP_TAG))
        } else {
            val at = s.positionMs
            if ((s.phase == CastRemotePhase.STOPPED || s.phase == CastRemotePhase.NO_ANSWER) && at > 0) {
                GcPrimaryButton(stringResource(R.string.media_cast_go_on_here, MediaPhoneLogic.clock(at)), onClick = {
                    viewModel.goOnHere(at)
                    onClose()
                })
            }
            GcOutlineButton(stringResource(R.string.media_cast_done), onClick = {
                viewModel.dismiss()
                onClose()
            })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TrackChips(label: String, tracks: List<CastTrack>, enabled: Boolean, onPick: (CastTrack) -> Unit) {
    if (tracks.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = GateControlTheme.extraColors.muted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            tracks.forEach { t -> GcFilterChip(text = t.label, selected = t.selected, onClick = { onPick(t) }, enabled = enabled) }
        }
    }
}

/** The volume on the other device (only when it tells it): mute, the slider, the value; the volume keys do the same. */
@Composable
private fun VolumeRow(volume: Int, muted: Boolean, enabled: Boolean, onVolume: (Int) -> Unit, onMute: () -> Unit) {
    val x = GateControlTheme.extraColors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag(REMOTE_VOLUME_TAG)) {
        Text(stringResource(R.string.media_cast_volume), style = MaterialTheme.typography.labelLarge, color = x.muted)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GcIconButton(
                if (muted || volume == 0) GcIcons.VolumeOff else GcIcons.Volume,
                contentDescription = stringResource(if (muted) R.string.media_cast_unmute else R.string.media_cast_mute),
                onClick = onMute,
                enabled = enabled,
                tint = if (muted) MaterialTheme.colorScheme.primary else x.muted,
                modifier = Modifier.testTag(REMOTE_MUTE_TAG),
            )
            Slider(
                value = volume / 100f,
                onValueChange = { onVolume((it * 100).roundToInt()) },
                enabled = enabled,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (muted) stringResource(R.string.media_cast_muted) else stringResource(R.string.media_cast_volume_value, volume),
                style = MaterialTheme.typography.labelMedium,
                color = x.muted,
                modifier = Modifier.widthIn(min = 44.dp),
            )
        }
    }
}

@Composable
private fun phaseText(s: CastRemoteState): String {
    val device = s.deviceName
    return when (s.phase) {
        CastRemotePhase.CONNECTING -> stringResource(R.string.media_cast_connecting, device)
        CastRemotePhase.LOADING -> stringResource(R.string.media_cast_loading)
        CastRemotePhase.PLAYING -> stringResource(R.string.media_cast_playing)
        CastRemotePhase.PAUSED -> stringResource(R.string.media_cast_paused)
        CastRemotePhase.BUFFERING -> stringResource(R.string.media_cast_buffering)
        CastRemotePhase.ENDED -> stringResource(R.string.media_cast_ended)
        CastRemotePhase.NO_ANSWER -> stringResource(R.string.media_cast_no_answer, device)
        CastRemotePhase.STOPPED, CastRemotePhase.FAILED -> when (s.error) {
            null -> stringResource(if (s.phase == CastRemotePhase.STOPPED) R.string.media_cast_stopped else R.string.media_cast_error_failed, device)
            CastProtocol.ERROR_SOURCE_MISSING -> stringResource(R.string.media_cast_error_source_missing, device)
            CastProtocol.ERROR_SIGNED_OUT -> stringResource(R.string.media_cast_error_signed_out, device)
            CastProtocol.ERROR_NOT_FOUND -> stringResource(R.string.media_cast_error_not_found, device)
            CastProtocol.ERROR_OFFLINE -> stringResource(R.string.media_cast_error_offline, device)
            CastProtocol.ERROR_UNSUPPORTED -> stringResource(R.string.media_cast_error_unsupported, device)
            CastProtocol.ERROR_BLOCKED -> stringResource(R.string.media_cast_error_blocked, device)
            CastProtocol.ERROR_TAKEN_OVER -> stringResource(R.string.media_cast_error_taken_over)
            CastProtocol.ERROR_CLOSED -> stringResource(R.string.media_cast_error_closed, device)
            else -> stringResource(R.string.media_cast_error_failed, device)
        }
    }
}

private const val SEEK_MS = 10_000L
internal const val CAST_SHEET_TAG = "media-cast-sheet"
internal const val REMOTE_TAG = "media-remote"
internal const val REMOTE_STATE_TAG = "media-remote-state"
internal const val REMOTE_SEEK_TAG = "media-remote-seek"
internal const val REMOTE_TOGGLE_TAG = "media-remote-toggle"
internal const val REMOTE_STOP_TAG = "media-remote-stop"
internal const val REMOTE_VOLUME_TAG = "media-remote-volume"
internal const val REMOTE_MUTE_TAG = "media-remote-mute"
