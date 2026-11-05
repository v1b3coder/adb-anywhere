/*
 * AdbRelay - one relayed ADB transport. It reads frames, applies the call
 * policy at A_OPEN and at the first sync frame, and forwards the original bytes.
 */
package cz.slush.adbanywhere.firewall

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream

class AdbRelay(
    private val clientIn: InputStream,
    private val clientOut: OutputStream,
    private val serverIn: InputStream,
    private val serverOut: OutputStream,
    private val policy: RelayPolicy,
    private val audit: AuditLog,
    private val peer: String,
    private val connectionAllowed: () -> Boolean = { true },
    private val onClose: () -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val streams = mutableMapOf<Int, StreamState>()
    private val denied = mutableSetOf<Int>()
    private val streamLock = Any()
    private var closed = false

    fun start() {
        scope.launch { clientToServer() }
        scope.launch { serverToClient() }
    }

    fun close() {
        if (closed) return
        closed = true
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
                val frame = AdbFrameCodec.read(clientIn) ?: break
                when (frame.command) {
                    AdbFrameCodec.A_OPEN -> handleOpen(frame)
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
            if (!closed) {
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
            if (!closed) {
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
        if (!connectionAllowed()) {
            deny(frame, call, "app")
            return
        }
        if (call.service == "sync") {
            // sync: carries the path in the first stream frame, not in OPEN.
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
                val response = policy.prompt(call, peer, verdict.timeoutMs)
                when (response?.decision) {
                    PromptDecision.ALLOW_ONCE, PromptDecision.ALWAYS_ALLOW, PromptDecision.CUSTOM -> {
                        if (response.decision == PromptDecision.CUSTOM && response.rule == null) {
                            deny(frame, call, "custom:no-rule")
                        } else {
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
                        }
                    }
                    else -> deny(frame, call, "prompt")
                }
            }
        }
    }

    private suspend fun handleWrite(frame: AdbFrame) {
        if (synchronized(streamLock) { frame.arg0 in denied }) return
        val state = synchronized(streamLock) { streams[frame.arg0] }
        if (state?.call?.service == "sync" && state.firstSyncFrame) {
            state.firstSyncFrame = false
            val sync = SyncParser.parse(frame.payload)
            if (sync != null && sync.direction != null) {
                val call = state.call.withSync(sync.path, sync.direction)
                val verdict = policy.evaluate(call)
                when (verdict.decision) {
                    CallDecision.ALLOW -> {
                        state.call = call
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
                        val response = policy.prompt(call, peer, verdict.timeoutMs)
                        if (response == null ||
                            response.decision == PromptDecision.BLOCK ||
                            (response.decision == PromptDecision.CUSTOM && response.rule == null)
                        ) {
                            audit.record(
                                AuditEvent(
                                    event = "call_denied",
                                    peer = peer,
                                    service = call.service,
                                    fields = call.fields,
                                    rule = "prompt",
                                ),
                            )
                            closeStream(state, frame.arg0)
                        } else {
                            state.call = call
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
                return
            }
        }
        forward(frame, serverOut)
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
                AdbFrameCodec.write(serverOut, AdbFrameCodec.A_CLSE, clientLocalId, serverId, ByteArray(0))
            }
            runCatching {
                AdbFrameCodec.write(clientOut, AdbFrameCodec.A_CLSE, serverId, clientLocalId, ByteArray(0))
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
        var firstSyncFrame = true
    }
}
