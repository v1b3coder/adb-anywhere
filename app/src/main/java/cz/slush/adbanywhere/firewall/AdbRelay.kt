/*
 * AdbRelay - one relayed ADB transport. It reads frames, applies the call
 * policy at A_OPEN and at every sync request, and forwards the original bytes.
 *
 * Rules that keep the policy sound (docs/FIREWALL.md §7.3, §12):
 * - Streams are tracked by both ids. A frame for a stream the relay does not
 *   know, or with a device id that does not match, is dropped, never forwarded:
 *   an unknown stream fails closed.
 * - A sync stream is followed as a byte stream (SyncStream), so requests packed
 *   into one frame or hidden in a data phase are each evaluated.
 * - A prompt holds only its own stream. The connection keeps flowing, and the
 *   held stream's later frames wait in order until the answer.
 * - The relay refuses an A_OPEN before the device's A_CNXN, and an
 *   unauthenticated transport is closed after an absolute deadline.
 * - The key is attributed from a signature the relay verifies against the
 *   device's own token, never from what the client claims.
 * - Every write to a side goes through one lock, so an injected A_CLSE can never
 *   interleave with a forwarded frame.
 * - A client close before the device's A_OKAY keeps the stream as a tombstone:
 *   adbd ignores a close without its id, so the relay closes the device stream
 *   itself when the A_OKAY arrives, and the id cannot be reused meanwhile. A
 *   device id the relay has retired never attaches to a new stream.
 * - Streams the device opens (`adb reverse`) are tracked by both ids once the
 *   host accepts them; they carry data to a phone-side socket, not to a service.
 * - A held stream buffers at most [MAX_HELD_BYTES]; more closes the stream.
 * - Events before authentication go through the audit log's rate limit, so an
 *   unauthenticated peer cannot rotate the audit trail away.
 */
package cz.slush.adbanywhere.firewall

import cz.slush.adbanywhere.adb.AdbTls
import cz.slush.adbanywhere.data.AndroidPubKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream
import java.security.PublicKey
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** A paired key the relay can attribute a transport to. */
data class KnownKey(val fingerprint: String, val publicKey: PublicKey)

