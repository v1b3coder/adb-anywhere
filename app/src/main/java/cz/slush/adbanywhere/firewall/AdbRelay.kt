/*
 * AdbRelay - one relayed ADB transport. It reads frames, applies the call
 * policy at A_OPEN and at every sync command frame, and forwards the original
 * bytes. It refuses an A_OPEN before the server has authenticated the transport,
 * and it records which key offered a public key.
 */
package cz.slush.adbanywhere.firewall

import cz.slush.adbanywhere.data.AndroidPubKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

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
    private val onClose: () -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val streams = mutableMapOf<Int, StreamState>()
    private val denied = mutableSetOf<Int>()
    private val streamLock = Any()
    private val closed = AtomicBoolean(false)

    // The server sends A_CNXN only after the ADB handshake, so it is the relay's
    // signal that the transport is authenticated. Until then the relay refuses an
    // A_OPEN: a peer without a key must not reach the call policy or a prompt.
    @Volatile
    private var authenticated = false
    @Volatile
    private var clientFingerprint = ""
    private val authRecorded = AtomicBoolean(false)

    fun start() {
        scope.launch { clientToServer() }
        scope.launch { serverToClient() }
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

    private suspend fun clientToServer() {
        try {
            while (true) {
                // Until the server's A_CNXN only the small handshake frames are
                // valid, so an unauthenticated peer cannot force a 1 MiB buffer.
                val maxPayload = if (authenticated) {
                    AdbFrameCodec.MAX_PAYLOAD
                } else {
                    AdbFrameCodec.PRE_AUTH_MAX_PAYLOAD
                }
                val frame = AdbFrameCodec.read(clientIn, maxPayload) ?: break
                when (frame.command) {
                    AdbFrameCodec.A_OPEN -> handleOpen(frame)
                    AdbFrameCodec.A_AUTH -> handleAuth(frame)
                    AdbFrameCodec.A_WRTE -> handleWrite(frame)
                    AdbFrameCodec.A_CLSE -> {
                        val wasDenied = synchronized(streamLock) { denied.remove(frame.arg0) }
                        if (!wasDenied) forward(frame, serverOut)
                        remove(frame.arg0)
                    }
                    AdbFrameCodec.A_STLS -> {
                        audit.record(
                            AuditEvent(
                                event = "call_denied",
                                peer = peer,
                                service = "stls",
                                detail = "TLS ADB is not supported",
                            ),
                        )
                        close()
                        return
                    }
                    else -> forward(frame, serverOut)
                }
            }
        } catch (e: Exception) {
            if (!closed.get()) {
                audit.record(
                    AuditEvent(
                        event = "proxy_error",
                        peer = peer,
                        detail = e.message ?: "relay failed",
                    ),
                )
            }
        } finally {
            close()
        }
    }

    private suspend fun serverToClient() {
        try {
            while (true) {
                val frame = AdbFrameCodec.read(serverIn) ?: break
                when (frame.command) {
                    AdbFrameCodec.A_CNXN -> {
                        authenticated = true
                        onAuthenticated()
                        if (authRecorded.compareAndSet(false, true)) {
                            audit.record(
                                AuditEvent(
                                    event = "auth_ok",
                                    peer = peer,
                                    fingerprint = clientFingerprint,
                                ),
                            )
                        }
                        forward(frame, clientOut)
                    }
                    AdbFrameCodec.A_OKAY -> {
                        synchronized(streamLock) {
                            streams[frame.arg1]?.serverId = frame.arg0
                        }
                        forward(frame, clientOut)
                    }
                    AdbFrameCodec.A_CLSE -> {
                        remove(frame.arg1)
                        forward(frame, clientOut)
                    }
                    else -> forward(frame, clientOut)
                }
            }
        } catch (e: Exception) {
            if (!closed.get()) {
                audit.record(
                    AuditEvent(
                        event = "proxy_error",
                        peer = peer,
                        detail = e.message ?: "relay failed",
                    ),
                )
            }
        } finally {
            close()
        }
    }

    /** The client offers a public key; the server's A_CNXN proves it accepted it. */
    private fun handleAuth(frame: AdbFrame) {
        if (AdbFrameCodec.authType(frame) == AdbFrameCodec.ADB_AUTH_RSAPUBLICKEY) {
            val key = AdbFrameCodec.authKey(frame)
            val fingerprint = runCatching { AndroidPubKey.decode(key)?.fingerprint }
                .getOrNull()
                .orEmpty()
            if (fingerprint.isNotEmpty() && fingerprint != clientFingerprint) {
                clientFingerprint = fingerprint
                audit.record(
                    AuditEvent(
                        event = "auth_unknown",
                        peer = peer,
                        fingerprint = fingerprint,
                        detail = "key offered",
                    ),
                )
            }
        }
        forward(frame, serverOut)
    }

    private suspend fun handleOpen(frame: AdbFrame) {
        val service = AdbFrameCodec.service(frame)
        val call = AdbCallParser.parse(service)
        audit.record(
            AuditEvent(
                event = "call_open",
                peer = peer,
                service = call.service,
                fields = call.fields,
            ),
        )
        if (!authenticated) {
            deny(frame, call, "unauthenticated")
            return
        }
        if (!connectionAllowed()) {
            deny(frame, call, "app")
            return
        }
        if (call.service == "sync") {
            // sync: carries the path in its command frames, not in OPEN.
            synchronized(streamLock) { streams[frame.arg0] = StreamState(call) }
            forward(frame, serverOut)
            return
        }
        val verdict = policy.evaluate(call)
        when (verdict.decision) {
            CallDecision.ALLOW -> {
                synchronized(streamLock) { streams[frame.arg0] = StreamState(call) }
                audit.record(
                    AuditEvent(
                        event = "call_allowed",
                        peer = peer,
                        service = call.service,
                        fields = call.fields,
                        rule = verdict.ruleId ?: "",
                    ),
                )
                forward(frame, serverOut)
            }
            CallDecision.DENY -> deny(frame, call, verdict.ruleId ?: "")
            CallDecision.PROMPT -> {
                val response = policy.prompt(
                    call,
                    peer,
                    clientFingerprint,
                    verdict.timeoutMs,
                )
                if (response != null && response.allowsCall) {
                    synchronized(streamLock) { streams[frame.arg0] = StreamState(call) }
                    audit.record(
                        AuditEvent(
                            event = "call_allowed",
                            peer = peer,
                            service = call.service,
                            fields = call.fields,
                            rule = response.rule?.id ?: "prompt",
                        ),
                    )
                    forward(frame, serverOut)
                } else {
                    deny(frame, call, response?.rule?.id ?: "prompt")
                }
            }
        }
    }

    private suspend fun handleWrite(frame: AdbFrame) {
        if (synchronized(streamLock) { frame.arg0 in denied }) return
        val state = synchronized(streamLock) { streams[frame.arg0] }
        if (state?.call?.service != "sync") {
            forward(frame, serverOut)
            return
        }
        // Every sync command frame carries a path and is evaluated, not only the
        // first one: a STAT prefix must not let a later SEND or RECV through.
        // File data is opaque and is forwarded until the DONE frame.
        if (state.expectingData) {
            if (SyncParser.isDone(frame.payload)) state.expectingData = false
            forward(frame, serverOut)
            return
        }
        val sync = SyncParser.parse(frame.payload)
        if (sync == null || sync.direction == null) {
            audit.record(
                AuditEvent(
                    event = "call_denied",
                    peer = peer,
                    service = state.call.service,
                    rule = "sync:malformed",
                ),
            )
            closeStream(state, frame.arg0)
            return
        }
        val call = state.call.withSync(sync.path, sync.direction)
        val verdict = policy.evaluate(call)
        when (verdict.decision) {
            CallDecision.ALLOW -> {
                state.call = call
                if (sync.direction == "SEND") state.expectingData = true
                audit.record(
                    AuditEvent(
                        event = "call_allowed",
                        peer = peer,
                        service = call.service,
                        fields = call.fields,
                        rule = verdict.ruleId ?: "",
                    ),
                )
                forward(frame, serverOut)
            }
            CallDecision.DENY -> {
                audit.record(
                    AuditEvent(
                        event = "call_denied",
                        peer = peer,
                        service = call.service,
                        fields = call.fields,
                        rule = verdict.ruleId ?: "",
                    ),
                )
                closeStream(state, frame.arg0)
            }
            CallDecision.PROMPT -> {
                val response = policy.prompt(
                    call,
                    peer,
                    clientFingerprint,
                    verdict.timeoutMs,
                )
                if (response == null || !response.allowsCall) {
                    audit.record(
                        AuditEvent(
                            event = "call_denied",
                            peer = peer,
                            service = call.service,
                            fields = call.fields,
                            rule = response?.rule?.id ?: "prompt",
                        ),
                    )
                    closeStream(state, frame.arg0)
                } else {
                    state.call = call
                    if (sync.direction == "SEND") state.expectingData = true
                    audit.record(
                        AuditEvent(
                            event = "call_allowed",
                            peer = peer,
                            service = call.service,
                            fields = call.fields,
                            rule = response.rule?.id ?: "prompt",
                        ),
                    )
                    forward(frame, serverOut)
                }
            }
        }
    }

    private fun deny(frame: AdbFrame, call: AdbCall, rule: String) {
        audit.record(
            AuditEvent(
                event = "call_denied",
                peer = peer,
                service = call.service,
                fields = call.fields,
                rule = rule,
            ),
        )
        synchronized(streamLock) { denied.add(frame.arg0) }
        runCatching { AdbFrameCodec.close(clientOut, frame.arg0) }
    }

    private fun closeStream(state: StreamState, clientLocalId: Int) {
        val serverId = state.serverId
        if (serverId != null) {
            runCatching {
                AdbFrameCodec.write(
                    serverOut,
                    AdbFrameCodec.A_CLSE,
                    clientLocalId,
                    serverId,
                    ByteArray(0),
                )
            }
            runCatching {
                AdbFrameCodec.write(
                    clientOut,
                    AdbFrameCodec.A_CLSE,
                    serverId,
                    clientLocalId,
                    ByteArray(0),
                )
            }
        } else {
            runCatching { AdbFrameCodec.close(clientOut, clientLocalId) }
        }
        synchronized(streamLock) { denied.add(clientLocalId) }
        remove(clientLocalId)
    }

    private fun remove(clientLocalId: Int) {
        synchronized(streamLock) { streams.remove(clientLocalId) }
    }

    private fun forward(frame: AdbFrame, output: OutputStream) {
        synchronized(output) {
            output.write(frame.raw)
            output.flush()
        }
    }

    private class StreamState(var call: AdbCall) {
        var serverId: Int? = null
        var expectingData = false
    }
}
