package cz.slush.adbanywhere.ui

import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import cz.slush.adbanywhere.data.AndroidPubKey
import cz.slush.adbanywhere.data.DeveloperKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class KeyQrTest {
    private fun fixtureKey(): AndroidPubKey.Key {
        val text = javaClass.classLoader!!.getResourceAsStream("adbkey.pub")!!
            .bufferedReader().readText()
        return AndroidPubKey.parse(text)!!
    }

    @Test
    fun savedKeyQrCarriesTheKeyTheScanTabAccepts() {
        val developer = DeveloperKey(fixtureKey(), "test-laptop")
        val payload = keyQrContent(developer)

        val decoded = scan(keyQrMatrix(payload))
        assertEquals(payload, decoded)

        val scanned = AndroidPubKey.parse(decoded)
        assertNotNull(scanned)
        assertEquals(developer.fingerprint, scanned!!.fingerprint)
        assertEquals("test-laptop", scanned.label)
    }

    /** Renders the matrix at 4 px per module, like a phone screen. */
    private fun scan(matrix: BitMatrix): String {
        val scale = 4
        val width = matrix.width * scale
        val height = matrix.height * scale
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val black = matrix.get(x / scale, y / scale)
                pixels[y * width + x] = if (black) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
        }
        val source = RGBLuminanceSource(width, height, pixels)
        return MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source))).text
    }
}
