package com.gatecontrol.android.ui.familie

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.familie.FamilieApi
import com.gatecontrol.android.familie.FamilieJson12
import com.gatecontrol.android.familie.FamilieProfile
import com.gatecontrol.android.familie.FamilieRepository
import com.gatecontrol.android.familie.FamilieResult
import com.gatecontrol.android.familie.money.Money
import com.gatecontrol.android.familie.money.WishDraft
import com.gatecontrol.android.familie.money.WishItem
import com.gatecontrol.android.familie.push.FamiliePushEvent
import com.gatecontrol.android.ui.components.GcBanner
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcChip
import com.gatecontrol.android.ui.components.GcFilterChip
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcRowDivider
import com.gatecontrol.android.ui.components.GcSegmented
import com.gatecontrol.android.ui.components.GcTextField
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.theme.GateControlTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** Wunschlisten: every member's list; own items never show who reserved them. */
@HiltViewModel
class WishesViewModel @Inject constructor(
    private val api: FamilieApi,
    repository: FamilieRepository,
) : FamilieLoadViewModel<List<WishItem>>(repository) {

    init {
        start()
    }

    override suspend fun load(): FamilieResult<List<WishItem>> {
        val me = repository.profile.value?.me?.userId ?: return FamilieResult.Failed("not_member", 403)
        return api.wishes(me)
    }

    override fun refreshOn(event: FamiliePushEvent): Boolean = event is FamiliePushEvent.Other && event.topicKey == "wishes"

    fun save(id: Int?, draft: WishDraft) = act(R.string.familie_saved) { if (id == null) api.createWish(draft) else api.updateWish(id, draft) }

    fun delete(id: Int) = act { api.deleteWish(id) }

    fun gotIt(id: Int) = act(R.string.familie_wish_got_it_done) { api.wishGotIt(id, true) }

    fun reserve(id: Int, reserve: Boolean) =
        act(if (reserve) R.string.familie_wish_reserved_done else R.string.familie_wish_released_done) { api.reserveWish(id, reserve) }
}

