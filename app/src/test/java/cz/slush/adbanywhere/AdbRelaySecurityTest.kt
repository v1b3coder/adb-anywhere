package cz.slush.adbanywhere

import cz.slush.adbanywhere.adb.AdbTls
import cz.slush.adbanywhere.data.AndroidPubKey
import cz.slush.adbanywhere.firewall.AdbCall
import cz.slush.adbanywhere.firewall.AdbFrame
import cz.slush.adbanywhere.firewall.AdbFrameCodec
import cz.slush.adbanywhere.firewall.AdbRelay
import cz.slush.adbanywhere.firewall.AuditLog
import cz.slush.adbanywhere.firewall.CallDecision
import cz.slush.adbanywhere.firewall.CallRule
import cz.slush.adbanywhere.firewall.CallVerdict
import cz.slush.adbanywhere.firewall.KnownKey
import cz.slush.adbanywhere.firewall.PromptDecision
import cz.slush.adbanywhere.firewall.PromptResponse
import cz.slush.adbanywhere.firewall.RelayPolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.util.Base64

/**
 * The relay's fail-closed rules: each test is an attack that worked against the
 * frame-per-request relay, or a liveness property the prompt must keep.
 */
class AdbRelaySecurityTest {
    /** Allows everything except paths containing "secret"; can be told to prompt. */
    private class PathPolicy(var promptService: String? = null) : RelayPolicy {
        val answer = CompletableDeferred<PromptResponse?>()
        var prompts = 0
        override fun evaluate(call: AdbCall): CallVerdict = when {
            call.path?.contains("secret") == true -> CallVerdict(CallDecision.DENY, "deny-secret")
            promptService != null && call.service == promptService && call.path == null ->
                CallVerdict(CallDecision.PROMPT)
            else -> CallVerdict(CallDecision.ALLOW)
        }
        override suspend fun prompt(call: AdbCall, peer: String, fingerprint: String, timeoutMs: Long): PromptResponse? {
            prompts++
            return answer.await()
        }
        override fun onRule(rule: CallRule) = Unit
        override fun promptPending() = false
    }

    /** One reader thread per pipe, so "nothing arrives" is a bounded poll, not a stuck read. */
    private class Frames(input: PipedInputStream) {
        private val queue = java.util.concurrent.LinkedBlockingQueue<Any>()
        init {
            Thread {
                try {
                    while (true) queue.put(AdbFrameCodec.read(input) ?: break)
                } catch (_: Exception) {
                }
                queue.put(EOF)
            }.apply { isDaemon = true }.start()
        }
        fun poll(timeoutMs: Long): AdbFrame? =
            queue.poll(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) as? AdbFrame
        private companion object { val EOF = Any() }
    }

    private class Pipes {
        val clientToRelay = PipedOutputStream()
        val clientIn = PipedInputStream(clientToRelay, 1 shl 20)
        val relayToClient = PipedOutputStream()
        val clientReader = Frames(PipedInputStream(relayToClient, 1 shl 20))
        val serverToRelay = PipedOutputStream()
        val serverIn = PipedInputStream(serverToRelay, 1 shl 20)
        val relayToServer = PipedOutputStream()
        val serverReader = Frames(PipedInputStream(relayToServer, 1 shl 20))
    }

    private fun frame(command: Int, arg0: Int, arg1: Int, payload: ByteArray = ByteArray(0)): ByteArray =
        ByteArrayOutputStream().also { AdbFrameCodec.write(it, command, arg0, arg1, payload) }.toByteArray()

    private fun id(text: String) = ByteBuffer.wrap(text.toByteArray()).order(ByteOrder.LITTLE_ENDIAN).int

    private fun req(type: String, path: String) = ByteBuffer.allocate(8 + path.length)
        .order(ByteOrder.LITTLE_ENDIAN).putInt(id(type)).putInt(path.length).put(path.toByteArray()).array()

    private fun read(frames: Frames, timeoutMs: Long = 1_500): AdbFrame? = frames.poll(timeoutMs)

