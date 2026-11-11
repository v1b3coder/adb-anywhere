/*
 * SyncParser - the adb sync protocol on the client-to-device side.
 *
 * Sync is a byte stream: ADB A_WRTE frame boundaries do not line up with sync
 * requests. A client may pack several requests into one frame (adb push of
 * small files writes SEND, DATA and DONE at once) or split one request across
 * frames. [SyncStream] therefore follows the stream byte by byte: every request
 * that carries a path is reported, DATA chunks are skipped by their exact
 * length, and anything that is not valid sync is malformed. It never reads file
 * contents.
 */
package cz.slush.adbanywhere.firewall

data class SyncRequest(
    val id: Int,
    val path: String,
    val direction: String?,
)

class SyncMalformedException(message: String) : Exception(message)

object SyncParser {
    internal const val ID_DATA = 0x41544144 // DATA
    internal const val ID_DONE = 0x454e4f44 // DONE
    internal const val ID_QUIT = 0x54495551 // QUIT
    private const val ID_STAT = 0x54415453 // STAT (v1; adbd answers it with lstat)
    private const val ID_LIST = 0x5453494c // LIST
    internal const val ID_SEND = 0x444e4553 // SEND
    private const val ID_RECV = 0x56434552 // RECV
    private const val ID_STAT_V2 = 0x32415453 // STA2
    private const val ID_LSTAT_V2 = 0x3254534c // LST2
    private const val ID_LIST_V2 = 0x3253494c // LIS2
    internal const val ID_SEND_V2 = 0x32444e53 // SND2
    internal const val ID_RECV_V2 = 0x32564352 // RCV2
    const val MAX_PATH = 1024

    /** sync DATA chunks are at most 64 KiB (SYNC_DATA_MAX). */
    const val MAX_DATA = 64 * 1024

    fun direction(id: Int): String? = when (id) {
        ID_SEND, ID_SEND_V2 -> "SEND"
        ID_RECV, ID_RECV_V2 -> "RECV"
        ID_STAT, ID_STAT_V2 -> "STAT"
        ID_LSTAT_V2 -> "LSTAT"
        ID_LIST, ID_LIST_V2 -> "LIST"
        else -> null
    }

    /** Parses one complete request at the start of [payload]; null when incomplete or unknown. */
    fun parse(payload: ByteArray): SyncRequest? {
        if (payload.size < 8) return null
        val id = le32(payload, 0)
        val length = le32(payload, 4)
        if (length < 0 || length > MAX_PATH || 8 + length > payload.size) return null
        val direction = direction(id)
        val raw = String(payload, 8, length, Charsets.UTF_8)
        return SyncRequest(id, normalizePath(id, raw), direction)
    }

    /** SEND v1 carries "path,mode"; adbd splits at the last comma. */
    internal fun normalizePath(id: Int, raw: String): String =
        if (id == ID_SEND && raw.contains(',')) raw.substringBeforeLast(',') else raw

    internal fun le32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)
}

/**
 * Follows one sync stream from the client. [feed] returns every request that
 * completed inside the given bytes, in order; a partial request is kept until
 * the next feed. Malformed input throws, and the stream must then be closed.
 */
class SyncStream {
    private enum class State { HEADER, PATH, SETUP, DATA_HEADER, DATA, QUIT }

    private var state = State.HEADER
    private val buffer = java.io.ByteArrayOutputStream()
    private var id = 0
    private var need = 8
    private var skip = 0L

    fun feed(bytes: ByteArray): List<SyncRequest> {
        val requests = mutableListOf<SyncRequest>()
        var index = 0
        while (index < bytes.size) {
            when (state) {
                State.QUIT -> throw SyncMalformedException("data after QUIT")
                State.DATA -> {
                    val take = minOf(skip, (bytes.size - index).toLong()).toInt()
                    index += take
                    skip -= take
                    if (skip == 0L) next(State.DATA_HEADER, 8)
                }
                else -> {
                    val take = minOf(need - buffer.size(), bytes.size - index)
                    buffer.write(bytes, index, take)
                    index += take
                    if (buffer.size() == need) complete(buffer.toByteArray(), requests)
                }
            }
        }
        return requests
    }

    private fun complete(chunk: ByteArray, requests: MutableList<SyncRequest>) {
        buffer.reset()
        when (state) {
            State.HEADER -> {
                id = SyncParser.le32(chunk, 0)
                val length = SyncParser.le32(chunk, 4)
                when {
                    id == SyncParser.ID_QUIT -> next(State.QUIT, 0)
                    SyncParser.direction(id) == null ->
                        throw SyncMalformedException("unknown sync request ${Integer.toHexString(id)}")
                    length < 0 || length > SyncParser.MAX_PATH ->
                        throw SyncMalformedException("bad sync path length $length")
                    length == 0 -> {
                        report(ByteArray(0), requests)
                    }
                    else -> next(State.PATH, length)
                }
            }
            State.PATH -> report(chunk, requests)
            State.SETUP -> {
                if (SyncParser.le32(chunk, 0) != id) throw SyncMalformedException("bad sync v2 setup")
                if (id == SyncParser.ID_SEND_V2) next(State.DATA_HEADER, 8) else next(State.HEADER, 8)
            }
            State.DATA_HEADER -> {
                val chunkId = SyncParser.le32(chunk, 0)
                val value = SyncParser.le32(chunk, 4)
                when (chunkId) {
                    SyncParser.ID_DATA -> {
                        if (value < 0 || value > SyncParser.MAX_DATA) {
                            throw SyncMalformedException("bad sync DATA size $value")
                        }
                        if (value == 0) next(State.DATA_HEADER, 8) else {
                            skip = value.toLong()
                            next(State.DATA, 0)
                        }
                    }
                    SyncParser.ID_DONE -> next(State.HEADER, 8)
                    else -> throw SyncMalformedException("expected DATA or DONE")
                }
            }
            State.DATA, State.QUIT -> Unit
        }
    }

    private fun report(path: ByteArray, requests: MutableList<SyncRequest>) {
        val raw = String(path, Charsets.UTF_8)
        requests += SyncRequest(id, SyncParser.normalizePath(id, raw), SyncParser.direction(id))
        when (id) {
            SyncParser.ID_SEND -> next(State.DATA_HEADER, 8)
            SyncParser.ID_SEND_V2 -> next(State.SETUP, 12)
            SyncParser.ID_RECV_V2 -> next(State.SETUP, 8)
            else -> next(State.HEADER, 8)
        }
    }

    private fun next(state: State, need: Int) {
        this.state = state
        this.need = need
    }
}
