package com.gatecontrol.android.navigation

sealed class Screen(val route: String) {
    data object Setup : Screen("setup")
    data object Vpn : Screen("vpn")
    /**
     * Former "Remote" tab (until 1.35): only a redirect to the Remote
     * segment of the Netzwerk tab (MainTabs.redirect).
     */
    data object Rdp : Screen("rdp")
    /** Services, Pi-hole and Remote; `tab` picks the initial segment (NetworkTabs). */
    data object Network : Screen("network?tab={tab}") {
        const val ARG_TAB = "tab"
        const val BASE = "network"
        fun create(tab: String? = null): String = if (tab == null) BASE else "$BASE?tab=$tab"
    }
    /**
     * Settings: no tab since 1.35, a normal destination with back (gear on
     * the profile). `pane` picks the page shown next to the list on wide
     * screens (SettingsPane.key).
     */
    data object Settings : Screen("settings?pane={pane}") {
        const val ARG_PANE = "pane"
        const val BASE = "settings"
        fun create(pane: String? = null): String = if (pane == null) BASE else "$BASE?pane=$pane"
    }
    /** Notification center: inbox ("Mitteilungen") and one message; `select` opens one beside the list on wide screens. */
    data object Inbox : Screen("inbox?select={select}") {
        const val ARG_SELECT = "select"
        const val BASE = "inbox"
        fun create(select: Long? = null): String = if (select == null) BASE else "$BASE?select=$select"
    }
    data object NotificationDetail : Screen("inbox/{id}") {
        const val ARG_ID = "id"
        fun create(id: Long): String = "inbox/$id"
    }
    /**
     * Tab of the Familie plugin (only when the server lists it with an app
     * tab). `dest` (FamilieDest key) is shown beside the start screen on
     * wide windows.
     */
    data object Familie : Screen("familie?dest={dest}") {
        const val ARG_DEST = "dest"
        const val BASE = "familie"
        fun create(dest: String? = null): String = if (dest == null) BASE else "$BASE?dest=" + android.net.Uri.encode(dest)
    }
    /** A screen of the Familie tab on its own (phones). */
    data object FamiliePage : Screen("familie/page?dest={dest}") {
        const val ARG_DEST = "dest"
        fun create(dest: String): String = "familie/page?dest=" + android.net.Uri.encode(dest)
    }
    /**
     * Door screen "Tür & Kamera" of the door plugin: a normal destination,
     * never a tab, opened by a door push or a long press on a door quick
     * action (docs/DOOR.md). `dest` (DoorDest key) is shown beside its list
     * on wide windows.
     */
    data object Door : Screen("door?dest={dest}") {
        const val ARG_DEST = "dest"
        const val BASE = "door"
        fun create(dest: String? = null): String = if (dest == null) BASE else "$BASE?dest=" + android.net.Uri.encode(dest)
    }
    /** A screen on top of the door screen (phones): history, a ring's detail. */
    data object DoorPage : Screen("door/page?dest={dest}") {
        const val ARG_DEST = "dest"
        fun create(dest: String): String = "door/page?dest=" + android.net.Uri.encode(dest)
    }
    /**
     * Logbook ("Fahrtenbuch") of the vehicles plugin: a normal destination,
     * never a tab, opened by the logbook quick action (`opens`, docs/LOGBOOK.md).
     * `vehicle` = vehicle id, none = the first own vehicle. Wide windows:
     * the map or a trip beside the list.
     */
    data object Logbook : Screen("logbook?vehicle={vehicle}") {
        const val ARG_VEHICLE = "vehicle"
        const val BASE = "logbook"
        fun create(vehicleId: Long? = null): String = if (vehicleId == null) BASE else "$BASE?vehicle=$vehicleId"
    }
    /** All trips of a month on the map (phones). */
    data object LogbookMap : Screen("logbook/map?vehicle={vehicle}&month={month}") {
        const val ARG_VEHICLE = "vehicle"
        const val ARG_MONTH = "month"
        fun create(vehicleId: Long?, month: String?): String =
            "logbook/map?" + listOfNotNull(vehicleId?.let { "vehicle=$it" }, month?.let { "month=$it" }).joinToString("&")
    }
    /** One trip with its map (phones). */
    data object LogbookTrip : Screen("logbook/trip/{id}?vehicle={vehicle}&month={month}") {
        const val ARG_ID = "id"
        const val ARG_VEHICLE = "vehicle"
        const val ARG_MONTH = "month"
        fun create(id: Long, vehicleId: Long?, month: String?): String =
            "logbook/trip/$id" + listOfNotNull(vehicleId?.let { "vehicle=$it" }, month?.let { "month=$it" })
                .takeIf { it.isNotEmpty() }?.joinToString("&", prefix = "?").orEmpty()
    }
    /**
     * Tab "Vault" (plugin gatecontrol-vault, only when the server lists it):
     * set up, unlock, the entries. Its pages are routes of their own (no tab bar).
     */
    data object Vault : Screen("vault")
    data object VaultSetup : Screen("vault/setup")
    data object VaultRecover : Screen("vault/recover")
    data object VaultEntry : Screen("vault/entry/{id}") {
        const val ARG_ID = "id"
        fun create(id: String) = "vault/entry/$id"
    }
    /** New entry (`type`, optional `vault`) or edit one (`id`). */
    data object VaultEdit : Screen("vault/edit?id={id}&type={type}&vault={vault}") {
        const val ARG_ID = "id"
        const val ARG_TYPE = "type"
        const val ARG_VAULT = "vault"
        fun create(id: String? = null, type: String? = null, vault: String? = null) =
            "vault/edit?" + listOfNotNull(id?.let { "id=$it" }, type?.let { "type=$it" }, vault?.let { "vault=$it" }).joinToString("&")
    }
    data object VaultHistory : Screen("vault/history/{id}") {
        const val ARG_ID = "id"
        fun create(id: String) = "vault/history/$id"
    }
    data object VaultVaults : Screen("vault/vaults")
    data object VaultShare : Screen("vault/share/{id}") {
        const val ARG_ID = "id"
        fun create(id: String) = "vault/share/$id"
    }
    data object VaultTrash : Screen("vault/trash")
    data object VaultEmergency : Screen("vault/emergency")
    data object VaultEmergencyView : Screen("vault/emergency/{id}") {
        const val ARG_ID = "id"
        fun create(id: String) = "vault/emergency/$id"
    }
    data object VaultImport : Screen("vault/import")
    data object VaultSettings : Screen("vault/settings")
    /**
     * Tab "Mail" (plugin gatecontrol-mail, only when the server lists it with its tab): the inboxes. A mail, writing,
     * the search, "Zum Ablegen", adding an account and the settings are routes of their own (no tab bar).
     */
    data object Mail : Screen("mail")
    data object MailMessage : Screen("mail/m/{id}") {
        const val ARG_ID = "id"
        fun create(id: Long) = "mail/m/$id"
    }
    /** Writing: a new mail (`account`, `to`), an answer or forward of `ref` (`mode`), or the draft `draft`. */
    data object MailCompose : Screen("mail/compose?draft={draft}&ref={ref}&mode={mode}&account={account}&to={to}&reply={reply}") {
        const val ARG_DRAFT = "draft"
        const val ARG_REF = "ref"
        const val ARG_MODE = "mode"
        const val ARG_ACCOUNT = "account"
        const val ARG_TO = "to"
        const val ARG_REPLY = "reply"
        fun create(draft: Long? = null, ref: Long? = null, mode: String? = null, account: Long? = null, to: String? = null, reply: String? = null): String {
            // %20, not "+": the navigation decodes arguments like a URI path
            fun enc(v: String) = java.net.URLEncoder.encode(v, "UTF-8").replace("+", "%20")
            val args = listOfNotNull(
                draft?.let { "draft=$it" },
                ref?.let { "ref=$it" },
                mode?.let { "mode=" + enc(it) },
                account?.let { "account=$it" },
                to?.let { "to=" + enc(it) },
                reply?.let { "reply=" + enc(it.take(2000)) },
            )
            return "mail/compose" + if (args.isEmpty()) "" else "?" + args.joinToString("&")
        }
    }
    data object MailSearch : Screen("mail/search")
    /** A sender's page: their mails in the account `account`. */
    data object MailSender : Screen("mail/sender?email={email}&name={name}&account={account}") {
        const val ARG_EMAIL = "email"
        const val ARG_NAME = "name"
        const val ARG_ACCOUNT = "account"
        fun create(email: String, name: String? = null, account: Long? = null): String {
            fun enc(v: String) = java.net.URLEncoder.encode(v, "UTF-8").replace("+", "%20")
            val args = listOfNotNull("email=" + enc(email), name?.takeIf { it.isNotBlank() }?.let { "name=" + enc(it.take(200)) }, account?.let { "account=$it" })
            return "mail/sender?" + args.joinToString("&")
        }
    }
    /** "Zum Ablegen": attachments the AI was not sure about. */
    data object MailFiling : Screen("mail/filing")
    /** "Konto hinzufügen"; `account` = sign in to that Microsoft account again. */
    data object MailAddAccount : Screen("mail/accounts/add?account={account}") {
        const val ARG_ACCOUNT = "account"
        fun create(account: Long? = null) = "mail/accounts/add" + (account?.let { "?account=$it" } ?: "")
    }
    /** "Mail-Einstellungen": the list settings and the accounts. */
    data object MailSettings : Screen("mail/settings")
    data object MailAccount : Screen("mail/accounts/{id}") {
        const val ARG_ID = "id"
        fun create(id: Long) = "mail/accounts/$id"
    }
    /** "Postausgang": sends planned or failed. */
    data object MailOutbox : Screen("mail/outbox")
    /** An event of the calendar (since Mail 1.2.0): the occurrence `occ` of a series, or the event itself. */
    data object MailEvent : Screen("mail/event/{id}?occ={occ}") {
        const val ARG_ID = "id"
        const val ARG_OCC = "occ"
        fun create(id: Long, occurrence: String? = null) = "mail/event/$id" + (occurrence?.let { "?occ=" + MailRoutes.enc(it) } ?: "")
    }
    /**
     * A new event (`cal`, `start`/`end` of the tapped time, `allDay`, `to`: attendees) or the event `id` to change
     * (`occ` + `scope`: only this occurrence or the whole series).
     */
    data object MailEventEdit : Screen("mail/event-edit?id={id}&occ={occ}&scope={scope}&cal={cal}&start={start}&end={end}&allDay={allDay}&to={to}") {
        const val ARG_ID = "id"
        const val ARG_OCC = "occ"
        const val ARG_SCOPE = "scope"
        const val ARG_CAL = "cal"
        const val ARG_START = "start"
        const val ARG_END = "end"
        const val ARG_ALL_DAY = "allDay"
        const val ARG_TO = "to"
        fun create(
            id: Long? = null,
            occurrence: String? = null,
            scope: String? = null,
            calendar: Long? = null,
            start: Long? = null,
            end: Long? = null,
            allDay: Boolean = false,
            to: String? = null,
        ): String = MailRoutes.build(
            "mail/event-edit",
            "id" to id?.toString(), "occ" to occurrence, "scope" to scope, "cal" to calendar?.toString(),
            "start" to start?.toString(), "end" to end?.toString(), "allDay" to (if (allDay) "1" else null), "to" to to,
        )
    }
    /** An invitation (a push of a GateControl person's invitation). */
    data object MailInvitation : Screen("mail/invitation/{id}") {
        const val ARG_ID = "id"
        fun create(id: Long) = "mail/invitation/$id"
    }
    /** A contact. */
    data object MailContact : Screen("mail/contact/{id}") {
        const val ARG_ID = "id"
        fun create(id: Long) = "mail/contact/$id"
    }
    /** A new contact, the contact `id` to change, or "Als Kontakt speichern" of `email` (from the mail `message`). */
    data object MailContactEdit : Screen("mail/contact-edit?id={id}&message={message}&email={email}&name={name}&group={group}") {
        const val ARG_ID = "id"
        const val ARG_MESSAGE = "message"
        const val ARG_EMAIL = "email"
        const val ARG_NAME = "name"
        const val ARG_GROUP = "group"
        fun create(id: Long? = null, message: Long? = null, email: String? = null, name: String? = null, group: Long? = null): String = MailRoutes.build(
            "mail/contact-edit",
            "id" to id?.toString(), "message" to message?.toString(), "email" to email, "name" to name?.take(200), "group" to group?.toString(),
        )
    }
    /** "Dubletten": contacts that may be one person. */
    data object MailContactDuplicates : Screen("mail/contacts/duplicates")
    /** A group of contacts: its members, "Mail an alle". */
    data object MailGroup : Screen("mail/group/{id}") {
        const val ARG_ID = "id"
        fun create(id: Long) = "mail/group/$id"
    }
    /**
     * Tab "Drive" (plugin gatecontrol-drive, only when the server lists it):
     * the areas and the newest files. A folder, the trash and the search are
     * routes of their own (no tab bar).
     */
    data object Drive : Screen("drive")
    data object DriveFolder : Screen("drive/folder/{id}") {
        const val ARG_ID = "id"
        fun create(id: Long) = "drive/folder/$id"
    }
    data object DriveTrash : Screen("drive/trash")
    data object DriveSearch : Screen("drive/search")
    data object DriveBackup : Screen("drive/backup")
    /** "Aufräumen" of the work photos (Drive 1.13): the AI's suggestions among the photos backed up. */
    data object DriveCleanup : Screen("drive/cleanup")
    /** "Drive-Einstellungen" (Drive 1.11): the gear of the Drive menu, the app settings' row "Drive". */
    data object DriveSettings : Screen("drive/settings")
    /** "Ordner sichern": phone folders backed up into Drive. */
    data object DriveFolders : Screen("drive/folders")
    /** The storage overview (Drive 1.7). */
    data object DriveStorage : Screen("drive/storage")
    /** One file: details, what the AI recognised, its deadlines (Drive 1.8). */
    data object DriveFile : Screen("drive/file/{id}") {
        const val ARG_ID = "id"
        fun create(id: Long) = "drive/file/$id"
    }
    /** "Fristen": the deadlines of my documents (Drive 1.8). */
    data object DriveDeadlines : Screen("drive/deadlines")
    /** Photos by what is in them (Drive 1.14, feature `photoSearch`). */
    data object DrivePhotoSearch : Screen("drive/photos/search")
    /** A folder of a vault (Drive 1.9); `requests=1` shows the approval requests at once (a request's push). */
    data object DriveVault : Screen("drive/vault/{vault}/{folder}?requests={requests}") {
        const val ARG_VAULT = "vault"
        const val ARG_FOLDER = "folder"
        const val ARG_REQUESTS = "requests"
        fun create(vault: String, folder: Long, requests: Boolean = false) = "drive/vault/$vault/$folder" + if (requests) "?requests=1" else ""
    }
    /** One photo album (Drive 1.7). */
    data object DriveAlbum : Screen("drive/album/{id}") {
        const val ARG_ID = "id"
        fun create(id: String) = "drive/album/$id"
    }
    /**
     * Tab "Chats" of the messenger plugin (only when the server lists it
     * with an app tab). `conv` is shown beside the list on wide windows;
     * `share` = pick a chat for what was shared from another app.
     */
    data object Chats : Screen("chats?conv={conv}&share={share}") {
        const val ARG_CONV = "conv"
        const val ARG_SHARE = "share"
        const val BASE = "chats"
        fun create(conv: Long? = null, share: Boolean = false): String = buildString {
            append(BASE)
            val args = listOfNotNull(conv?.let { "conv=$it" }, if (share) "share=true" else null)
            if (args.isNotEmpty()) append("?").append(args.joinToString("&"))
        }
    }
    /** One conversation on its own (phones). */
    data object ChatConversation : Screen("chats/c/{id}?share={share}") {
        const val ARG_ID = "id"
        const val ARG_SHARE = "share"
        fun create(id: Long, share: Boolean = false): String = "chats/c/$id" + if (share) "?share=true" else ""
    }
    /** "Neuer Chat" (`group` = "Neue Gruppe"). */
    data object ChatNew : Screen("chats/new?group={group}") {
        const val ARG_GROUP = "group"
        fun create(group: Boolean = false): String = if (group) "chats/new?group=true" else "chats/new"
    }
    /** Chat / group info. */
    data object ChatInfo : Screen("chats/info/{id}") {
        const val ARG_ID = "id"
        fun create(id: Long): String = "chats/info/$id"
    }
    /** Media overview of a chat: pictures and videos, files, voice messages. */
    data object ChatMedia : Screen("chats/media/{id}") {
        const val ARG_ID = "id"
        fun create(id: Long): String = "chats/media/$id"
    }
    /** Messenger 1.4: Chat-Einstellungen as a page; `page` a subpage (ChatSettingsPage). */
    data object ChatSettings : Screen("chats/settings?page={page}") {
        const val ARG_PAGE = "page"
        fun create(page: String? = null): String = if (page.isNullOrEmpty()) "chats/settings" else "chats/settings?page=$page"
    }
    /** Messenger 1.4: "Status erstellen" (`kind` text | image | video). */
    data object ChatStoryCompose : Screen("chats/story/new?kind={kind}") {
        const val ARG_KIND = "kind"
        fun create(kind: String? = null): String = if (kind == null) "chats/story/new" else "chats/story/new?kind=$kind"
    }
    /** Messenger 1.4: a person's stories (`user` 0 = the own). */
    data object ChatStoryView : Screen("chats/story/view/{user}") {
        const val ARG_USER = "user"
        fun create(userId: Long): String = "chats/story/view/$userId"
    }
    /** A chat picture full screen. */
    data object ChatImage : Screen("chats/image/{media}") {
        const val ARG_MEDIA = "media"
        fun create(mediaId: String): String = "chats/image/$mediaId"
    }
    data object SettingsNotifications : Screen("settings/notifications")
    /** "Wo Mitteilungen erscheinen" (core 1.191): from Benachrichtigungen and from the chat settings. */
    data object NotifyRoutes : Screen("settings/notifications/routes")
    /**
     * Own profile: picture and favourite colour (own avatar on the start
     * tab and in the Familie tab); its gear opens the settings.
     */
    data object Profile : Screen("settings/profile")
    /** Tunnel details (Settings → Verbindung → Verbindungsdetails). */
    data object ConnectionDetails : Screen("settings/connection")
    data object SettingsServer : Screen("settings/server")
    data object SplitTunnel : Screen("settings/split")
    data object TrustedNetworks : Screen("settings/trusted-networks")
    data object LocationSharing : Screen("settings/location")
    /** Diagnose & Problem melden (Settings › Hilfe, start screen on connection problems). */
    data object Diagnosis : Screen("settings/diagnosis")
    /**
     * "Schnellaktionen und Widgets"; `page` = the widget page beside Start
     * it was opened from (what is pinned there joins that page).
     */
    data object QuickActions : Screen("quick-actions?page={page}") {
        const val ARG_PAGE = "page"
        const val BASE = "quick-actions"
        fun create(page: String? = null): String = if (page == null) BASE else "$BASE?page=$page"
    }
    /** "Seiten anordnen": the pages beside Start (StartPages), from a page's "⋯" and the settings. */
    data object StartPagesArrange : Screen("start-pages")
    /** Einstellungen → "Leiste und Seiten": each function as tab or page beside Start. */
    data object SettingsPages : Screen("settings/pages")
    /**
     * New personal reminder for a device ("Erinnerung für dieses Gerät" in
     * a start-tab widget's settings): the reminder editor with the action
     * `<plugin>/<id>` chosen. Route only — the editor registers it.
     */
    data object ReminderNew : Screen("reminder/new?actionId={actionId}") {
        const val ARG_ACTION_ID = "actionId"
        fun create(actionId: String? = null): String =
            if (actionId == null) "reminder/new" else "reminder/new?actionId=" + android.net.Uri.encode(actionId)
    }
    /** Picker "Inhalte der Info-Karte" (pencil of the start tab's Info card). */
    data object InfoCardPicker : Screen("info-card")
    data object Logs : Screen("settings/logs")
    /** GateDrop "Geteilt" (Empfangen / Gesendet / Links); `tab` picks the list. */
    data object Shared : Screen("shared?tab={tab}") {
        const val ARG_TAB = "tab"
        const val BASE = "shared"
        fun create(tab: String? = null): String = if (tab == null) BASE else "$BASE?tab=$tab"
    }
    data object QrScanner : Screen("setup/qr")
    /** Setup screen opened from Settings to connect an existing install (scan starts right away). */
    data object Enroll : Screen("setup/enroll?link={link}") {
        const val ARG_LINK = "link"
        fun create(link: String? = null): String =
            if (link == null) "setup/enroll" else "setup/enroll?link=" + android.net.Uri.encode(link)
    }
}

/** Query arguments of Mail's routes (%20, not "+": the navigation decodes arguments like a URI path). */
internal object MailRoutes {
    fun enc(v: String): String = java.net.URLEncoder.encode(v, "UTF-8").replace("+", "%20")

    fun build(base: String, vararg args: Pair<String, String?>): String {
        val q = args.mapNotNull { (k, v) -> v?.let { "$k=" + enc(it) } }
        return base + if (q.isEmpty()) "" else "?" + q.joinToString("&")
    }
}
