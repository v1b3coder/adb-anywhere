/*
 * AdbClient - the app's own ADB client. It exists so the app decides when a
 * token is signed: libadb signs any plain AUTH token a server sends, which turns
 * the app key into a signing oracle for whoever owns the port it connects to.
 *
 * Modes:
 * - Plain: plain ADB key auth. Only for the system adbd's plain 5555 listener,
 *   whose owner is fixed by construction.
 * - Tls: the server must answer A_STLS; a plain AUTH token is refused unsigned.
 *   With a pin, the server key must be the app's daemon key. With innerAuth,
 *   ADB key auth runs inside the verified TLS channel (the daemon tunnel).
 */
package cz.slush.adbanywhere.adb

import cz.slush.adbanywhere.firewall.AdbFrameCodec
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLException

sealed interface AdbMode {
    /** Plain key auth; only for a listener the app identified by construction. */
    data object Plain : AdbMode

    /** TLS required. [serverPin] pins the server key; [innerAuth] signs inside TLS. */
    class Tls(val serverPin: ByteArray? = null, val innerAuth: Boolean = false) : AdbMode
}

/** The TLS listener rejected the client certificate: the key is not paired. */
class AdbPairingRequiredException(message: String) : IOException(message)

/** The server broke the mode's rules, for example a plain token on a TLS-only connection. */
open class AdbProtocolException(message: String) : IOException(message)

/** The server asked for a second token: it rejected the signature of a key it does not trust. */
class AdbKeyRejectedException : AdbProtocolException("the server rejected the key")

/** A plain AUTH token arrived where only a verified TLS channel may be signed for. */
class AdbPlainTokenRefusedException : AdbProtocolException(
    "refusing to sign a plain ADB token on a TLS-only connection",
)

/** Anything that can open ADB streams: the client, or a fake in tests. */
interface AdbChannel : Closeable {
    fun openStream(service: String): AdbStream
    override fun close()
}

