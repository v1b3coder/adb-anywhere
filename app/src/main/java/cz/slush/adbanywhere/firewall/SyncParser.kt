/*
 * SyncParser - reads the first client frame of an adb sync stream. It extracts the
 * direction and path the call policy can match, and never reads file contents.
 */
package cz.slush.adbanywhere.firewall

data class SyncRequest(
    val id: Int,
    val path: String,
    val direction: String?,
)

object SyncParser {
    private const val ID_STAT = 0x54415453 // STAT
    private const val ID_LSTAT = 0x4154534c // LSTAT
    private const val ID_LIST = 0x5453494c // LIST
    private const val ID_SEND = 0x444e4553 // SEND
    private const val ID_RECV = 0x56434552 // RECV
    private const val MAX_PATH = 1024

    fun parse(payload: ByteArray): SyncRequest? {
        if (payload.size < 8) return null
        val id = le32(payload, 0)
        val length = le32(payload, 4)
        if (length < 0 || length > MAX_PATH || 8 + length > payload.size) return null
        val path = String(payload, 8, length, Charsets.UTF_8)
        val direction = when (id) {
            ID_SEND -> "SEND"
            ID_RECV -> "RECV"
            ID_STAT -> "STAT"
            ID_LSTAT -> "LSTAT"
            ID_LIST -> "LIST"
            else -> null
        }
        return SyncRequest(id, path, direction)
    }

    private fun le32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)
}
