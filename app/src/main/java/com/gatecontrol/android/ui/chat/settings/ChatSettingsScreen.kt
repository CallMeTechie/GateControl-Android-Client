package com.gatecontrol.android.ui.chat.settings

import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.BuildConfig
import com.gatecontrol.android.R
import com.gatecontrol.android.chat.ChatAutoDownload
import com.gatecontrol.android.chat.ChatConversation
import com.gatecontrol.android.chat.ChatFeatures14
import com.gatecontrol.android.chat.ChatList
import com.gatecontrol.android.chat.ChatLook
import com.gatecontrol.android.chat.ChatNotifier
import com.gatecontrol.android.chat.ChatNotifyPrefs
import com.gatecontrol.android.chat.ChatPrivacy
import com.gatecontrol.android.chat.ChatQuality
import com.gatecontrol.android.chat.ChatStatus
import com.gatecontrol.android.chat.ChatSystemBubbles
import com.gatecontrol.android.chat.actions
import com.gatecontrol.android.chat.events
import com.gatecontrol.android.chat.msgTools
import com.gatecontrol.android.chat.privacy2
import com.gatecontrol.android.chat.status
import com.gatecontrol.android.quickactions.QuickActionIcons
import com.gatecontrol.android.ui.chat.ChatPersonAvatar
import com.gatecontrol.android.ui.chat.ChatText
import com.gatecontrol.android.ui.chat.OptionDialog
import com.gatecontrol.android.ui.chat.ConfirmDialog
import com.gatecontrol.android.ui.chat.rememberLocalBitmap
import com.gatecontrol.android.ui.chat.reminderLabel
import com.gatecontrol.android.ui.chat.status.StatusText
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcChevron
import com.gatecontrol.android.ui.components.GcListRow
import com.gatecontrol.android.ui.components.GcRowDivider
import com.gatecontrol.android.ui.components.GcSectionLabel
import com.gatecontrol.android.ui.components.GcSubpageBar
import com.gatecontrol.android.ui.components.GcSwitchRow
import com.gatecontrol.android.ui.components.rememberGcDeviceAuth
import com.gatecontrol.android.ui.components.DeviceAuthResult
import com.gatecontrol.android.ui.theme.GateControlTheme

/** Pages of the chat settings (route argument `page`). */
object ChatSettingsPage {
    const val MAIN = ""
    const val PRIVACY = "privacy"
    const val BLOCKS = "blocks"
    const val CHATS = "chats"
    const val LOOK = "look"
    const val NOTIFY = "notify"
    const val STORAGE = "storage"
    const val DEVICES = "devices"
    const val LISTS = "lists"
    const val HOME = "home"
    const val HELP = "help"
    val ALL = setOf(MAIN, PRIVACY, BLOCKS, CHATS, LOOK, NOTIFY, STORAGE, DEVICES, LISTS, HOME, HELP)
}

/**
 * Chat-Einstellungen as a page (mockup "Chat-Einstellungen"): profile with
 * status, then Datenschutz, Chats, Darstellung, Benachrichtigungen,
 * Speicher und Daten, Geräte, Listen, Haus-Aktionen, Hilfe und Info — each
 * its own page ([page]).
 */