    /** The denied request never reaches the device: it sees the stream's close, then nothing. */
    private fun assertDeviceSawOnlyTheClose(pipes: Pipes) {
        val next = read(pipes.serverReader)
        assertEquals(AdbFrameCodec.A_CLSE, next?.command)
        assertEquals(7, next?.arg0)
        assertEquals(9, next?.arg1)
        assertNull(read(pipes.serverReader, 500))
    }

    private fun relay(
        pipes: Pipes,
        policy: RelayPolicy,
        audit: AuditLog = AuditLog(null) { false },
        keys: List<KnownKey> = emptyList(),
        authTimeoutMs: Long = 0,
        onClose: () -> Unit = {},
    ) = AdbRelay(
        pipes.clientIn, pipes.relayToClient, pipes.serverIn, pipes.relayToServer,
        policy, audit, "peer",
        knownKeys = { keys },
        authTimeoutMs = authTimeoutMs,
        onClose = onClose,
    ).also { it.start() }

    /** Authenticates, opens sync stream 7 (device id 9) and returns once the client saw OKAY. */
    private suspend fun openSync(pipes: Pipes) {
        pipes.serverToRelay.write(frame(AdbFrameCodec.A_CNXN, 0, 0))
        assertNotNull(read(pipes.clientReader))
        pipes.clientToRelay.write(frame(AdbFrameCodec.A_OPEN, 7, 0, "sync:".toByteArray()))
        assertNotNull(read(pipes.serverReader))
        pipes.serverToRelay.write(frame(AdbFrameCodec.A_OKAY, 9, 7))
        assertNotNull(read(pipes.clientReader))
    }

    @Test
    fun aDeniedRequestPackedAfterAnAllowedOneNeverReachesTheDevice() = runBlocking {
        val pipes = Pipes()
        val relay = relay(pipes, PathPolicy())
        openSync(pipes)
        pipes.clientToRelay.write(frame(AdbFrameCodec.A_WRTE, 7, 9, req("STAT", "/sdcard/ok") + req("RECV", "/data/secret")))
        assertDeviceSawOnlyTheClose(pipes)
        assertEquals(AdbFrameCodec.A_CLSE, read(pipes.clientReader)?.command)
        relay.close()
    }

    @Test
    fun aCloseNamingAnotherDeviceStreamKeepsTheSyncPolicy() = runBlocking {
        val pipes = Pipes()
        val relay = relay(pipes, PathPolicy())
        openSync(pipes)
        // adbd ignores this close (no stream 12345), so the relay must not forget stream 7.
        pipes.clientToRelay.write(frame(AdbFrameCodec.A_CLSE, 7, 12345))
        assertEquals(AdbFrameCodec.A_CLSE, read(pipes.serverReader)?.command)
        pipes.clientToRelay.write(frame(AdbFrameCodec.A_WRTE, 7, 9, req("RECV", "/data/secret")))
        assertDeviceSawOnlyTheClose(pipes)
        relay.close()
    }

    @Test
    fun aRequestInsideTheSendDataPhaseIsEvaluated() = runBlocking {
        val pipes = Pipes()
        val relay = relay(pipes, PathPolicy())
        openSync(pipes)
        pipes.clientToRelay.write(frame(AdbFrameCodec.A_WRTE, 7, 9, req("SEND", "/sdcard/ok,33188")))
        assertNotNull(read(pipes.serverReader))
        val dataThenDone = req("DATA", "x") + ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(id("DONE")).putInt(0).array()
        pipes.clientToRelay.write(frame(AdbFrameCodec.A_WRTE, 7, 9, dataThenDone + req("RECV", "/data/secret")))
        assertDeviceSawOnlyTheClose(pipes)
        relay.close()
    }

