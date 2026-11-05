package cz.slush.adbanywhere

import cz.slush.adbanywhere.firewall.AdbCallParser
import cz.slush.adbanywhere.firewall.AdbFrameCodec
import cz.slush.adbanywhere.firewall.CallAction
import cz.slush.adbanywhere.firewall.CallDecision
import cz.slush.adbanywhere.firewall.CallPredicate
import cz.slush.adbanywhere.firewall.CallRule
import cz.slush.adbanywhere.firewall.CallRules
import cz.slush.adbanywhere.firewall.FirewallConfig
import cz.slush.adbanywhere.firewall.ForegroundTracker
import cz.slush.adbanywhere.firewall.IpAllowlist
import cz.slush.adbanywhere.firewall.IpNetwork
import cz.slush.adbanywhere.firewall.ShellLexer
import cz.slush.adbanywhere.firewall.SyncParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

class FirewallDataTest {
    @Test
    fun ipv4NetworkParsesAndMatches() {
        val network = IpNetwork.parse("100.64.0.0/10")!!
        assertTrue(network.contains(ip("100.64.0.2")))
        assertFalse(network.contains(ip("100.128.0.1")))
        assertEquals("100.64.0.0/10", network.format())
        assertEquals(32, IpNetwork.parse("10.0.0.1")!!.prefix)
        assertNull(IpNetwork.parse("10.0.0.0/33"))
        assertNull(IpNetwork.parse("10.0.0"))
        assertNull(IpNetwork.parse("::1/128"))
    }

    @Test
    fun allowlistRejectsLocalAndOwnAddresses() {
        val allowlist = IpAllowlist(listOf(IpNetwork.parse("100.64.0.0/10")!!))
        val own = InetAddress.getByName("100.64.0.10")
        assertTrue(allowlist.allows(InetAddress.getByName("100.64.0.2"), own))
        assertFalse(allowlist.allows(InetAddress.getByName("127.0.0.1"), own))
        assertFalse(allowlist.allows(InetAddress.getByName("169.254.1.1"), own))
        assertFalse(allowlist.allows(InetAddress.getByName("100.64.0.10"), own))
        assertFalse(allowlist.allows(InetAddress.getByName("::1"), own))
    }

    @Test
    fun shellLexerHandlesQuotesAndMetacharacters() {
        assertEquals(listOf("ls", "-la", "/sdcard/a b"), ShellLexer.lex("ls -la '/sdcard/a b'"))
        assertEquals(listOf("echo", ";"), ShellLexer.lex("echo ';'"))
        assertEquals(listOf("echo", ";"), ShellLexer.lex("echo \\;"))
        assertTrue(ShellLexer.hasMetacharacters("ls; rm -rf /sdcard"))
        assertTrue(ShellLexer.hasMetacharacters("echo \"\$(id)\""))
        assertFalse(ShellLexer.hasMetacharacters("echo ';'"))
        assertFalse(ShellLexer.hasMetacharacters("echo \\;"))
        assertFalse(ShellLexer.hasMetacharacters("ls -la /sdcard"))
    }

    @Test
    fun callParserNormalizesServices() {
        val shell = AdbCallParser.parse("shell,v2,raw:ls -la /sdcard")
        assertEquals("shell", shell.service)
        assertEquals(listOf("ls", "-la", "/sdcard"), shell.argv)
        assertFalse(shell.interactive)
        assertFalse(shell.metacharacters)
        assertEquals("/sdcard", shell.fields["argv[2]"])

        val interactive = AdbCallParser.parse("shell:")
        assertTrue(interactive.interactive)

        val exec = AdbCallParser.parse("exec:echo hi")
        assertEquals("exec", exec.service)
        assertEquals(listOf("echo", "hi"), exec.argv)

        val abb = AdbCallParser.parse("abb_exec:package\u0000install\u0000-x")
        assertEquals("abb_exec", abb.service)
        assertEquals(listOf("package", "install", "-x"), abb.argv)

        val sync = AdbCallParser.parse("sync:")
        assertEquals("sync", sync.service)

        val tcp = AdbCallParser.parse("tcp:5555")
        assertEquals(5555, tcp.port)

        val reverse = AdbCallParser.parse("reverse:forward:tcp:8080;tcp:8080")
        assertEquals("tcp:8080", reverse.fields["remote"])
        assertEquals("tcp:8080", reverse.fields["local"])

        assertEquals("jdwp", AdbCallParser.parse("jdwp:123").service)
        assertEquals("unknown", AdbCallParser.parse("unknown:thing").service)
    }