@Composable
fun ChatSettingsScreen(
    page: String,
    onBack: () -> Unit,
    onOpenPage: (String) -> Unit,
    onOpenStatus: () -> Unit,
    onOpenQuietHours: () -> Unit,
    /** "Wo Mitteilungen erscheinen" (core 1.191+; hidden on older servers). */
    onOpenRoutes: (() -> Unit)? = null,
    viewModel: ChatSettingsViewModel = hiltViewModel(),
) {
    val ctx = LocalContext.current
    val res = androidx.compose.ui.platform.LocalResources.current
    LaunchedEffect(Unit) {
        viewModel.notice.collect { n ->
            when (n) {
                is SettingsNotice.Failed -> Toast.makeText(ctx, res.getString(R.string.chat_settings_saved_failed, ChatText.actionError(ctx, n.code)), Toast.LENGTH_LONG).show()
                is SettingsNotice.Done -> Toast.makeText(ctx, n.res, Toast.LENGTH_SHORT).show()
                is SettingsNotice.Export -> {
                    if (n.truncated) Toast.makeText(ctx, R.string.chat_export_truncated, Toast.LENGTH_LONG).show()
                    runCatching {
                        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".provider", n.file)
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
                            .putExtra(Intent.EXTRA_SUBJECT, n.file.name).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        send.clipData = android.content.ClipData.newRawUri(n.file.name, uri)
                        ctx.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            }
        }
    }
    val title = stringResource(
        when (page) {
            ChatSettingsPage.PRIVACY -> R.string.chat_settings_privacy
            ChatSettingsPage.BLOCKS -> R.string.chat_privacy_blocked
            ChatSettingsPage.CHATS -> R.string.chat_settings_chats
            ChatSettingsPage.LOOK -> R.string.chat_settings_look
            ChatSettingsPage.NOTIFY -> R.string.chat_settings_notify
            ChatSettingsPage.STORAGE -> R.string.chat_settings_storage
            ChatSettingsPage.DEVICES -> R.string.chat_settings_devices
            ChatSettingsPage.LISTS -> R.string.chat_settings_lists
            ChatSettingsPage.HOME -> R.string.chat_settings_home
            ChatSettingsPage.HELP -> R.string.chat_settings_help
            else -> R.string.chat_settings_title
        },
    )
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        GcSubpageBar(title = title, onBack = onBack, backDescription = stringResource(R.string.chat_back))
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (page) {
                ChatSettingsPage.PRIVACY -> PrivacyPage(viewModel, onOpenPage)
                ChatSettingsPage.BLOCKS -> BlocksPage(viewModel)
                ChatSettingsPage.CHATS -> ChatsPage(viewModel)
                ChatSettingsPage.LOOK -> LookPage(viewModel)
                ChatSettingsPage.NOTIFY -> NotifyPage(viewModel, onOpenQuietHours)
                ChatSettingsPage.STORAGE -> StoragePage(viewModel)
                ChatSettingsPage.DEVICES -> DevicesPage(viewModel)
                ChatSettingsPage.LISTS -> ListsPage(viewModel)
                ChatSettingsPage.HOME -> HomePage(viewModel)
                ChatSettingsPage.HELP -> HelpPage(viewModel)
                else -> MainPage(viewModel, onOpenPage, onOpenStatus, onOpenRoutes)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ─── shared rows ────────────────────────────────────────────────────

@Composable
private fun IconSquare(icon: ImageVector, tint: Color, bg: Color) {
    Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(bg), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun NavRow(icon: ImageVector, tint: Color, bg: Color, title: String, description: String?, onClick: () -> Unit) {
    GcListRow(title = title, description = description, onClick = onClick, leading = { IconSquare(icon, tint, bg) }, trailing = { GcChevron() })
}

@Composable
private fun ValueRow(title: String, value: String, description: String? = null, onClick: () -> Unit) {
    GcListRow(
        title = title,
        description = description,
        onClick = onClick,
        trailing = { Text(value, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), color = GateControlTheme.extraColors.accentText) },
    )
}

@Composable
private fun Section(label: String?, content: @Composable () -> Unit) {
    if (label != null) GcSectionLabel(label, Modifier.padding(start = 4.dp, top = 4.dp))
    GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) { content() }
}

@Composable
private fun Footer(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = GateControlTheme.extraColors.muted, modifier = Modifier.padding(horizontal = 4.dp))
}

@Composable
private fun audienceLabel(v: String, groups: Boolean = false): String = stringResource(
    when (v) {
        ChatPrivacy.AUDIENCE_FAMILY -> R.string.chat_aud_family
        ChatPrivacy.AUDIENCE_NOBODY -> R.string.chat_aud_nobody
        else -> if (groups) R.string.chat_aud_all_accounts else R.string.chat_aud_all
    },
)

@Composable
private fun statusVisLabel(v: String): String = stringResource(
    when (v) {
        ChatStatus.VIS_FAMILY -> R.string.chat_status_vis_family
        ChatStatus.VIS_CUSTOM -> R.string.chat_status_vis_custom
        else -> R.string.chat_status_vis_all
    },
)

// ─── main ───────────────────────────────────────────────────────────

@Composable
private fun MainPage(vm: ChatSettingsViewModel, onOpenPage: (String) -> Unit, onOpenStatus: () -> Unit, onOpenRoutes: (() -> Unit)?) {
    val ctx = LocalContext.current
    val extra = GateControlTheme.extraColors
    val profile by vm.profile.collectAsStateWithLifecycle()
    val lists by vm.lists.collectAsStateWithLifecycle()
    val f = profile?.features
    // Profile header with the status line (tap: "Dein Status").
    GcCard(contentPadding = PaddingValues(0.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable(enabled = f?.status == true, role = Role.Button, onClick = onOpenStatus).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ChatPersonAvatar(profile?.user, 56.dp)
            Column(Modifier.weight(1f)) {
                Text(ChatText.name(ctx, profile?.user), style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold), color = MaterialTheme.colorScheme.onSurface)
                if (f?.status == true) {
                    Text(StatusText.line(ctx, vm.ownStatus()) ?: stringResource(R.string.chat_story_add), style = MaterialTheme.typography.bodyMedium, color = extra.muted)
                }
            }
            if (f?.status == true) GcChevron()
        }
    }
    Section(null) {
        NavRow(Icons.Outlined.Lock, extra.accentText, extra.accentBg, stringResource(R.string.chat_settings_privacy), stringResource(R.string.chat_settings_privacy_desc)) { onOpenPage(ChatSettingsPage.PRIVACY) }
        NavRow(Icons.AutoMirrored.Outlined.Chat, extra.blue, extra.blueBg, stringResource(R.string.chat_settings_chats), stringResource(R.string.chat_settings_chats_desc)) { onOpenPage(ChatSettingsPage.CHATS) }
        NavRow(Icons.Outlined.Palette, extra.pro, extra.proBg, stringResource(R.string.chat_settings_look), stringResource(R.string.chat_settings_look_desc)) { onOpenPage(ChatSettingsPage.LOOK) }
        NavRow(Icons.Outlined.Notifications, extra.warn, extra.warnBg, stringResource(R.string.chat_settings_notify), stringResource(R.string.chat_settings_notify_desc)) { onOpenPage(ChatSettingsPage.NOTIFY) }
        if (onOpenRoutes != null) {
            // GateControl's notifications in chats (core 1.191): only when the server has the page.
            val routesEntry: com.gatecontrol.android.ui.notifications.NotifyRoutesEntryViewModel = hiltViewModel()
            val routesOn by routesEntry.visible.collectAsStateWithLifecycle()
            if (routesOn) {
                NavRow(Icons.Outlined.Inbox, extra.blue, extra.blueBg, stringResource(R.string.push_routes_title), stringResource(R.string.push_routes_entry_desc)) { onOpenRoutes() }
            }
        }
        NavRow(Icons.Outlined.Storage, extra.accentText, extra.accentBg, stringResource(R.string.chat_settings_storage), stringResource(R.string.chat_settings_storage_desc)) { onOpenPage(ChatSettingsPage.STORAGE) }
        NavRow(Icons.Outlined.Devices, extra.blue, extra.blueBg, stringResource(R.string.chat_settings_devices), stringResource(R.string.chat_settings_devices_desc)) { onOpenPage(ChatSettingsPage.DEVICES) }
        if (f?.msgTools == true) {
            NavRow(
                Icons.Outlined.FilterList, extra.pro, extra.proBg, stringResource(R.string.chat_settings_lists),
                if (lists.isEmpty()) stringResource(R.string.chat_settings_lists_desc) else stringResource(R.string.chat_settings_lists_desc_n, lists.joinToString(", ") { it.name }),
            ) { onOpenPage(ChatSettingsPage.LISTS) }
        }
        if (f?.actions == true) {
            NavRow(Icons.Outlined.Home, extra.warn, extra.warnBg, stringResource(R.string.chat_settings_home), stringResource(R.string.chat_settings_home_desc)) { onOpenPage(ChatSettingsPage.HOME) }
        }
        NavRow(Icons.AutoMirrored.Outlined.HelpOutline, extra.muted, extra.panel2, stringResource(R.string.chat_settings_help), stringResource(R.string.chat_settings_help_desc, vm.serverHost)) { onOpenPage(ChatSettingsPage.HELP) }
    }
}