    @Test
    fun aWriteForAnUnknownStreamIsDropped() = runBlocking {
        val pipes = Pipes()
        val relay = relay(pipes, PathPolicy())
        openSync(pipes)
        pipes.clientToRelay.write(frame(AdbFrameCodec.A_WRTE, 8, 9, req("RECV", "/data/secret")))
        assertNull(read(pipes.serverReader))
        // and a write that names stream 7 with the wrong device id
        pipes.clientToRelay.write(frame(AdbFrameCodec.A_WRTE, 7, 10, req("RECV", "/data/secret")))
        assertNull(read(pipes.serverReader))
        relay.close()
    }

    @Test
    fun aPromptHoldsOnlyItsOwnStream() = runBlocking {
        val pipes = Pipes()
        val policy = PathPolicy(promptService = "exec")
        val relay = relay(pipes, policy)
        openSync(pipes)
        pipes.clientToRelay.write(frame(AdbFrameCodec.A_OPEN, 11, 0, "exec:id".toByteArray()))
        // The held call does not reach the device, but the other stream keeps flowing.
        pipes.clientToRelay.write(frame(AdbFrameCodec.A_WRTE, 7, 9, req("STAT", "/sdcard/ok")))
        val next = read(pipes.serverReader)
        assertEquals(AdbFrameCodec.A_WRTE, next?.command)
        withTimeout(3_000) { while (policy.prompts == 0) kotlinx.coroutines.delay(10) }
        assertEquals(1, policy.prompts)
        assertEquals(1, relay.pendingPrompts)
        policy.answer.complete(PromptResponse(PromptDecision.ALLOW_ONCE))
        val opened = read(pipes.serverReader)
        assertEquals("exec:id", opened?.let { AdbFrameCodec.service(it) })
        relay.close()
    }

    @Test
    fun aSignatureIsAttributedOnlyWhenItVerifies() = runBlocking {
        val generator = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
        val trusted = generator.generateKeyPair()
        val attacker = generator.generateKeyPair()
        val trustedKey = AndroidPubKey.decode(AndroidPubKey.encode(trusted.public as RSAPublicKey))!!
        val pipes = Pipes()
        val audit = AuditLog(null) { false }
        val relay = relay(pipes, PathPolicy(), audit, keys = listOf(KnownKey(trustedKey.fingerprint, trusted.public)))
        val token = ByteArray(20) { it.toByte() }
        pipes.serverToRelay.write(frame(AdbFrameCodec.A_AUTH, AdbFrameCodec.ADB_AUTH_TOKEN, 0, token))
        assertNotNull(read(pipes.clientReader))
        // An attacker offers the trusted machine's public key, then signs with its own.
        val offered = Base64.getEncoder().encodeToString(AndroidPubKey.encode(trusted.public as RSAPublicKey)) + " victim\u0000"
        pipes.clientToRelay.write(frame(AdbFrameCodec.A_AUTH, AdbFrameCodec.ADB_AUTH_RSAPUBLICKEY, 0, offered.toByteArray()))
        assertNotNull(read(pipes.serverReader))
        pipes.clientToRelay.write(
            frame(AdbFrameCodec.A_AUTH, AdbFrameCodec.ADB_AUTH_SIGNATURE, 0, AdbTls.signToken(attacker.private, token)),
        )
        assertNotNull(read(pipes.serverReader))
        pipes.serverToRelay.write(frame(AdbFrameCodec.A_CNXN, 0, 0))
        assertNotNull(read(pipes.clientReader))
        val ok = audit.entries.value.last { it.event == "auth_ok" }
        assertEquals("", ok.fingerprint)
        assertTrue(audit.entries.value.any { it.event == "auth_unknown" && it.fingerprint == trustedKey.fingerprint })

        // A second handshake signed by the trusted key is attributed to it.
        pipes.clientToRelay.write(frame(AdbFrameCodec.A_CNXN, 0x01000001, 4096, "host::".toByteArray()))
        assertNotNull(read(pipes.serverReader))
        pipes.serverToRelay.write(frame(AdbFrameCodec.A_AUTH, AdbFrameCodec.ADB_AUTH_TOKEN, 0, token))
        assertNotNull(read(pipes.clientReader))
        pipes.clientToRelay.write(
            frame(AdbFrameCodec.A_AUTH, AdbFrameCodec.ADB_AUTH_SIGNATURE, 0, AdbTls.signToken(trusted.private, token)),
        )
        assertNotNull(read(pipes.serverReader))
        pipes.serverToRelay.write(frame(AdbFrameCodec.A_CNXN, 0, 0))
        assertNotNull(read(pipes.clientReader))
        assertEquals(trustedKey.fingerprint, audit.entries.value.last { it.event == "auth_ok" }.fingerprint)
        relay.close()
    }