    @Test
    fun rulesAreFirstMatchAndPromptOnlyUnmatched() {
        val call = AdbCallParser.parse("shell:ls /sdcard/a")
        val allow = CallRule(
            id = "allow-ls",
            action = CallAction.ALLOW,
            service = "shell",
            predicates = listOf(CallPredicate.Exact("argv[0]", "ls")),
        )
        val deny = CallRule(
            id = "deny-all-shell",
            action = CallAction.DENY,
            service = "shell",
            predicates = emptyList(),
        )
        val config = FirewallConfig(
            callPolicyEnabled = true,
            callRules = listOf(allow, deny),
        )
        assertEquals("allow-ls", CallRules.evaluate(call, config).ruleId)

        val unmatched = CallRules.evaluate(
            AdbCallParser.parse("shell:rm -rf /sdcard"),
            config,
        )
        assertEquals(CallDecision.DENY, unmatched.decision)
        assertEquals("deny-all-shell", unmatched.ruleId)
    }

    @Test
    fun rulesSupportExactPrefixGlobAndAny() {
        val call = AdbCallParser.parse("shell:ls /sdcard/a")
        fun rule(predicate: CallPredicate) = CallRule(
            id = "r",
            action = CallAction.ALLOW,
            service = "shell",
            predicates = listOf(predicate),
        )
        assertTrue(rule(CallPredicate.Exact("argv[0]", "ls")).matches(call))
        assertTrue(rule(CallPredicate.Prefix("argv[1]", "/sdcard")).matches(call))
        assertTrue(rule(CallPredicate.Glob("argv[1]", "/sdcard/*")).matches(call))
        assertTrue(rule(CallPredicate.AnyValue("argv[1]")).matches(call))
        assertFalse(rule(CallPredicate.Exact("argv[0]", "rm")).matches(call))
        assertFalse(rule(CallPredicate.Glob("argv[1]", "/data/*")).matches(call))
    }

    @Test
    fun normalRulesDoNotMatchShellMetacharacters() {
        val call = AdbCallParser.parse("shell:ls; rm -rf /sdcard")
        val broad = CallRule(
            id = "broad",
            action = CallAction.ALLOW,
            service = "shell",
            predicates = listOf(CallPredicate.Exact("argv[0]", "ls")),
        )
        val exact = CallRule.forCall(call, CallAction.ALLOW)
        assertFalse(broad.matches(call))
        assertTrue(exact.matches(call))
    }

    @Test
    fun rulesMatchSyncPathsAndDirections() {
        val send = AdbCallParser.parse("sync:").withSync("/sdcard/a", "SEND")
        val rule = CallRule(
            id = "sync-sdcard",
            action = CallAction.ALLOW,
            service = "sync",
            predicates = listOf(
                CallPredicate.Glob("path", "/sdcard/*"),
                CallPredicate.Exact("direction", "SEND"),
            ),
        )
        assertTrue(rule.matches(send))
        assertFalse(rule.matches(send.withSync("/data/a", "SEND")))
        assertFalse(rule.matches(send.withSync("/sdcard/a", "RECV")))
    }

    @Test
    fun builtinsDenyUnsupportedAndInteractiveWithoutRule() {
        val config = FirewallConfig(callPolicyEnabled = true, promptTimeoutMs = 30_000)
        assertEquals(
            CallDecision.DENY,
            CallRules.evaluate(AdbCallParser.parse("jdwp:123"), config).decision,
        )
        assertEquals(
            CallDecision.PROMPT,
            CallRules.evaluate(AdbCallParser.parse("shell:"), config).decision,
        )
        assertEquals(
            CallDecision.DENY,
            CallRules.evaluate(
                AdbCallParser.parse("shell:"),
                config.copy(promptTimeoutMs = 0),
            ).decision,
        )
        val interactiveRule = CallRule.forCall(
            AdbCallParser.parse("shell:"),
            CallAction.ALLOW,
        )
        assertEquals(
            CallDecision.ALLOW,
            CallRules.evaluate(
                AdbCallParser.parse("shell:"),
                config.copy(callRules = listOf(interactiveRule)),
            ).decision,
        )
    }