// ─── privacy ────────────────────────────────────────────────────────

@Composable
private fun PrivacyPage(vm: ChatSettingsViewModel, onOpenPage: (String) -> Unit) {
    val ctx = LocalContext.current
    val profile by vm.profile.collectAsStateWithLifecycle()
    val config by vm.config.collectAsStateWithLifecycle()
    val blocks by vm.blocks.collectAsStateWithLifecycle()
    val f = profile?.features
    val prefs = profile?.prefs
    val p = profile?.prefs14?.privacy ?: ChatPrivacy()
    var dialog by remember { mutableStateOf<String?>(null) }
    val auth = rememberGcDeviceAuth()
    val lockTitle = stringResource(R.string.chat_lock_title)
    val lockSubtitle = stringResource(R.string.chat_lock_subtitle)
    if (f?.privacy2 == true) {
        Section(stringResource(R.string.chat_privacy_who)) {
            ValueRow(stringResource(R.string.chat_privacy_last_seen), audienceLabel(p.lastSeen), stringResource(R.string.chat_privacy_last_seen_desc)) { dialog = "lastSeen" }
            ValueRow(stringResource(R.string.chat_privacy_avatar), audienceLabel(p.avatar)) { dialog = "avatar" }
            if (f.status) {
                ValueRow(stringResource(R.string.chat_privacy_status), statusVisLabel(p.statusDefault), stringResource(R.string.chat_privacy_status_desc)) { dialog = "statusDefault" }
                ValueRow(stringResource(R.string.chat_privacy_place), audienceLabel(p.placeInStatus), stringResource(R.string.chat_privacy_place_desc)) { dialog = "place" }
            }
        }
    }
    Section(if (f?.privacy2 == true) null else stringResource(R.string.chat_privacy_who)) {
        GcSwitchRow(stringResource(R.string.chat_prefs_receipts), stringResource(R.string.chat_privacy_receipts_desc), prefs?.readReceipts != false, { vm.setPrefs(readReceipts = it) })
        GcSwitchRow(stringResource(R.string.chat_prefs_typing), stringResource(R.string.chat_prefs_typing_desc), prefs?.typing != false, { vm.setPrefs(typing = it) })
    }
    if (f?.privacy2 == true || f?.msgTools == true) {
        Section(stringResource(R.string.chat_privacy_messages)) {
            if (f.privacy2) {
                if (f.disappearing) {
                    ValueRow(stringResource(R.string.chat_privacy_disappearing), ChatText.disappearing(ctx, p.defaultDisappearingS), stringResource(R.string.chat_privacy_disappearing_desc)) { dialog = "disappearing" }
                }
                ValueRow(stringResource(R.string.chat_privacy_groups), audienceLabel(p.groupAdd, groups = true), stringResource(R.string.chat_privacy_groups_desc)) { dialog = "groups" }
            }
            if (f.msgTools) {
                ValueRow(stringResource(R.string.chat_privacy_blocked), (blocks?.size ?: 0).toString(), stringResource(R.string.chat_privacy_blocked_desc)) { onOpenPage(ChatSettingsPage.BLOCKS) }
            }
        }
    }
    Section(stringResource(R.string.chat_privacy_security)) {
        GcSwitchRow(stringResource(R.string.chat_privacy_lock), stringResource(R.string.chat_privacy_lock_desc), config.appLock, { on ->
            if (!on) {
                vm.setDevice { it.copy(appLock = false) }
            } else if (!auth.available()) {
                Toast.makeText(ctx, R.string.chat_privacy_lock_unavailable, Toast.LENGTH_LONG).show()
            } else {
                // Confirmed once, so nobody locks themselves out.
                auth.authenticate(lockTitle, lockSubtitle) { r ->
                    if (r == DeviceAuthResult.SUCCESS) vm.setDevice { it.copy(appLock = true) }
                }
            }
        })
        GcSwitchRow(stringResource(R.string.chat_privacy_secure), stringResource(R.string.chat_privacy_secure_desc), config.secure, { on -> vm.setDevice { it.copy(secure = on) } })
    }
    Footer(stringResource(R.string.chat_privacy_footer) + " " + stringResource(R.string.chat_privacy_device_footer))
    val audiences = ChatPrivacy.AUDIENCES.map { it to audienceLabel(it) }
    when (dialog) {
        "lastSeen" -> OptionDialog(stringResource(R.string.chat_privacy_last_seen), audiences, p.lastSeen, { v -> dialog = null; vm.setPrefs14 { it.copy(privacy = it.privacy.copy(lastSeen = v)) } }) { dialog = null }
        "avatar" -> OptionDialog(stringResource(R.string.chat_privacy_avatar), audiences, p.avatar, { v -> dialog = null; vm.setPrefs14 { it.copy(privacy = it.privacy.copy(avatar = v)) } }) { dialog = null }
        "place" -> OptionDialog(stringResource(R.string.chat_privacy_place), audiences, p.placeInStatus, { v -> dialog = null; vm.setPrefs14 { it.copy(privacy = it.privacy.copy(placeInStatus = v)) } }) { dialog = null }
        "groups" -> OptionDialog(stringResource(R.string.chat_privacy_groups), ChatPrivacy.AUDIENCES.map { it to audienceLabel(it, groups = true) }, p.groupAdd, { v ->
            dialog = null
            vm.setPrefs14 { it.copy(privacy = it.privacy.copy(groupAdd = v)) }
        }) { dialog = null }
        "statusDefault" -> OptionDialog(stringResource(R.string.chat_privacy_status), ChatStatus.VISIBILITIES.filter { it != ChatStatus.VIS_CUSTOM }.map { it to statusVisLabel(it) }, p.statusDefault, { v ->
            dialog = null
            vm.setPrefs14 { it.copy(privacy = it.privacy.copy(statusDefault = v)) }
        }) { dialog = null }
        "disappearing" -> OptionDialog(stringResource(R.string.chat_privacy_disappearing), ChatPrivacy.DISAPPEARING.map { it to ChatText.disappearing(ctx, it) }, p.defaultDisappearingS, { v ->
            dialog = null
            vm.setPrefs14 { it.copy(privacy = it.privacy.copy(defaultDisappearingS = v)) }
        }) { dialog = null }
    }
}

