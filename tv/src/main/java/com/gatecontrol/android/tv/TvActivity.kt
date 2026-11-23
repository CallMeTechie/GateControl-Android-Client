package com.gatecontrol.android.tv

import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.quickactions.QuickActionsLoad
import com.gatecontrol.android.tunnel.TunnelState
import com.gatecontrol.android.tv.doorbell.TvForeground
import com.gatecontrol.android.tv.drive.TvDriveOpen
import com.gatecontrol.android.tv.drive.TvDriveViewModel
import com.gatecontrol.android.tv.ui.drive.TvDrivePage
import com.gatecontrol.android.tv.ui.drive.TvMediaActivity
import android.content.Intent
import com.gatecontrol.android.tv.drive.TvMediaSelection
import com.gatecontrol.android.tv.gatedrop.TvDropEntry
import com.gatecontrol.android.tv.gatedrop.TvDropLogic
import com.gatecontrol.android.tv.gatedrop.TvGateDrop
import com.gatecontrol.android.tv.ui.drive.TvLocalFile
import com.gatecontrol.android.tv.ui.gatedrop.TvDropEntryDialog
import com.gatecontrol.android.tv.ui.gatedrop.TvDropFileDialog
import com.gatecontrol.android.tv.ui.gatedrop.TvDropProgressDialog
import com.gatecontrol.android.tv.ui.gatedrop.TvSendDialog
import com.gatecontrol.android.tv.ui.drive.TvLocalPhotos
import android.content.ActivityNotFoundException
import android.graphics.Bitmap
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.gatecontrol.android.tv.ui.gatedrop.TvDropOfferDialog
import com.gatecontrol.android.tv.ui.gatedrop.TvGateDropPage
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import android.os.SystemClock
import com.gatecontrol.android.tv.frame.TvFrameActivity
import com.gatecontrol.android.tv.frame.TvFrameSchedule
import com.gatecontrol.android.tv.frame.TvFrameSource
import com.gatecontrol.android.tv.frame.TvFrameStatus
import com.gatecontrol.android.tv.ui.settings.TvFrameOptions
import com.gatecontrol.android.tv.home.TvHomeViewModel
import com.gatecontrol.android.media.MediaTitle
import com.gatecontrol.android.tv.media.TvMedia
import com.gatecontrol.android.tv.media.TvMediaLogic
import com.gatecontrol.android.tv.media.TvMediaViewModel
import com.gatecontrol.android.tv.ui.media.TvMediaActions
import com.gatecontrol.android.tv.ui.media.TvMediaPage
import com.gatecontrol.android.tv.ui.media.TvMediaScreen
import com.gatecontrol.android.tv.security.TvPinFunction
import com.gatecontrol.android.tv.ui.cameras.TvCamerasPage
import com.gatecontrol.android.tv.ui.components.TvConfirmDialog
import com.gatecontrol.android.tv.ui.components.TvPinDialog
import com.gatecontrol.android.tv.ui.home.TvDestination
import com.gatecontrol.android.tv.ui.home.TvStartRows
import com.gatecontrol.android.tv.ui.home.TvStartUi
import com.gatecontrol.android.tv.ui.settings.TvNoticeOptions
import com.gatecontrol.android.tv.ui.settings.TvSecurityOptions
import com.gatecontrol.android.tv.ui.settings.TvSplitTunnelOptions
import com.gatecontrol.android.tv.pair.PairState
import com.gatecontrol.android.tv.pair.PairingViewModel
import com.gatecontrol.android.tv.pair.TvPairingApi
import com.gatecontrol.android.tv.tunnel.TvSupervisor
import com.gatecontrol.android.tv.ui.components.TvPillTone
import com.gatecontrol.android.tv.ui.home.TvHome
import com.gatecontrol.android.tv.ui.home.TvTopStatus
import com.gatecontrol.android.tv.ui.pair.TvPairScreen
import com.gatecontrol.android.tv.ui.settings.TvConnectionUi
import com.gatecontrol.android.tv.ui.settings.TvSettingsPage
import com.gatecontrol.android.tv.ui.settings.TvUpdateUi
import com.gatecontrol.android.tv.ui.theme.GateControlTvTheme
import com.gatecontrol.android.tv.update.TvUpdateViewModel
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber
import javax.inject.Inject

