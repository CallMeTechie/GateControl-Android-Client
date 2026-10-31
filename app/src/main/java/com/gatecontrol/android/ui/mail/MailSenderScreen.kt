package com.gatecontrol.android.ui.mail

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.mail.MContact
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.components.GcSubpageBar
import com.gatecontrol.android.ui.theme.GateControlTheme

/** Where the sender's page leads. */
data class MailSenderNav(
    val back: () -> Unit,
    /** A new mail to [to] from the account [accountId]. */
    val write: (to: String, accountId: Long?) -> Unit,
    val open: (Long) -> Unit,
    /** Since Mail 1.2.0: the sender's contact, or "Als Kontakt speichern". */
    val contact: (Long) -> Unit = {},
    val saveContact: (email: String, name: String?) -> Unit = { _, _ -> },
)

/**
 * A sender (a tap on the sender of a mail): avatar or initials, name and address, "Mail schreiben" and "Adresse
 * kopieren"; below all their mails in the account, page by page.
 */
@Composable
fun MailSenderScreen(nav: MailSenderNav, viewModel: MailSenderViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val me by viewModel.me.collectAsStateWithLifecycle()
    val context = mailContext()
    val extra = GateControlTheme.extraColors
    val account = me?.account(state.accountId)
    Column(Modifier.fillMaxSize()) {
        GcSubpageBar(title = stringResource(R.string.mail_sender_title), onBack = nav.back, backDescription = stringResource(R.string.mail_back))
        LazyColumn(Modifier.weight(1f)) {
            item(key = "head") {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val card = state.card
                    if (card != null) {
                        com.gatecontrol.android.ui.mail.contacts.ContactAvatar(card.label, state.email, card.id.takeIf { card.photo }, card.photoEtag, size = 72.dp)
                    } else {
                        SenderAvatar(state.label.takeIf { it != state.email }, state.email, accountColor(account), size = 72.dp)
                    }
                    Text(
                        state.label,
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 6.dp).semantics { heading() },
                    )
                    if (state.label != state.email) {
                        SelectionContainer { Text(state.email, style = MaterialTheme.typography.bodyMedium, color = extra.muted, textAlign = TextAlign.Center) }
                    }
                    SenderContact(state.contact, state.card, state.looked, state.person, onOpen = nav.contact, onSave = { nav.saveContact(state.email, state.name) })
                    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GcPrimaryButton(
                            stringResource(R.string.mail_sender_write),
                            onClick = { nav.write(state.email, state.accountId) },
                            modifier = Modifier.weight(1f),
                            icon = MailIcons.Pen,
                            minHeight = 44.dp,
                        )
                        GcOutlineButton(
                            stringResource(R.string.mail_sender_copy),
                            onClick = {
                                copy(context, state.email)
                                toast(context, context.getString(R.string.mail_sender_copied))
                            },
                            modifier = Modifier.weight(1f),
                            icon = GcIcons.Copy,
                            minHeight = 44.dp,
                        )
                    }
                }
            }
            item(key = "label") {
                Text(
                    if (account != null) stringResource(R.string.mail_sender_mails_in, account.label) else stringResource(R.string.mail_sender_mails),
                    style = MaterialTheme.typography.labelMedium,
                    color = extra.muted,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp).semantics { heading() },
                )
            }
            items(state.items, key = { it.id }) { item ->
                MailRow(item = item, account = me?.account(item.accountId), onClick = { nav.open(item.id) }, onLongClick = { nav.open(item.id) })
                MailDivider()
            }
            when {
                state.loading -> item(key = "busy") {
                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator(Modifier.size(24.dp)) }
                }
                state.error != null && state.items.isEmpty() -> item(key = "error") { ErrorBox(mailText(context, state.error), onRetry = viewModel::load) }
                state.items.isEmpty() -> item(key = "none") { ErrorBox(stringResource(R.string.mail_sender_none), onRetry = null) }
                state.next != null -> item(key = "more") { TextButton(onClick = viewModel::more, modifier = Modifier.padding(16.dp)) { Text(stringResource(R.string.mail_more_results)) } }
            }
        }
    }
}

/**
 * What the person's contacts know about the sender (contract D5): with a contact its position · company, its numbers
 * (a tap calls) and "Kontakt öffnen"; without one "Als Kontakt speichern"; a GateControl person as before. Servers
 * before Mail 1.2.0 (or contacts off): only that the sender is a GateControl person.
 */
@Composable
private fun SenderContact(contact: MContact?, card: com.gatecontrol.android.mail.MCard?, looked: Boolean, person: String?, onOpen: (Long) -> Unit, onSave: () -> Unit) {
    val extra = GateControlTheme.extraColors
    val context = mailContext()
    if (card != null) {
        val job = listOfNotNull(card.title, card.company).joinToString(" · ")
        if (job.isNotEmpty()) Text(job, style = MaterialTheme.typography.bodyMedium, color = extra.muted)
        card.phones.forEach { p ->
            TextButton(onClick = { com.gatecontrol.android.mail.MailPimLogic.telUri(p.value)?.let { openExternal(context, it) } }) {
                Text(com.gatecontrol.android.ui.mail.contacts.typeName(p.type) + ": " + p.value)
            }
        }
        TextButton(onClick = { onOpen(card.id) }) { Text(stringResource(R.string.con_open), fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) }
        return
    }
    if (person != null || contact != null) {
        Text(stringResource(R.string.mail_sender_in_contacts), style = MaterialTheme.typography.bodySmall, color = extra.accentText)
    }
    if (looked) TextButton(onClick = onSave) { Text(stringResource(R.string.con_save_sender), fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) }
}

private fun copy(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    runCatching { cm.setPrimaryClip(ClipData.newPlainText("Mail", text)) }
}