class AdbClient private constructor(
    private val socket: Socket,
    private val input: InputStream,
    private val output: OutputStream,
    private val maxData: Int,
    val tls: Boolean,
) : AdbChannel {
    private val streams = ConcurrentHashMap<Int, AdbStream>()
    private val nextId = AtomicInteger(1)
    private val writeLock = Any()
    private val closed = AtomicBoolean(false)

    val isConnected: Boolean get() = !closed.get()

    private val reader = Thread({ readLoop() }, "adb-client-reader").apply {
        isDaemon = true
    }

    private fun startReader() = reader.start()

    override fun openStream(service: String): AdbStream {
        if (closed.get()) throw IOException("ADB connection is closed")
        val id = nextId.getAndIncrement()
        val stream = AdbStream(this, id)
        streams[id] = stream
        send(AdbFrameCodec.A_OPEN, id, 0, (service + "\u0000").toByteArray(Charsets.UTF_8))
        if (!stream.awaitOpen(OPEN_TIMEOUT_MS)) {
            streams.remove(id)
            throw IOException("the device refused $service")
        }
        return stream
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { socket.close() }
        streams.values.forEach { it.remoteClosed() }
        streams.clear()
    }

    internal fun send(command: Int, arg0: Int, arg1: Int, payload: ByteArray) {
        val frame = frame(command, arg0, arg1, payload)
        synchronized(writeLock) {
            output.write(frame)
            output.flush()
        }
    }

    internal fun write(stream: AdbStream, data: ByteArray, offset: Int, length: Int) {
        var position = offset
        val end = offset + length
        while (position < end) {
            val chunk = minOf(maxData, end - position)
            stream.acquireCredit()
            send(AdbFrameCodec.A_WRTE, stream.localId, stream.remoteId, data.copyOfRange(position, position + chunk))
            position += chunk
        }
    }

    internal fun closeStream(stream: AdbStream) {
        streams.remove(stream.localId)
        if (!closed.get()) {
            runCatching { send(AdbFrameCodec.A_CLSE, stream.localId, stream.remoteId, ByteArray(0)) }
        }
    }

    private fun readLoop() {
        try {
            while (!closed.get()) {
                val frame = AdbFrameCodec.read(input) ?: break
                when (frame.command) {
                    AdbFrameCodec.A_OKAY -> streams[frame.arg1]?.okay(frame.arg0)
                    AdbFrameCodec.A_WRTE -> {
                        val stream = streams[frame.arg1]
                        if (stream != null) {
                            stream.deliver(frame.payload)
                            runCatching {
                                send(AdbFrameCodec.A_OKAY, stream.localId, frame.arg0, ByteArray(0))
                            }
                        }
                    }
                    AdbFrameCodec.A_CLSE -> streams.remove(frame.arg1)?.remoteClosed()
                    else -> Unit
                }
            }
        } catch (_: Exception) {
            // The connection is gone; every stream sees EOF below.
        } finally {
            close()
        }
    }

    companion object {
        const val A_VERSION = 0x01000001
        const val A_STLS_VERSION = 0x01000000
        const val MAX_PAYLOAD = 1 shl 20
        private const val OPEN_TIMEOUT_MS = 10_000L
        private const val BANNER =
            "host::features=shell_v2,cmd,stat_v2,ls_v2,fixed_push_mkdir,abb_exec,sendrecv_v2\u0000"

        /**
         * Connects and authenticates. Throws [AdbPairingRequiredException] when a TLS
         * listener rejects the key, [AdbServerIdentityException] when a pinned
         * server presents another key, and [AdbProtocolException] when the server
         * asks for something the mode forbids. Never signs a plain token outside
         * [AdbMode.Plain] or a pinned tunnel.
         */
        fun connect(
            host: String,
            port: Int,
            identity: AdbIdentity,
            mode: AdbMode,
            timeoutMs: Int = 10_000,
        ): AdbClient {
            val socket = Socket()
            try {
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                socket.soTimeout = timeoutMs
                return handshake(socket, identity, mode)
            } catch (e: Exception) {
                runCatching { socket.close() }
                throw e
            }
        }

        private fun handshake(plain: Socket, identity: AdbIdentity, mode: AdbMode): AdbClient {
            var socket: Socket = plain
            var input: InputStream = plain.getInputStream()
            var output: OutputStream = plain.getOutputStream()
            var tls = false
            var signed = false
            output.write(frame(AdbFrameCodec.A_CNXN, A_VERSION, MAX_PAYLOAD, BANNER.toByteArray()))
            output.flush()
            while (true) {
                val frame = try {
                    AdbFrameCodec.read(input, AdbFrameCodec.PRE_AUTH_MAX_PAYLOAD)
                        ?: throw EOFException("the server closed the connection")
                } catch (e: IOException) {
                    // TLS 1.3 tells the client about a rejected certificate only on
                    // the first read after the handshake.
                    if (tls && mode is AdbMode.Tls && mode.serverPin == null) {
                        throw AdbPairingRequiredException("the TLS listener rejected the key")
                    }
                    throw e
                }
                when (frame.command) {
                    AdbFrameCodec.A_STLS -> {
                        if (mode !is AdbMode.Tls) {
                            throw AdbProtocolException("the plain listener asked for TLS")
                        }
                        if (tls) throw AdbProtocolException("a second TLS upgrade")
                        output.write(frame(AdbFrameCodec.A_STLS, A_STLS_VERSION, 0, ByteArray(0)))
                        output.flush()
                        val ssl = try {
                            AdbTls.upgrade(plain, identity, mode.serverPin)
                        } catch (e: SSLException) {
                            if (mode.serverPin == null) {
                                throw AdbPairingRequiredException("the TLS listener rejected the key")
                            }
                            throw e
                        }
                        socket = ssl
                        input = ssl.inputStream
                        output = ssl.outputStream
                        tls = true
                    }
                    AdbFrameCodec.A_AUTH -> {
                        if (frame.arg0 != AdbFrameCodec.ADB_AUTH_TOKEN) {
                            throw AdbProtocolException("unexpected AUTH type ${frame.arg0}")
                        }
                        val allowed = when (mode) {
                            AdbMode.Plain -> true
                            is AdbMode.Tls -> tls && mode.innerAuth && mode.serverPin != null
                        }
                        if (!allowed) throw AdbPlainTokenRefusedException()
                        // A second token means the first signature was rejected. The
                        // public key is never offered: unknown keys are never prompted.
                        if (signed) throw AdbKeyRejectedException()
                        val token = frame.payload
                        if (token.size != AdbTls.TOKEN_SIZE) {
                            throw AdbProtocolException("bad ADB token size ${token.size}")
                        }
                        output.write(
                            frame(
                                AdbFrameCodec.A_AUTH,
                                AdbFrameCodec.ADB_AUTH_SIGNATURE,
                                0,
                                AdbTls.signToken(identity.privateKey, token),
                            ),
                        )
                        output.flush()
                        signed = true
                    }
                    AdbFrameCodec.A_CNXN -> {
                        if (mode is AdbMode.Tls && !tls) {
                            throw AdbProtocolException("the server skipped the TLS upgrade")
                        }
                        if (mode is AdbMode.Tls && mode.innerAuth && !signed) {
                            throw AdbProtocolException("the tunnel skipped key auth")
                        }
                        socket.soTimeout = 0
                        val maxData = frame.arg1.takeIf { it in 1..MAX_PAYLOAD } ?: (256 * 1024)
                        return AdbClient(socket, input, output, maxData, tls).also { it.startReader() }
                    }
                    else -> throw AdbProtocolException("unexpected frame ${frame.command} before CNXN")
                }
            }
        }

        /**
         * True when [host]:[port] is an ADB listener that asks for TLS. It sends
         * CNXN and reads one frame; it never signs, never handshakes and never
         * opens a stream, so it is safe against any listener.
         */
        fun offersTls(host: String, port: Int, timeoutMs: Int = 600): Boolean = runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                socket.soTimeout = timeoutMs
                socket.getOutputStream().write(
                    frame(AdbFrameCodec.A_CNXN, A_VERSION, MAX_PAYLOAD, BANNER.toByteArray()),
                )
                val reply = AdbFrameCodec.read(socket.getInputStream(), AdbFrameCodec.PRE_AUTH_MAX_PAYLOAD)
                reply?.command == AdbFrameCodec.A_STLS
            }
        }.getOrDefault(false)

        fun frame(command: Int, arg0: Int, arg1: Int, payload: ByteArray): ByteArray {
            val out = java.io.ByteArrayOutputStream(AdbFrameCodec.HEADER + payload.size)
            AdbFrameCodec.write(out, command, arg0, arg1, payload)
            return out.toByteArray()
        }
    }
}