    @Test
    fun anUnauthenticatedTransportIsClosedAtTheDeadline() = runBlocking {
        val pipes = Pipes()
        val closed = CompletableDeferred<Unit>()
        relay(pipes, PathPolicy(), authTimeoutMs = 300, onClose = { closed.complete(Unit) })
        // Trickling bytes does not extend an absolute deadline.
        pipes.clientToRelay.write(ByteArray(1))
        withTimeout(3_000) { closed.await() }
    }

    // Stream identity, path identity, resource bounds and reverse streams.

    private fun authenticate(pipes: Pipes) {
        pipes.serverToRelay.write(frame(AdbFrameCodec.A_CNXN, 0, 0))
        assertNotNull(read(pipes.clientReader))
    }

    private fun rawReq(type: String, path: ByteArray) = ByteBuffer.allocate(8 + path.size)
        .order(ByteOrder.LITTLE_ENDIAN).putInt(id(type)).putInt(path.size).put(path).array()

    @Test
    fun aCloseBeforeTheDeviceAnswersKeepsTheIdTaken() = runBlocking {
        val pipes = Pipes()
        val closed = CompletableDeferred<Unit>()
        relay(pipes, PathPolicy(), onClose = { closed.complete(Unit) })
        authenticate(pipes)
        pipes.clientToRelay.write(frame(AdbFrameCodec.A_OPEN, 7, 0, "sync:".toByteArray()))
        assertEquals(AdbFrameCodec.A_OPEN, read(pipes.serverReader)?.command)
        // adbd ignores a close without its id, so the sync stream stays open there,
        // and the relay does not forward it.
        pipes.clientToRelay.write(frame(AdbFrameCodec.A_CLSE, 7, 0))
        pipes.clientToRelay.write(frame(AdbFrameCodec.A_OPEN, 8, 0, "exec:id".toByteArray()))
        assertEquals(AdbFrameCodec.A_OPEN, read(pipes.serverReader)?.command)
        // Re-opening the id as another call must not inherit the sync stream.
        pipes.clientToRelay.write(frame(AdbFrameCodec.A_OPEN, 7, 0, "exec:id".toByteArray()))
        withTimeout(3_000) { closed.await() }
        assertNull(read(pipes.serverReader, 300))
    }

    @Test
    fun theRelayClosesADeviceStreamTheClientAbandonedBeforeItsOkay() = runBlocking {
        val pipes = Pipes()
        val relay = relay(pipes, PathPolicy())
        authenticate(pipes)
        pipes.clientToRelay.write(frame(AdbFrameCodec.A_OPEN, 7, 0, "sync:".toByteArray()))
        assertNotNull(read(pipes.serverReader))
        pipes.clientToRelay.write(frame(AdbFrameCodec.A_CLSE, 7, 0))
        // The relay handles client frames in order: once the device sees this
        // OPEN, the close before it was handled, and it was held back.
        pipes.clientToRelay.write(frame(AdbFrameCodec.A_OPEN, 8, 0, "exec:id".toByteArray()))
        assertEquals(8, read(pipes.serverReader)?.arg0)
        pipes.serverToRelay.write(frame(AdbFrameCodec.A_OKAY, 9, 7))
        val close = read(pipes.serverReader)
        assertEquals(AdbFrameCodec.A_CLSE, close?.command)
        assertEquals(7, close?.arg0)
        assertEquals(9, close?.arg1)
        // The client closed the stream; it does not see the late OKAY.
        assertNull(read(pipes.clientReader, 300))
        relay.close()
    }