@Composable
private fun BlocksPage(vm: ChatSettingsViewModel) {
    val ctx = LocalContext.current
    val blocks by vm.blocks.collectAsStateWithLifecycle()
    val list = blocks.orEmpty()
    Section(null) {
        if (list.isEmpty()) GcListRow(title = stringResource(R.string.chat_blocked_none))
        list.forEach { p ->
            GcListRow(
                title = ChatText.name(ctx, p),
                leading = { ChatPersonAvatar(p, 40.dp) },
                trailing = { TextButton(onClick = { vm.unblock(p.userId) }) { Text(stringResource(R.string.chat_unblock)) } },
            )
        }
    }
    Footer(stringResource(R.string.chat_privacy_blocked_desc))
}

// ─── chats ──────────────────────────────────────────────────────────

@Composable
private fun ChatsPage(vm: ChatSettingsViewModel) {
    val ctx = LocalContext.current
    val profile by vm.profile.collectAsStateWithLifecycle()
    val config by vm.config.collectAsStateWithLifecycle()
    val conversations by vm.conversations.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val f = profile?.features
    var pickExport by remember { mutableStateOf(false) }
    Section(null) {
        GcSwitchRow(stringResource(R.string.chat_chats_enter), null, config.enterSends, { on -> vm.setDevice { it.copy(enterSends = on) } })
        if (f?.msgTools == true) {
            GcSwitchRow(
                stringResource(R.string.chat_chats_keep_archived), stringResource(R.string.chat_chats_keep_archived_desc),
                profile?.prefs14?.keepArchived != false, { on -> vm.setPrefs14 { it.copy(keepArchived = on) } },
            )
            GcListRow(
                title = stringResource(R.string.chat_chats_export),
                description = stringResource(R.string.chat_chats_export_desc),
                onClick = { if (!busy) pickExport = true },
                trailing = { if (busy) androidx.compose.material3.CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else GcChevron() },
            )
        }
    }
    if (pickExport) {
        OptionDialog(
            stringResource(R.string.chat_chats_export_pick),
            conversations.sortedByDescending { it.activityAt }.map { it to ChatText.title(ctx, it) },
            null,
            { c: ChatConversation -> pickExport = false; vm.export(c, ChatText.title(ctx, c)) },
        ) { pickExport = false }
    }
}