/**
 * Wunschlisten (mockup AppAllowance, lower half): my own list (add, edit,
 * "Bekommen"), the others' lists with "Ich besorge das" / "Freigeben".
 * Who reserved one of my own wishes is never shown (the server does not
 * send it, and the app drops it too). Parents also edit their children's lists.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WishesScreen(onBack: () -> Unit, initialUserId: Int? = null, viewModel: WishesViewModel = hiltViewModel()) {
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val items by viewModel.data.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    var selected by rememberSaveable { mutableStateOf(initialUserId) }
    var editing by remember { mutableStateOf<WishItem?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    FamilieToasts(viewModel)

    val p = profile
    val owner = selected?.takeIf { id -> p?.everyone?.any { it.userId == id } == true } ?: p?.me?.userId
    val own = p != null && owner == p.me.userId
    val canEdit = p != null && owner != null && (own || (p.me.isParent && p.member(owner)?.isChild == true))

    FamiliePageFrame(
        title = stringResource(R.string.familie_wishes_title),
        onBack = onBack,
        refreshing = busy && items != null,
        onRefresh = { viewModel.refresh() },
        actions = {
            if (canEdit) GcIconButton(GcIcons.Plus, contentDescription = stringResource(R.string.familie_wish_new), onClick = { creating = true }, tint = MaterialTheme.colorScheme.primary)
        },
    ) {
        val data = items
        when {
            data == null && error != null -> FamilieErrorBanner(error, onRetry = { viewModel.refresh() })
            data == null || p == null || owner == null -> FamilieLoading()
            else -> {
                if (error != null) FamilieErrorBanner(error)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    p.everyone.forEach { m ->
                        val count = data.count { it.ownerId == m.userId && !it.gotIt }
                        GcFilterChip(
                            text = (if (m.userId == p.me.userId) stringResource(R.string.familie_wishes_me) else m.name) + if (count > 0) " · $count" else "",
                            selected = m.userId == owner,
                            onClick = { selected = m.userId },
                        )
                    }
                }
                val list = data.filter { it.ownerId == owner && !it.gotIt }
                val got = data.count { it.ownerId == owner && it.gotIt }
                WishList(p, owner, list, own, canEdit, onEdit = { editing = it }, viewModel = viewModel)
                if (canEdit && got > 0) {
                    Text(
                        pluralStringResource(R.plurals.familie_wishes_got_count, got, got),
                        style = MaterialTheme.typography.bodySmall,
                        color = GateControlTheme.extraColors.muted,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
                if (canEdit) {
                    GcOutlineButton(text = stringResource(R.string.familie_wish_new), onClick = { creating = true }, icon = GcIcons.Plus)
                }
                GcBanner(tone = GcTone.Neutral, icon = GcIcons.Eye) {
                    Text(
                        stringResource(if (own) R.string.familie_wishes_surprise else R.string.familie_wishes_reserve_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = GateControlTheme.extraColors.muted,
                    )
                }
            }
        }
    }

    val e = editing
    if (p != null && owner != null && (creating || e != null)) {
        WishDialog(
            item = e,
            onDismiss = {
                creating = false
                editing = null
            },
            onSave = { draft ->
                viewModel.save(e?.id, draft.copy(ownerId = if (e == null && owner != p.me.userId) owner else null))
                creating = false
                editing = null
            },
            onDelete = e?.let { item ->
                {
                    viewModel.delete(item.id)
                    editing = null
                }
            },
        )
    }
}

@Composable
private fun WishList(
    p: FamilieProfile,
    owner: Int,
    list: List<WishItem>,
    own: Boolean,
    canEdit: Boolean,
    onEdit: (WishItem) -> Unit,
    viewModel: WishesViewModel,
) {
    val extra = GateControlTheme.extraColors
    val context = LocalContext.current
    val locale = appLocale()
    GcCard(contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (own) stringResource(R.string.familie_wishes_mine) else stringResource(R.string.familie_wishes_of, memberName(p, owner)),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
        }
        if (list.isEmpty()) {
            Text(
                stringResource(if (own) R.string.familie_wishes_empty_own else R.string.familie_wishes_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = extra.muted,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
        list.forEachIndexed { i, w ->
            if (i > 0) GcRowDivider()
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(w.title, style = MaterialTheme.typography.titleSmall)
                        val meta = listOfNotNull(
                            w.occasion?.let { stringResource(occasionRes(it)) },
                            w.note.takeIf { it.isNotBlank() },
                        ).joinToString(" · ")
                        if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.bodySmall, color = extra.muted)
                    }
                    Text(
                        w.priceCents?.let { stringResource(R.string.familie_wish_price, Money.short(it, locale)) } ?: "–",
                        style = MaterialTheme.typography.bodyMedium,
                        color = extra.muted,
                    )
                    val url = w.url
                    if (url != null) {
                        GcIconButton(
                            GcIcons.Link,
                            contentDescription = stringResource(R.string.familie_wish_open_link, w.title),
                            onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } },
                            size = 40.dp,
                            iconSize = 18.dp,
                        )
                    }
                    if (canEdit) {
                        GcIconButton(GcIcons.Edit, contentDescription = stringResource(R.string.familie_wish_edit_cd, w.title), onClick = { onEdit(w) }, size = 40.dp, iconSize = 18.dp)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    // Never a reservation on my own items (contract); on others' lists "Ich besorge das".
                    if (!own) {
                        when {
                            w.reservedBy == p.me.userId -> {
                                GcChip(stringResource(R.string.familie_wish_reserved_by_me), tone = GcTone.Ok, small = true)
                                TextButton(onClick = { viewModel.reserve(w.id, false) }) { Text(stringResource(R.string.familie_wish_release)) }
                            }
                            w.reserved -> GcChip(reservedText(p, w), tone = GcTone.Neutral, small = true)
                            else -> GcOutlineButton(
                                text = stringResource(R.string.familie_wish_reserve),
                                onClick = { viewModel.reserve(w.id, true) },
                                fillWidth = false,
                                minHeight = 38.dp,
                                icon = GcIcons.Gift,
                            )
                        }
                    }
                    if (canEdit) TextButton(onClick = { viewModel.gotIt(w.id) }) { Text(stringResource(R.string.familie_wish_got_it)) }
                }
            }
        }
    }
}

@Composable
private fun reservedText(p: FamilieProfile, w: WishItem): String =
    w.reservedBy?.let { stringResource(R.string.familie_wish_reserved_by, memberName(p, it)) } ?: stringResource(R.string.familie_wish_reserved)

private fun occasionRes(occasion: String): Int = when (occasion) {
    WishItem.BIRTHDAY -> R.string.familie_wish_occasion_birthday
    WishItem.CHRISTMAS -> R.string.familie_wish_occasion_christmas
    else -> R.string.familie_wish_occasion_other
}

@Composable
private fun WishDialog(item: WishItem?, onDismiss: () -> Unit, onSave: (WishDraft) -> Unit, onDelete: (() -> Unit)?) {
    val locale = appLocale()
    var title by rememberSaveable { mutableStateOf(item?.title.orEmpty()) }
    var price by rememberSaveable { mutableStateOf(item?.priceCents?.let { Money.input(it, locale) }.orEmpty()) }
    var url by rememberSaveable { mutableStateOf(item?.url.orEmpty()) }
    var note by rememberSaveable { mutableStateOf(item?.note.orEmpty()) }
    var occasion by rememberSaveable { mutableStateOf(item?.occasion ?: "") }
    val priceOk = price.isBlank() || Money.parseCents(price) != null
    val urlOk = url.isBlank() || FamilieJson12.httpsUrl(url) != null
    FamilieFormDialog(
        title = stringResource(if (item == null) R.string.familie_wish_new else R.string.familie_wish_edit),
        onDismiss = onDismiss,
        saveEnabled = title.isNotBlank() && priceOk && urlOk,
        onSave = {
            onSave(
                WishDraft(
                    title = title,
                    url = url.trim().ifBlank { null },
                    priceCents = price.takeIf { it.isNotBlank() }?.let { Money.parseCents(it) },
                    note = note,
                    occasion = occasion.ifBlank { null },
                ),
            )
        },
        onDelete = onDelete,
    ) {
        GcTextField(value = title, onValueChange = { title = it.take(WishItem.MAX_TITLE) }, label = stringResource(R.string.familie_wish_title_label))
        AmountField(price, { price = it }, label = stringResource(R.string.familie_wish_price_label))
        GcTextField(
            value = url,
            onValueChange = { url = it.take(WishItem.MAX_URL) },
            label = stringResource(R.string.familie_wish_url_label),
            placeholder = "https://",
            error = if (!urlOk) stringResource(R.string.familie_wish_url_invalid) else null,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri),
        )
        Text(stringResource(R.string.familie_wish_occasion), style = MaterialTheme.typography.titleSmall, color = GateControlTheme.extraColors.muted)
        GcSegmented(
            options = listOf(
                "" to stringResource(R.string.familie_none_short),
                WishItem.BIRTHDAY to stringResource(R.string.familie_wish_occasion_birthday),
                WishItem.CHRISTMAS to stringResource(R.string.familie_wish_occasion_christmas),
                WishItem.OTHER to stringResource(R.string.familie_wish_occasion_other),
            ),
            selected = occasion,
            onSelect = { occasion = it },
        )
        GcTextField(value = note, onValueChange = { note = it.take(WishItem.MAX_NOTE) }, label = stringResource(R.string.familie_wish_note_label), singleLine = false)
    }
}