/**
 * The only activity of GateControl TV (Fire TV / Android TV launcher): the pairing until the TV is set up, then the
 * start page with the rail. Asks for the VPN consent (`VpnService.prepare`, once per install) when the VPN is
 * switched on or right after the pairing.
 */
@AndroidEntryPoint
class TvActivity : ComponentActivity() {

    private val pairing: PairingViewModel by viewModels()
    private val main: TvMainViewModel by viewModels()
    private val updates: TvUpdateViewModel by viewModels()
    private val home: TvHomeViewModel by viewModels()
    private val prefs: TvPrefsViewModel by viewModels()
    private val drive: TvDriveViewModel by viewModels()
    private val mediaVm: TvMediaViewModel by viewModels()

    @Inject lateinit var pairingApi: TvPairingApi
    @Inject lateinit var supervisor: TvSupervisor
    @Inject lateinit var gatedrop: TvGateDrop
    @Inject lateinit var selection: TvMediaSelection
    @Inject lateinit var frameSource: TvFrameSource
    @Inject lateinit var media: TvMedia

    private val vpnConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        main.onConsent(result.resultCode == RESULT_OK)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GateControlTvTheme {
                val state by pairing.state.collectAsStateWithLifecycle()
                if (state is PairState.Paired) {
                    val pairedNow by pairing.pairedNow.collectAsStateWithLifecycle()
                    // Just paired: the tunnel is on by default — the consent once, then connect.
                    LaunchedEffect(pairedNow) {
                        if (pairedNow) {
                            pairing.consumePairedNow()
                            setVpn(true)
                        }
                    }
                    Paired()
                } else {
                    TvPairScreen(state, onEvent = pairing::dispatch)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        TvForeground.onStart()
        pairing.setVisible(true)
    }

    override fun onStop() {
        pairing.setVisible(false)
        TvForeground.onStop()
        super.onStop()
    }

    /** The pictures and videos of a send kept on the TV in the viewer, from its file [from] (or the first). */
    private fun showDrop(e: TvDropEntry, from: Int? = null) {
        gatedrop.seen(e.sendId)
        val files = e.files.mapIndexedNotNull { i, f ->
            if (!TvDropLogic.media(f.mime)) return@mapIndexedNotNull null
            gatedrop.file(e, i)?.let { i to TvLocalFile(it, f.name, f.mime) }
        }
        if (files.isEmpty()) return
        selection.setLocal(e.sendId, files, files.indexOfFirst { it.first == from }.coerceAtLeast(0))
        startActivity(Intent(this, TvMediaActivity::class.java))
    }

    /** A received file: pictures and videos in the viewer, anything else in another app on the TV (if one takes it). */
    private fun openDropFile(e: TvDropEntry, i: Int) {
        val f = e.files.getOrNull(i) ?: return
        if (TvDropLogic.media(f.mime)) {
            showDrop(e, i)
            return
        }
        val file = gatedrop.file(e, i) ?: return
        gatedrop.seen(e.sendId)
        val opened = runCatching {
            val uri = FileProvider.getUriForFile(this, "$packageName.provider", file)
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, f.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }
        if (opened.isFailure) {
            if (opened.exceptionOrNull() !is ActivityNotFoundException) Timber.d("GateDrop TV: not opened (%s)", opened.exceptionOrNull()?.javaClass?.simpleName)
            Toast.makeText(this, R.string.tv_drop_no_app, Toast.LENGTH_LONG).show()
        }
    }

    /** A link of a send in the TV's browser (if there is one). */
    private fun openDropLink(e: TvDropEntry) {
        val link = e.link?.trim()?.takeIf(TvDropLogic::openableLink) ?: return
        gatedrop.seen(e.sendId)
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, link.toUri())) }
            .onFailure { Toast.makeText(this, R.string.tv_drop_no_app, Toast.LENGTH_LONG).show() }
    }

    /** The small picture of a send's card on the start page. */
    private suspend fun dropCover(e: TvDropEntry): Bitmap? {
        val i = TvDropLogic.cover(e) ?: return null
        val file = gatedrop.file(e, i) ?: return null
        return TvLocalPhotos(listOf(TvLocalFile(file, e.files[i].name, e.files[i].mime)), COVER_PX).load(0)
    }

    /** When the remote last pressed a key here (the photo frame's idle start). */
    @Volatile
    private var lastKeyAt = SystemClock.elapsedRealtime()

    @Volatile
    private var resumed = false

    /** Every key of the remote (and any other input) in GateControl. */
    override fun onUserInteraction() {
        super.onUserInteraction()
        lastKeyAt = SystemClock.elapsedRealtime()
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }

    /** By hand: always (the frame itself says when nothing is shared). */
    private fun startFrame() {
        startActivity(Intent(this, TvFrameActivity::class.java))
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        lastKeyAt = SystemClock.elapsedRealtime()
        // back from the system page "Unbekannte Apps installieren": the installer follows if it is allowed now
        updates.onResume(this)
        // the push service, if Fire OS refused to start it from the background
        supervisor.onForeground()
        // GateDrop: what was sent to the TV while it was away
        if (pairing.state.value is PairState.Paired) gatedrop.poll()
        // Medien: back from the player, "Weiterschauen" and the detail page show where it is now
        if (pairing.state.value is PairState.Paired) mediaVm.onResumed()
    }

    /** Plays a film or episode of the media source in the player (Medien). */
    private fun playMedia(t: MediaTitle, fromStart: Boolean) {
        mediaVm.play(t, fromStart) { startActivity(Intent(this, TvMediaActivity::class.java)) }
    }

    private fun mediaActions(showMedia: () -> Unit) = TvMediaActions(
        onStart = mediaVm::refresh,
        onRetry = mediaVm::refresh,
        onOpen = { t ->
            mediaVm.open(t)
            showMedia()
        },
        onPlay = ::playMedia,
        onPicture = mediaVm::wantPicture,
        pictureKey = mediaVm::pictureKey,
        onMore = mediaVm::more,
        onBack = mediaVm::back,
        onSeason = mediaVm::season,
        onAudio = mediaVm::chooseAudio,
        onSubtitle = mediaVm::chooseSubtitle,
        onOriginal = mediaVm::chooseOriginal,
        onPlayed = mediaVm::togglePlayed,
        onSelect = mediaVm::select,
        onRemove = mediaVm::remove,
        onAlwaysOriginal = mediaVm::setAlwaysOriginal,
        onAdd = mediaVm::startAdd,
        onAddCancel = mediaVm::cancelAdd,
        onJellyfin = mediaVm::chooseJellyfin,
        onSearch = mediaVm::search,
        onAddress = mediaVm::setAddress,
        onPick = mediaVm::pick,
        onCheck = mediaVm::check,
        onLogin = mediaVm::toLogin,
        onQuickConnect = mediaVm::chooseQuickConnect,
        onPassword = mediaVm::signIn,
    )

    @Composable
    private fun Paired() {
        val tunnel by main.tunnelState.collectAsStateWithLifecycle()
        val autostart by main.autostartTunnel.collectAsStateWithLifecycle()
        val openOnBoot by main.openOnBoot.collectAsStateWithLifecycle()
        val consentDenied by main.consentDenied.collectAsStateWithLifecycle()
        val check by updates.check.collectAsStateWithLifecycle()
        val download by updates.download.collectAsStateWithLifecycle()
        val host = main.serverHost()
        val status = when (tunnel) {
            is TunnelState.Connected -> TvTopStatus(stringResource(R.string.tv_status_vpn_on, host), TvPillTone.Accent)
            is TunnelState.Connecting, is TunnelState.Reconnecting -> TvTopStatus(stringResource(R.string.tv_vpn_connecting), TvPillTone.Info)
            else -> TvTopStatus(stringResource(R.string.tv_status_vpn_off, host), TvPillTone.Neutral)
        }
        val tiles by home.tiles.collectAsStateWithLifecycle()
        val busy by home.busy.collectAsStateWithLifecycle()
        val cameras by home.cameras.collectAsStateWithLifecycle()
        val previews by home.previews.collectAsStateWithLifecycle()
        val notice by home.notice.collectAsStateWithLifecycle()
        val load by home.load.collectAsStateWithLifecycle()
        val pending by home.pending.collectAsStateWithLifecycle()
        val live by home.liveUi.collectAsStateWithLifecycle()
        val pinState by prefs.pin.collectAsStateWithLifecycle()
        val bell by prefs.doorbellSettings.collectAsStateWithLifecycle()
        val split by prefs.splitTunnel.collectAsStateWithLifecycle()
        val apps by prefs.apps.collectAsStateWithLifecycle()
        val bellCameras by prefs.cameras.collectAsStateWithLifecycle()
        val push by prefs.pushEnabled.collectAsStateWithLifecycle()
        var requested by remember { mutableStateOf<TvDestination?>(null) }
        var wanted by remember { mutableStateOf<String?>(null) }
        // A page waiting for the TV PIN (Einstellungen).
        var pinFor by remember { mutableStateOf<Pair<TvPinFunction, () -> Unit>?>(null) }
        val driveUi by drive.ui.collectAsStateWithLifecycle()
        val drivePreviews by drive.previews.collectAsStateWithLifecycle()
        val recent by drive.recent.collectAsStateWithLifecycle()
        val dropEntries by gatedrop.entries.collectAsStateWithLifecycle()
        val dropOffer by gatedrop.offer.collectAsStateWithLifecycle()
        val dropWaiting by gatedrop.waiting.collectAsStateWithLifecycle()
        val dropReceiving by gatedrop.receiving.collectAsStateWithLifecycle()
        val dropAvailable by gatedrop.available.collectAsStateWithLifecycle()
        val dropProgress by gatedrop.progress.collectAsStateWithLifecycle()
        var dropOpen by remember { mutableStateOf<TvDropEntry?>(null) }
        // a file of a send: its actions, then "Senden an …"
        var dropFile by remember { mutableStateOf<Pair<TvDropEntry, Int>?>(null) }
        var dropSend by remember { mutableStateOf<Pair<TvDropEntry, Int>?>(null) }
        // "Annehmen und zeigen": the send whose files are awaited to be shown
        var dropShowing by remember { mutableStateOf<String?>(null) }
        val frame by prefs.frame.collectAsStateWithLifecycle()
        val mediaUi by mediaVm.ui.collectAsStateWithLifecycle()
        val mediaDetails by mediaVm.details.collectAsStateWithLifecycle()
        val mediaAdd by mediaVm.add.collectAsStateWithLifecycle()
        val mediaLink by mediaVm.link.collectAsStateWithLifecycle()
        val mediaPictures by mediaVm.pictures.collectAsStateWithLifecycle()
        val mediaActions = remember { mediaActions(showMedia = { requested = TvDestination.Media }) }
        // the photo frame when nothing happened for a while (only while GateControl is in front)
        // (only with photos shared: GET frame is asked first — a screen saver never shows just a hint)
        LaunchedEffect(frame) {
            while (frame.idleMinutes > 0) {
                kotlinx.coroutines.delay(IDLE_CHECK_MS)
                if (!resumed || !TvFrameSchedule.idleDue(frame, frameSource.status.value, lastKeyAt, SystemClock.elapsedRealtime())) continue
                frameSource.load()
                if (frameSource.status.value !is TvFrameStatus.Photos) {
                    lastKeyAt = SystemClock.elapsedRealtime()
                    continue
                }
                lastKeyAt = SystemClock.elapsedRealtime()
                if (resumed) startFrame()
            }
        }
        LaunchedEffect(Unit) { home.refresh() }
        TvHome(
            status = status,
            startPage = {
                DisposableEffect(Unit) {
                    home.setVisible(true)
                    drive.loadRecent()
                    mediaVm.refreshResume()
                    onDispose { home.setVisible(false) }
                }
                // with a TV PIN for Drive its files do not show on the start page either
                val driveOpen = !pinState.needsPin(TvPinFunction.DRIVE)
                TvStartRows(
                    ui = TvStartUi(
                        tiles, busy, cameras, previews, notice,
                        loading = load is QuickActionsLoad.Loading || load is QuickActionsLoad.Idle,
                        drive = if (driveOpen) recent else emptyList(),
                        drivePreviews = drivePreviews,
                        drops = dropEntries,
                        media = mediaUi.resume,
                        mediaSource = mediaUi.source?.let(TvMediaLogic::sourceLabel),
                        mediaPictures = mediaPictures,
                    ),
                    media = mediaActions,
                    onDrop = { dropOpen = it },
                    dropCover = ::dropCover,
                    onDrive = { node ->
                        when (drive.pressRecent(node)) {
                            TvDriveOpen.PHOTO, TvDriveOpen.VIDEO, TvDriveOpen.PREVIEW -> startActivity(Intent(this, TvMediaActivity::class.java))
                            TvDriveOpen.FOLDER -> requested = TvDestination.Drive
                            else -> Unit
                        }
                    },
                    onDrivePreview = drive::wantPreview,
                    onTile = home::press,
                    onSetpoint = { tile, control, value -> home.setControl(tile, control.id, value) },
                    onCamera = {
                        wanted = it.id
                        requested = TvDestination.Cameras
                    },
                )
            },
            camerasPage = {
                LaunchedEffect(wanted) { home.showLive(wanted) }
                TvCamerasPage(
                    cameras = cameras,
                    live = live,
                    sourceFactory = home.live::sourceFactory,
                    onShow = { wanted = it },
                    onLeave = {
                        wanted = null
                        home.stopLive()
                    },
                )
            },
            drivePage = {
                TvDrivePage(
                    ui = driveUi,
                    previews = drivePreviews,
                    onStart = drive::start,
                    onArea = drive::openArea,
                    onItem = { node ->
                        drive.press(node).also { open ->
                            if (open == TvDriveOpen.PHOTO || open == TvDriveOpen.VIDEO || open == TvDriveOpen.PREVIEW) {
                                startActivity(Intent(this, TvMediaActivity::class.java))
                            }
                        }
                    },
                    onBack = drive::back,
                    onMore = drive::more,
                    onPreview = drive::wantPreview,
                    onRetry = drive::reload,
                )
            },
            mediaPage = {
                TvMediaPage(TvMediaScreen(mediaUi, mediaDetails.lastOrNull(), mediaAdd, mediaLink, mediaPictures), mediaActions)
            },
            gatedropPage = {
                TvGateDropPage(
                    entries = dropEntries,
                    waiting = dropWaiting,
                    available = dropAvailable,
                    receiving = dropReceiving,
                    onOpen = { dropOpen = it },
                    onTake = { send -> lifecycleScope.launch { gatedrop.accept(send) } },
                    onStart = gatedrop::poll,
                )
            },
            onOpen = { destination, open ->
                val function = when (destination) {
                    TvDestination.Settings -> TvPinFunction.SETTINGS
                    TvDestination.Drive -> TvPinFunction.DRIVE
                    else -> null
                }
                if (function != null && pinState.needsPin(function)) pinFor = function to open else open()
            },
            requested = requested,
            onRequestedShown = { requested = null },
            settingsPage = {
                TvSettingsPage(
                    connection = TvConnectionUi(tunnel, host, main.tunnelAddress(), autostart, openOnBoot, consentDenied),
                    update = TvUpdateUi(updates.version, check, download),
                    deviceName = pairingApi.deviceName(),
                    onVpn = ::setVpn,
                    onAutostartTunnel = main::setAutostartTunnel,
                    onOpenOnBoot = main::setOpenOnBoot,
                    onCheckUpdate = updates::checkNow,
                    onInstallUpdate = { updates.install(this) },
                    onUnpair = {
                        main.unpair {
                            gatedrop.clear()
                            media.clear()
                            pairing.reset()
                        }
                    },
                    splitTunnel = { TvSplitTunnelOptions(split, apps, prefs::loadApps, prefs::setSplitTunnel) },
                    notices = {
                        TvNoticeOptions(push, prefs::setPush, bell, prefs::setDoorbell, prefs.canDrawOverlays(), bellCameras, prefs::loadCameras)
                    },
                    security = { TvSecurityOptions(pinState, prefs::checkPin, prefs::setPin, prefs::removePin, prefs::setRequired) },
                    frame = {
                        val frameStatus by prefs.frameStatus.collectAsStateWithLifecycle()
                        TvFrameOptions(frame, frameStatus, prefs.frameServer(), prefs::setFrame, ::startFrame, prefs::refreshFrame)
                    },
                )
            },
        )
        pinFor?.let { (function, open) ->
            TvPinDialog(
                title = stringResource(if (function == TvPinFunction.DRIVE) R.string.tv_pin_for_drive else R.string.tv_pin_for_settings),
                check = prefs::checkPin,
                onPassed = {
                    pinFor = null
                    open()
                },
                onDismiss = { pinFor = null },
            )
        }
        dropOffer?.let { offer ->
            TvDropOfferDialog(
                offer,
                onShow = {
                    dropShowing = offer.entry.id
                    lifecycleScope.launch {
                        val kept = gatedrop.accept(offer.entry)
                        val wanted = dropShowing == offer.entry.id
                        dropShowing = null
                        if (wanted) kept?.let { showDrop(it) }
                    }
                },
                onTake = { lifecycleScope.launch { gatedrop.accept(offer.entry) } },
                onLater = gatedrop::later,
            )
        }
        dropOpen?.let { e ->
            TvDropEntryDialog(
                entry = e,
                onShow = {
                    dropOpen = null
                    showDrop(e)
                },
                onDelete = {
                    dropOpen = null
                    gatedrop.delete(e.sendId)
                },
                onDismiss = {
                    dropOpen = null
                    gatedrop.seen(e.sendId)
                },
                onFile = { i ->
                    dropOpen = null
                    dropFile = e to i
                },
                onLink = if (TvDropLogic.openableLink(e.link)) ({ openDropLink(e) }) else null,
            )
        }
        dropFile?.let { (e, i) ->
            val f = e.files.getOrNull(i)
            if (f == null) {
                dropFile = null
            } else {
                TvDropFileDialog(
                    file = f,
                    onShow = {
                        dropFile = null
                        openDropFile(e, i)
                    },
                    onSend = {
                        dropFile = null
                        dropSend = e to i
                    },
                    onDismiss = { dropFile = null },
                )
            }
        }
        dropSend?.let { (e, i) ->
            TvSendDialog(
                name = e.files.getOrNull(i)?.name.orEmpty(),
                targets = gatedrop::targets,
                send = { to -> gatedrop.sendReceived(e.sendId, i, to) },
                onDismiss = { dropSend = null },
            )
        }
        dropShowing?.let { id ->
            TvDropProgressDialog(progress = dropProgress?.takeIf { it.sendId == id }, onDismiss = { dropShowing = null })
        }
        pending?.let { p ->
            if (p.needsPin) {
                TvPinDialog(stringResource(R.string.tv_pin_for_door), prefs::checkPin, home::pinPassed, home::cancelPending)
            } else if (p.needsConfirm) {
                TvConfirmDialog(
                    title = stringResource(R.string.tv_confirm_title, p.item.title),
                    text = p.item.subtitle,
                    confirm = stringResource(R.string.tv_confirm_run),
                    onConfirm = home::confirmPending,
                    onDismiss = home::cancelPending,
                )
            }
        }
    }

    /** VPN on (consent first, if not given yet) or off. */
    private fun setVpn(on: Boolean) {
        if (!on) {
            main.disconnect()
            return
        }
        val consent = runCatching { VpnService.prepare(this) }.getOrNull()
        if (consent == null) {
            main.connect()
            return
        }
        runCatching { vpnConsent.launch(consent) }.onFailure {
            // no dialog for it on this system
            Timber.w("VPN consent could not be asked (%s)", it.javaClass.simpleName)
            main.onConsent(false)
        }
    }

    private companion object {
        /** The side of a send's picture on its card (px). */
        const val COVER_PX = 160
        const val IDLE_CHECK_MS = 30_000L
    }
}
