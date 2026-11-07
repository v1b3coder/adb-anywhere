package cz.slush.adbanywhere

import cz.slush.adbanywhere.firewall.AdbCall
import cz.slush.adbanywhere.firewall.AdbFrameCodec
import cz.slush.adbanywhere.firewall.AuditLog
import cz.slush.adbanywhere.firewall.CallDecision
import cz.slush.adbanywhere.firewall.CallRule
import cz.slush.adbanywhere.firewall.CallVerdict
import cz.slush.adbanywhere.firewall.FirewallConfig
import cz.slush.adbanywhere.firewall.FirewallProxy
import cz.slush.adbanywhere.firewall.IpNetwork
import cz.slush.adbanywhere.firewall.PromptResponse
import cz.slush.adbanywhere.firewall.RelayPolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.Socket

class FirewallProxyTest {
    private class FakePolicy : RelayPolicy {
        override fun evaluate(call: AdbCall) = CallVerdict(CallDecision.ALLOW)
        override suspend fun prompt(
            call: AdbCall,
            peer: String,
            fingerprint: String,
            timeoutMs: Long,
        ): PromptResponse? = null
        override fun onRule(rule: CallRule) = Unit
        override fun promptPending() = false
    }

    @Test
    fun acceptsAndRelaysAnAllowedConnection() = runBlocking {
        val upstream = ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
        val accepted = CompletableDeferred<Socket>()
        val upstreamJob = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            accepted.complete(upstream.accept())
        }
        val proxyPort = freePort()
        val audit = AuditLog(null) { false }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val proxy = FirewallProxy(
            scope = scope,
            config = { FirewallConfig(enabled = true) },
            monitor = null,
            policy = FakePolicy(),
            audit = audit,
            onState = {},
        )
        proxy.start("127.0.0.1", proxyPort, upstream.localPort)
        try {
            Socket("127.0.0.1", proxyPort).use { client ->
                val server = withTimeout(3_000) { accepted.await() }
                // The server sends A_CNXN only after the ADB handshake; the relay
                // then accepts the client's A_OPEN.
                val cnxn = ByteArrayOutputStream()
                AdbFrameCodec.write(cnxn, AdbFrameCodec.A_CNXN, 0, 0, ByteArray(0))
                server.getOutputStream().write(cnxn.toByteArray())
                server.getOutputStream().flush()
                assertNotNull(withTimeout(3_000) { AdbFrameCodec.read(client.getInputStream()) })

                val output = ByteArrayOutputStream()
                AdbFrameCodec.write(output, AdbFrameCodec.A_OPEN, 7, 0, "shell:ls".toByteArray())
                client.getOutputStream().write(output.toByteArray())
                client.getOutputStream().flush()

                val open = withTimeout(3_000) { AdbFrameCodec.read(server.getInputStream()) }
                assertNotNull(open)
                assertEquals("shell:ls", AdbFrameCodec.service(open!!))

                val okay = ByteArrayOutputStream()
                AdbFrameCodec.write(okay, AdbFrameCodec.A_OKAY, 9, 7, ByteArray(0))
                server.getOutputStream().write(okay.toByteArray())
                server.getOutputStream().flush()

                val reply = withTimeout(3_000) {
                    AdbFrameCodec.read(client.getInputStream())
                }
                assertNotNull(reply)
                assertEquals(AdbFrameCodec.A_OKAY, reply!!.command)
            }
            assertTrue(audit.entries.value.any { it.event == "connection_open" })
            assertTrue(audit.entries.value.any { it.event == "call_allowed" })
        } finally {
            proxy.stop()
            upstreamJob.cancel()
            scope.cancel()
            upstream.close()
        }
    }

    @Test
    fun deniesAPeerOutsideTheIpAllowlist() = runBlocking {
        val upstream = ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
        val proxyPort = freePort()
        val audit = AuditLog(null) { false }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val config = FirewallConfig(
            enabled = true,
            ipAllowlistEnabled = true,
            ipAllowlist = listOf(IpNetwork.parse("10.0.0.0/8")!!),
        )
        val proxy = FirewallProxy(
            scope = scope,
            config = { config },
            monitor = null,
            policy = FakePolicy(),
            audit = audit,
            onState = {},
        )
        proxy.start("127.0.0.1", proxyPort, upstream.localPort)
        try {
            Socket("127.0.0.1", proxyPort).use { client ->
                client.soTimeout = 2_000
                assertEquals(-1, client.getInputStream().read())
            }
            assertTrue(audit.entries.value.any { it.event == "ip_denied" })
        } finally {
            proxy.stop()
            scope.cancel()
            upstream.close()
        }
    }

    @Test
    fun deniesWhenTheAppAllowlistHasNoForegroundApp() = runBlocking {
        val upstream = ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
        val proxyPort = freePort()
        val audit = AuditLog(null) { false }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val config = FirewallConfig(
            enabled = true,
            appAllowlistEnabled = true,
            appAllowlist = setOf("com.example.app"),
        )
        val proxy = FirewallProxy(
            scope = scope,
            config = { config },
            monitor = null,
            policy = FakePolicy(),
            audit = audit,
            onState = {},
        )
        proxy.start("127.0.0.1", proxyPort, upstream.localPort)
        try {
            Socket("127.0.0.1", proxyPort).use { client ->
                client.soTimeout = 2_000
                assertEquals(-1, client.getInputStream().read())
            }
            assertTrue(audit.entries.value.any { it.event == "app_denied" })
        } finally {
            proxy.stop()
            scope.cancel()
            upstream.close()
        }
    }

    @Test
    fun reportsOnlyTheAddressItBinds() = runBlocking {
        val upstream = ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
        val proxyPort = freePort()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val proxy = FirewallProxy(
            scope = scope,
            config = { FirewallConfig(enabled = true) },
            monitor = null,
            policy = FakePolicy(),
            audit = AuditLog(null) { false },
            onState = {},
        )
        assertFalse(proxy.isListeningOn("127.0.0.1", proxyPort, upstream.localPort))
        proxy.start("127.0.0.1", proxyPort, upstream.localPort)
        try {
            // The watchdog reuses a relay that already listens on exactly this
            // address; any other tuple must start a new one.
            assertTrue(proxy.isListeningOn("127.0.0.1", proxyPort, upstream.localPort))
            assertFalse(proxy.isListeningOn("127.0.0.1", proxyPort + 1, upstream.localPort))
            assertFalse(proxy.isListeningOn("127.0.0.1", proxyPort, upstream.localPort + 1))
        } finally {
            proxy.stop()
            scope.cancel()
            upstream.close()
        }
        assertFalse(proxy.isListeningOn("127.0.0.1", proxyPort, upstream.localPort))
    }

    private fun freePort(): Int =
        ServerSocket(0).use { it.localPort }
}
