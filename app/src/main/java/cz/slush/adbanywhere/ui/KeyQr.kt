/*
 * KeyQr - a saved developer key rendered as the pairing QR another phone scans.
 */
package cz.slush.adbanywhere.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.zxing.BarcodeFormat
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import cz.slush.adbanywhere.data.DeveloperKey

/** The same text as ~/.android/adbkey.pub, accepted by the scan tab. */
internal fun keyQrContent(key: DeveloperKey): String = key.keyLine

internal fun keyQrMatrix(content: String): BitMatrix =
    QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0)

@Composable
fun KeyQrCode(content: String, modifier: Modifier = Modifier) {
    val matrix = remember(content) { keyQrMatrix(content) }
    Canvas(modifier.background(Color.White)) {
        val side = minOf(size.width, size.height)
        val cell = side / matrix.width
        val left = (size.width - side) / 2
        val top = (size.height - side) / 2
        // Horizontal runs keep the draw calls near the module count.
        for (y in 0 until matrix.height) {
            var x = 0
            while (x < matrix.width) {
                if (!matrix.get(x, y)) {
                    x++
                    continue
                }
                val start = x
                while (x < matrix.width && matrix.get(x, y)) x++
                drawRect(
                    Color.Black,
                    topLeft = Offset(left + start * cell, top + y * cell),
                    size = Size((x - start) * cell, cell),
                )
            }
        }
    }
}

@Composable
fun KeyQrDialog(key: DeveloperKey, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier.fillMaxSize().padding(20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth().widthIn(max = 420.dp),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 6.dp,
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        key.label,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    KeyQrCode(
                        content = keyQrContent(key),
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(12.dp)),
                    )
                    FingerprintText(key.fingerprint)
                    Text(
                        "Scan it with Add key → Scan QR on another phone. It holds only the " +
                            "public key.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                        Text("Close")
                    }
                }
            }
        }
    }
}
