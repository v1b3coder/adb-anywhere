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

    /** Prompts this connection is waiting on; a pending one defers the app-allowlist close. */
    val pendingPrompts: Int get() = prompts.get()

    fun start() {
        scope.launch { clientToServer() }
        scope.launch { serverToClient() }
        if (authTimeoutMs > 0) {
            scope.launch {
                delay(authTimeoutMs)
                if (!authenticated) {
                    audit.record(AuditEvent(event = "proxy_error", peer = peer, detail = "authentication timed out"))
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
                        }
                        toServer(frame.raw)
                    }
                    AdbFrameCodec.A_STLS -> {
                        audit.record(
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
                audit.record(AuditEvent(event = "proxy_error", peer = peer, detail = e.message ?: "relay failed"))
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
                        audit.record(AuditEvent(event = "auth_ok", peer = peer, fingerprint = clientFingerprint))
                        toClient(frame.raw)
                    }
                    AdbFrameCodec.A_AUTH -> {
                        if (frame.arg0 == AdbFrameCodec.ADB_AUTH_TOKEN) lastToken = frame.payload
                        toClient(frame.raw)
                    }
                    AdbFrameCodec.A_OKAY -> {
                        synchronized(lock) {
                            val state = streams[frame.arg1]
                            if (state != null && state.serverId == null) state.serverId = frame.arg0
                        }
                        toClient(frame.raw)
                    }
                    AdbFrameCodec.A_CLSE -> {
                        synchronized(lock) {
                            val state = streams[frame.arg1]
                            if (state != null && (state.serverId == null || state.serverId == frame.arg0)) {
                                streams.remove(frame.arg1)
                            }
                        }
                        toClient(frame.raw)
                    }
                    else -> toClient(frame.raw)
                }
            }
        } catch (e: Exception) {
            if (!closed.get()) {
                audit.record(AuditEvent(event = "proxy_error", peer = peer, detail = e.message ?: "relay failed"))
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
                    audit.record(
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
        audit.record(AuditEvent(event = "call_open", peer = peer, service = call.service, fields = call.fields))
        if (!authenticated) {
            deny(localId, call, "unauthenticated")
            return
        }
        if (localId == 0) {
            deny(localId, call, "protocol")
            return
        }
        val reused = synchronized(lock) { streams.containsKey(localId) || localId in denied }
        if (reused) {
            // A live id reused for another OPEN would let the relay's view of the
            // stream and adbd's diverge. A conforming client never does this.
            audit.record(AuditEvent(event = "proxy_error", peer = peer, detail = "stream id $localId reused"))
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
        audit.record(
            AuditEvent(event = "call_allowed", peer = peer, service = call.service, fields = call.fields, rule = rule),
        )
        toServer(frame.raw)
    }

    private fun handleClientOkay(frame: AdbFrame) {
        val forward = synchronized(lock) {
            val state = streams[frame.arg0]
            state != null && (state.serverId == null || state.serverId == frame.arg1)
        }
        if (forward) toServer(frame.raw)
    }

    private fun handleClientClose(frame: AdbFrame) {
        val localId = frame.arg0
        val forward = synchronized(lock) {
            if (denied.remove(localId)) return@synchronized false
            val state = streams[localId] ?: return@synchronized false
            if (state.held != null && state.serverId == null) {
                // The client gave up on a call the device never saw; the
                // cancelled prompt releases its slot.
                streams.remove(localId)
                state.job?.cancel()
                return@synchronized false
            }
            // A close that names another device stream must not drop this
            // stream's state: adbd ignores it and keeps the stream open.
            if (state.serverId == null || state.serverId == frame.arg1) streams.remove(localId)
            true
        }
        if (forward) toServer(frame.raw)
    }

    private fun handleWrite(frame: AdbFrame) {
        val state = synchronized(lock) {
            if (frame.arg0 in denied) return
            val state = streams[frame.arg0] ?: return
            if (state.serverId == null || state.serverId != frame.arg1) return
            val held = state.held
            if (held != null) {
                held += frame
                return
            }
            state
        }
        process(frame.arg0, state, frame)
    }

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
            audit.record(
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
                CallDecision.ALLOW -> audit.record(
                    AuditEvent(event = "call_allowed", peer = peer, service = call.service, fields = call.fields, rule = verdict.ruleId ?: ""),
                )
                CallDecision.DENY -> {
                    audit.record(
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
                    audit.record(
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
                    return
                }
                held.removeAt(0)
            }
            if (process(localId, state, next)) return
        }
    }

    private fun deny(localId: Int, call: AdbCall, rule: String) {
        audit.record(
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
        @Volatile
        var job: kotlinx.coroutines.Job? = null
    }
}
