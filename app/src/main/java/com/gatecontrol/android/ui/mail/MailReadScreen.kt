package com.gatecontrol.android.ui.mail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.mail.MAddress
import com.gatecontrol.android.mail.MAi
import com.gatecontrol.android.mail.MMe
import com.gatecontrol.android.mail.MMessage
import com.gatecontrol.android.mail.MSummary
import com.gatecontrol.android.mail.MTodo
import com.gatecontrol.android.mail.MailLogic
import com.gatecontrol.android.ui.components.GcBanner
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.theme.GateControlTheme
import com.gatecontrol.android.ui.mail.calendar.InviteConsentCard
import com.gatecontrol.android.ui.mail.calendar.InviteNav
import com.gatecontrol.android.ui.mail.calendar.MailInvitationCard
import com.gatecontrol.android.mail.MInvitation
import com.gatecontrol.android.mail.MailMicrosoft

/** Where the reading screen leads. */
data class MailReadNav(
    val back: () -> Unit,
    /** Answer (`reply`, `replyAll`) or forward the mail, with an AI suggestion as the start of the text. */
    val compose: (mode: String, reply: String?) -> Unit,
    /** A new mail to an address (a `mailto:` link, "Abmelden" by mail). */
    val mailTo: (String) -> Unit,
    /** Another mail of the conversation. */
    val open: (Long) -> Unit,
    /** The sender's page (their mails in the account [accountId]). */
    val sender: (email: String, name: String?, accountId: Long) -> Unit = { _, _, _ -> },
    /** Since Mail 1.2.0: "Als Kontakt speichern" of an address of this mail. */
    val saveContact: (email: String, name: String?, messageId: Long) -> Unit = { _, _, _ -> },
    val contact: (Long) -> Unit = {},
    val event: (Long) -> Unit = {},
    /** "Kalender freigeben": sign in to the Microsoft account again. */
    val relogin: (Long) -> Unit = {},
)

/**
 * A mail (mockup "Mail lesen mit KI und Anhang nach Drive"): archive, delete and more on top — while the app is dark
 * also the sun / moon that shows this mail light as it was made ("Hell anzeigen", as in the Outlook app) or dark
 * again ("Mails dunkel darstellen" in the settings); subject, the
 * conversation ("Unterhaltung · n Mails": a tap shows all its mails, oldest first, each unfolding), sender (a tap
 * opens the sender's page) and account; the AI bar ("Kurz gesagt", to-dos as chips); the attachments with Drive's
 * suggestion; "Bilder laden"; the body; answer suggestions, "Antworten" and "Weiterleiten" below.
 */
