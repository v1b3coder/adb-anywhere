/*
 * AdbFrameCodec - reads and writes the 24-byte ADB transport header. It keeps
 * the original header and payload bytes so a relay can forward them unchanged.
 */
package cz.slush.adbanywhere.firewall

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

data class AdbFrame(
    val command: Int,
    val arg0: Int,
    val arg1: Int,
    val raw: ByteArray,
) {
    val payload: ByteArray get() = raw.copyOfRange(AdbFrameCodec.HEADER, raw.size)
}

object AdbFrameCodec {
    const val HEADER = 24
    const val MAX_PAYLOAD = 1 shl 20

    /**
     * Before the server's A_CNXN the transport is unauthenticated, so a peer
     * can only send the small handshake frames. The 4 KiB cap keeps an
     * unauthenticated peer from forcing a 1 MiB allocation per connection.
     */
    const val PRE_AUTH_MAX_PAYLOAD = 4 * 1024

    const val A_SYNC = 0x434e5953
    const val A_CNXN = 0x4e584e43
    const val A_OPEN = 0x4e45504f
    const val A_OKAY = 0x59414b4f
    const val A_CLSE = 0x45534c43
    const val A_WRTE = 0x45545257
    const val A_AUTH = 0x48545541
    const val A_STLS = 0x534c5453

    /** A_AUTH types, little-endian in the first payload word. */
    const val ADB_AUTH_TOKEN = 1
    const val ADB_AUTH_SIGNATURE = 2
    const val ADB_AUTH_RSAPUBLICKEY = 3

    fun read(input: InputStream, maxPayload: Int = MAX_PAYLOAD): AdbFrame? {
        val header = readFully(input, HEADER) ?: return null
        val command = le32(header, 0)
        val arg0 = le32(header, 4)
        val arg1 = le32(header, 8)
        val length = le32(header, 12)
        val magic = le32(header, 20)
        if (magic != command.inv()) throw IOException("bad ADB magic")
        if (length < 0 || length > maxPayload) throw IOException("bad ADB payload length $length")
        val payload = readFully(input, length) ?: throw EOFException("truncated ADB payload")
        return AdbFrame(command, arg0, arg1, header + payload)
    }

    fun write(
        output: OutputStream,
        command: Int,
        arg0: Int,
        arg1: Int,
        payload: ByteArray,
    ) {
        val header = ByteArray(HEADER)
        put32(header, 0, command)
        put32(header, 4, arg0)
        put32(header, 8, arg1)
        put32(header, 12, payload.size)
        put32(header, 16, payload.sumOf { it.toInt() and 0xff })
        put32(header, 20, command.inv())
        output.write(header)
        output.write(payload)
        output.flush()
    }

    fun close(output: OutputStream, clientLocalId: Int) {
        write(output, A_CLSE, 0, clientLocalId, ByteArray(0))
    }

    fun service(frame: AdbFrame): String =
        String(frame.payload, Charsets.UTF_8).trimEnd('\u0000')

    /** The A_AUTH type in the first payload word, or 0 when absent. */
    fun authType(frame: AdbFrame): Int {
        val payload = frame.payload
        return if (payload.size < 4) 0 else le32(payload, 0)
    }

    /** The A_AUTH public key that follows the type word. */
    fun authKey(frame: AdbFrame): ByteArray {
        val payload = frame.payload
        return if (payload.size <= 4) ByteArray(0) else payload.copyOfRange(4, payload.size)
    }

    private fun readFully(input: InputStream, size: Int): ByteArray? {
        if (size == 0) return ByteArray(0)
        val buffer = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val read = input.read(buffer, offset, size - offset)
            if (read < 0) {
                if (offset == 0) return null
                throw EOFException("truncated ADB frame")
            }
            offset += read
        }
        return buffer
    }

    private fun le32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)

    private fun put32(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xff).toByte()
        bytes[offset + 1] = ((value ushr 8) and 0xff).toByte()
        bytes[offset + 2] = ((value ushr 16) and 0xff).toByte()
        bytes[offset + 3] = ((value ushr 24) and 0xff).toByte()
    }
}