// ─── appearance ─────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LookPage(vm: ChatSettingsViewModel) {
    val extra = GateControlTheme.extraColors
    val config by vm.config.collectAsStateWithLifecycle()
    var fontDialog by remember { mutableStateOf(false) }
    val photo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) vm.setWallpaperPhoto(uri) }
    GcSectionLabel(stringResource(R.string.chat_look_wallpaper), Modifier.padding(start = 4.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        WallpaperTile(stringResource(R.string.chat_look_wallpaper_none), MaterialTheme.colorScheme.background, config.wallpaper == ChatLook.WALLPAPER_NONE) {
            vm.setDevice { it.copy(wallpaper = ChatLook.WALLPAPER_NONE) }
        }
        ChatLook.WALLPAPERS.forEach { (key, colors) ->
            WallpaperTile(null, Color(if (extra.isDark) colors.first else colors.second), config.wallpaper == key) { vm.setDevice { it.copy(wallpaper = key) } }
        }
        val px = with(LocalDensity.current) { 120.dp.roundToPx() }
        val bmp = rememberLocalBitmap(vm.wallpaperFile.path.takeIf { config.wallpaper == ChatLook.WALLPAPER_PHOTO && vm.wallpaperFile.isFile }, px)
        Box(
            Modifier.size(width = 72.dp, height = 112.dp).clip(RoundedCornerShape(14.dp)).background(extra.panel2)
                .border(2.dp, if (config.wallpaper == ChatLook.WALLPAPER_PHOTO) MaterialTheme.colorScheme.primary else extra.border, RoundedCornerShape(14.dp))
                .clickable(role = Role.Button) { photo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            contentAlignment = Alignment.Center,
        ) {
            if (bmp != null) {
                Image(bmp, contentDescription = stringResource(R.string.chat_look_wallpaper_photo), contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                Icon(Icons.Outlined.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(18.dp))
            } else {
                Column(Modifier.padding(horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.Image, contentDescription = null, tint = extra.muted)
                    TileLabel(stringResource(R.string.chat_look_wallpaper_photo))
                }
            }
        }
    }
    Section(null) {
        ValueRow(stringResource(R.string.chat_look_font), fontLabel(config.fontSize)) { fontDialog = true }
        GcListRow(title = stringResource(R.string.chat_look_bubble))
        FlowRow(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ChatLook.BUBBLE_COLORS.forEach { (key, colors) ->
                val sel = config.bubbleColor == key
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(Color(if (extra.isDark) colors.first else colors.second))
                        .border(if (sel) 3.dp else 1.dp, if (sel) MaterialTheme.colorScheme.primary else extra.border2, CircleShape)
                        .clickable(role = Role.RadioButton) { vm.setDevice { it.copy(bubbleColor = key) } },
                    contentAlignment = Alignment.Center,
                ) { if (sel) Icon(Icons.Outlined.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface) }
            }
        }
    }
    // Preview.
    val scale = ChatLook.fontScale(config.fontSize)
    val own = ChatLook.BUBBLE_COLORS[config.bubbleColor]?.let { Color(if (extra.isDark) it.first else it.second) } ?: extra.accentBg
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PreviewBubble(stringResource(R.string.chat_look_preview_other), MaterialTheme.colorScheme.surface, scale, Alignment.Start)
        PreviewBubble(stringResource(R.string.chat_look_preview), own, scale, Alignment.End)
    }
    if (fontDialog) {
        OptionDialog(stringResource(R.string.chat_look_font), ChatLook.FONTS.map { it to fontLabel(it) }, config.fontSize, { v -> fontDialog = false; vm.setDevice { it.copy(fontSize = v) } }) { fontDialog = false }
    }
}

@Composable
private fun fontLabel(key: String): String = stringResource(
    when (key) {
        ChatLook.FONT_SMALL -> R.string.chat_look_font_small
        ChatLook.FONT_LARGE -> R.string.chat_look_font_large
        ChatLook.FONT_XLARGE -> R.string.chat_look_font_xlarge
        else -> R.string.chat_look_font_medium
    },
)

@Composable
private fun WallpaperTile(label: String?, color: Color, selected: Boolean, onClick: () -> Unit) {
    val extra = GateControlTheme.extraColors
    Box(
        Modifier.size(width = 72.dp, height = 112.dp).clip(RoundedCornerShape(14.dp)).background(color)
            .border(2.dp, if (selected) MaterialTheme.colorScheme.primary else extra.border, RoundedCornerShape(14.dp))
            .clickable(role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (label != null) TileLabel(label, Modifier.padding(horizontal = 4.dp))
        if (selected) Icon(Icons.Outlined.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(18.dp))
    }
}

/** A wallpaper tile's caption: centred, wraps between words and stays inside the 72 dp tile. */
@Composable
private fun TileLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Default, letterSpacing = 0.sp, lineHeight = 14.sp),
        color = GateControlTheme.extraColors.muted,
        textAlign = TextAlign.Center,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

