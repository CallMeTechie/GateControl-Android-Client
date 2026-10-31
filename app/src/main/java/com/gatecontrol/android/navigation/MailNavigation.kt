package com.gatecontrol.android.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.gatecontrol.android.ui.mail.MailAccountScreen
import com.gatecontrol.android.ui.mail.MailAddAccountScreen
import com.gatecontrol.android.ui.mail.MailComposeScreen
import com.gatecontrol.android.ui.mail.MailDrawerFoot
import com.gatecontrol.android.ui.mail.MailFilingScreen
import com.gatecontrol.android.ui.mail.MailHomeScreen
import com.gatecontrol.android.ui.mail.MailNav
import com.gatecontrol.android.ui.mail.MailOutboxScreen
import com.gatecontrol.android.ui.mail.MailReadNav
import com.gatecontrol.android.ui.mail.MailReadScreen
import com.gatecontrol.android.ui.mail.MailSearchScreen
import com.gatecontrol.android.ui.mail.MailSenderNav
import com.gatecontrol.android.ui.mail.MailSenderScreen
import com.gatecontrol.android.ui.mail.MailSettingsScreen
import com.gatecontrol.android.ui.mail.calendar.EventNav
import com.gatecontrol.android.ui.mail.calendar.InviteNav
import com.gatecontrol.android.ui.mail.calendar.MailEventEditScreen
import com.gatecontrol.android.ui.mail.calendar.MailEventScreen
import com.gatecontrol.android.ui.mail.calendar.MailInvitationScreen
import com.gatecontrol.android.ui.mail.contacts.ContactNav
import com.gatecontrol.android.ui.mail.contacts.MailContactEditScreen
import com.gatecontrol.android.ui.mail.contacts.MailContactScreen
import com.gatecontrol.android.ui.mail.contacts.MailDuplicatesScreen
import com.gatecontrol.android.ui.mail.contacts.MailGroupScreen

/** Mail's ways between its screens (the tab, a page beside Start and the routes on top share them). */
private class MailNavigation(private val navController: NavHostController) {
    val go: (String) -> Unit = { route -> navController.navigate(route) { launchSingleTop = true } }

    val nav = MailNav(
        open = { go(Screen.MailMessage.create(it)) },
        compose = { account -> go(Screen.MailCompose.create(account = account)) },
        draft = { go(Screen.MailCompose.create(draft = it)) },
        search = { go(Screen.MailSearch.route) },
        filing = { go(Screen.MailFiling.route) },
        addAccount = {
            // an account is added in the settings: back from adding leads there
            go(Screen.MailSettings.route)
            go(Screen.MailAddAccount.create())
        },
        settings = { go(Screen.MailSettings.route) },
        account = { go(Screen.MailAccount.create(it)) },
        outbox = { go(Screen.MailOutbox.route) },
        event = { id, occ -> go(Screen.MailEvent.create(id, occ)) },
        newEvent = { start, end, allDay -> go(Screen.MailEventEdit.create(start = start, end = end, allDay = allDay)) },
        contact = { go(Screen.MailContact.create(it)) },
        newContact = { go(Screen.MailContactEdit.create()) },
        group = { go(Screen.MailGroup.create(it)) },
        duplicates = { go(Screen.MailContactDuplicates.route) },
        relogin = { go(Screen.MailAddAccount.create(it)) },
    )
}

/**
 * The Mail tab's screen (as the tab or as a page beside Start); [area] the one it starts with: the mail, or the
 * calendar / the contacts as their own page beside Start (no tab of their own).
 */
@Composable
internal fun MailHome(navController: NavHostController, area: String = MailDrawerFoot.MAIL) {
    val nav = androidx.compose.runtime.remember(navController) { MailNavigation(navController) }
    GcContentWidth { MailHomeScreen(nav.nav, initialArea = area) }
}

/**
 * Mail (plugin gatecontrol-mail, contract D): the tab, a mail, writing, the search, "Zum Ablegen", the outbox,
 * adding an account and the settings, each a route of its own so the tab bar shows on the tab only.
 */