/** One ADB stream. Reads block until data or close; writes wait for the device's OKAY. */
class AdbStream internal constructor(
    private val client: AdbClient,
    internal val localId: Int,
) : Closeable {
    @Volatile
    internal var remoteId = 0
        private set
    private val opened = CountDownLatch(1)
    @Volatile
    private var accepted = false
    private val queue = LinkedBlockingQueue<ByteArray>()
    private val credit = Semaphore(0)
    private val closed = AtomicBoolean(false)

    internal fun awaitOpen(timeoutMs: Long): Boolean =
        opened.await(timeoutMs, TimeUnit.MILLISECONDS) && accepted

    internal fun okay(remote: Int) {
        if (opened.count > 0) {
            remoteId = remote
            accepted = true
            credit.release()
            opened.countDown()
        } else {
            credit.release()
        }
    }

    internal fun deliver(data: ByteArray) {
        if (data.isNotEmpty()) queue.put(data)
    }

    internal fun remoteClosed() {
        closed.set(true)
        opened.countDown()
        queue.put(EOF)
        // Unblock a writer waiting for credit; it then sees the close.
        credit.release(Int.MAX_VALUE / 2)
    }

    internal fun acquireCredit() {
        credit.acquire()
        if (closed.get()) throw IOException("stream closed")
    }

    val isClosed: Boolean get() = closed.get()

    private val inputStream = object : InputStream() {
        private var current: ByteArray? = null
        private var offset = 0

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            var buffer = current
            if (buffer == null || offset >= buffer.size) {
                buffer = queue.take()
                if (buffer === EOF) {
                    queue.put(EOF)
                    current = null
                    return -1
                }
                current = buffer
                offset = 0
            }
            val count = minOf(len, buffer.size - offset)
            System.arraycopy(buffer, offset, b, off, count)
            offset += count
            return count
        }
    }

    private val outputStream = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (closed.get()) throw IOException("stream closed")
            client.write(this@AdbStream, b, off, len)
        }
    }

    fun openInputStream(): InputStream = inputStream

    fun openOutputStream(): OutputStream = outputStream

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        client.closeStream(this)
        queue.put(EOF)
        credit.release(Int.MAX_VALUE / 2)
    }

    private companion object {
        val EOF = ByteArray(0)
    }
}
