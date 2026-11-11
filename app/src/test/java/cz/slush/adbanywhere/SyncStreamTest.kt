package cz.slush.adbanywhere

import cz.slush.adbanywhere.firewall.SyncMalformedException
import cz.slush.adbanywhere.firewall.SyncStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The sync stream is a byte stream, not a frame-per-request protocol. Each case
 * here is one way a frame-based parser let a request through unevaluated.
 */
class SyncStreamTest {
    private fun id(text: String): Int = ByteBuffer.wrap(text.toByteArray()).order(ByteOrder.LITTLE_ENDIAN).int

    private fun request(type: String, path: String): ByteArray =
        ByteBuffer.allocate(8 + path.length).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(id(type)).putInt(path.length).put(path.toByteArray()).array()

    private fun header(type: String, value: Int): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(id(type)).putInt(value).array()

    private fun setup(type: String, vararg words: Int): ByteArray =
        ByteBuffer.allocate(4 + 4 * words.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(id(type))
            words.forEach { putInt(it) }
        }.array()

    private fun bytes(vararg parts: ByteArray): ByteArray =
        ByteArrayOutputStream().apply { parts.forEach { write(it) } }.toByteArray()

    @Test
    fun everyRequestPackedIntoOneFrameIsReported() {
        val requests = SyncStream().feed(bytes(request("STAT", "/sdcard/ok"), request("RECV", "/data/secret")))
        assertEquals(listOf("STAT" to "/sdcard/ok", "RECV" to "/data/secret"), requests.map { it.direction to it.path })
    }

    @Test
    fun aRequestAfterTheDataPhaseIsReported() {
        val stream = SyncStream()
        // adb push of a small file: SEND, DATA and DONE in one write, then the next file.
        val first = stream.feed(
            bytes(request("SEND", "/sdcard/a,33188"), header("DATA", 4), "RECV".toByteArray(), header("DONE", 0)),
        )
        assertEquals(listOf("SEND" to "/sdcard/a"), first.map { it.direction to it.path })
        val second = stream.feed(request("SEND", "/data/local/tmp/adb-anywhere/adb_keys,33188"))
        assertEquals("/data/local/tmp/adb-anywhere/adb_keys", second.single().path)
    }

    @Test
    fun fileBytesThatLookLikeRequestsAreSkippedByLength() {
        val stream = SyncStream()
        stream.feed(request("SEND", "/sdcard/a,33188"))
        val payload = request("RECV", "/data/secret")
        val requests = stream.feed(bytes(header("DATA", payload.size), payload, header("DONE", 0)))
        assertTrue(requests.isEmpty())
    }

    @Test
    fun aRequestSplitAcrossFramesIsReportedWhenComplete() {
        val stream = SyncStream()
        val whole = request("RECV", "/data/secret")
        assertTrue(stream.feed(whole.copyOfRange(0, 5)).isEmpty())
        assertTrue(stream.feed(whole.copyOfRange(5, 12)).isEmpty())
        assertEquals("/data/secret", stream.feed(whole.copyOfRange(12, whole.size)).single().path)
    }

    @Test
    fun syncV2SetupMessagesAreConsumed() {
        val stream = SyncStream()
        val requests = stream.feed(
            bytes(
                request("SND2", "/sdcard/b"), setup("SND2", 33188, 0), header("DATA", 1), byteArrayOf(1), header("DONE", 0),
                request("RCV2", "/sdcard/c"), setup("RCV2", 0),
                request("STA2", "/sdcard/d"),
            ),
        )
        assertEquals(listOf("SEND", "RECV", "STAT"), requests.map { it.direction })
    }

    @Test(expected = SyncMalformedException::class)
    fun anUnknownRequestIsMalformed() {
        SyncStream().feed(header("XXXX", 0))
    }

    @Test(expected = SyncMalformedException::class)
    fun dataOutsideASendIsMalformed() {
        SyncStream().feed(header("DATA", 4))
    }

    @Test(expected = SyncMalformedException::class)
    fun anOversizedDataChunkIsMalformed() {
        val stream = SyncStream()
        stream.feed(request("SEND", "/sdcard/a,33188"))
        stream.feed(header("DATA", 70_000))
    }

    @Test(expected = SyncMalformedException::class)
    fun bytesAfterQuitAreMalformed() {
        SyncStream().feed(bytes(header("QUIT", 0), request("RECV", "/data/secret")))
    }
}
