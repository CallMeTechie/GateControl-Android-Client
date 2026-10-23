package com.gatecontrol.android.ui.vault

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gatecontrol.android.R
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcTextField
import com.gatecontrol.android.ui.theme.GateControlTheme
import com.gatecontrol.android.ui.theme.MonoFontFamily
import com.gatecontrol.android.vault.EntryData
import com.gatecontrol.android.vault.VaultGenerator
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** Copy to the clipboard: marked sensitive (no preview on Android 13+), cleared again after [seconds]. */
object VaultClipboard {
    private val handler = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null

    fun copy(context: Context, value: String, secret: Boolean, seconds: Int) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val clip = ClipData.newPlainText("GateControl Vault", value)
        if (secret) {
            clip.description.extras = PersistableBundle().apply {
                if (Build.VERSION.SDK_INT >= 33) putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) else putBoolean("android.content.extra.IS_SENSITIVE", true)
            }
        }
        cm.setPrimaryClip(clip)
        pending?.let { handler.removeCallbacks(it) }
        if (secret && seconds > 0) {
            val r = Runnable {
                runCatching {
                    val now = cm.primaryClip?.getItemAt(0)?.text?.toString()
                    if (now == value) cm.clearPrimaryClip()
                }
            }
            pending = r
            handler.postDelayed(r, seconds * 1000L)
        }
    }
}

/** Mockup colors of an entry's letter square: by title, one of five tones. */
@Composable
fun vaultTone(key: String): Pair<Color, Color> {
    val x = GateControlTheme.extraColors
    val tones = listOf(
        x.blueBg to x.blue,
        x.errorBg to MaterialTheme.colorScheme.error,
        x.accentBg to x.accentText,
        x.warnBg to x.warn,
        x.proBg to x.pro,
    )
    return tones[Math.floorMod(key.lowercase().hashCode(), tones.size)]
}

/** 40 dp rounded square with the entry's first letter (`.av` of the mockups). */
@Composable
fun VaultLetter(title: String, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val (bg, fg) = vaultTone(title)
    Box(
        modifier = modifier.size(size).clip(RoundedCornerShape(if (size > 48.dp) 16.dp else 12.dp)).background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = title.trim().firstOrNull()?.uppercase() ?: "•",
            color = fg,
            fontWeight = FontWeight.Bold,
            fontSize = if (size > 48.dp) 22.sp else 16.sp,
        )
    }
}

/** The label of an entry type. */
@Composable
fun vaultTypeLabel(type: String): String = stringResource(
    when (type) {
        "totp" -> R.string.vault_type_totp
        "note" -> R.string.vault_type_note
        "card" -> R.string.vault_type_card
        "wifi" -> R.string.vault_type_wifi
        "identity" -> R.string.vault_type_identity
        else -> R.string.vault_type_login
    },
)

fun vaultTypeIcon(type: String) = when (type) {
    "totp" -> GcIcons.Clock
    "note" -> GcIcons.Note
    "card" -> GcIcons.CreditCard
    "wifi" -> GcIcons.Wifi
    "identity" -> GcIcons.IdCard
    else -> GcIcons.Key
}

/** The second line of a list row: user name · site, card ending, network name, … */
fun vaultSubtitle(e: EntryData): String = when (e.type) {
    "card" -> e.field("number").takeLast(4).let { if (it.isEmpty()) "" else "•••• $it" }
    "wifi" -> listOf(e.field("ssid"), e.field("security")).filter { it.isNotBlank() }.joinToString(" · ")
    "identity" -> listOf(e.field("name"), e.field("email")).filter { it.isNotBlank() }.joinToString(" · ")
    "note" -> e.notes.lineSequence().firstOrNull().orEmpty()
    else -> listOf(e.username, e.urls.firstOrNull()?.let { com.gatecontrol.android.vault.VaultMatch.host(it) }).filter { !it.isNullOrBlank() }.joinToString(" · ")
}

/** Password input with show / hide (mockup A1: mono, wide letter spacing). */
@Composable
fun VaultPasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    error: String? = null,
    hint: String? = null,
    trailingExtra: (@Composable RowScope.() -> Unit)? = null,
) {
    var shown by rememberSaveable { mutableStateOf(false) }
    GcTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        modifier = modifier,
        mono = true,
        error = error,
        hint = hint,
        visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        trailingIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                trailingExtra?.invoke(this)
                GcIconButton(
                    icon = if (shown) GcIcons.EyeOff else GcIcons.Eye,
                    contentDescription = stringResource(if (shown) R.string.vault_hide_password else R.string.vault_show_password),
                    onClick = { shown = !shown },
                    size = 44.dp,
                    iconSize = 20.dp,
                )
            }
        },
    )
}