@Composable
fun MailReadScreen(nav: MailReadNav, viewModel: MailReadViewModel = hiltViewModel(), display: MailDisplayViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val darkMails by display.darkMails.collectAsStateWithLifecycle()
    // "Hell anzeigen": this mail light as it was made (until it is closed)
    var shownLight by rememberSaveable(viewModel.id) { mutableStateOf(false) }
    val me by viewModel.me.collectAsStateWithLifecycle()
    val event by viewModel.event.collectAsStateWithLifecycle()
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val context = mailContext()
    var menu by remember { mutableStateOf(false) }
    var move by remember { mutableStateOf(false) }
    var driveFor by remember { mutableStateOf<Pair<MailAttachmentUi, Boolean>?>(null) }
    val extra = GateControlTheme.extraColors

    LaunchedEffect(event) {
        when (val e = event) {
            null -> Unit
            MailReadEvent.Close -> nav.back()
            is MailReadEvent.Toast -> toast(context, e.text)
            is MailReadEvent.Open -> openFile(context, e.file, e.mime)
        }
        if (event != null) viewModel.consumed()
    }

    val m = state.message
    val account = me?.account(m?.summary?.accountId)
    val canDark = extra.isDark && darkMails
    val dark = MailLogic.showDark(extra.isDark, darkMails, shownLight)
    val onLink: (String) -> Unit = { url ->
        val to = MailLogic.mailtoAddress(url)
        if (to != null) nav.mailTo(to.email) else openExternal(context, url)
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            GcIconButton(GcIcons.Back, contentDescription = stringResource(R.string.mail_back), onClick = nav.back, tint = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.weight(1f))
            if (m != null) {
                if (canDark && (m.html != null || state.conversation.any { it.html != null })) {
                    GcIconButton(
                        if (shownLight) GcIcons.Moon else GcIcons.Sun,
                        contentDescription = stringResource(if (shownLight) R.string.mail_show_dark else R.string.mail_show_light),
                        onClick = { shownLight = !shownLight },
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                }
                GcIconButton(GcIcons.Archive, contentDescription = stringResource(R.string.mail_archive), onClick = { viewModel.act(MailLogic.SWIPE_ARCHIVE) }, tint = MaterialTheme.colorScheme.onBackground)
                GcIconButton(GcIcons.Trash, contentDescription = stringResource(R.string.mail_delete), onClick = { viewModel.act(MailLogic.SWIPE_DELETE) }, tint = MaterialTheme.colorScheme.onBackground)
                Box {
                    GcIconButton(GcIcons.MoreVertical, contentDescription = stringResource(R.string.mail_more), onClick = { menu = true }, tint = MaterialTheme.colorScheme.onBackground)
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.mail_mark_unread)) }, onClick = { menu = false; viewModel.act("unread") })
                        DropdownMenuItem(text = { Text(stringResource(if (m.summary.flagged) R.string.mail_unflag else R.string.mail_flag)) }, onClick = { menu = false; viewModel.act("flag") })
                        DropdownMenuItem(
                            text = { Text(stringResource(if (m.summary.tab == MSummary.TAB_OTHER) R.string.mail_to_relevant else R.string.mail_to_other)) },
                            onClick = { menu = false; viewModel.act("tab") },
                        )
                        if (folders[m.summary.accountId].orEmpty().isNotEmpty()) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.mail_move)) }, onClick = { menu = false; move = true })
                        }
                        DropdownMenuItem(text = { Text(stringResource(R.string.mail_junk)) }, onClick = { menu = false; viewModel.act("junk") })
                        // since Mail 1.2.0: the sender into the contacts (name, address, the signature's number …)
                        if (me?.has(MMe.FEATURE_CONTACTS) == true && m.summary.from.email.contains('@')) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.con_save_sender)) },
                                onClick = { menu = false; nav.saveContact(m.summary.from.email, m.summary.from.name, m.id) },
                            )
                        }
                        m.unsubscribe?.let { u ->
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.mail_unsubscribe)) },
                                onClick = {
                                    menu = false
                                    when {
                                        u.url != null -> openLink(context, u.url)
                                        u.mail != null -> MailLogic.mailtoAddress(if (u.mail.startsWith("mailto:")) u.mail else "mailto:" + u.mail)?.let { nav.mailTo(it.email) }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
        when {
            m == null && state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            m == null -> ErrorBox(mailText(context, state.error), onRetry = viewModel::load)
            else -> {
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Subject(m)
                    // the conversation: its line, and once tapped the mails before this one (oldest first)
                    val (older, newer) = remember(state.conversation, m.id) { MailLogic.conversation(state.conversation, m.id) }
                    val thread = me?.settings?.threads != false && m.summary.threadId != null && m.summary.threadCount > 1
                    val threadMail: @Composable (MMessage) -> Unit = { t ->
                        ThreadMail(
                            t,
                            color = accountColor(me?.account(t.summary.accountId)),
                            unfolded = t.id in state.threadUnfolded,
                            onFold = { viewModel.toggleThreadMail(t.id) },
                            onOpen = { nav.open(t.id) },
                            loader = viewModel::picture,
                            onLink = onLink,
                            dark = dark,
                        )
                    }
                    if (thread) {
                        ThreadLine(
                            count = maxOf(m.summary.threadCount, state.conversation.size),
                            names = MailLogic.participants(state.conversation.map { it.summary }.ifEmpty { listOf(m.summary) }),
                            open = state.threadOpen,
                            loading = state.threadLoading,
                            onToggle = viewModel::toggleThread,
                        )
                        if (state.threadOpen) older.forEach { threadMail(it) }
                    }
                    SenderLine(m, account?.label, accountColor(account), onSender = { nav.sender(m.summary.from.email, m.summary.from.name, m.summary.accountId) })
                    if (state.offline) {
                        GcBanner(tone = GcTone.Neutral, icon = GcIcons.Wifi) { Text(stringResource(R.string.mail_offline_copy), style = MaterialTheme.typography.bodySmall) }
                    }
                    // since Mail 1.2.0: the invitation the mail carries (mockup "Einladung"), above the text
                    if (me?.has(MMe.FEATURE_CALENDAR) == true) {
                        val inv = m.invite
                        if (inv != null) {
                            MailInvitationCard(inv, InviteNav(event = nav.event, contact = nav.contact, saveContact = { e, n -> nav.saveContact(e, n, m.id) }), enabled = !state.offline)
                        } else if (m.summary.invite == MInvitation.METHOD_REQUEST && account?.missingScopes?.any { it.equals(MailMicrosoft.CALENDAR_SCOPE, true) } == true) {
                            InviteConsentCard(account.label) { nav.relogin(account.id) }
                        }
                    }
                    AiCard(state.ai, me, onRetry = { viewModel.runAi(force = true) }, onFamily = viewModel::todoToFamily, onCalendar = { todo ->
                        addToCalendar(context, todo, todo.text)
                    })
                    val drive = me?.has(MMe.FEATURE_DRIVE) == true
                    state.attachments.forEach { a ->
                        AttachmentCard(
                            a = a,
                            sender = m.summary.from.label,
                            drive = drive,
                            onOpen = { viewModel.open(a.attachment) },
                            onSave = { remember -> viewModel.saveToDrive(a, null, remember) },
                            onOther = { remember -> driveFor = a to remember },
                        )
                    }
                    if (drive && state.attachments.count { it.savedTo == null } > 1) {
                        TextButton(onClick = viewModel::saveAll, enabled = !state.savingAll) { Text(stringResource(R.string.mail_save_all)) }
                    }
                    if (m.imagesBlocked) {
                        GcBanner(tone = GcTone.Info, icon = GcIcons.Photo) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.mail_images_blocked), style = MaterialTheme.typography.bodySmall)
                                if (me?.has(MMe.FEATURE_IMAGES) == true) {
                                    Row {
                                        TextButton(onClick = { viewModel.allowImages(false) }, enabled = !state.imagesBusy) { Text(stringResource(R.string.mail_images_load)) }
                                        TextButton(onClick = { viewModel.allowImages(true) }, enabled = !state.imagesBusy) { Text(stringResource(R.string.mail_images_always)) }
                                    }
                                }
                            }
                        }
                    }
                    if (m.bodyMissing) {
                        // the provider has not sent the body yet: the snippet below, the body comes with the live event
                        GcBanner(tone = GcTone.Info, icon = GcIcons.Clock) {
                            Text(stringResource(R.string.mail_body_loading), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            if (state.loading) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                TextButton(onClick = viewModel::load) { Text(stringResource(R.string.mail_retry)) }
                            }
                        }
                    }
                    if (state.picturesLoading) {
                        Text(stringResource(R.string.mail_pictures_loading), style = MaterialTheme.typography.bodySmall, color = extra.muted)
                    }
                    val html = m.html
                    if (html != null) {
                        val page = remember(html, m.attachments) { MailLogic.rewriteCid(html, m.id, m.attachments) }
                        MailHtmlView(page, loader = viewModel::picture, onLink = onLink, round = state.pictureRound, dark = dark)
                    } else {
                        SelectionContainer {
                            Text(m.text.orEmpty(), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f))
                        }
                    }
                    if (m.trackers > 0 || (!m.imagesBlocked && m.html?.contains("img/") == true)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(GcIcons.Shield, contentDescription = null, tint = extra.muted, modifier = Modifier.size(14.dp))
                            Text(
                                listOfNotNull(
                                    m.trackers.takeIf { it > 0 }?.let { context.resources.getQuantityString(R.plurals.mail_trackers, it, it) },
                                    stringResource(R.string.mail_images_via).takeIf { !m.imagesBlocked && m.html?.contains("img/") == true },
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = extra.muted,
                            )
                        }
                    }
                    // the mails after this one (the newest at the bottom)
                    if (thread && state.threadOpen) newer.forEach { threadMail(it) }
                }
                BottomBar(
                    replies = (state.ai as? MailAiState.Ready)?.ai?.replies.orEmpty(),
                    replyAll = (m.summary.to.size + m.cc.size) > 1,
                    onReply = { text -> nav.compose("reply", text) },
                    onReplyAll = { nav.compose("replyAll", null) },
                    onForward = { nav.compose("forward", null) },
                )
            }
        }
    }

    if (move && m != null) {
        FolderPicker(
            folders = folders[m.summary.accountId].orEmpty(),
            current = m.summary.folderId,
            onPick = { f -> move = false; viewModel.act("move", f.id) },
            onDismiss = { move = false },
        )
    }
    driveFor?.let { (a, remember) ->
        com.gatecontrol.android.ui.drive.DriveFolderChooser(
            onPicked = { folderId ->
                driveFor = null
                viewModel.saveToDrive(a, folderId, remember)
            },
            onDismiss = { driveFor = null },
        )
    }
}

