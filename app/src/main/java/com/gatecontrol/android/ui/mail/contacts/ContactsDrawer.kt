package com.gatecontrol.android.ui.mail.contacts

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.gatecontrol.android.R
import com.gatecontrol.android.mail.MBooks
import com.gatecontrol.android.mail.MMe
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.mail.accountColor
import com.gatecontrol.android.ui.mail.calendar.CheckRow
import com.gatecontrol.android.ui.theme.GateControlTheme

/**
 * The contacts' part of the menu (mockup "Seitenmenü neu"): "Posteingänge" back to the mail, "Alle Kontakte", the
 * address books with a check (shown in the list) and their count, the groups with their count, "Dubletten".
 */
internal fun LazyListScope.contactsDrawerItems(
    me: MMe?,
    books: MBooks?,
    total: Int,
    duplicates: Int,
    filter: ContactsFilter,
    inboxRow: @Composable () -> Unit,
    label: @Composable (String) -> Unit,
    row: @Composable (icon: androidx.compose.ui.graphics.vector.ImageVector?, label: String, count: Int, on: Boolean, onClick: () -> Unit) -> Unit,
    onFilter: (ContactsFilter) -> Unit,
    onToggleBook: (Long) -> Unit,
    onDuplicates: () -> Unit,
) {
    item(key = "con-inbox") { inboxRow() }
    item(key = "con-all") { row(GcIcons.User, stringResource(R.string.con_all), total, filter == ContactsFilter()) { onFilter(ContactsFilter()) } }
    books ?: return
    if (books.items.isNotEmpty()) {
        item(key = "con-books") { label(stringResource(R.string.con_books_label)) }
        books.items.forEach { b ->
            item(key = "book${b.id}") {
                val color = me?.account(b.accountId)?.let { accountColor(it) } ?: GateControlTheme.extraColors.accentText
                CheckRow(
                    label = b.name,
                    color = color,
                    checked = b.visible,
                    trailing = b.count.toString(),
                    onClick = { onToggleBook(b.id) },
                    onLongClick = { onFilter(ContactsFilter(book = b.id)) },
                )
            }
        }
    }
    if (books.groups.isNotEmpty()) {
        item(key = "con-groups") { label(stringResource(R.string.con_groups)) }
        books.groups.forEach { g ->
            item(key = "grp${g.id}") { row(null, g.name, g.count, filter.group == g.id) { onFilter(ContactsFilter(group = g.id)) } }
        }
    }
    item(key = "con-dup") { row(GcIcons.Users, stringResource(R.string.con_duplicates), duplicates, false, onDuplicates) }
}