/** Four bars and a word (mockup A1). */
@Composable
fun VaultStrengthMeter(password: String, modifier: Modifier = Modifier) {
    val x = GateControlTheme.extraColors
    val s = VaultGenerator.strength(password)
    val (bars, color, label) = when (s) {
        VaultGenerator.Strength.WEAK -> Triple(1, MaterialTheme.colorScheme.error, R.string.vault_strength_weak)
        VaultGenerator.Strength.FAIR -> Triple(2, x.warn, R.string.vault_strength_fair)
        VaultGenerator.Strength.STRONG -> Triple(3, MaterialTheme.colorScheme.primary, R.string.vault_strength_strong)
        VaultGenerator.Strength.VERY_STRONG -> Triple(4, MaterialTheme.colorScheme.primary, R.string.vault_strength_very)
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(4) { i ->
                Box(
                    Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp))
                        .background(if (password.isNotEmpty() && i < bars) color else x.border),
                )
            }
        }
        if (password.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(label), color = if (s == VaultGenerator.Strength.WEAK) MaterialTheme.colorScheme.error else color, style = MaterialTheme.typography.labelMedium)
                Text(
                    stringResource(R.string.vault_strength_detail, password.length, VaultGenerator.wordCount(password)),
                    color = x.faint, style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

/** Progress of the setup: three segments (mockups A1, A2). */
@Composable
fun VaultSteps(step: Int, total: Int = 3, modifier: Modifier = Modifier) {
    val x = GateControlTheme.extraColors
    Row(modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(total) { i ->
            Box(
                Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp))
                    .background(if (i < step) MaterialTheme.colorScheme.primary else x.border),
            )
        }
    }
}

/** One row of the detail card: label over value, actions on the right (mockup A5). */
@Composable
fun VaultFieldRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    mono: Boolean = false,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    big: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = GateControlTheme.extraColors.faint)
            Text(
                value,
                color = valueColor,
                style = when {
                    big -> MaterialTheme.typography.headlineMedium.copy(fontFamily = MonoFontFamily, fontWeight = FontWeight.Medium, letterSpacing = 1.5.sp)
                    mono -> MaterialTheme.typography.bodyLarge.copy(fontFamily = MonoFontFamily, letterSpacing = 1.sp)
                    else -> MaterialTheme.typography.bodyLarge
                },
                maxLines = if (mono || big) 1 else 6,
                overflow = TextOverflow.Ellipsis,
            )
        }
        actions()
    }
}

/** The ring that counts down a 2FA code's seconds (mockup A5). */
@Composable
fun VaultTotpRing(secondsLeft: Int, period: Int, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    val track = GateControlTheme.extraColors.border
    val warn = GateControlTheme.extraColors.warn
    val desc = stringResource(R.string.vault_totp_left, secondsLeft)
    Box(modifier.size(36.dp).semantics { contentDescription = desc }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(36.dp)) {
            drawArc(track, 0f, 360f, false, style = Stroke(4.dp.toPx()))
            drawArc(if (secondsLeft <= 5) warn else accent, -90f, 360f * secondsLeft / period, false, style = Stroke(4.dp.toPx()))
        }
        Text(secondsLeft.toString(), style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
    }
}

/** A QR code as an image (zxing): black on white, a quiet zone of two modules. */
fun vaultQr(text: String, size: Int = 512): ImageBitmap {
    val m = QRCodeWriter().encode(
        text, BarcodeFormat.QR_CODE, size, size,
        mapOf(EncodeHintType.MARGIN to 2, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
    )
    val px = IntArray(m.width * m.height) { i -> if (m.get(i % m.width, i / m.width)) 0xFF0D1015.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(px, m.width, m.height, Bitmap.Config.ARGB_8888).asImageBitmap()
}

@Composable
fun VaultQrImage(text: String, description: String, modifier: Modifier = Modifier) {
    val bmp = remember(text) { vaultQr(text) }
    Image(
        bitmap = bmp,
        contentDescription = description,
        modifier = modifier.clip(RoundedCornerShape(12.dp)).border(1.dp, GateControlTheme.extraColors.border, RoundedCornerShape(12.dp)),
        filterQuality = FilterQuality.None,
    )
}

/** A tinted square with an icon (mockup A1 lock, A3 vault). */
@Composable
fun VaultHeroIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier, size: Dp = 64.dp) {
    val x = GateControlTheme.extraColors
    Box(modifier.size(size).clip(RoundedCornerShape(size * 0.31f)).background(x.accentBg), contentAlignment = Alignment.Center) {
        androidx.compose.material3.Icon(icon, contentDescription = null, tint = x.accentText, modifier = Modifier.size(size * 0.47f))
    }
}