@Composable
private fun Subject(m: MMessage) {
    Text(
        m.summary.subject.ifEmpty { stringResource(R.string.mail_no_subject) },
        style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.semantics { heading() },
    )
}

/** The sender (a tap: their page), the account and the time (a tap: the details). */
@Composable
private fun SenderLine(m: MMessage, accountLabel: String?, color: androidx.compose.ui.graphics.Color, onSender: () -> Unit) {
    val extra = GateControlTheme.extraColors
    var details by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier
                    .weight(1f, fill = false)
                    .heightIn(min = 32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button, onClickLabel = stringResource(R.string.mail_sender_show), onClick = onSender),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MailDot(color)
                Text(m.summary.from.label.ifEmpty { stringResource(R.string.mail_unknown_sender) }, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(
                Modifier.heightIn(min = 32.dp).clickable(role = Role.Button, onClickLabel = stringResource(R.string.mail_details)) { details = !details },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    listOfNotNull(accountLabel?.let { stringResource(R.string.mail_to_account, it) }, mailTime(m.summary.date)).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = extra.muted,
                    maxLines = 1,
                )
                Icon(if (details) GcIcons.ChevronUp else GcIcons.ChevronDown, contentDescription = null, tint = extra.muted, modifier = Modifier.size(14.dp))
            }
        }
        if (details) {
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    DetailLine(stringResource(R.string.mail_from), listOf(m.summary.from))
                    DetailLine(stringResource(R.string.mail_to), m.summary.to)
                    if (m.cc.isNotEmpty()) DetailLine(stringResource(R.string.mail_cc), m.cc)
                    if (m.replyTo.isNotEmpty()) DetailLine(stringResource(R.string.mail_reply_to), m.replyTo)
                    Text(mailDateTime(m.summary.date), style = MaterialTheme.typography.bodySmall, color = extra.muted)
                }
            }
        }
    }
}