class AdbRelay(
    private val clientIn: InputStream,
    private val clientOut: OutputStream,
    private val serverIn: InputStream,
    private val serverOut: OutputStream,
    private val policy: RelayPolicy,
    private val audit: AuditLog,
    private val peer: String,
    private val connectionAllowed: () -> Boolean = { true },
    private val onAuthenticated: () -> Unit = {},
    private val knownKeys: () -> List<KnownKey> = { emptyList() },
    private val authTimeoutMs: Long = 0L,
    private val onPromptFinished: () -> Unit = {},
    private val onClose: () -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val streams = mutableMapOf<Int, StreamState>()
    private val denied = mutableSetOf<Int>()

    /** Device ids of streams that are closed; a late A_OKAY naming one is stale. */
    private val retired = object : LinkedHashSet<Int>() {}

    /** Device ids of streams the device opened that the host has not answered yet. */
    private val pendingReverse = mutableSetOf<Int>()

    /** Host id to device id of the accepted device-opened (reverse) streams. */
    private val reverse = mutableMapOf<Int, Int>()
    private val closed = AtomicBoolean(false)
    private val prompts = AtomicInteger(0)

    // The device sends A_CNXN only after the ADB handshake, so it is the relay's
    // signal that the transport is authenticated.
    @Volatile
    private var authenticated = false

    @Volatile
    private var lastToken: ByteArray? = null

    @Volatile
    private var signedBy = ""

    @Volatile
    private var clientFingerprint = ""

    private fun record(event: AuditEvent) = audit.record(event, unauthenticated = !authenticated)

    /** Remembers a closed device id; bounded, because adbd ids only grow. Caller holds [lock]. */
    private fun retire(serverId: Int) {
        retired.add(serverId)
        if (retired.size > MAX_RETIRED) retired.remove(retired.first())
    }

    /** Prompts this connection is waiting on; a pending one defers the app-allowlist close. */
    val pendingPrompts: Int get() = prompts.get()

    fun start() {
        scope.launch { clientToServer() }
        scope.launch { serverToClient() }
        if (authTimeoutMs > 0) {
            scope.launch {
                delay(authTimeoutMs)
                if (!authenticated) {
                    record(AuditEvent(event = "proxy_error", peer = peer, detail = "authentication timed out"))
                    close()
                }
            }
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        scope.cancel()
        runCatching { clientIn.close() }
        runCatching { clientOut.close() }
        runCatching { serverIn.close() }
        runCatching { serverOut.close() }
        onClose()
    }

    private fun clientToServer() {
        try {
            while (true) {
                // Until the device's A_CNXN only the small handshake frames are
                // valid, so an unauthenticated peer cannot force a 1 MiB buffer.
                val maxPayload = if (authenticated) AdbFrameCodec.MAX_PAYLOAD else AdbFrameCodec.PRE_AUTH_MAX_PAYLOAD
                val frame = AdbFrameCodec.read(clientIn, maxPayload) ?: break
                when (frame.command) {
                    AdbFrameCodec.A_OPEN -> handleOpen(frame)
                    AdbFrameCodec.A_AUTH -> handleAuth(frame)
                    AdbFrameCodec.A_WRTE -> handleWrite(frame)
                    AdbFrameCodec.A_OKAY -> handleClientOkay(frame)
                    AdbFrameCodec.A_CLSE -> handleClientClose(frame)
                    AdbFrameCodec.A_CNXN -> {
                        // A second CNXN resets the transport in adbd: it goes offline
                        // and authenticates again. Every stream is gone.
                        synchronized(lock) {
                            authenticated = false
                            signedBy = ""
                            lastToken = null
                            streams.values.forEach { it.job?.cancel() }
                            streams.clear()
                            denied.clear()
                            retired.clear()
                            pendingReverse.clear()
                            reverse.clear()
                        }
                        toServer(frame.raw)
                    }
                    AdbFrameCodec.A_STLS -> {
                        record(
                            AuditEvent(event = "call_denied", peer = peer, service = "stls", detail = "TLS ADB is not supported"),
                        )
                        close()
                        return
                    }
                    else -> toServer(frame.raw)
                }
            }
        } catch (e: Exception) {
            if (!closed.get()) {
                record(AuditEvent(event = "proxy_error", peer = peer, detail = e.message ?: "relay failed"))
            }
        } finally {
            close()
        }
    }

    private fun serverToClient() {
        try {
            while (true) {
                val frame = AdbFrameCodec.read(serverIn) ?: break
                when (frame.command) {
                    AdbFrameCodec.A_CNXN -> {
                        authenticated = true
                        clientFingerprint = signedBy.ifEmpty { clientFingerprint }
                        onAuthenticated()
                        record(AuditEvent(event = "auth_ok", peer = peer, fingerprint = clientFingerprint))
                        toClient(frame.raw)
                    }
                    AdbFrameCodec.A_AUTH -> {
                        if (frame.arg0 == AdbFrameCodec.ADB_AUTH_TOKEN) lastToken = frame.payload
                        toClient(frame.raw)
                    }
                    AdbFrameCodec.A_OPEN -> {
                        // A device-opened stream (adb reverse). The host answers
                        // with its own id in A_OKAY; until then only that answer is valid.
                        if (frame.arg0 != 0) synchronized(lock) { pendingReverse.add(frame.arg0) }
                        toClient(frame.raw)
                    }
                    AdbFrameCodec.A_OKAY -> {
                        val tombstone = synchronized(lock) {
                            val state = streams[frame.arg1]
                            when {
                                reverse[frame.arg1] == frame.arg0 -> false
                                state == null || state.serverId != null || frame.arg0 in retired -> false
                                state.clientClosed -> {
                                    // The client closed before adbd answered; close it now.
                                    streams.remove(frame.arg1)
                                    retire(frame.arg0)
                                    true
                                }
                                else -> {
                                    state.serverId = frame.arg0
                                    false
                                }
                            }
                        }
                        if (tombstone) {
                            toServer(frame(AdbFrameCodec.A_CLSE, frame.arg1, frame.arg0))
                        } else {
                            toClient(frame.raw)
                        }
                    }
                    AdbFrameCodec.A_CLSE -> {
                        val forward = synchronized(lock) {
                            pendingReverse.remove(frame.arg0)
                            if (reverse[frame.arg1] == frame.arg0) reverse.remove(frame.arg1)
                            val state = streams[frame.arg1]
                            if (state != null && (state.serverId == null || state.serverId == frame.arg0)) {
                                streams.remove(frame.arg1)
                                state.serverId?.let(::retire)
                                if (frame.arg0 != 0) retire(frame.arg0)
                                state.job?.cancel()
                                !state.clientClosed
                            } else {
                                true
                            }
                        }
                        if (forward) toClient(frame.raw)
                    }
                    else -> toClient(frame.raw)
                }
            }
        } catch (e: Exception) {
            if (!closed.get()) {
                record(AuditEvent(event = "proxy_error", peer = peer, detail = e.message ?: "relay failed"))
            }
        } finally {
            close()
        }
    }

    /**
     * ADB puts the AUTH type in arg0. A signature is attributed only when it
     * verifies over the device's last token with a paired key; a public key the
     * client offers is recorded as unknown, never as an identity.
     */
    private fun handleAuth(frame: AdbFrame) {
        when (frame.arg0) {
            AdbFrameCodec.ADB_AUTH_SIGNATURE -> {
                val token = lastToken
                if (token != null) {
                    val match = knownKeys().firstOrNull {
                        AdbTls.verifyToken(it.publicKey, token, frame.payload)
                    }
                    signedBy = match?.fingerprint ?: ""
                }
            }
            AdbFrameCodec.ADB_AUTH_RSAPUBLICKEY -> {
                val text = String(frame.payload, Charsets.UTF_8).trimEnd('\u0000')
                val fingerprint = runCatching { AndroidPubKey.parse(text)?.fingerprint }.getOrNull().orEmpty()
                if (fingerprint.isNotEmpty()) {
                    record(
                        AuditEvent(event = "auth_unknown", peer = peer, fingerprint = fingerprint, detail = "key offered"),
                    )
                }
            }
        }
        toServer(frame.raw)
    }

    private fun handleOpen(frame: AdbFrame) {
        val localId = frame.arg0
        val service = AdbFrameCodec.service(frame)
        val call = AdbCallParser.parse(service)
        record(AuditEvent(event = "call_open", peer = peer, service = call.service, fields = call.fields))
        if (!authenticated) {
            deny(localId, call, "unauthenticated")
            return
        }
        if (localId == 0) {
            deny(localId, call, "protocol")
            return
        }
        // adbd ends a service name at a NUL (`shell:` hands sh a C string), so the
        // relay would judge more than adbd runs. Only abb_exec separates its
        // arguments with NUL, and the parser splits them as adbd does.
        if (service.contains('\u0000') && call.service != "abb_exec") {
            deny(localId, call, "builtin:nul")
            return
        }
        val reused = synchronized(lock) {
            streams.containsKey(localId) || localId in denied || reverse.containsKey(localId)
        }
        if (reused) {
            // A live id reused for another OPEN would let the relay's view of the
            // stream and adbd's diverge. A conforming client never does this.
            record(AuditEvent(event = "proxy_error", peer = peer, detail = "stream id $localId reused"))
            close()
            return
        }
        if (!connectionAllowed()) {
            deny(localId, call, "app")
            return
        }
        if (call.service == "sync") {
            // sync: carries its paths in the stream, not in OPEN.
            synchronized(lock) { streams[localId] = StreamState(call, SyncStream()) }
            toServer(frame.raw)
            return
        }
        val verdict = policy.evaluate(call)
        when (verdict.decision) {
            CallDecision.ALLOW -> allowOpen(frame, call, verdict.ruleId ?: "")
            CallDecision.DENY -> deny(localId, call, verdict.ruleId ?: "")
            CallDecision.PROMPT -> {
                // Hold this stream only; the reader keeps relaying the others.
                val state = StreamState(call, null, held = mutableListOf())
                synchronized(lock) { streams[localId] = state }
                prompts.incrementAndGet()
                state.job = scope.launch {
                    val response = try {
                        policy.prompt(call, peer, clientFingerprint, verdict.timeoutMs)
                    } finally {
                        prompts.decrementAndGet()
                        onPromptFinished()
                    }
                    val stillHeld = synchronized(lock) { streams[localId] === state }
                    if (!stillHeld) return@launch
                    if (response != null && response.allowsCall && !closed.get()) {
                        synchronized(lock) { state.held = null }
                        allowOpen(frame, call, response.rule?.id ?: "prompt", registered = true)
                    } else {
                        synchronized(lock) { streams.remove(localId) }
                        deny(localId, call, response?.rule?.id ?: "prompt")
                    }
                }
            }
        }
    }

    private fun allowOpen(frame: AdbFrame, call: AdbCall, rule: String, registered: Boolean = false) {
        if (!registered) synchronized(lock) { streams[frame.arg0] = StreamState(call, null) }
        record(
            AuditEvent(event = "call_allowed", peer = peer, service = call.service, fields = call.fields, rule = rule),
        )
        toServer(frame.raw)
    }

    private fun handleClientOkay(frame: AdbFrame) {
        val forward = synchronized(lock) {
            val state = streams[frame.arg0]
            when {
                state != null -> !state.clientClosed && (state.serverId == null || state.serverId == frame.arg1)
                reverse[frame.arg0] == frame.arg1 -> true
                frame.arg0 != 0 && frame.arg1 in pendingReverse && frame.arg0 !in denied -> {
                    // The host accepted a device-opened stream under its own id.
                    pendingReverse.remove(frame.arg1)
                    reverse[frame.arg0] = frame.arg1
                    true
                }
                else -> false
            }
        }
        if (forward) toServer(frame.raw)
    }

    private fun handleClientClose(frame: AdbFrame) {
        val localId = frame.arg0
        val forward = synchronized(lock) {
            if (denied.remove(localId)) return@synchronized false
            val state = streams[localId]
            if (state == null) {
                if (reverse[localId] == frame.arg1) {
                    reverse.remove(localId)
                    return@synchronized true
                }
                // The host refused a device-opened stream.
                if (localId == 0 && pendingReverse.remove(frame.arg1)) return@synchronized true
                return@synchronized false
            }
            if (state.clientClosed) return@synchronized false
            val serverId = state.serverId
            if (serverId == null) {
                if (state.held != null) {
                    // The client gave up on a call the device never saw; the
                    // cancelled prompt releases its slot.
                    streams.remove(localId)
                    state.job?.cancel()
                    return@synchronized false
                }
                // adbd has not answered yet, so it would ignore this close and
                // keep its stream open. Keep a tombstone: the id stays taken and
                // the relay closes the device stream when its A_OKAY arrives.
                state.clientClosed = true
                return@synchronized false
            }
            // A close that names another device stream must not drop this
            // stream's state: adbd ignores it and keeps the stream open.
            if (serverId == frame.arg1) {
                streams.remove(localId)
                retire(serverId)
                state.job?.cancel()
            }
            true
        }
        if (forward) toServer(frame.raw)
    }

    private fun handleWrite(frame: AdbFrame) {
        var route = WriteRoute.DROP
        val state = synchronized(lock) {
            if (frame.arg0 in denied) return
            val state = streams[frame.arg0]
            when {
                // Data for a device-opened stream goes to a phone-side socket.
                state == null -> if (reverse[frame.arg0] == frame.arg1) route = WriteRoute.REVERSE
                state.clientClosed || state.serverId == null || state.serverId != frame.arg1 -> Unit
                state.held == null -> route = WriteRoute.PROCESS
                state.heldBytes + frame.raw.size > MAX_HELD_BYTES -> route = WriteRoute.OVERFLOW
                else -> {
                    state.held?.add(frame)
                    state.heldBytes += frame.raw.size
                }
            }
            state
        }
        when (route) {
            WriteRoute.DROP -> Unit
            WriteRoute.REVERSE -> toServer(frame.raw)
            WriteRoute.PROCESS -> process(frame.arg0, state!!, frame)
            WriteRoute.OVERFLOW -> {
                // A client that ignores flow control while a prompt holds the
                // stream must not grow the queue until the app runs out of memory.
                record(
                    AuditEvent(event = "proxy_error", peer = peer, service = state!!.call.service, detail = "held stream overflow"),
                )
                closeStream(frame.arg0, state)
            }
        }
    }

    private enum class WriteRoute { DROP, REVERSE, PROCESS, OVERFLOW }

    /**
     * Evaluates every sync request in the frame before the frame is forwarded.
     * Returns true when a prompt now holds the stream; the frames that follow
     * wait in [StreamState.held] and are replayed in order by [drain].
     */
    private fun process(localId: Int, state: StreamState, frame: AdbFrame): Boolean {
        val sync = state.sync
        if (sync == null) {
            toServer(frame.raw)
            return false
        }
        val requests = try {
            sync.feed(frame.payload)
        } catch (e: SyncMalformedException) {
            record(
                AuditEvent(event = "call_denied", peer = peer, service = "sync", rule = "sync:malformed", detail = e.message ?: ""),
            )
            closeStream(localId, state)
            return false
        }
        val pending = mutableListOf<Pair<AdbCall, CallVerdict>>()
        for (request in requests) {
            val direction = request.direction ?: continue
            val call = state.call.withSync(request.path, direction)
            val verdict = policy.evaluate(call)
            when (verdict.decision) {
                CallDecision.ALLOW -> record(
                    AuditEvent(event = "call_allowed", peer = peer, service = call.service, fields = call.fields, rule = verdict.ruleId ?: ""),
                )
                CallDecision.DENY -> {
                    record(
                        AuditEvent(event = "call_denied", peer = peer, service = call.service, fields = call.fields, rule = verdict.ruleId ?: ""),
                    )
                    closeStream(localId, state)
                    return false
                }
                CallDecision.PROMPT -> pending += call to verdict
            }
        }
        if (pending.isEmpty()) {
            toServer(frame.raw)
            return false
        }
        synchronized(lock) { if (state.held == null) state.held = mutableListOf() }
        prompts.incrementAndGet()
        state.job = scope.launch {
            var allowed = true
            try {
                for ((call, verdict) in pending) {
                    val response = policy.prompt(call, peer, clientFingerprint, verdict.timeoutMs)
                    val ok = response != null && response.allowsCall
                    record(
                        AuditEvent(
                            event = if (ok) "call_allowed" else "call_denied",
                            peer = peer,
                            service = call.service,
                            fields = call.fields,
                            rule = response?.rule?.id ?: "prompt",
                        ),
                    )
                    if (!ok) {
                        allowed = false
                        break
                    }
                }
            } finally {
                prompts.decrementAndGet()
                onPromptFinished()
            }
            val live = synchronized(lock) { streams[localId] === state }
            if (!live) return@launch
            if (!allowed) {
                closeStream(localId, state)
                return@launch
            }
            toServer(frame.raw)
            drain(localId, state)
        }
        return true
    }

    /** Replays the frames that arrived while the stream was held, in order. */
    private fun drain(localId: Int, state: StreamState) {
        while (true) {
            val next = synchronized(lock) {
                if (streams[localId] !== state) return
                val held = state.held ?: return
                if (held.isEmpty()) {
                    state.held = null
                    state.heldBytes = 0
                    return
                }
                held.removeAt(0).also { state.heldBytes -= it.raw.size }
            }
            if (process(localId, state, next)) return
        }
    }

    private fun deny(localId: Int, call: AdbCall, rule: String) {
        record(
            AuditEvent(event = "call_denied", peer = peer, service = call.service, fields = call.fields, rule = rule),
        )
        synchronized(lock) { denied.add(localId) }
        runCatching { toClient(frame(AdbFrameCodec.A_CLSE, 0, localId)) }
    }

    private fun closeStream(localId: Int, state: StreamState) {
        val serverId = state.serverId
        synchronized(lock) {
            streams.remove(localId)
            denied.add(localId)
            state.held = null
            state.heldBytes = 0
            serverId?.let(::retire)
        }
        if (serverId != null) {
            runCatching { toServer(frame(AdbFrameCodec.A_CLSE, localId, serverId)) }
            runCatching { toClient(frame(AdbFrameCodec.A_CLSE, serverId, localId)) }
        } else {
            runCatching { toClient(frame(AdbFrameCodec.A_CLSE, 0, localId)) }
        }
    }

    private fun toServer(bytes: ByteArray) = write(serverOut, bytes)

    private fun toClient(bytes: ByteArray) = write(clientOut, bytes)

    private fun write(output: OutputStream, bytes: ByteArray) {
        synchronized(output) {
            output.write(bytes)
            output.flush()
        }
    }

    private fun frame(command: Int, arg0: Int, arg1: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(AdbFrameCodec.HEADER)
        AdbFrameCodec.write(out, command, arg0, arg1, ByteArray(0))
        return out.toByteArray()
    }

    private class StreamState(
        val call: AdbCall,
        val sync: SyncStream?,
        var held: MutableList<AdbFrame>? = null,
    ) {
        var serverId: Int? = null
        var heldBytes = 0L
        /** The client closed before the device answered; see [handleClientClose]. */
        var clientClosed = false
        @Volatile
        var job: kotlinx.coroutines.Job? = null
    }

    companion object {
        /** What a held stream may buffer while its prompt is open. */
        const val MAX_HELD_BYTES = 4L * 1024 * 1024
        private const val MAX_RETIRED = 4096
    }
}