@Composable
private fun PreviewBubble(text: String, color: Color, scale: Float, align: Alignment.Horizontal) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = align) {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge.let { it.copy(fontSize = it.fontSize * scale) },
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(color).border(1.dp, GateControlTheme.extraColors.border, RoundedCornerShape(18.dp)).padding(12.dp),
        )
    }
}

// ─── notifications ──────────────────────────────────────────────────

@Composable
private fun NotifyPage(vm: ChatSettingsViewModel, onOpenQuietHours: () -> Unit) {
    val ctx = LocalContext.current
    val profile by vm.profile.collectAsStateWithLifecycle()
    val config by vm.config.collectAsStateWithLifecycle()
    val shortcutsOn by vm.shortcutsOn.collectAsStateWithLifecycle()
    val bubblesOn by vm.bubblesOn.collectAsStateWithLifecycle()
    val f = profile?.features
    val n = profile?.prefs14?.notify ?: ChatNotifyPrefs()
    var direct by remember { mutableStateOf(vm.channelInfo(false)) }
    var groups by remember { mutableStateOf(vm.channelInfo(true)) }
    var reminderDialog by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        direct = vm.channelInfo(false)
        groups = vm.channelInfo(true)
        onPauseOrDispose { }
    }
    val openChannel: (String) -> Unit = { id ->
        runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
    @Composable
    fun tone(info: Pair<String?, Boolean?>) = when (info.first) {
        null -> stringResource(R.string.chat_notify_system)
        "" -> stringResource(R.string.chat_notify_tone_silent)
        else -> info.first!!
    }
    Section(null) {
        GcSwitchRow(stringResource(R.string.chat_notify_sounds), stringResource(R.string.chat_notify_sounds_desc), config.sounds, { on -> vm.setDevice { it.copy(sounds = on) } })
    }
    Section(stringResource(R.string.chat_notify_direct)) {
        ValueRow(stringResource(R.string.chat_notify_tone), tone(direct)) { openChannel(ChatNotifier.CHANNEL_ID) }
        ValueRow(stringResource(R.string.chat_notify_vibration), direct.second?.let { stringResource(if (it) R.string.chat_notify_vibrate_on else R.string.chat_notify_vibrate_off) } ?: stringResource(R.string.chat_notify_system)) {
            openChannel(ChatNotifier.CHANNEL_ID)
        }
        GcSwitchRow(stringResource(R.string.chat_notify_preview), stringResource(R.string.chat_notify_preview_desc), profile?.prefs?.preview != false, { vm.setPrefs(preview = it) })
        if (f?.privacy2 == true) GcSwitchRow(stringResource(R.string.chat_notify_reactions), null, n.reactions, { on -> vm.setPrefs14 { it.copy(notify = it.notify.copy(reactions = on)) } })
    }
    Section(stringResource(R.string.chat_notify_groups)) {
        ValueRow(stringResource(R.string.chat_notify_tone), tone(groups)) { openChannel(ChatNotifier.CHANNEL_GROUPS) }
        if (f?.privacy2 == true) {
            val first = profile?.user?.name?.split(' ')?.firstOrNull().orEmpty()
            GcSwitchRow(stringResource(R.string.chat_notify_mentions, first), stringResource(R.string.chat_notify_mentions_desc), n.groupsMentionsOnly, { on ->
                vm.setPrefs14 { it.copy(notify = it.notify.copy(groupsMentionsOnly = on)) }
            })
        }
    }
    Section(stringResource(R.string.chat_notify_more)) {
        if (f?.status == true) GcSwitchRow(stringResource(R.string.chat_notify_digest), stringResource(R.string.chat_notify_digest_desc), n.statusDigest, { on -> vm.setPrefs14 { it.copy(notify = it.notify.copy(statusDigest = on)) } })
        if (f?.events == true) ValueRow(stringResource(R.string.chat_notify_event), reminderLabel(n.eventReminderMin), stringResource(R.string.chat_notify_event_desc)) { reminderDialog = true }
        GcListRow(title = stringResource(R.string.chat_notify_quiet), description = stringResource(R.string.chat_notify_quiet_desc), onClick = onOpenQuietHours, trailing = { GcChevron() })
        val systemBubbles = remember { ChatSystemBubbles.allowed(ctx) }
        GcSwitchRow(stringResource(R.string.chat_prefs_shortcuts), stringResource(R.string.chat_prefs_shortcuts_desc), shortcutsOn, { vm.setShortcuts(it) })
        GcSwitchRow(
            stringResource(R.string.chat_prefs_bubbles),
            stringResource(
                when {
                    !shortcutsOn -> R.string.chat_prefs_bubbles_needs_shortcuts
                    systemBubbles == false -> R.string.chat_prefs_bubbles_system_off
                    else -> R.string.chat_prefs_bubbles_desc
                },
            ),
            bubblesOn && shortcutsOn,
            { vm.setBubbles(it) },
            enabled = shortcutsOn,
        )
        if (shortcutsOn && bubblesOn && systemBubbles == false) {
            TextButton(onClick = { ChatSystemBubbles.openSettings(ctx) }, modifier = Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.chat_prefs_bubbles_open_settings)) }
        }
    }
    if (reminderDialog) {
        OptionDialog(stringResource(R.string.chat_notify_event), ChatNotifyPrefs.REMINDER_MIN.map { it to reminderLabel(it) }, n.eventReminderMin, { v ->
            reminderDialog = false
            vm.setPrefs14 { it.copy(notify = it.notify.copy(eventReminderMin = v)) }
        }) { reminderDialog = false }
    }
}

