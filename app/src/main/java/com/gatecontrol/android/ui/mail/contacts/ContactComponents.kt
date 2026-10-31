package com.gatecontrol.android.ui.mail.contacts

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gatecontrol.android.R
import com.gatecontrol.android.mail.MAccount
import com.gatecontrol.android.mail.MContact
import com.gatecontrol.android.mail.MMe
import com.gatecontrol.android.mail.MailEntryPoint
import com.gatecontrol.android.mail.MailLogic
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.mail.accountColor
import com.gatecontrol.android.ui.theme.GateControlTheme
import dagger.hilt.android.EntryPointAccessors

/** The colors of initials (like the mockup), picked by the name. */
private val INITIAL_COLORS = listOf(0xFF79B8FF, 0xFF46E0A0, 0xFFF5B544, 0xFFB79CFF, 0xFFFF8080, 0xFF8C96A5)

internal fun initialsColor(seed: String): Color = Color(INITIAL_COLORS[(seed.lowercase().hashCode() and 0x7fffffff) % INITIAL_COLORS.size])

/** Decoded contact photos while the app runs (the files are in the offline copy). */
private object CardPhotos {
    val memory = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
}

/** A contact's photo ([id] with its [etag]); null without one, while loading or offline without a copy. */
@Composable
internal fun rememberCardPhoto(id: Long?, etag: String?): ImageBitmap? {
    val context = LocalContext.current.applicationContext
    val preview = LocalInspectionMode.current
    val repo = remember(context, preview) {
        if (preview) null else runCatching { EntryPointAccessors.fromApplication(context, MailEntryPoint::class.java).mailPim() }.getOrNull()
    }
    val key = if (id != null && etag != null) "$id:$etag" else null
    val bitmap by produceState(key?.let { CardPhotos.memory.get(it) }?.asImageBitmap(), key, repo) {
        if (key == null || id == null || etag == null || repo == null || value != null) return@produceState
        val p = repo.photo(id, etag) ?: return@produceState
        val bmp = runCatching { BitmapFactory.decodeByteArray(p.bytes, 0, p.bytes.size) }.getOrNull() ?: return@produceState
        CardPhotos.memory.put(key, bmp)
        value = bmp.asImageBitmap()
    }
    return bitmap
}

/** Round: the contact's photo, else the initials on a color of the name. */
@Composable
internal fun ContactAvatar(name: String, email: String?, photoId: Long? = null, photoEtag: String? = null, size: Dp = 40.dp) {
    val photo = rememberCardPhoto(photoId, photoEtag)
    Box(
        Modifier.size(size).clip(CircleShape).background(initialsColor(name.ifBlank { email.orEmpty() })),
        contentAlignment = Alignment.Center,
    ) {
        if (photo != null) {
            Image(photo, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size))
        } else {
            Text(
                MailLogic.initials(name.ifBlank { null }, email.orEmpty().ifBlank { "?" }),
                color = Color(0xFF0D1015),
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.36f).sp,
            )
        }
    }
}

/** The color dot of a contact's source: the account's color; own contacts have none. */
@Composable
internal fun sourceColor(me: MMe?, accountId: Long?): Color? = me?.account(accountId)?.let { accountColor(it) }

/** The account a source belongs to (its label for "aus <Konto>"). */
internal fun accountLabel(me: MMe?, accountId: Long?): String? = me?.account(accountId)?.let(MAccount::label)

/**
 * A suggestion while typing addresses (writing, attendees): photo or initials, name, the address and where it comes
 * from; a group as "Familie (5)" with its members.
 */
@Composable
internal fun SuggestionRow(c: MContact, onClick: () -> Unit) {
    val extra = GateControlTheme.extraColors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (c.group) {
            Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(extra.accentBg), contentAlignment = Alignment.Center) {
                Icon(GcIcons.Users, contentDescription = null, tint = extra.accentText, modifier = Modifier.size(18.dp))
            }
        } else {
            ContactAvatar(c.name.orEmpty(), c.email, size = 32.dp)
        }
        Column(Modifier.weight(1f)) {
            Text(c.name ?: c.email, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = if (c.group) c.members.joinToString(", ") { it.label } else listOfNotNull(c.email.takeIf { c.name != null }, sourceLabel(c.source)).joinToString(" · ")
            if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = extra.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun sourceLabel(source: String): String? = when (source) {
    MContact.SOURCE_CONTACT -> stringResource(R.string.pim_source_contact)
    MContact.SOURCE_PERSON -> stringResource(R.string.pim_source_person)
    MContact.SOURCE_RECENT -> stringResource(R.string.pim_source_recent)
    else -> null
}

/** "Auch in Outlook geändert" / "Nicht bei <Konto> geändert" after saving a GC contact (Mail 1.4.0 `writeBack`). */
internal fun writeBackToast(context: android.content.Context, ok: List<String>, failed: List<String>) {
    val parts = listOfNotNull(
        ok.takeIf { it.isNotEmpty() }?.let { context.getString(R.string.con_writeback_ok, it.joinToString(", ")) },
        failed.takeIf { it.isNotEmpty() }?.let { context.getString(R.string.con_writeback_failed, it.joinToString(", ")) },
    )
    if (parts.isNotEmpty()) android.widget.Toast.makeText(context, parts.joinToString("\n"), android.widget.Toast.LENGTH_SHORT).show()
}

/**
 * Delete a contact: a GC contact with linked cards (Mail 1.4.0) asks whether they go at their provider too (off by
 * default: they stay there and are no longer linked).
 */
@Composable
internal fun DeleteContactDialog(c: com.gatecontrol.android.mail.MCard, onDelete: (linkedToo: Boolean) -> Unit, onDismiss: () -> Unit) {
    val links = c.sources.filter { it.id != c.id }
    val ask = com.gatecontrol.android.mail.MailContactLogic.masterModel(c) && c.linked.isNotEmpty() && c.source == "local"
    var linkedToo by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.con_delete_title, c.label)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(if (c.source == "local") R.string.con_delete_text else R.string.con_delete_text_account))
                if (ask) {
                    val names = links.map { com.gatecontrol.android.mail.MailContactLogic.originLabel(it) }.filter { it.isNotBlank() }.distinct()
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp))
                            .toggleable(value = linkedToo, role = Role.Checkbox, onValueChange = { linkedToo = it }),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        androidx.compose.material3.Checkbox(checked = linkedToo, onCheckedChange = null)
                        Text(
                            stringResource(R.string.con_delete_linked_too, names.joinToString(", ").ifEmpty { c.linked.size.toString() }),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { onDelete(ask && linkedToo) }) {
                Text(stringResource(R.string.mail_delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text(stringResource(R.string.mail_cancel)) } },
    )
}

/** The dots of the accounts a contact comes from (one each; own contacts none). */
@Composable
internal fun SourceDots(me: MMe?, accountIds: List<Long>) {
    if (accountIds.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        accountIds.take(4).forEach { a ->
            val color = sourceColor(me, a) ?: return@forEach
            val label = accountLabel(me, a)
            Box(
                Modifier.size(8.dp).clip(CircleShape).background(color)
                    .then(if (label != null) Modifier.semantics { contentDescription = label } else Modifier),
            )
        }
    }
}