@Composable
private fun DetailLine(label: String, list: List<MAddress>) {
    if (list.isEmpty()) return
    Text(
        "$label: " + list.joinToString(", ") { a -> a.name?.let { "$it <${a.email}>" } ?: a.email },
        style = MaterialTheme.typography.bodySmall,
        color = GateControlTheme.extraColors.muted,
    )
}

/** "Unterhaltung · n Mails" with its senders: a tap shows all its mails (or folds them again). */
@Composable
private fun ThreadLine(count: Int, names: List<String>, open: Boolean, loading: Boolean, onToggle: () -> Unit) {
    val extra = GateControlTheme.extraColors
    val state = stringResource(if (open) R.string.mail_thread_shown else R.string.mail_thread_hidden)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(role = Role.Button, onClickLabel = stringResource(if (open) R.string.mail_thread_hide else R.string.mail_thread_show), onClick = onToggle)
            .semantics { stateDescription = state }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(pluralStringResource(R.plurals.mail_thread, count, count), style = MaterialTheme.typography.labelMedium, color = extra.muted)
            if (names.isNotEmpty()) {
                Text(names.joinToString(", "), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (loading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        Icon(if (open) GcIcons.ChevronUp else GcIcons.ChevronDown, contentDescription = null, tint = extra.muted, modifier = Modifier.size(18.dp))
    }
}

/**
 * Another mail of the conversation: sender, time and preview; a tap unfolds it (its body, "Mail öffnen" for the
 * attachments and answering).
 */
@Composable
private fun ThreadMail(
    t: MMessage,
    color: androidx.compose.ui.graphics.Color,
    unfolded: Boolean,
    onFold: () -> Unit,
    onOpen: () -> Unit,
    loader: suspend (String) -> Pair<ByteArray, String>?,
    onLink: (String) -> Unit,
    dark: Boolean,
) {
    val extra = GateControlTheme.extraColors
    val s = t.summary
    val state = stringResource(if (unfolded) R.string.mail_thread_shown else R.string.mail_thread_hidden)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surface)) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable(role = Role.Button, onClick = onFold)
                .semantics { stateDescription = state }
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SenderAvatar(s.from.name, s.from.email, color, size = 32.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        s.from.label.ifEmpty { stringResource(R.string.mail_unknown_sender) },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (s.unread) FontWeight.Bold else FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(mailTime(s.date), style = MaterialTheme.typography.bodySmall, color = extra.muted)
                }
                if (!unfolded) {
                    val preview = s.snippet.ifBlank { s.subject }
                    if (preview.isNotBlank()) Text(preview, style = MaterialTheme.typography.bodySmall, color = extra.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (unfolded) {
            Column(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val html = t.html
                if (html != null) {
                    val page = remember(html, t.attachments) { MailLogic.rewriteCid(html, t.id, t.attachments) }
                    MailHtmlView(page, loader = loader, onLink = onLink, dark = dark)
                } else {
                    SelectionContainer {
                        Text(t.text.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f), modifier = Modifier.padding(horizontal = 6.dp))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (t.imagesBlocked || t.attachments.any { !it.inline }) {
                        Icon(if (t.imagesBlocked) GcIcons.Photo else GcIcons.Paperclip, contentDescription = null, tint = extra.muted, modifier = Modifier.padding(start = 6.dp).size(14.dp))
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onOpen) { Text(stringResource(R.string.mail_thread_open)) }
                }
            }
        }
    }
}

/** "Kurz gesagt" with the to-dos as chips (mockup: card with the sparkle). */
@Composable
private fun AiCard(ai: MailAiState, me: MMe?, onRetry: () -> Unit, onFamily: (Int) -> Unit, onCalendar: (MTodo) -> Unit) {
    if (ai is MailAiState.Off) return
    val extra = GateControlTheme.extraColors
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surface).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(MailIcons.Sparkle, contentDescription = null, tint = extra.accentText, modifier = Modifier.size(14.dp))
            Text(stringResource(R.string.mail_ai_short), style = MaterialTheme.typography.labelMedium, color = extra.accentText, fontWeight = FontWeight.SemiBold)
        }
        when (ai) {
            MailAiState.Loading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.mail_ai_loading), style = MaterialTheme.typography.bodySmall, color = extra.muted)
            }
            is MailAiState.Error -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(mailText(mailContext(), ai.code), style = MaterialTheme.typography.bodySmall, color = extra.muted, modifier = Modifier.weight(1f))
                if (ai.code != "ai_unavailable") TextButton(onClick = onRetry) { Text(stringResource(R.string.mail_retry)) }
            }
            is MailAiState.Ready -> AiContent(ai.ai, family = me?.has(MMe.FEATURE_FAMILY_CALENDAR) == true, onFamily = onFamily, onCalendar = onCalendar)
            MailAiState.Off -> Unit
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun AiContent(ai: MAi, family: Boolean, onFamily: (Int) -> Unit, onCalendar: (MTodo) -> Unit) {
    ai.summary?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
    if (ai.summary == null && ai.todos.isEmpty()) {
        Text(stringResource(R.string.mail_ai_nothing), style = MaterialTheme.typography.bodySmall, color = GateControlTheme.extraColors.muted)
    }
    if (ai.todos.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ai.todos.forEachIndexed { i, todo -> TodoChip(todo, family = family, onFamily = { onFamily(i) }, onCalendar = { onCalendar(todo) }) }
        }
    }
}