internal fun NavGraphBuilder.mailRoutes(
    navController: NavHostController,
    navigateToTab: (String) -> Unit,
    home: @Composable () -> Unit,
) {
    val back: () -> Unit = { if (!navController.popBackStack()) navigateToTab(Screen.Mail.route) }
    val nav = MailNavigation(navController)
    val go = nav.go
    val optional: (String) -> androidx.navigation.NamedNavArgument = { name ->
        navArgument(name) {
            type = NavType.StringType
            nullable = true
            defaultValue = null
        }
    }
    composable(Screen.Mail.route) { home() }
    composable(Screen.MailMessage.route, arguments = listOf(navArgument(Screen.MailMessage.ARG_ID) { type = NavType.StringType })) { entry ->
        val id = entry.arguments?.getString(Screen.MailMessage.ARG_ID)?.toLongOrNull()
        GcContentWidth {
            MailReadScreen(
                MailReadNav(
                    back = back,
                    compose = { mode, reply -> if (id != null) go(Screen.MailCompose.create(ref = id, mode = mode, reply = reply)) },
                    mailTo = { to -> go(Screen.MailCompose.create(to = to)) },
                    open = { other -> navController.navigate(Screen.MailMessage.create(other)) },
                    sender = { email, name, account -> go(Screen.MailSender.create(email, name, account)) },
                    saveContact = { email, name, message -> go(Screen.MailContactEdit.create(message = message, email = email, name = name)) },
                    contact = nav.nav.contact,
                    event = { nav.nav.event(it, null) },
                    relogin = nav.nav.relogin,
                ),
            )
        }
    }
    composable(Screen.MailSender.route, arguments = listOf(optional(Screen.MailSender.ARG_EMAIL), optional(Screen.MailSender.ARG_NAME), optional(Screen.MailSender.ARG_ACCOUNT))) {
        GcContentWidth {
            MailSenderScreen(
                MailSenderNav(
                    back = back,
                    write = { to, account -> go(Screen.MailCompose.create(account = account, to = to)) },
                    open = { id -> navController.navigate(Screen.MailMessage.create(id)) },
                    contact = { id -> go(Screen.MailContact.create(id)) },
                    saveContact = { email, name -> go(Screen.MailContactEdit.create(email = email, name = name)) },
                ),
            )
        }
    }
    composable(
        Screen.MailCompose.route,
        arguments = listOf(
            optional(Screen.MailCompose.ARG_DRAFT),
            optional(Screen.MailCompose.ARG_REF),
            optional(Screen.MailCompose.ARG_MODE),
            optional(Screen.MailCompose.ARG_ACCOUNT),
            optional(Screen.MailCompose.ARG_TO),
            optional(Screen.MailCompose.ARG_REPLY),
        ),
    ) {
        GcContentWidth { MailComposeScreen(onClose = back) }
    }
    composable(Screen.MailSearch.route) {
        GcContentWidth { MailSearchScreen(onBack = back, onOpen = nav.nav.open) }
    }
    composable(Screen.MailFiling.route) {
        GcContentWidth { MailFilingScreen(onBack = back) }
    }
    composable(Screen.MailOutbox.route) {
        GcContentWidth {
            MailOutboxScreen(onBack = back, onDraft = { draft -> navController.navigate(Screen.MailCompose.create(draft = draft)) })
        }
    }
    composable(Screen.MailAddAccount.route, arguments = listOf(optional(Screen.MailAddAccount.ARG_ACCOUNT))) {
        GcContentWidth { MailAddAccountScreen(onBack = back, onDone = { back() }) }
    }
    composable(Screen.MailSettings.route) {
        GcContentWidth {
            MailSettingsScreen(
                onBack = back,
                onAccount = { go(Screen.MailAccount.create(it)) },
                onAdd = { go(Screen.MailAddAccount.create()) },
            )
        }
    }
    composable(Screen.MailAccount.route, arguments = listOf(navArgument(Screen.MailAccount.ARG_ID) { type = NavType.StringType })) {
        GcContentWidth { MailAccountScreen(onBack = back, onRelogin = { go(Screen.MailAddAccount.create(it)) }) }
    }
    // ─── calendar and contacts (since Mail 1.2.0): routes of their own, no tab bar ───
    val required: (String) -> androidx.navigation.NamedNavArgument = { name -> navArgument(name) { type = NavType.StringType } }
    val saveContact: (String, String?) -> Unit = { email, name -> go(Screen.MailContactEdit.create(email = email, name = name)) }
    val mailTo: (String) -> Unit = { to -> go(Screen.MailCompose.create(to = to)) }
    composable(Screen.MailEvent.route, arguments = listOf(required(Screen.MailEvent.ARG_ID), optional(Screen.MailEvent.ARG_OCC))) {
        GcContentWidth {
            MailEventScreen(
                EventNav(
                    back = back,
                    edit = { id, occ -> go(Screen.MailEventEdit.create(id = id, occurrence = occ)) },
                    contact = nav.nav.contact,
                    saveContact = saveContact,
                    mailTo = mailTo,
                ),
            )
        }
    }
    composable(
        Screen.MailEventEdit.route,
        arguments = listOf(
            optional(Screen.MailEventEdit.ARG_ID),
            optional(Screen.MailEventEdit.ARG_OCC),
            optional(Screen.MailEventEdit.ARG_SCOPE),
            optional(Screen.MailEventEdit.ARG_CAL),
            optional(Screen.MailEventEdit.ARG_START),
            optional(Screen.MailEventEdit.ARG_END),
            optional(Screen.MailEventEdit.ARG_ALL_DAY),
            optional(Screen.MailEventEdit.ARG_TO),
        ),
    ) {
        GcContentWidth { MailEventEditScreen(onClose = back) }
    }
    composable(Screen.MailInvitation.route, arguments = listOf(required(Screen.MailInvitation.ARG_ID))) {
        GcContentWidth {
            MailInvitationScreen(onBack = back, nav = InviteNav(event = { nav.nav.event(it, null) }, contact = nav.nav.contact, saveContact = saveContact))
        }
    }
    composable(Screen.MailContact.route, arguments = listOf(required(Screen.MailContact.ARG_ID))) {
        GcContentWidth {
            MailContactScreen(
                ContactNav(
                    back = back,
                    edit = { go(Screen.MailContactEdit.create(id = it)) },
                    mailTo = mailTo,
                    openMail = nav.nav.open,
                    sender = { email, name -> go(Screen.MailSender.create(email, name)) },
                    event = nav.nav.event,
                ),
            )
        }
    }
    composable(
        Screen.MailContactEdit.route,
        arguments = listOf(
            optional(Screen.MailContactEdit.ARG_ID),
            optional(Screen.MailContactEdit.ARG_MESSAGE),
            optional(Screen.MailContactEdit.ARG_EMAIL),
            optional(Screen.MailContactEdit.ARG_NAME),
            optional(Screen.MailContactEdit.ARG_GROUP),
        ),
    ) {
        GcContentWidth {
            MailContactEditScreen(
                onClose = back,
                // a new contact opens after saving; a change goes back to it
                onSaved = { id ->
                    val editing = it.arguments?.getString(Screen.MailContactEdit.ARG_ID) != null
                    navController.popBackStack()
                    if (!editing) go(Screen.MailContact.create(id))
                },
            )
        }
    }
    composable(Screen.MailContactDuplicates.route) {
        GcContentWidth { MailDuplicatesScreen(onBack = back, onContact = nav.nav.contact) }
    }
    composable(Screen.MailGroup.route, arguments = listOf(required(Screen.MailGroup.ARG_ID))) {
        GcContentWidth { MailGroupScreen(onBack = back, onContact = nav.nav.contact, mailTo = mailTo) }
    }
}
