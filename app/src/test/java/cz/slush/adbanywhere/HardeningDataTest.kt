package cz.slush.adbanywhere

import cz.slush.adbanywhere.data.DeveloperKey
import cz.slush.adbanywhere.data.Endpoint
import cz.slush.adbanywhere.firewall.AdbCallParser
import cz.slush.adbanywhere.firewall.CallDecision
import cz.slush.adbanywhere.firewall.CallRules
import cz.slush.adbanywhere.firewall.FirewallConfig
import cz.slush.adbanywhere.firewall.Protected
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HardeningDataTest {
    private val policyOn = FirewallConfig(enabled = true, callPolicyEnabled = true)
    private val protected = Protected(setOf(15037, 15038, 5555), "/data/local/tmp/adb-anywhere")

    @Test
    fun tcpToTheAppsOwnPortsIsDeniedUnderTheCallPolicy() {
        for (service in listOf("tcp:15037", "tcp:localhost:5555", "tcp:127.0.0.1:15038", "tcp:nonsense")) {
            val verdict = CallRules.evaluate(AdbCallParser.parse(service), policyOn, protected)
            assertEquals(service, CallDecision.DENY, verdict.decision)
        }
        val other = CallRules.evaluate(AdbCallParser.parse("tcp:localhost:8080"), policyOn, protected)
        assertEquals(CallDecision.PROMPT, other.decision)
        assertEquals("8080", AdbCallParser.parse("tcp:localhost:8080").fields["port"])
    }

    @Test
    fun syncIntoTheDaemonDirectoryIsDeniedAfterLexicalNormalization() {
        val sync = AdbCallParser.parse("sync:")
        for (path in listOf(
            "/data/local/tmp/adb-anywhere/adb_keys",
            "/data/local/tmp//adb-anywhere/tls_clients",
            "/data/local/tmp/x/../adb-anywhere/adbd",
            "/data/local/tmp/./adb-anywhere",
        )) {
            assertEquals(path, CallDecision.DENY, CallRules.evaluate(sync.withSync(path, "SEND"), policyOn, protected).decision)
        }
        val elsewhere = CallRules.evaluate(sync.withSync("/data/local/tmp/adb-anywhere2/x", "SEND"), policyOn, protected)
        assertEquals(CallDecision.PROMPT, elsewhere.decision)
    }

    @Test
    fun aShellThatReadsStdinIsTheInteractiveCapability() {
        assertTrue(AdbCallParser.parse("shell,v2,raw:sh").interactive)
        assertTrue(AdbCallParser.parse("exec:/system/bin/sh -i").interactive)
        assertTrue(AdbCallParser.parse("shell:toybox sh").interactive)
        assertFalse(AdbCallParser.parse("shell:sh -c 'id'").interactive)
        assertFalse(AdbCallParser.parse("shell:sh script.sh").interactive)
        assertFalse(AdbCallParser.parse("shell:ls").interactive)
    }

    @Test
    fun aLabelCannotBreakTheKeyFileCommand() {
        assertEquals("evil x", DeveloperKey.sanitize("evil\u0000x"))
        assertEquals("a b c", DeveloperKey.sanitize("a\nb\r\tc"))
        assertEquals(DeveloperKey.MAX_LABEL, DeveloperKey.sanitize("x".repeat(500)).length)
        assertEquals("developer", DeveloperKey.sanitize("\u0000\u0000"))
    }

    private fun ip(text: String): Int = text.split('.').fold(0) { acc, part -> (acc shl 8) or part.toInt() }

    private fun prefix(text: String): Endpoint.Prefix {
        val (address, length) = text.split('/')
        return Endpoint.Prefix(ip(address), length.toInt())
    }

    @Test
    fun aPeerMustBeRoutedThroughTheVpn() {
        val lan = listOf(prefix("192.168.1.0/24"))
        // Split tunnel: only VPN-range sources are answered through the tunnel.
        val split = listOf(prefix("100.64.0.0/10"))
        assertTrue(Endpoint.peerRoutedThroughVpn(ip("100.101.1.2"), split, lan))
        assertFalse(Endpoint.peerRoutedThroughVpn(ip("192.168.1.50"), split, lan))
        assertFalse(Endpoint.peerRoutedThroughVpn(ip("8.8.8.8"), split, lan))
        // Full tunnel: the LAN subnet is more specific than the default route.
        val full = listOf(prefix("0.0.0.0/0"))
        assertTrue(Endpoint.peerRoutedThroughVpn(ip("8.8.8.8"), full, lan))
        assertFalse(Endpoint.peerRoutedThroughVpn(ip("192.168.1.50"), full, lan))
        // A VPN subnet route that is more specific than the LAN wins.
        assertTrue(Endpoint.peerRoutedThroughVpn(ip("192.168.1.50"), listOf(prefix("192.168.1.48/28")), lan))
        // No routes reported: treated as a full tunnel.
        assertTrue(Endpoint.peerRoutedThroughVpn(ip("10.0.0.2"), emptyList(), lan))
    }
}
