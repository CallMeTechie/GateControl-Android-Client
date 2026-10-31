package com.gatecontrol.android.ui.mail.contacts

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.graphics.scale
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.mail.MCard
import com.gatecontrol.android.ui.components.GcBanner
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcSubpageBar
import com.gatecontrol.android.ui.components.GcTextField
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.mail.SheetRow
import com.gatecontrol.android.ui.mail.mailContext
import com.gatecontrol.android.ui.mail.mailText
import com.gatecontrol.android.ui.mail.toast
import com.gatecontrol.android.ui.mail.calendar.calColor
import com.gatecontrol.android.ui.theme.GateControlTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** The photo as the server takes it: at most 512 px, JPEG, at most 256 KB. */
private const val PHOTO_PX = 512
private const val PHOTO_MAX = 256 * 1024

/**
 * A new contact or a change (mockup "Kontakt bearbeiten"): photo (camera or gallery, 512 px JPEG), first and last
 * name, company, position, several addresses and numbers with their kind, postal addresses, birthday (the year
 * optional), note, groups and the address book of a new one. Saving sends the etag; a conflict offers "Neu laden"
 * and "Meine Änderung erneut anwenden".
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MailContactEditScreen(onClose: () -> Unit, onSaved: (Long) -> Unit, viewModel: MailContactEditViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val books by viewModel.books.collectAsStateWithLifecycle()
    val me by viewModel.me.collectAsStateWithLifecycle()
    val context = mailContext()
    val scope = rememberCoroutineScope()
    val extra = GateControlTheme.extraColors
    var photoSheet by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val c = state.card
    LaunchedEffect(state.done) {
        state.done?.let { if (it > 0) onSaved(it) else onClose() }
    }
    LaunchedEffect(state.message) {
        state.message?.let {
            toast(context, mailText(context, it))
            viewModel.clearMessage()
        }
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = withContext(Dispatchers.IO) { readPhoto(context, uri) }
            if (bytes != null) viewModel.setPhoto(PhotoChange.Set(bytes)) else toast(context, mailText(context, "too_large"))
        }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bmp ->
        bmp ?: return@rememberLauncherForActivityResult
        scope.launch { withContext(Dispatchers.Default) { jpeg(bmp) }?.let { viewModel.setPhoto(PhotoChange.Set(it)) } }
    }
    // the app holds CAMERA (QR codes): the camera app may only be asked once it is granted
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) runCatching { camera.launch(null) } else toast(context, context.getString(R.string.con_photo_no_camera))
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        GcSubpageBar(title = stringResource(if (viewModel.isNew) R.string.con_new else R.string.con_edit_title), onBack = onClose, backDescription = stringResource(R.string.mail_close)) {
            TextButton(onClick = viewModel::save, enabled = viewModel.canSave(state)) {
                if (state.saving) CircularProgressIndicator(Modifier.size(18.dp)) else Text(stringResource(R.string.mail_save), fontWeight = FontWeight.Bold)
            }
        }
        if (state.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp)) }
            return@Column
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            state.existing?.let { ex ->
                GcBanner(tone = GcTone.Info, icon = GcIcons.User) {
                    Text(stringResource(R.string.con_exists, ex.label), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = viewModel::addToExisting) { Text(stringResource(R.string.con_add_to, ex.label), fontWeight = FontWeight.Bold) }
                }
            }
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(88.dp)) {
                    val p = state.photo
                    when {
                        p is PhotoChange.Set -> {
                            val bmp = remember(p) { BitmapFactory.decodeByteArray(p.bytes, 0, p.bytes.size)?.asImageBitmap() }
                            if (bmp != null) Image(bmp, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(88.dp).clip(CircleShape))
                        }
                        p == PhotoChange.Remove -> ContactAvatar(c.label, c.emails.firstOrNull()?.value, size = 88.dp)
                        else -> ContactAvatar(c.label, c.emails.firstOrNull()?.value, c.id.takeIf { c.photo }, c.photoEtag, size = 88.dp)
                    }
                    val desc = stringResource(R.string.con_photo)
                    Box(
                        Modifier.align(Alignment.BottomEnd).offset(2.dp, 2.dp).size(34.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary)
                            .border(3.dp, MaterialTheme.colorScheme.background, CircleShape)
                            .clickable(role = Role.Button) { photoSheet = true }.semantics { contentDescription = desc },
                        contentAlignment = Alignment.Center,
                    ) { Icon(GcIcons.Camera, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp)) }
                }
                val book = books?.book(if (viewModel.isNew) state.addressbookId else c.addressbookId)
                if (!viewModel.isNew && book != null && !book.local) {
                    Text(stringResource(R.string.con_goes_to, me?.account(book.accountId)?.label ?: book.name), style = MaterialTheme.typography.bodySmall, color = extra.muted)
                }
            }
            GcCard(contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GcTextField(c.name.given, { v -> viewModel.edit { it.copy(name = it.name.copy(given = v.take(200), display = "")) } }, stringResource(R.string.con_given), modifier = Modifier.weight(1f))
                    GcTextField(c.name.family, { v -> viewModel.edit { it.copy(name = it.name.copy(family = v.take(200), display = "")) } }, stringResource(R.string.con_family), modifier = Modifier.weight(1f))
                }
                GcTextField(c.company.orEmpty(), { v -> viewModel.edit { it.copy(company = v.take(200)) } }, stringResource(R.string.con_company))
                GcTextField(c.title.orEmpty(), { v -> viewModel.edit { it.copy(title = v.take(200)) } }, stringResource(R.string.con_position))
            }
            GcCard(contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Label(stringResource(R.string.con_email))
                c.emails.forEachIndexed { i, e ->
                    MultiRow(
                        type = e.type, types = MCard.EMAIL_TYPES, value = e.value, label = stringResource(R.string.con_email),
                        keyboard = KeyboardType.Email,
                        onType = { t -> viewModel.edit { it.copy(emails = it.emails.toMutableList().also { l -> l[i] = e.copy(type = t) }) } },
                        onValue = { v -> viewModel.edit { it.copy(emails = it.emails.toMutableList().also { l -> l[i] = e.copy(value = v.trim().take(254)) }) } },
                        onRemove = { viewModel.edit { it.copy(emails = it.emails.filterIndexed { j, _ -> j != i }) } },
                    )
                }
                AddButton(stringResource(R.string.con_add_email), viewModel::addEmail)
            }
            GcCard(contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Label(stringResource(R.string.con_phone))
                c.phones.forEachIndexed { i, p ->
                    MultiRow(
                        type = p.type, types = MCard.PHONE_TYPES, value = p.value, label = stringResource(R.string.con_phone),
                        keyboard = KeyboardType.Phone,
                        onType = { t -> viewModel.edit { it.copy(phones = it.phones.toMutableList().also { l -> l[i] = p.copy(type = t) }) } },
                        onValue = { v -> viewModel.edit { it.copy(phones = it.phones.toMutableList().also { l -> l[i] = p.copy(value = v.take(40)) }) } },
                        onRemove = { viewModel.edit { it.copy(phones = it.phones.filterIndexed { j, _ -> j != i }) } },
                    )
                }
                AddButton(stringResource(R.string.con_add_phone), viewModel::addPhone)
            }
            c.addresses.forEachIndexed { i, a ->
                GcCard(contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TypeButton(a.type, MCard.ADDRESS_TYPES) { t -> viewModel.edit { it.copy(addresses = it.addresses.toMutableList().also { l -> l[i] = a.copy(type = t) }) } }
                        Text(" · " + stringResource(R.string.con_address), style = MaterialTheme.typography.labelMedium, color = extra.muted, modifier = Modifier.weight(1f))
                        GcIconButton(GcIcons.Close, contentDescription = stringResource(R.string.mail_remove), onClick = { viewModel.edit { it.copy(addresses = it.addresses.filterIndexed { j, _ -> j != i }) } }, size = 36.dp)
                    }
                    fun set(f: (com.gatecontrol.android.mail.MCardAddress) -> com.gatecontrol.android.mail.MCardAddress) =
                        viewModel.edit { it.copy(addresses = it.addresses.toMutableList().also { l -> l[i] = f(a) }) }
                    GcTextField(a.street, { v -> set { it.copy(street = v.take(200)) } }, stringResource(R.string.con_street))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GcTextField(a.postalCode, { v -> set { it.copy(postalCode = v.take(20)) } }, stringResource(R.string.con_postal), modifier = Modifier.width(110.dp))
                        GcTextField(a.city, { v -> set { it.copy(city = v.take(100)) } }, stringResource(R.string.con_city), modifier = Modifier.weight(1f))
                    }
                    GcTextField(a.country, { v -> set { it.copy(country = v.take(100)) } }, stringResource(R.string.con_country))
                }
            }
            AddButton(stringResource(R.string.con_add_address), viewModel::addAddress)
            GcCard(contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GcTextField(
                        state.birthdayDayMonth, { viewModel.setBirthday(it, state.birthdayYear) }, stringResource(R.string.con_birthday),
                        placeholder = "14.03.", modifier = Modifier.weight(1f),
                        error = if (viewModel.birthdayBad(state)) stringResource(R.string.con_birthday_bad) else null,
                    )
                    GcTextField(state.birthdayYear, { viewModel.setBirthday(state.birthdayDayMonth, it) }, stringResource(R.string.con_year), modifier = Modifier.width(110.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                GcTextField(c.note, { v -> viewModel.edit { it.copy(note = v.take(8000)) } }, stringResource(R.string.con_note), singleLine = false)
            }
            val groups = books?.groups.orEmpty()
            if (groups.isNotEmpty()) {
                GcCard(contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Label(stringResource(R.string.con_groups))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        groups.forEach { g ->
                            val on = g.id in c.groups
                            val col = calColor(g.color, extra.accentText)
                            Row(
                                Modifier.heightIn(min = 32.dp).clip(RoundedCornerShape(999.dp))
                                    .then(if (on) Modifier.background(col.copy(alpha = 0.16f)) else Modifier.border(1.dp, extra.border2, RoundedCornerShape(999.dp)))
                                    .clickable(role = Role.Checkbox) { viewModel.edit { it.copy(groups = if (on) it.groups - g.id else it.groups + g.id) } }
                                    .padding(horizontal = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                if (on) Icon(GcIcons.Check, contentDescription = null, tint = col, modifier = Modifier.size(14.dp))
                                Text(g.name, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = if (on) col else extra.muted)
                            }
                        }
                    }
                }
            }
            if (viewModel.isNew) {
                val writable = books?.items.orEmpty().filter { it.writable }
                if (writable.size > 1) {
                    var open by remember { mutableStateOf(false) }
                    Box {
                        GcOutlineButton(
                            stringResource(R.string.con_book, books?.book(state.addressbookId)?.name ?: "–"),
                            onClick = { open = true },
                            icon = GcIcons.IdCard,
                        )
                        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                            writable.forEach { b -> DropdownMenuItem(text = { Text(b.name) }, onClick = { open = false; viewModel.setBook(b.id) }) }
                        }
                    }
                }
            } else {
                GcOutlineButton(stringResource(R.string.con_delete), onClick = { deleting = true }, icon = GcIcons.Trash)
            }
            Box(Modifier.heightIn(min = 24.dp))
        }
    }
    if (photoSheet) {
        ModalBottomSheet(onDismissRequest = { photoSheet = false }, containerColor = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(bottom = 24.dp)) {
                SheetRow(GcIcons.Camera, stringResource(R.string.con_photo_camera)) {
                    photoSheet = false
                    cameraPermission.launch(android.Manifest.permission.CAMERA)
                }
                SheetRow(GcIcons.Photo, stringResource(R.string.con_photo_gallery)) {
                    photoSheet = false
                    gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
                if (c.photo || state.photo is PhotoChange.Set) {
                    SheetRow(GcIcons.Trash, stringResource(R.string.con_photo_remove), MaterialTheme.colorScheme.error) { photoSheet = false; viewModel.setPhoto(PhotoChange.Remove) }
                }
            }
        }
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text(stringResource(R.string.con_delete_title, c.label)) },
            text = { Text(stringResource(if (c.source == "local") R.string.con_delete_text else R.string.con_delete_text_account)) },
            confirmButton = { TextButton(onClick = { deleting = false; viewModel.delete() }) { Text(stringResource(R.string.mail_delete), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.mail_cancel)) } },
        )
    }
    state.conflict?.let {
        AlertDialog(
            onDismissRequest = viewModel::reloadConflict,
            title = { Text(stringResource(R.string.con_conflict_title)) },
            text = { Text(stringResource(R.string.con_conflict_text)) },
            confirmButton = { TextButton(onClick = viewModel::applyAgain) { Text(stringResource(R.string.pim_apply_again)) } },
            dismissButton = { TextButton(onClick = viewModel::reloadConflict) { Text(stringResource(R.string.pim_reload)) } },
        )
    }
}

@Composable
private fun Label(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = GateControlTheme.extraColors.faint)
}

@Composable
private fun AddButton(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Icon(GcIcons.Plus, contentDescription = null, modifier = Modifier.size(16.dp))
        Text(label, modifier = Modifier.padding(start = 6.dp), fontWeight = FontWeight.SemiBold)
    }
}

/** A kind ("Arbeit ▾"), the value and "×" (mockup). */
@Composable
private fun MultiRow(type: String, types: List<String>, value: String, label: String, keyboard: KeyboardType, onType: (String) -> Unit, onValue: (String) -> Unit, onRemove: () -> Unit) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.padding(bottom = 4.dp)) { TypeButton(type, types, onType) }
        GcTextField(value, onValue, "", modifier = Modifier.weight(1f).semantics { contentDescription = label }, keyboardOptions = KeyboardOptions(keyboardType = keyboard))
        GcIconButton(GcIcons.Close, contentDescription = stringResource(R.string.mail_remove), onClick = onRemove, size = 40.dp)
    }
}

