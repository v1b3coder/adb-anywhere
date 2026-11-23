/*
 * The audit log must explain a decision to a human: who connected, what ran and
 * why it was refused. The JSONL keeps the raw event; these are the sentences.
 */
package cz.slush.adbanywhere

import cz.slush.adbanywhere.firewall.AuditEvent
import cz.slush.adbanywhere.firewall.AuditKind
import cz.slush.adbanywhere.firewall.AuditText
import cz.slush.adbanywhere.firewall.CallAction
import cz.slush.adbanywhere.firewall.CallRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuditTextTest {
    private fun event(
        name: String,
        peer: String = "100.64.0.13",
        service: String = "",
        fields: Map<String, String> = emptyMap(),
        rule: String = "",
        detail: String = "",
        fingerprint: String = "",
    ) = AuditEvent(
        event = name,
        peer = peer,
        service = service,
        fields = fields,
        rule = rule,
        detail = detail,
        fingerprint = fingerprint,
    )

    @Test
    fun appDeniedNamesTheForegroundApp() {
        val text = AuditText.describe(event("app_denied", detail = "foreground=com.example.chat"))
        assertEquals("Denied 100.64.0.13", text.title)
        assertTrue(text.detail.contains("com.example.chat"))
        assertTrue(text.detail.contains("app allowlist"))
        assertEquals(AuditKind.Denied, text.kind)
    }

    @Test
    fun appDeniedExplainsAScreenOffDenial() {
        val text = AuditText.describe(event("app_denied", detail = "screen off"))
        assertTrue(text.detail.contains("screen is off"))
    }

    @Test
    fun ipDeniedNamesTheAllowlist() {
        val text = AuditText.describe(event("ip_denied", detail = "not in the IP allowlist"))
        assertTrue(text.detail.contains("IP allowlist"))
        assertEquals(AuditKind.Denied, text.kind)
    }

    @Test
    fun allowedShellShowsTheCommand() {
        val text = AuditText.describe(
            event(
                "call_allowed",
                service = "shell",
                fields = mapOf("argv[0]" to "ls", "argv[1]" to "-la", "argv[2]" to "/sdcard"),
            ),
        )
        assertEquals("Allowed shell from 100.64.0.13", text.title)
        assertEquals("ls -la /sdcard", text.detail)
        assertEquals(AuditKind.Allowed, text.kind)
    }

    @Test
    fun aUserRuleIsNamedByItsNote() {
        val rule = CallRule(
            id = "r3",
            action = CallAction.DENY,
            service = "shell",
            predicates = emptyList(),
            note = "No destructive commands",
        )
        val text = AuditText.describe(
            event("call_denied", service = "shell", fields = mapOf("argv[0]" to "rm"), rule = "r3"),
            rules = listOf(rule),
        )
        assertTrue(text.title.startsWith("Blocked shell"))
        assertTrue(text.detail.contains("No destructive commands"))
    }

    @Test
    fun aBuiltinDenialIsPhrasedNotRaw() {
        val text = AuditText.describe(
            event("call_denied", service = "sync", rule = "builtin:protected-path"),
        )
        assertTrue(text.detail.contains("protected path"))
        assertTrue(!text.detail.contains("builtin:"))
    }

    @Test
    fun authenticationUsesThePairedKeyLabel() {
        val fingerprint = "SHA256:abcdefghijklmnopqrstuvwxyz"
        val text = AuditText.describe(
            event("auth_ok", fingerprint = fingerprint),
            keyLabels = mapOf(fingerprint to "Laptop"),
        )
        assertEquals("Authenticated 100.64.0.13", text.title)
        assertTrue(text.detail.contains("Laptop"))
    }

    @Test
    fun aFileTransferIsNamedByDirectionAndPath() {
        assertEquals(
            "push /sdcard/a b.txt",
            AuditText.callSummary("sync", mapOf("direction" to "send", "path" to "/sdcard/a b.txt")),
        )
        assertEquals(
            "pull /sdcard/a",
            AuditText.callSummary("sync", mapOf("direction" to "recv", "path" to "/sdcard/a")),
        )
    }

    @Test
    fun anIdleEvictionIsExplained() {
        val text = AuditText.describe(event("session_evicted"))
        assertEquals("Replaced an idle session from 100.64.0.13", text.title)
        assertTrue(text.detail.contains("no open stream"))
    }

    @Test
    fun aConnectionLimitIsExplained() {
        val text = AuditText.describe(event("proxy_error", detail = "connection limit reached"))
        assertTrue(text.detail.contains("maximum open sessions"))
        assertEquals(AuditKind.Error, text.kind)
    }

    @Test
    fun anApprovalTimeoutShowsTheCall() {
        val text = AuditText.describe(
            event("call_timeout", service = "shell", fields = mapOf("argv[0]" to "id")),
        )
        assertEquals("Approval timed out for 100.64.0.13", text.title)
        assertEquals("id", text.detail)
        assertEquals(AuditKind.Denied, text.kind)
    }
}