    @Test
    fun aStaleOkayNeverAttachesARetiredDeviceStreamToANewCall() = runBlocking {
        val pipes = Pipes()
        val relay = relay(pipes, PathPolicy())
        openSync(pipes)
        pipes.clientToRelay.write(frame(AdbFrameCodec.A_CLSE, 7, 9))
        assertEquals(AdbFrameCodec.A_CLSE, read(pipes.serverReader)?.command)
        pipes.clientToRelay.write(frame(AdbFrameCodec.A_OPEN, 7, 0, "exec:id".toByteArray()))
        assertEquals(AdbFrameCodec.A_OPEN, read(pipes.serverReader)?.command)
        // A late OKAY for the retired sync stream 9, then the real one for exec.
        pipes.serverToRelay.write(frame(AdbFrameCodec.A_OKAY, 9, 7))
        pipes.serverToRelay.write(frame(AdbFrameCodec.A_OKAY, 12, 7))
        assertNotNull(read(pipes.clientReader))
        assertNotNull(read(pipes.clientReader))
        pipes.clientToRelay.write(frame(AdbFrameCodec.A_WRTE, 7, 9, req("RECV", "/data/secret")))
        assertNull(read(pipes.serverReader, 500))
        pipes.clientToRelay.write(frame(AdbFrameCodec.A_WRTE, 7, 12, "x".toByteArray()))
        val write = read(pipes.serverReader)
        assertEquals(AdbFrameCodec.A_WRTE, write?.command)
        assertEquals(12, write?.arg1)
        relay.close()
    }

    @Test
    fun aNulInASyncPathClosesTheStream() = runBlocking {
        val pipes = Pipes()
        val relay = relay(pipes, PathPolicy())
        openSync(pipes)
        // adbd would open /data/secret; the relay would have judged /data/ok.
        val path = "/data/secret\u0000/../../ok".toByteArray()
        pipes.clientToRelay.write(frame(AdbFrameCodec.A_WRTE, 7, 9, rawReq("RECV", path)))
        assertDeviceSawOnlyTheClose(pipes)
        relay.close()
    }

    @Test
    fun aSyncPathThatIsNotUtf8ClosesTheStream() = runBlocking {
        val pipes = Pipes()
        val relay = relay(pipes, PathPolicy())
        openSync(pipes)
        val path = byteArrayOf('/'.code.toByte(), 0xff.toByte(), 'x'.code.toByte())
        pipes.clientToRelay.write(frame(AdbFrameCodec.A_WRTE, 7, 9, rawReq("STAT", path)))
        assertDeviceSawOnlyTheClose(pipes)
        relay.close()
    }

    @Test
    fun aNulInsideAServiceNameIsDenied() = runBlocking {
        val pipes = Pipes()
        val relay = relay(pipes, PathPolicy())
        authenticate(pipes)
        pipes.clientToRelay.write(frame(AdbFrameCodec.A_OPEN, 11, 0, "shell:ls\u0000rm -rf /sdcard".toByteArray()))
        val close = read(pipes.clientReader)
        assertEquals(AdbFrameCodec.A_CLSE, close?.command)
        assertEquals(11, close?.arg1)
        assertNull(read(pipes.serverReader, 300))
        relay.close()
    }