// ─── storage and data ───────────────────────────────────────────────

@Composable
private fun StoragePage(vm: ChatSettingsViewModel) {
    val extra = GateControlTheme.extraColors
    val profile by vm.profile.collectAsStateWithLifecycle()
    val config by vm.config.collectAsStateWithLifecycle()
    val storage by vm.storage.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        vm.refreshStorage()
        vm.refreshProfile()
    }
    GcCard {
        val s = storage
        Text(stringResource(R.string.chat_storage_device, ChatText.bytes(s?.total ?: 0L)), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        val usage = profile?.limits?.usage
        when {
            usage?.usedBytes != null && usage.quotaBytes != null -> Text(stringResource(R.string.chat_storage_server, ChatText.bytes(usage.usedBytes), ChatText.bytes(usage.quotaBytes)), style = MaterialTheme.typography.bodyMedium, color = extra.muted)
            usage?.usedBytes != null -> Text(stringResource(R.string.chat_storage_server_used, ChatText.bytes(usage.usedBytes)), style = MaterialTheme.typography.bodyMedium, color = extra.muted)
        }
        if (profile?.limits?.quotaFull == true) Text(stringResource(R.string.chat_storage_full), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        if (s != null && s.total > 0) {
            // Bar by kind.
            val parts = listOf(s.photos to extra.blue, s.videos to extra.pro, s.voice to extra.warn, s.files + s.outbox to extra.accentText)
            Row(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(extra.panel2)) {
                parts.filter { it.first > 0 }.forEach { (b, c) -> Box(Modifier.weight(b.toFloat()).height(10.dp).background(c)) }
            }
            FlowRow4(
                listOf(
                    stringResource(R.string.chat_storage_photos) to s.photos,
                    stringResource(R.string.chat_storage_videos) to s.videos,
                    stringResource(R.string.chat_storage_voice) to s.voice,
                    stringResource(R.string.chat_storage_files) to s.files + s.outbox,
                ),
            )
        }
        TextButton(onClick = vm::clearCache) { Text(stringResource(R.string.chat_storage_clear)) }
    }
    Section(null) {
        ValueRow(stringResource(R.string.chat_storage_wifi), autoLabel(config.autoWifi)) { dialog = "wifi" }
        ValueRow(stringResource(R.string.chat_storage_mobile), autoLabel(config.autoMobile)) { dialog = "mobile" }
        ValueRow(stringResource(R.string.chat_storage_quality), qualityLabel(config.sendQuality), stringResource(R.string.chat_storage_quality_desc)) { dialog = "quality" }
    }
    when (dialog) {
        "wifi" -> OptionDialog(stringResource(R.string.chat_storage_wifi), ChatAutoDownload.CHOICES.map { it to autoLabel(it) }, config.autoWifi, { v -> dialog = null; vm.setDevice { it.copy(autoWifi = v) } }) { dialog = null }
        "mobile" -> OptionDialog(stringResource(R.string.chat_storage_mobile), ChatAutoDownload.CHOICES.map { it to autoLabel(it) }, config.autoMobile, { v -> dialog = null; vm.setDevice { it.copy(autoMobile = v) } }) { dialog = null }
        "quality" -> OptionDialog(stringResource(R.string.chat_storage_quality), ChatQuality.CHOICES.map { it to qualityLabel(it) }, config.sendQuality, { v -> dialog = null; vm.setDevice { it.copy(sendQuality = v) } }) { dialog = null }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRow4(items: List<Pair<String, Long>>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        items.forEach { (label, bytes) ->
            Text("$label ${ChatText.bytes(bytes)}", style = MaterialTheme.typography.bodySmall, color = GateControlTheme.extraColors.muted)
        }
    }
}

@Composable
private fun autoLabel(v: String): String = stringResource(
    when (v) {
        ChatAutoDownload.ALL -> R.string.chat_auto_all
        ChatAutoDownload.NONE -> R.string.chat_auto_none
        else -> R.string.chat_auto_photos
    },
)

@Composable
private fun qualityLabel(v: String): String = stringResource(
    when (v) {
        ChatQuality.SAVER -> R.string.chat_quality_saver
        ChatQuality.ORIGINAL -> R.string.chat_quality_original
        else -> R.string.chat_quality_standard
    },
)

// ─── devices ────────────────────────────────────────────────────────

@Composable
private fun DevicesPage(@Suppress("UNUSED_PARAMETER") vm: ChatSettingsViewModel) {
    val extra = GateControlTheme.extraColors
    Section(null) {
        GcListRow(
            title = stringResource(R.string.chat_devices_this),
            description = stringResource(R.string.chat_devices_this_desc, "${Build.MANUFACTURER} ${Build.MODEL}".trim(), Build.VERSION.RELEASE, BuildConfig.VERSION_NAME),
            leading = { IconSquare(Icons.Outlined.Devices, extra.blue, extra.blueBg) },
        )
    }
    Footer(stringResource(R.string.chat_devices_info))
}

// ─── lists ──────────────────────────────────────────────────────────

@Composable
private fun ListsPage(vm: ChatSettingsViewModel) {
    val ctx = LocalContext.current
    val lists by vm.lists.collectAsStateWithLifecycle()
    val conversations by vm.conversations.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<ChatList?>(null) }
    var creating by remember { mutableStateOf(false) }
    Section(null) {
        if (lists.isEmpty()) GcListRow(title = stringResource(R.string.chat_lists_none))
        lists.forEach { l ->
            GcListRow(
                title = l.name,
                description = pluralStringResource(R.plurals.chat_lists_chats, l.conversationIds.size, l.conversationIds.size),
                onClick = { editing = l },
                leading = { IconSquare(Icons.Outlined.FilterList, GateControlTheme.extraColors.pro, GateControlTheme.extraColors.proBg) },
                trailing = { GcChevron() },
            )
        }
        if (lists.size < ChatList.MAX) {
            TextButton(onClick = { creating = true }, modifier = Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.chat_lists_add)) }
        }
    }
    if (creating) {
        com.gatecontrol.android.ui.chat.TextLineDialog(stringResource(R.string.chat_lists_add), stringResource(R.string.chat_lists_name), ChatList.NAME_MAX, { name ->
            creating = false
            val l = vm.newList(name)
            vm.saveLists(lists + l)
            editing = l
        }) { creating = false }
    }
    editing?.let { l ->
        val chosen = remember(l.id) { mutableStateListOf<Long>().apply { addAll(l.conversationIds) } }
        var name by rememberSaveable(l.id) { mutableStateOf(l.name) }
        var confirmDelete by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(stringResource(R.string.chat_lists_edit)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(value = name, onValueChange = { name = it.take(ChatList.NAME_MAX) }, singleLine = true, label = { Text(stringResource(R.string.chat_lists_name)) }, shape = RoundedCornerShape(14.dp))
                    Spacer(Modifier.height(8.dp))
                    conversations.sortedByDescending { it.activityAt }.forEach { c ->
                        val on = c.id in chosen
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp)).clickable(role = Role.Checkbox) { if (on) chosen.remove(c.id) else chosen.add(c.id) },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            com.gatecontrol.android.ui.chat.ConversationAvatar(c, 32.dp)
                            Text(ChatText.title(ctx, c), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                            if (on) Icon(Icons.Outlined.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    TextButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.chat_lists_delete), color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.saveLists(lists.map { if (it.id == l.id) it.copy(name = name.trim().ifEmpty { l.name }, conversationIds = chosen.toList()) else it })
                    editing = null
                }) { Text(stringResource(R.string.chat_save)) }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(stringResource(R.string.chat_cancel)) } },
        )
        if (confirmDelete) {
            ConfirmDialog(stringResource(R.string.chat_lists_delete), l.name, stringResource(R.string.chat_lists_delete), {
                confirmDelete = false
                vm.saveLists(lists.filter { it.id != l.id })
                editing = null
            }) { confirmDelete = false }
        }
    }
}