@Composable
private fun TypeButton(type: String, types: List<String>, onType: (String) -> Unit) {
    val extra = GateControlTheme.extraColors
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, extra.border2, RoundedCornerShape(12.dp))
                .clickable(role = Role.DropdownList) { open = true }.padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(typeName(type), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Icon(GcIcons.ChevronDown, contentDescription = null, tint = extra.muted, modifier = Modifier.size(14.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            types.forEach { t -> DropdownMenuItem(text = { Text(typeName(t)) }, onClick = { open = false; onType(t) }) }
        }
    }
}

/** A picture from the gallery as the contact's photo (scaled to 512 px, JPEG ≤ 256 KB); null when it cannot be read. */
private fun readPhoto(context: Context, uri: Uri): ByteArray? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= PHOTO_PX && bounds.outHeight / (sample * 2) >= PHOTO_PX) sample *= 2
    val bmp = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return null
    jpeg(bmp)
}.getOrNull()

/** [bmp] cut to a centered square of at most 512 px, as JPEG small enough for the server. */
internal fun jpeg(bmp: Bitmap): ByteArray? {
    val side = minOf(bmp.width, bmp.height)
    if (side <= 0) return null
    val square = Bitmap.createBitmap(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side)
    val scaled = if (side > PHOTO_PX) square.scale(PHOTO_PX, PHOTO_PX) else square
    var quality = 88
    while (quality >= 40) {
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
        if (out.size() <= PHOTO_MAX) return out.toByteArray()
        quality -= 12
    }
    return null
}