@Composable
private fun TodoChip(todo: MTodo, family: Boolean, onFamily: () -> Unit, onCalendar: () -> Unit) {
    val extra = GateControlTheme.extraColors
    var menu by remember { mutableStateOf(false) }
    val calendar = MailLogic.calendarTodo(todo)
    val icon = when (todo.kind) {
        MTodo.DEADLINE -> GcIcons.Bell
        MTodo.EVENT -> GcIcons.Calendar
        MTodo.AMOUNT -> GcIcons.Wallet
        MTodo.QUESTION -> GcIcons.Info
        else -> GcIcons.Check
    }
    Box {
        Row(
            Modifier
                .heightIn(min = 36.dp)
                .clip(RoundedCornerShape(999.dp))
                .border(1.dp, extra.border2, RoundedCornerShape(999.dp))
                .then(if (calendar) Modifier.clickable(role = Role.Button) { menu = true } else Modifier)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp))
            Text(todo.text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (family) DropdownMenuItem(text = { Text(stringResource(R.string.mail_todo_family)) }, onClick = { menu = false; onFamily() })
            DropdownMenuItem(text = { Text(stringResource(R.string.mail_todo_calendar)) }, onClick = { menu = false; onCalendar() })
        }
    }
}

/** An attachment: open it; with Drive its suggestion, "In Drive speichern", "Anderer Ordner" and "künftig automatisch". */
@Composable
private fun AttachmentCard(
    a: MailAttachmentUi,
    sender: String,
    drive: Boolean,
    onOpen: () -> Unit,
    onSave: (Boolean) -> Unit,
    onOther: (Boolean) -> Unit,
) {
    val extra = GateControlTheme.extraColors
    var remember by remember(a.attachment.id) { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surface).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = stringResource(R.string.mail_open_attachment), onClick = onOpen),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FileBadge(a.attachment.name, a.attachment.mime)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(a.attachment.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    if (a.loading) stringResource(R.string.mail_attachment_loading) else mailSize(a.attachment.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = extra.muted,
                )
            }
            if (a.busy || a.loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        }
        if (!drive) return@Column
        val saved = a.savedTo
        when {
            saved != null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(GcIcons.Check, contentDescription = null, tint = extra.accentText, modifier = Modifier.size(16.dp))
                Text(stringResource(R.string.mail_saved_in, saved.where), style = MaterialTheme.typography.bodySmall, color = extra.accentText)
            }
            else -> {
                val s = a.suggestion
                if (a.duplicate) Text(stringResource(R.string.mail_drive_duplicate), style = MaterialTheme.typography.bodySmall, color = extra.muted)
                if (s != null) {
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.background).padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(stringResource(R.string.mail_drive_save_as), style = MaterialTheme.typography.bodySmall, color = extra.muted)
                        Text(s.name ?: a.attachment.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            listOfNotNull(
                                (s.path ?: s.where)?.let { stringResource(R.string.mail_drive_in, it) },
                                reasonText(s.reason, s.confidence),
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = extra.muted,
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (s != null) {
                        com.gatecontrol.android.ui.components.GcPrimaryButton(
                            stringResource(R.string.mail_drive_save),
                            onClick = { onSave(remember) },
                            enabled = !a.busy,
                            modifier = Modifier.weight(1f),
                            minHeight = 44.dp,
                        )
                        com.gatecontrol.android.ui.components.GcOutlineButton(
                            stringResource(R.string.mail_drive_other),
                            onClick = { onOther(remember) },
                            enabled = !a.busy,
                            fillWidth = false,
                            minHeight = 44.dp,
                        )
                    } else {
                        com.gatecontrol.android.ui.components.GcOutlineButton(
                            stringResource(R.string.mail_drive_save),
                            onClick = { onOther(remember) },
                            enabled = !a.busy,
                            minHeight = 44.dp,
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 40.dp).toggleable(value = remember, role = Role.Checkbox) { remember = it },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Checkbox(checked = remember, onCheckedChange = null)
                    Text(stringResource(R.string.mail_drive_remember, sender), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/** Why Drive suggests the folder ("wie die letzten Rechnungen", "72 % sicher"). */
@Composable
internal fun reasonText(reason: String, confidence: Double): String = when (reason) {
    "rule" -> stringResource(R.string.mail_reason_rule)
    "learned" -> stringResource(R.string.mail_reason_learned)
    "kind" -> stringResource(R.string.mail_reason_kind)
    else -> stringResource(R.string.mail_reason_sure, MailLogic.percent(confidence))
}

/** Answer suggestions of the AI, "Antworten", "Allen antworten" and "Weiterleiten". */
@Composable
private fun BottomBar(replies: List<com.gatecontrol.android.mail.MReply>, replyAll: Boolean, onReply: (String?) -> Unit, onReplyAll: () -> Unit, onForward: () -> Unit) {
    val extra = GateControlTheme.extraColors
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .navigationBarsPadding()
            .imePadding()
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (replies.isNotEmpty()) {
            Text(stringResource(R.string.mail_ai_replies), style = MaterialTheme.typography.bodySmall, color = extra.muted)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                replies.forEach { r ->
                    Box(
                        Modifier
                            .heightIn(min = 40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(extra.panel2)
                            .clickable(role = Role.Button) { onReply(r.text) }
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(r.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            com.gatecontrol.android.ui.components.GcOutlineButton(stringResource(R.string.mail_reply), onClick = { onReply(null) }, modifier = Modifier.weight(1f), minHeight = 44.dp, icon = MailIcons.Reply)
            if (replyAll) {
                com.gatecontrol.android.ui.components.GcOutlineButton(stringResource(R.string.mail_reply_all), onClick = onReplyAll, modifier = Modifier.weight(1f), minHeight = 44.dp)
            }
            com.gatecontrol.android.ui.components.GcOutlineButton(stringResource(R.string.mail_forward), onClick = onForward, modifier = Modifier.weight(1f), minHeight = 44.dp, icon = MailIcons.Forward)
        }
    }
}