// ─── home actions ───────────────────────────────────────────────────

@Composable
private fun HomePage(vm: ChatSettingsViewModel) {
    val actions by vm.actions.collectAsStateWithLifecycle()
    val config by vm.config.collectAsStateWithLifecycle()
    val homeProfile by vm.profile.collectAsStateWithLifecycle()
    val homeConversations by vm.conversations.collectAsStateWithLifecycle()
    // Mockup ChatHomeSettings: doorbell, door requests and shared devices as the server set them (read only).
    val doorbell by vm.doorbell.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.loadDoorbell() }
    ChatHomeDefaults(homeProfile?.home ?: com.gatecontrol.android.chat.ChatHomeInfo(), homeConversations, doorbell)
    Section(stringResource(R.string.chat_home_show)) {
        val list = actions
        when {
            list == null -> androidx.compose.material3.CircularProgressIndicator(Modifier.padding(16.dp).size(24.dp))
            list.isEmpty() -> GcListRow(title = stringResource(R.string.chat_home_none))
            else -> list.forEach { a ->
                val icon = remember(a.icon) { QuickActionIcons.vector(a.icon) }
                GcSwitchRow(a.label, a.plugin, a.id !in config.hiddenActions, { on -> vm.setActionShown(a.id, on) }, icon = icon)
            }
        }
    }
    Footer(stringResource(R.string.chat_home_info))
}

// ─── help ───────────────────────────────────────────────────────────

@Composable
private fun HelpPage(vm: ChatSettingsViewModel) {
    val profile by vm.profile.collectAsStateWithLifecycle()
    Section(null) {
        GcListRow(title = stringResource(R.string.chat_help_app, BuildConfig.VERSION_NAME))
        GcRowDivider()
        GcListRow(title = stringResource(R.string.chat_help_server, vm.serverHost))
    }
    val on = profile?.features?.tokens.orEmpty().filter { it in ChatFeatures14.ALL || it == "video_meta" || it == "file_thumbs" }.sorted()
    if (on.isNotEmpty()) {
        Section(stringResource(R.string.chat_help_features)) {
            Text(on.joinToString(" · "), style = MaterialTheme.typography.bodyMedium.copy(fontFamily = com.gatecontrol.android.ui.theme.MonoFontFamily), color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(16.dp))
        }
    }
    Footer(stringResource(R.string.chat_help_privacy))
}
