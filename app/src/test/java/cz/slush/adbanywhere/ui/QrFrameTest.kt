package cz.slush.adbanywhere.ui

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import cz.slush.adbanywhere.data.AndroidPubKey
import cz.slush.adbanywhere.data.DeveloperKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrFrameTest {
    private val payload = DeveloperKey(
        AndroidPubKey.parse(
            javaClass.classLoader!!.getResourceAsStream("adbkey.pub")!!
                .bufferedReader().readText(),
        )!!,
        "test-laptop",
    ).keyLine
    private val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 0, 0)

    /**
     * A 720p analysis frame with the key's QR centred at [modulePx] pixels per
     * module. [stride] emulates the HAL's padded Y-plane row stride; [rotate]
     * emulates the landscape sensor frame of a phone held upright.
     */
    private fun frame(
        modulePx: Int,
        stride: Int = WIDTH,
        rotate: Boolean = false,
        invert: Boolean = false,
    ): ByteArray {
        val out = ByteArray(stride * HEIGHT) { if (invert) 0 else 255.toByte() }
        val value = if (invert) 255.toByte() else 0
        val size = matrix.width * modulePx
        val left = (WIDTH - size) / 2
        val top = (HEIGHT - size) / 2
        for (my in 0 until matrix.height) {
            for (mx in 0 until matrix.width) {
                if (!matrix.get(mx, my)) continue
                for (dy in 0 until modulePx) for (dx in 0 until modulePx) {
                    val qx = mx * modulePx + dx
                    val qy = my * modulePx + dy
                    val fx = if (rotate) left + (size - 1 - qy) else left + qx
                    val fy = if (rotate) top + qx else top + qy
                    out[fy * stride + fx] = value
                }
            }
        }
        return out
    }

    private fun decode(data: ByteArray, stride: Int = WIDTH): String? =
        decodeQrFrame(WIDTH, HEIGHT, stride, 1, data)

    @Test
    fun tightFrameDecodes() {
        assertEquals(payload, decode(frame(modulePx = 4)))
    }

    @Test
    fun rotatedFrameDecodes() {
        assertEquals(payload, decode(frame(modulePx = 4, rotate = true)))
    }

    @Test
    fun paddedRowStrideDecodes() {
        val stride = 1408
        assertEquals(payload, decode(frame(modulePx = 4, stride = stride), stride))
    }

    @Test
    fun invertedFrameDecodes() {
        assertEquals(payload, decode(frame(modulePx = 4, invert = true)))
    }

    @Test
    fun blankFrameDecodesNothing() {
        assertNull(decode(ByteArray(WIDTH * HEIGHT) { 255.toByte() }))
    }

    private companion object {
        const val WIDTH = 1280
        const val HEIGHT = 720
    }
}
