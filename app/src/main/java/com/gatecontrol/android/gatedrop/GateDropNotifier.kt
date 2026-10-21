package com.gatecontrol.android.gatedrop

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.gatecontrol.android.R
import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.push.PushOpenActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * GateDrop's notifications: progress of a send / a receive (the
 * foreground notification of the workers), "<Absender> hat dir …
 * geschickt" with Öffnen / Speichern (text: Kopieren), the created link
 * and failures. Texts in the app language.
 */
@Singleton
class GateDropNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
) {
    companion object {
        const val CHANNEL_PROGRESS = "gatedrop_progress"
        const val CHANNEL_RECEIVED = "gatedrop_received"
        private const val GROUP = "push"
        const val TAB_RECEIVED = "received"
        const val TAB_SENT = "sent"
        const val TAB_LINKS = "links"
        private const val DONE_TIMEOUT_MS = 20_000L

        fun progressId(key: String): Int = 0x6D000000 or (key.hashCode() and 0x00FFFFFF)

        fun receivedId(key: String): Int = 0x6E000000 or (key.hashCode() and 0x00FFFFFF)

        /** Outcome of a send: not the progress id, which WorkManager takes away with its service. */
        fun resultId(key: String): Int = 0x6F000000 or (key.hashCode() and 0x00FFFFFF)

        /** "1,2 MB" style size. */
        fun size(bytes: Long, locale: Locale): String = when {
            bytes >= 1024L * 1024 * 1024 -> String.format(locale, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
            bytes >= 1024L * 1024 -> String.format(locale, "%.1f MB", bytes / (1024.0 * 1024))
            bytes >= 1024 -> String.format(locale, "%d KB", bytes / 1024)
            else -> String.format(locale, "%d B", bytes)
        }
    }

    private val nm: NotificationManager get() = context.getSystemService(NotificationManager::class.java)

    suspend fun localized(): Context {
        val lang = runCatching { settingsRepository.getLocale().first() }.getOrNull() ?: return context
        val config = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(lang)) }
        return context.createConfigurationContext(config)
    }

    private fun ensureChannels(ctx: Context) {
        if (nm.getNotificationChannelGroup(GROUP) == null) {
            nm.createNotificationChannelGroup(NotificationChannelGroup(GROUP, ctx.getString(R.string.push_channel_group)))
        }
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, ctx.getString(R.string.gatedrop_channel_progress), NotificationManager.IMPORTANCE_LOW).apply {
                group = GROUP
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RECEIVED, ctx.getString(R.string.gatedrop_channel_received), NotificationManager.IMPORTANCE_HIGH).apply {
                group = GROUP
            },
        )
    }

    private fun sharedTap(tab: String, messageId: Long? = null, slot: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            slot,
            PushOpenActivity.sharedIntent(context, tab, messageId).setData(Uri.parse("gatecontrol-gatedrop://shared/$tab/$slot")),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun receiver(action: String, key: String, slot: Int, extra: (Intent) -> Unit = {}): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            slot,
            Intent(context, GateDropActionReceiver::class.java).setAction(action)
                .setData(Uri.parse("gatecontrol-gatedrop://action/${Uri.encode(key)}/$slot"))
                .putExtra(GateDropActionReceiver.EXTRA_KEY, key)
                .also(extra),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun viewFile(uri: Uri, mime: String, slot: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            slot,
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun viewUrl(url: String, slot: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            slot,
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun shareText(ctx: Context, text: String, slot: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            slot,
            Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), ctx.getString(R.string.gatedrop_link_share))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    // ─── progress (foreground) ──────────────────────────────────────

    /** Ongoing progress of a send ([sending]) or a receive; [percent] null = not known yet. */
    suspend fun progress(key: String, sending: Boolean, title: String?, percent: Int?, cancel: PendingIntent?): Notification {
        val ctx = localized()
        ensureChannels(ctx)
        val head = ctx.getString(if (sending) R.string.gatedrop_progress_sending else R.string.gatedrop_progress_receiving)
        return NotificationCompat.Builder(ctx, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(head)
            .apply { title?.let { setContentText(it) } }
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(100, percent ?: 0, percent == null)
            .setContentIntent(sharedTap(if (sending) TAB_SENT else TAB_RECEIVED, slot = 10))
            .apply { cancel?.let { addAction(0, ctx.getString(R.string.gatedrop_cancel), it) } }
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    fun cancel(id: Int) {
        NotificationManagerCompat.from(context).cancel(id)
    }

    // ─── sending ────────────────────────────────────────────────────

    /** Sent: a short confirmation that goes away by itself. */
    suspend fun sent(key: String, names: String?) {
        val ctx = localized()
        ensureChannels(ctx)
        post(
            resultId(key),
            NotificationCompat.Builder(ctx, CHANNEL_PROGRESS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(ctx.getString(R.string.gatedrop_sent_title))
                .apply { names?.let { setContentText(ctx.getString(R.string.gatedrop_sent_to, it)) } }
                .setAutoCancel(true)
                .setSilent(true)
                .setTimeoutAfter(DONE_TIMEOUT_MS)
                .setContentIntent(sharedTap(TAB_SENT, slot = 11))
                .build(),
        )
    }

    /** The link for externals is ready: URL (and PIN) with Kopieren / Teilen. */
    suspend fun linkReady(key: String, link: GdLinkResult) {
        val ctx = localized()
        ensureChannels(ctx)
        val text = if (link.pin != null) ctx.getString(R.string.gatedrop_link_with_pin, link.url, link.pin) else link.url
        post(
            resultId(key),
            NotificationCompat.Builder(ctx, CHANNEL_RECEIVED)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(ctx.getString(R.string.gatedrop_link_ready))
                .setContentText(link.url)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setSilent(true)
                .setContentIntent(sharedTap(TAB_LINKS, slot = 12))
                .addAction(0, ctx.getString(R.string.gatedrop_copy), receiver(GateDropActionReceiver.ACTION_COPY_TEXT, key, 13) { it.putExtra(GateDropActionReceiver.EXTRA_TEXT, link.url) })
                .addAction(0, ctx.getString(R.string.gatedrop_link_share), shareText(ctx, link.url, 14))
                .build(),
        )
    }

    /** Not sent (no server, refused): tap opens "Gesendet". */
    suspend fun sendFailed(key: String, reason: String?) {
        val ctx = localized()
        ensureChannels(ctx)
        post(
            resultId(key),
            NotificationCompat.Builder(ctx, CHANNEL_RECEIVED)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(ctx.getString(R.string.gatedrop_send_failed))
                .setContentText(reason ?: ctx.getString(R.string.gatedrop_send_failed_desc))
                .setAutoCancel(true)
                .setContentIntent(sharedTap(TAB_SENT, slot = 15))
                .build(),
        )
    }

    // ─── receiving ──────────────────────────────────────────────────

    /** "<from> hat dir … geschickt" — the headline of [entry]. */
    fun headline(ctx: Context, entry: GdReceived): String {
        val from = entry.from ?: ctx.getString(R.string.gatedrop_someone)
        val files = entry.files.size
        val what = when {
            files > 1 -> ctx.resources.getQuantityString(R.plurals.gatedrop_files, files, files)
            files == 1 -> ctx.getString(R.string.gatedrop_what_file)
            entry.link != null -> ctx.getString(R.string.gatedrop_what_link)
            else -> ctx.getString(R.string.gatedrop_what_text)
        }
        return ctx.getString(R.string.gatedrop_received_title, from, what)
    }

    /** The detail line: file names and size, or the text / link, and the note. */
    fun detail(ctx: Context, entry: GdReceived): String {
        val locale = ctx.resources.configuration.locales[0] ?: Locale.getDefault()
        val main = when {
            entry.files.isNotEmpty() -> entry.files.take(3).joinToString(", ") { it.name } +
                (if (entry.files.size > 3) " …" else "") + " · " + size(entry.files.sumOf { it.bytes }, locale)
            entry.link != null -> entry.link
            else -> entry.text?.lineSequence()?.firstOrNull().orEmpty()
        }
        return listOfNotNull(main.takeIf { it.isNotBlank() }, entry.note).joinToString("\n")
    }

    /**
     * Shows (or updates) the notification of [entry]. Files saved: Öffnen
     * (one file: the file, several: "Geteilt"); not saved yet: Öffnen
     * ("Geteilt") and Speichern; a text: Kopieren; a link: Öffnen and
     * Kopieren. [notificationId] = the push's when it replaces one.
     */
    suspend fun received(entry: GdReceived, notificationId: Int = receivedId(entry.key), messageId: Long? = null, quiet: Boolean = false, saving: Boolean = false) {
        val ctx = localized()
        ensureChannels(ctx)
        val base = (notificationId and 0xFFFF) * 8
        val detail = detail(ctx, entry)
        val b = NotificationCompat.Builder(ctx, CHANNEL_RECEIVED)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(headline(ctx, entry))
            .setContentText(detail.lineSequence().firstOrNull())
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setSilent(quiet)
            .setWhen(entry.at.takeIf { it > 0 } ?: System.currentTimeMillis())
            .setShowWhen(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(sharedTap(TAB_RECEIVED, messageId, slot = base))
        val saved = entry.files.mapNotNull { f -> f.uri?.let { Uri.parse(it) to f.mime } }
        when {
            entry.files.isNotEmpty() -> {
                if (saved.size == 1 && entry.files.size == 1) {
                    b.addAction(0, ctx.getString(R.string.gatedrop_open), viewFile(saved[0].first, saved[0].second, base + 1))
                } else {
                    b.addAction(0, ctx.getString(R.string.gatedrop_open), sharedTap(TAB_RECEIVED, messageId, slot = base + 1))
                }
                if (saving) {
                    b.setSubText(ctx.getString(R.string.gatedrop_saving))
                } else if (saved.size < entry.files.size && entry.sendId != null) {
                    b.addAction(0, ctx.getString(R.string.gatedrop_save), receiver(GateDropActionReceiver.ACTION_SAVE, entry.key, base + 2) { it.putExtra(GateDropActionReceiver.EXTRA_NOTIFICATION, notificationId) })
                } else if (saved.isNotEmpty()) {
                    b.setSubText(ctx.getString(R.string.gatedrop_saved_in))
                }
            }
            entry.link != null -> {
                b.addAction(0, ctx.getString(R.string.gatedrop_open), viewUrl(entry.link, base + 1))
                b.addAction(0, ctx.getString(R.string.gatedrop_copy), receiver(GateDropActionReceiver.ACTION_COPY, entry.key, base + 2))
            }
            entry.text != null -> b.addAction(0, ctx.getString(R.string.gatedrop_copy), receiver(GateDropActionReceiver.ACTION_COPY, entry.key, base + 2))
        }
        post(notificationId, b.build())
    }

    /**
     * A delivery push before its content is known: the server's title and
     * text, tap and Öffnen lead to "Geteilt". Replaced under the same id
     * once the send is fetched.
     */
    suspend fun placeholder(title: String, body: String, sendId: String, notificationId: Int, messageId: Long, quiet: Boolean) {
        val ctx = localized()
        ensureChannels(ctx)
        val base = (notificationId and 0xFFFF) * 8
        post(
            notificationId,
            NotificationCompat.Builder(ctx, CHANNEL_RECEIVED)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title.ifBlank { ctx.getString(R.string.gatedrop_received_generic) })
                .apply { if (body.isNotBlank()) setContentText(body.lineSequence().first()).setStyle(NotificationCompat.BigTextStyle().bigText(body)) }
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setSilent(quiet)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setContentIntent(sharedTap(TAB_RECEIVED, messageId, slot = base))
                .addAction(0, ctx.getString(R.string.gatedrop_open), sharedTap(TAB_RECEIVED, messageId, slot = base + 1))
                .addExtras(android.os.Bundle().apply { putString("gatedrop.send", sendId) })
                .build(),
        )
    }

    /** A direct receive failed for good and nothing came over the server either. */
    suspend fun receiveFailed(key: String, from: String?) {
        val ctx = localized()
        ensureChannels(ctx)
        post(
            receivedId(key),
            NotificationCompat.Builder(ctx, CHANNEL_RECEIVED)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(ctx.getString(R.string.gatedrop_receive_failed))
                .apply { from?.let { setContentText(ctx.getString(R.string.gatedrop_receive_failed_from, it)) } }
                .setAutoCancel(true)
                .setContentIntent(sharedTap(TAB_RECEIVED, slot = 16))
                .build(),
        )
    }

    private fun post(id: Int, n: Notification): Boolean {
        // POST_NOTIFICATIONS exists from Android 13; before, posting needs no runtime permission.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return try {
            NotificationManagerCompat.from(context).notify(id, n)
            true
        } catch (_: SecurityException) {
            false
        }
    }
}
