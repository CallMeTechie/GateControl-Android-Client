package com.gatecontrol.android.ui.drive

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.gatecontrol.android.R
import com.gatecontrol.android.drive.DriveScanner
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcChevron
import com.gatecontrol.android.ui.components.GcFilterChip
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.components.GcRowDivider
import com.gatecontrol.android.ui.components.GcTextField
import com.gatecontrol.android.ui.theme.GateControlTheme
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Starts Google's document scanner; its pages go to [onScanned]. The returned function opens the scanner (a message
 * when the phone has none: Play services missing or too old).
 */
@Composable
internal fun rememberDriveScan(scanner: DriveScanner, onScanned: (DriveScanner.Pages) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val scan = GmsDocumentScanningResult.fromActivityResultIntent(result.data) ?: return@rememberLauncherForActivityResult
        onScanned(DriveScanner.Pages(scan.pages?.map { it.imageUri }.orEmpty(), scan.pdf?.uri))
    }
    return remember(scanner) {
        {
            val activity = context.activity()
            if (activity == null) {
                Toast.makeText(context, R.string.drive_scan_unavailable, Toast.LENGTH_LONG).show()
            } else {
                scanner.client().getStartScanIntent(activity)
                    .addOnSuccessListener { launcher.launch(IntentSenderRequest.Builder(it).build()) }
                    .addOnFailureListener { Toast.makeText(context, R.string.drive_scan_unavailable, Toast.LENGTH_LONG).show() }
            }
        }
    }
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/**
 * "Scan speichern" (mockup "Android – Dokument scannen"): the pages, the name, the folder, PDF or pictures, whether
 * the text was recognised; "Neu scannen" and "Im Drive speichern".
 */
@Composable
internal fun ScanSaveDialog(
    draft: DriveFolderViewModel.ScanDraft,
    onPdf: (Boolean) -> Unit,
    onPickFolder: () -> Unit,
    onRescan: () -> Unit,
    onSave: (name: String, fallback: String) -> Unit,
    onClose: () -> Unit,
) {
    val x = GateControlTheme.extraColors
    val fallback = remember(draft.scan) { "Scan " + SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.GERMANY).format(Date()) }
    var name by rememberSaveable(draft.scan) { mutableStateOf("") }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    GcIconButton(GcIcons.Close, contentDescription = stringResource(R.string.drive_scan_close), onClick = onClose, tint = MaterialTheme.colorScheme.onBackground)
                    Text(stringResource(R.string.drive_scan_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text(
                        pluralStringResource(R.plurals.drive_scan_pages, draft.scan.pages.size, draft.scan.pages.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = x.muted,
                        modifier = Modifier.padding(end = 12.dp),
                    )
                }
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        itemsIndexed(draft.scan.pages) { i, uri -> ScanPage(uri, first = i == 0) }
                    }
                    GcCard(verticalArrangement = Arrangement.spacedBy(0.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                        GcTextField(
                            value = name,
                            onValueChange = { name = it.take(200) },
                            label = stringResource(R.string.drive_scan_name),
                            placeholder = fallback,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                        GcRowDivider()
                        Row(
                            Modifier.fillMaxWidth().clickable(onClick = onPickFolder).padding(vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(stringResource(R.string.drive_scan_folder), color = x.muted, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                draft.folderLabel,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.End,
                                modifier = Modifier.weight(1f),
                            )
                            GcChevron()
                        }
                        GcRowDivider()
                        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.drive_scan_format), color = x.muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                GcFilterChip("PDF", selected = draft.pdf && draft.scan.pdf != null, onClick = { onPdf(true) })
                                GcFilterChip(stringResource(R.string.drive_scan_format_image), selected = !draft.pdf || draft.scan.pdf == null, onClick = { onPdf(false) })
                            }
                        }
                        GcRowDivider()
                        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.drive_scan_text), color = x.muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            val texts = draft.texts
                            when {
                                texts == null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = x.muted)
                                    Text(stringResource(R.string.drive_scan_text_running), color = x.muted, style = MaterialTheme.typography.bodyMedium)
                                }
                                texts.any { it != null } -> Text(stringResource(R.string.drive_scan_text_yes), color = x.accentText, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                                else -> Text(stringResource(R.string.drive_scan_text_no), color = x.muted, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    Text(stringResource(R.string.drive_scan_note), style = MaterialTheme.typography.bodySmall, color = x.muted)
                }
                Surface(color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, x.border)) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GcOutlineButton(stringResource(R.string.drive_scan_again), onClick = onRescan, modifier = Modifier.weight(1f))
                        GcPrimaryButton(stringResource(R.string.drive_scan_save), onClick = { onSave(name, fallback) }, modifier = Modifier.weight(1.4f))
                    }
                }
            }
        }
    }
}

/** One page of the scan, the first one framed (the cover). */
@Composable
private fun ScanPage(uri: Uri, first: Boolean) {
    val x = GateControlTheme.extraColors
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, uri) { value = withContext(Dispatchers.IO) { decodeSmall(context, uri, 600) } }
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier
            .height(if (first) 200.dp else 160.dp)
            .aspectRatio(0.72f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(if (first) 2.dp else 1.dp, if (first) x.accentText else x.border, shape),
        contentAlignment = Alignment.Center,
    ) {
        val b = bitmap
        if (b != null) {
            Image(b.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = x.muted)
        }
    }
}

/** The page at about [max] px on its long side (a scan page is several megapixels). */
internal fun decodeSmall(context: Context, uri: Uri, max: Int): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= max) sample *= 2
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
}.getOrNull()