    @Test
    fun aHeldStreamThatIgnoresFlowControlIsClosedAtTheCap() = runBlocking {
        val pipes = Pipes()
        val policy = object : RelayPolicy {
            override fun evaluate(call: AdbCall) =
                if (call.path == "/sdcard/ask") CallVerdict(CallDecision.PROMPT) else CallVerdict(CallDecision.ALLOW)
            override suspend fun prompt(call: AdbCall, peer: String, fingerprint: String, timeoutMs: Long): PromptResponse? =
                CompletableDeferred<PromptResponse?>().await()
            override fun onRule(rule: CallRule) = Unit
            override fun promptPending() = true
        }
        val relay = relay(pipes, policy)
        openSync(pipes)
        pipes.clientToRelay.write(frame(AdbFrameCodec.A_WRTE, 7, 9, req("STAT", "/sdcard/ask")))
        val chunk = ByteArray(512 * 1024)
        val frames = (AdbRelay.MAX_HELD_BYTES / chunk.size + 2).toInt()
        withContext(Dispatchers.IO) {
            repeat(frames) { pipes.clientToRelay.write(frame(AdbFrameCodec.A_WRTE, 7, 9, chunk)) }
        }
        assertDeviceSawOnlyTheClose(pipes)
        relay.close()
    }

    @Test
    fun aReverseStreamTheHostAcceptsCarriesDataBothWays() = runBlocking {
        val pipes = Pipes()
        val relay = relay(pipes, PathPolicy())
        authenticate(pipes)
        // The device opens a stream for `adb reverse`; the host accepts it as 100.
        pipes.serverToRelay.write(frame(AdbFrameCodec.A_OPEN, 20, 0, "tcp:8080".toByteArray()))
        assertEquals(AdbFrameCodec.A_OPEN, read(pipes.clientReader)?.command)
        pipes.clientToRelay.write(frame(AdbFrameCodec.A_OKAY, 100, 20))
        assertEquals(AdbFrameCodec.A_OKAY, read(pipes.serverReader)?.command)
        pipes.clientToRelay.write(frame(AdbFrameCodec.A_WRTE, 100, 20, "hi".toByteArray()))
        val write = read(pipes.serverReader)
        assertEquals(AdbFrameCodec.A_WRTE, write?.command)
        assertEquals("hi", write?.payload?.let { String(it) })
        pipes.serverToRelay.write(frame(AdbFrameCodec.A_OKAY, 20, 100))
        assertEquals(AdbFrameCodec.A_OKAY, read(pipes.clientReader)?.command)
        pipes.serverToRelay.write(frame(AdbFrameCodec.A_WRTE, 20, 100, "yo".toByteArray()))
        assertEquals(AdbFrameCodec.A_WRTE, read(pipes.clientReader)?.command)
        pipes.clientToRelay.write(frame(AdbFrameCodec.A_CLSE, 100, 20))
        assertEquals(AdbFrameCodec.A_CLSE, read(pipes.serverReader)?.command)
        // Closed: the ids no longer carry data.
        pipes.clientToRelay.write(frame(AdbFrameCodec.A_WRTE, 100, 20, "late".toByteArray()))
        assertNull(read(pipes.serverReader, 300))
        relay.close()
    }

    @Test
    fun aHostCannotAcceptAStreamTheDeviceNeverOpened() = runBlocking {
        val pipes = Pipes()
        val relay = relay(pipes, PathPolicy())
        openSync(pipes)
        // Device stream 9 is the sync stream; the host cannot claim it as reverse.
        pipes.clientToRelay.write(frame(AdbFrameCodec.A_OKAY, 100, 9))
        pipes.clientToRelay.write(frame(AdbFrameCodec.A_WRTE, 100, 9, req("RECV", "/data/secret")))
        assertNull(read(pipes.serverReader, 500))
        relay.close()
    }

    @Test
    fun unauthenticatedOpensCannotFloodTheAudit() = runBlocking {
        val pipes = Pipes()
        val audit = AuditLog(null) { false }
        val relay = relay(pipes, PathPolicy(), audit)
        withContext(Dispatchers.IO) {
            repeat(300) { pipes.clientToRelay.write(frame(AdbFrameCodec.A_OPEN, it + 1, 0, "shell:id".toByteArray())) }
        }
        repeat(300) { read(pipes.clientReader, 1_000) }
        assertTrue(audit.entries.value.size <= AuditLog.UNAUTHENTICATED_BURST)
        relay.close()
    }
}
