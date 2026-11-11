package cz.slush.adbanywhere

import cz.slush.adbanywhere.firewall.AdbCallParser
import cz.slush.adbanywhere.firewall.CallAction
import cz.slush.adbanywhere.firewall.CallDecision
import cz.slush.adbanywhere.firewall.CallPredicate
import cz.slush.adbanywhere.firewall.CallRule
import cz.slush.adbanywhere.firewall.CallRules
import cz.slush.adbanywhere.firewall.FirewallConfig
import cz.slush.adbanywhere.firewall.Protected
import cz.slush.adbanywhere.firewall.SyncMalformedException
import cz.slush.adbanywhere.firewall.SyncParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The call policy judges what adbd will run: one path, whole words, no cut-off allow. */
class CallPolicyHardeningTest {
    private val sync = AdbCallParser.parse("sync:")

    private fun rule(action: CallAction, service: String, vararg predicates: CallPredicate) =
        CallRule(id = "r", action = action, service = service, predicates = predicates.toList())

    private fun config(vararg rules: CallRule) =
        FirewallConfig(callPolicyEnabled = true, callRules = rules.toList(), promptTimeoutMs = 0)

    @Test
    fun aDenyPathRuleMatchesEverySpellingOfThePath() {
        val deny = rule(CallAction.DENY, "sync", CallPredicate.Glob("path", "/sdcard/DCIM/**"))
        val allow = rule(CallAction.ALLOW, "sync", CallPredicate.AnyValue("path"))
        val config = config(deny, allow.copy(id = "a"))
        for (spelling in listOf("/sdcard/DCIM/x", "//sdcard/DCIM/x", "/sdcard/./DCIM/x", "/a/../sdcard/DCIM/x")) {
            val verdict = CallRules.evaluate(sync.withSync(spelling, "RECV"), config)
            assertEquals(spelling, "r", verdict.ruleId)
            assertEquals(spelling, CallDecision.DENY, verdict.decision)
        }
    }

    @Test
    fun anAllowPathRuleDoesNotAdmitATraversal() {
        val allow = rule(CallAction.ALLOW, "sync", CallPredicate.Glob("path", "/sdcard/**"))
        val verdict = CallRules.evaluate(sync.withSync("/sdcard/../data/local/tmp/x", "SEND"), config(allow))
        assertEquals(CallDecision.DENY, verdict.decision)
    }

    @Test
    fun theProtectedDirectoryIsJudgedOnTheNormalizedPath() {
        val protected = Protected(directory = "/data/local/tmp/adb-anywhere")
        val allow = rule(CallAction.ALLOW, "sync", CallPredicate.AnyValue("path"))
        val call = sync.withSync("/data/local/tmp/./x/../adb-anywhere//tls_key.pem", "RECV")
        assertEquals("builtin:protected-path", CallRules.evaluate(call, config(allow), protected).ruleId)
        assertEquals("/data/local/tmp/./x/../adb-anywhere//tls_key.pem", call.fields["raw_path"])
    }

    @Test
    fun aRelativeSyncPathIsDenied() {
        // adbd resolves it against a working directory the relay cannot see.
        val allow = rule(CallAction.ALLOW, "sync", CallPredicate.AnyValue("path"))
        for (path in listOf("tls_key.pem", "../adb-anywhere/adb_keys", "")) {
            val verdict = CallRules.evaluate(sync.withSync(path, "RECV"), config(allow))
            assertEquals(path, "builtin:relative-path", verdict.ruleId)
        }
    }

    @Test
    fun aSyncPathWithANulOrBadUtf8IsMalformed() {
        for (bytes in listOf("/ok\u0000/../x".toByteArray(), byteArrayOf(0x2f, 0xc0.toByte(), 0xaf.toByte()))) {
            assertTrue(runCatching { SyncParser.decodePath(bytes) }.exceptionOrNull() is SyncMalformedException)
        }
        assertEquals("/sdcard/žluťoučký", SyncParser.decodePath("/sdcard/žluťoučký".toByteArray()))
    }

    @Test
    fun aCommandPrefixCoversWholeWordsOnly() {
        val runAs = CallRule(
            id = "p",
            action = CallAction.ALLOW,
            service = "shell",
            predicates = listOf(CallPredicate.Prefix("command", "run-as com.foo")),
            rawShell = true,
        )
        assertTrue(runAs.matches(AdbCallParser.parse("shell:run-as com.foo")))
        assertTrue(runAs.matches(AdbCallParser.parse("shell:run-as com.foo id")))
        assertFalse(runAs.matches(AdbCallParser.parse("shell:run-as com.foobar id")))
        assertFalse(runAs.matches(AdbCallParser.parse("shell:run-as com.foo.evil id")))
        // A prefix that ends at a word boundary keeps its meaning.
        val withSpace = runAs.copy(predicates = listOf(CallPredicate.Prefix("command", "run-as com.foo ")))
        assertTrue(withSpace.matches(AdbCallParser.parse("shell:run-as com.foo id")))
        assertFalse(withSpace.matches(AdbCallParser.parse("shell:run-as com.foobar id")))
    }

    @Test
    fun anAllowNeverMatchesATruncatedCallByItsKeptPart() {
        val long = "echo " + "a".repeat(600)
        val call = AdbCallParser.parse("shell:$long")
        assertTrue(call.truncated)
        val pinned = CallRule.forCall(call, CallAction.ALLOW)
        // The same first 512 characters followed by something else.
        val other = AdbCallParser.parse("shell:${long}b /data/local/tmp/x")
        assertEquals(call.fields["command"], other.fields["command"])
        assertFalse(pinned.matches(other))
        assertFalse(pinned.matches(call))
        // A deny still matches, and a service-wide allow does not look at fields.
        assertTrue(CallRule.forCall(call, CallAction.DENY).matches(other))
        assertTrue(rule(CallAction.ALLOW, "shell", CallPredicate.AnyValue("command")).matches(call))
    }
}
