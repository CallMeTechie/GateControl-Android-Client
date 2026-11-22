package com.gatecontrol.android.tv.ui.pair

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** The modules of a QR code for [text] (no quiet zone: the white box around it is the margin). Pure, unit-tested. */
object TvQr {
    fun matrix(text: String): BitMatrix =
        QRCodeWriter().encode(
            text,
            BarcodeFormat.QR_CODE,
            0,
            0,
            mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.CHARACTER_SET to "UTF-8"),
        )
}

/** `.qr` of the mockup: dark modules on a white box with rounded corners. */
@Composable
fun TvQrCode(text: String, description: String, modifier: Modifier = Modifier, size: Dp = 140.dp) {
    val matrix = remember(text) { TvQr.matrix(text) }
    Box(
        modifier
            .size(size)
            .background(Color.White, RoundedCornerShape(6.dp))
            .padding(8.dp)
            .semantics { contentDescription = description },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val n = matrix.width
            val cell = this.size.width / n
            for (y in 0 until n) {
                for (x in 0 until n) {
                    if (matrix[x, y]) {
                        drawRect(QrDark, topLeft = Offset(x * cell, y * cell), size = Size(cell + 0.5f, cell + 0.5f))
                    }
                }
            }
        }
    }
}

private val QrDark = Color(0xFF0D1015)
