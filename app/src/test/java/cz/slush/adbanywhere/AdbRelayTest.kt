package cz.slush.adbanywhere

import cz.slush.adbanywhere.firewall.AdbCall
import cz.slush.adbanywhere.firewall.AdbFrameCodec
import cz.slush.adbanywhere.firewall.AdbRelay
import cz.slush.adbanywhere.firewall.AuditLog
import cz.slush.adbanywhere.firewall.CallDecision
import cz.slush.adbanywhere.firewall.CallRule
import cz.slush.adbanywhere.firewall.CallVerdict
import cz.slush.adbanywhere.firewall.PromptDecision
import cz.slush.adbanywhere.firewall.PromptResponse
import cz.slush.adbanywhere.firewall.RelayPolicy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AdbRelayTest {
    private class FakePolicy(
        var verdict: CallVerdict = CallVerdict(CallDecision.ALLOW),
        var response: PromptResponse? = null,
    ) : RelayPolicy {
        var prompts = 0
        var rule: CallRule? = null
        override fun evaluate(call: AdbCall): CallVerdict = verdict
        override suspend fun prompt(call: AdbCall, peer: String, timeoutMs: Long): PromptResponse? {
            prompts++
            return response
        }
        override fun onRule(rule: CallRule) {
            this.rule = rule
        }
        override fun promptPending(): Boolean = false
    }

    @Test
    fun relaysAnAllowedOpen() = runBlocking {
        val pipes = pipes()
        val relay = AdbRelay(
            pipes.clientIn, pipes.relayToClient,
            pipes.serverIn, pipes.relayToServer,
            FakePolicy(),
            AuditLog(null) { false },
            "peer",
        ) {}
        relay.start()
        pipes.clientToRelay.write(frame(AdbFrameCodec.A_OPEN, 7, 0, "shell:ls".toByteArray()))
        val opened = withTimeout(3_000) { AdbFrameCodec.read(pipes.serverReader) }
        assertNotNull(opened)
        assertEquals(AdbFrameCodec.A_OPEN, opened!!.command)
        assertEquals("shell:ls", AdbFrameCodec.service(opened))

        pipes.serverToRelay.write(frame(AdbFrameCodec.A_OKAY, 9, 7, ByteArray(0)))
        val okay = withTimeout(3_000) { AdbFrameCodec.read(pipes.clientReader) }
        assertNotNull(okay)
        assertEquals(AdbFrameCodec.A_OKAY, okay!!.command)
        relay.close()
    }

    @Test
    fun deniesAnOpenWithClose() = runBlocking {
        val pipes = pipes()
        val relay = AdbRelay(
            pipes.clientIn, pipes.relayToClient,
            pipes.serverIn, pipes.relayToServer,
            FakePolicy(verdict = CallVerdict(CallDecision.DENY)),
            AuditLog(null) { false },
            "peer",
        ) {}
        relay.start()
        pipes.clientToRelay.write(frame(AdbFrameCodec.A_OPEN, 7, 0, "shell:rm".toByteArray()))
        val closed = withTimeout(3_000) { AdbFrameCodec.read(pipes.clientReader) }
        assertNotNull(closed)
        assertEquals(AdbFrameCodec.A_CLSE, closed!!.command)
        assertEquals(0, closed.arg0)
        assertEquals(7, closed.arg1)
        relay.close()
    }

    @Test
    fun promptsAndRelaysOnAllowOnce() = runBlocking {
        val pipes = pipes()
        val policy = FakePolicy(
            verdict = CallVerdict(CallDecision.PROMPT),
            response = PromptResponse(PromptDecision.ALLOW_ONCE),
        )
        val relay = AdbRelay(
            pipes.clientIn, pipes.relayToClient,
            pipes.serverIn, pipes.relayToServer,
            policy,
            AuditLog(null) { false },
            "peer",
        ) {}
        relay.start()
        pipes.clientToRelay.write(frame(AdbFrameCodec.A_OPEN, 7, 0, "shell:ls".toByteArray()))
        val opened = withTimeout(3_000) { AdbFrameCodec.read(pipes.serverReader) }
        assertNotNull(opened)
        assertEquals(1, policy.prompts)
        relay.close()
    }

    @Test
    fun deniesSyncPathAfterOkay() = runBlocking {
        val pipes = pipes()
        val policy = FakePolicy()
        val relay = AdbRelay(
            pipes.clientIn, pipes.relayToClient,
            pipes.serverIn, pipes.relayToServer,
            policy,
            AuditLog(null) { false },
            "peer",
        ) {}
        relay.start()
        pipes.clientToRelay.write(frame(AdbFrameCodec.A_OPEN, 7, 0, "sync:".toByteArray()))
        assertNotNull(withTimeout(3_000) { AdbFrameCodec.read(pipes.serverReader) })

        pipes.serverToRelay.write(frame(AdbFrameCodec.A_OKAY, 9, 7, ByteArray(0)))
        assertNotNull(withTimeout(3_000) { AdbFrameCodec.read(pipes.clientReader) })

        policy.verdict = CallVerdict(CallDecision.DENY)
        pipes.clientToRelay.write(frame(AdbFrameCodec.A_WRTE, 7, 9, syncPayload("/data/secret")))
        val closed = withTimeout(3_000) { AdbFrameCodec.read(pipes.clientReader) }
        assertNotNull(closed)
        assertEquals(AdbFrameCodec.A_CLSE, closed!!.command)
        relay.close()
    }

    @Test
    fun deniesSyncV2StatAfterOkay() = runBlocking {
        val pipes = pipes()
        val policy = FakePolicy()
        val audit = AuditLog(null) { false }
        val relay = AdbRelay(
            pipes.clientIn, pipes.relayToClient,
            pipes.serverIn, pipes.relayToServer,
            policy,
            audit,
            "peer",
        ) {}
        relay.start()
        try {
            pipes.clientToRelay.write(frame(AdbFrameCodec.A_OPEN, 7, 0, "sync:".toByteArray()))
            assertNotNull(withTimeout(3_000) { AdbFrameCodec.read(pipes.serverReader) })

            pipes.serverToRelay.write(frame(AdbFrameCodec.A_OKAY, 9, 7, ByteArray(0)))
            assertNotNull(withTimeout(3_000) { AdbFrameCodec.read(pipes.clientReader) })

            policy.verdict = CallVerdict(CallDecision.DENY)
            pipes.clientToRelay.write(
                frame(AdbFrameCodec.A_WRTE, 7, 9, syncPayload("/data/secret", 0x32415453)),
            )
            val denied = withTimeoutOrNull(2_000) {
                audit.entries.first { events -> events.any { it.event == "call_denied" } }
            }
            assertNotNull(denied)
        } finally {
            relay.close()
        }
    }

    @Test
    fun closesOnMalformedFrame() = runBlocking {
        val pipes = pipes()
        val relay = AdbRelay(
            pipes.clientIn, pipes.relayToClient,
            pipes.serverIn, pipes.relayToServer,
            FakePolicy(),
            AuditLog(null) { false },
            "peer",
        ) {}
        relay.start()
        pipes.clientToRelay.write(ByteArray(24))
        val read = withTimeout(3_000) { AdbFrameCodec.read(pipes.clientReader) }
        assertNull(read)
        relay.close()
    }

    private class Pipes(
        val clientToRelay: PipedOutputStream,
        val clientIn: PipedInputStream,
        val relayToClient: PipedOutputStream,
        val clientReader: PipedInputStream,
        val serverToRelay: PipedOutputStream,
        val serverIn: PipedInputStream,
        val relayToServer: PipedOutputStream,
        val serverReader: PipedInputStream,
    )

    private fun pipes(): Pipes {
        val clientToRelay = PipedOutputStream()
        val clientIn = PipedInputStream(clientToRelay, 1 shl 20)
        val relayToClient = PipedOutputStream()
        val clientReader = PipedInputStream(relayToClient, 1 shl 20)
        val serverToRelay = PipedOutputStream()
        val serverIn = PipedInputStream(serverToRelay, 1 shl 20)
        val relayToServer = PipedOutputStream()
        val serverReader = PipedInputStream(relayToServer, 1 shl 20)
        return Pipes(
            clientToRelay, clientIn,
            relayToClient, clientReader,
            serverToRelay, serverIn,
            relayToServer, serverReader,
        )
    }

    private fun frame(command: Int, arg0: Int, arg1: Int, payload: ByteArray): ByteArray =
        ByteArrayOutputStream().also {
            AdbFrameCodec.write(it, command, arg0, arg1, payload)
        }.toByteArray()

    private fun syncPayload(path: String, id: Int = 0x444e4553): ByteArray =
        ByteBuffer.allocate(8 + path.length)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(id)
            .putInt(path.length)
            .put(path.toByteArray())
            .array()
}