    @Test
    fun unmatchedCallPromptsOrDenies() {
        val call = AdbCallParser.parse("shell:ls")
        val prompting = FirewallConfig(callPolicyEnabled = true, promptTimeoutMs = 30_000)
        assertEquals(CallDecision.PROMPT, CallRules.evaluate(call, prompting).decision)
        val deny = FirewallConfig(callPolicyEnabled = true, promptTimeoutMs = 0)
        assertEquals(CallDecision.DENY, CallRules.evaluate(call, deny).decision)
        val off = FirewallConfig(callPolicyEnabled = false)
        assertEquals(CallDecision.ALLOW, CallRules.evaluate(call, off).decision)
    }

    @Test
    fun syncParserExtractsPathAndDirection() {
        val send = SyncParser.parse(syncPayload(0x444e4553, "/sdcard/a"))
        assertEquals("SEND", send!!.direction)
        assertEquals("/sdcard/a", send.path)

        val recv = SyncParser.parse(syncPayload(0x56434552, "/sdcard/b"))
        assertEquals("RECV", recv!!.direction)

        val stat = SyncParser.parse(syncPayload(0x54415453, "/sdcard/c"))
        assertEquals("STAT", stat!!.direction)

        assertNull(SyncParser.parse(ByteArray(4)))
        assertNull(SyncParser.parse(syncPayload(0x444e4553, "x".repeat(1025))))
    }

    @Test
    fun frameCodecRoundTripsAndRejectsBadMagic() {
        val output = ByteArrayOutputStream()
        AdbFrameCodec.write(output, AdbFrameCodec.A_OPEN, 7, 0, "shell:ls".toByteArray())
        val frame = AdbFrameCodec.read(output.toByteArray().inputStream())!!
        assertEquals(AdbFrameCodec.A_OPEN, frame.command)
        assertEquals(7, frame.arg0)
        assertEquals("shell:ls", AdbFrameCodec.service(frame))

        val bad = output.toByteArray().also { it[20] = 0 }
        try {
            AdbFrameCodec.read(bad.inputStream())
            assertTrue("bad magic must fail", false)
        } catch (_: Exception) {
        }
    }

    @Test
    fun foregroundTrackerClearsOnScreenAndLock() {
        val tracker = ForegroundTracker()
        tracker.onResumed("com.example.app")
        assertEquals("com.example.app", tracker.state().foreground)
        tracker.onInteractive(false)
        assertNull(tracker.state().foreground)
        tracker.onInteractive(true)
        tracker.onLocked(true)
        assertNull(tracker.state().foreground)
        tracker.onLocked(false)
        assertEquals("com.example.app", tracker.state().foreground)
    }

    @Test
    fun configRoundTripsThroughJson() {
        val config = FirewallConfig(
            enabled = true,
            ipAllowlistEnabled = true,
            ipAllowlist = listOf(IpNetwork.parse("100.64.0.0/10")!!),
            appAllowlistEnabled = true,
            appAllowlist = setOf("com.example.app"),
            appGraceMs = 10_000,
            callPolicyEnabled = true,
            callRules = listOf(
                CallRule(
                    id = "r1",
                    action = CallAction.ALLOW,
                    service = "shell",
                    predicates = listOf(CallPredicate.Exact("argv[0]", "ls")),
                ),
            ),
            promptTimeoutMs = 20_000,
            redactAudit = true,
        )
        val copy = FirewallConfig.fromJson(config.toJson())
        assertEquals(config.enabled, copy.enabled)
        assertEquals(config.ipAllowlist.map { it.format() }, copy.ipAllowlist.map { it.format() })
        assertEquals(config.appAllowlist, copy.appAllowlist)
        assertEquals(config.appGraceMs, copy.appGraceMs)
        assertEquals(config.callRules.single().predicates, copy.callRules.single().predicates)
        assertEquals(config.promptTimeoutMs, copy.promptTimeoutMs)
        assertEquals(config.redactAudit, copy.redactAudit)
    }

    private fun ip(text: String): Int =
        IpNetwork.parse("$text/32")!!.address

    private fun syncPayload(id: Int, path: String): ByteArray =
        ByteBuffer.allocate(8 + path.length)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(id)
            .putInt(path.length)
            .put(path.toByteArray())
            .array()
}
