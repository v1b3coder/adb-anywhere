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
    private const val ID_DONE = 0x454e4f44 // DONE
    private const val ID_STAT = 0x54415453 // STAT
    private const val ID_LSTAT = 0x4154534c // LSTAT
    private const val ID_LIST = 0x5453494c // LIST
    private const val ID_SEND = 0x444e4553 // SEND
    private const val ID_RECV = 0x56434552 // RECV
    private const val ID_STAT_V2 = 0x32415453 // STA2
    private const val ID_LSTAT_V2 = 0x3253544c // LST2
    private const val ID_LIST_V2 = 0x3253494c // LIS2
    private const val ID_SEND_V2 = 0x32444e53 // SND2
    private const val ID_RECV_V2 = 0x32564352 // RCV2
    private const val MAX_PATH = 1024

    fun parse(payload: ByteArray): SyncRequest? {
        if (payload.size < 8) return null
        val id = le32(payload, 0)
        val length = le32(payload, 4)
        if (length < 0 || length > MAX_PATH || 8 + length > payload.size) return null
        val path = String(payload, 8, length, Charsets.UTF_8)
        val direction = when (id) {
            ID_SEND, ID_SEND_V2 -> "SEND"
            ID_RECV, ID_RECV_V2 -> "RECV"
            ID_STAT, ID_STAT_V2 -> "STAT"
            ID_LSTAT, ID_LSTAT_V2 -> "LSTAT"
            ID_LIST, ID_LIST_V2 -> "LIST"
            else -> null
        }
        return SyncRequest(id, path, direction)
    }

    /** A DATA frame carries file bytes, so it is never parsed as a command. */
    fun isDone(payload: ByteArray): Boolean =
        payload.size >= 4 && le32(payload, 0) == ID_DONE

    private fun le32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)
}
