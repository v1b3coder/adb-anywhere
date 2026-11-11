package cz.slush.adbanywhere

import cz.slush.adbanywhere.firewall.AuditEvent
import cz.slush.adbanywhere.firewall.AuditLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AuditLogTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun recordsRingAndFile() {
        val file = File(folder.root, "audit.jsonl")
        val log = AuditLog(file) { false }
        log.record(AuditEvent(event = "connection_open", peer = "100.64.0.2"))
        log.record(
            AuditEvent(
                event = "call_allowed",
                peer = "100.64.0.2",
                service = "shell",
                fields = mapOf("argv[0]" to "ls"),
            ),
        )
        assertEquals(2, log.entries.value.size)
        assertEquals(2, file.readLines().size)
        assertTrue(file.readText().contains("\"event\":\"call_allowed\""))
    }

    @Test
    fun redactsValuesAndKeepsTheEvent() {
        val file = File(folder.root, "audit.jsonl")
        val log = AuditLog(file) { true }
        log.record(
            AuditEvent(
                event = "call_allowed",
                service = "shell",
                fields = mapOf("argv[0]" to "secret"),
            ),
        )
        val line = file.readText()
        assertTrue(line.contains("sha256:"))
        assertTrue(!line.contains("secret"))
        assertEquals("call_allowed", log.entries.value.single().event)
    }

    @Test
    fun redactionIsStable() {
        assertEquals(AuditEvent.redact("secret"), AuditEvent.redact("secret"))
        assertNotEquals(AuditEvent.redact("secret"), AuditEvent.redact("other"))
    }

    @Test
    fun clearsRingAndFile() {
        val file = File(folder.root, "audit.jsonl")
        val log = AuditLog(file) { false }
        log.record(AuditEvent(event = "connection_open"))
        log.clear()
        assertEquals(0, log.entries.value.size)
        assertTrue(!file.exists())
    }

    @Test
    fun ringIsBounded() {
        val log = AuditLog(null) { false }
        repeat(600) { log.record(AuditEvent(event = "connection_open")) }
        assertEquals(500, log.entries.value.size)
    }

    @Test
    fun unauthenticatedEventsAreRateLimitedAndCounted() {
        var now = 1_000_000L
        val file = File(folder.root, "audit.jsonl")
        val log = AuditLog(file, clock = { now }) { false }
        log.record(AuditEvent(event = "auth_ok", peer = "100.64.0.2"))
        repeat(10_000) { log.record(AuditEvent(event = "ip_denied", peer = "100.64.0.9"), unauthenticated = true) }
        // The flood cannot rotate earlier entries out of the file.
        assertTrue(file.readText().contains("auth_ok"))
        val denied = log.entries.value.count { it.event == "ip_denied" }
        assertEquals(AuditLog.UNAUTHENTICATED_BURST, denied)
        // Authenticated events are never limited, and the next one reports the gap.
        log.record(AuditEvent(event = "call_allowed", peer = "100.64.0.2"))
        val suppressed = log.entries.value.last { it.event == "audit_suppressed" }
        assertTrue(suppressed.detail.startsWith("${10_000 - AuditLog.UNAUTHENTICATED_BURST} "))
        assertEquals("call_allowed", log.entries.value.last().event)
        // Tokens refill with time.
        now += AuditLog.UNAUTHENTICATED_REFILL_MS * 3
        repeat(5) { log.record(AuditEvent(event = "ip_denied", peer = "100.64.0.9"), unauthenticated = true) }
        assertEquals(3, log.entries.value.takeLastWhile { it.event != "call_allowed" }.count { it.event == "ip_denied" })
    }
}
