/*
 * AuditText - one audit event as a short human sentence. The JSONL file keeps the
 * raw event for support; the UI says what happened, from where, and why.
 */
package cz.slush.adbanywhere.firewall

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class AuditKind { Info, Allowed, Denied, Error }

data class AuditDescription(
    val title: String,
    val detail: String = "",
    val kind: AuditKind = AuditKind.Info,
)

object AuditText {
    private val clock = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val fullClock = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
    private val argvKey = Regex("""argv\[(\d+)]""")

    fun time(ms: Long): String =
        Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(clock)

    fun fullTime(ms: Long): String =
        Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(fullClock)

    fun describe(
        event: AuditEvent,
        keyLabels: Map<String, String> = emptyMap(),
        rules: List<CallRule> = emptyList(),
    ): AuditDescription {
        val peer = event.peer.ifBlank { "an unknown peer" }
        return when (event.event) {
            "connection_open" -> AuditDescription(
                "Connection from $peer",
                "A developer machine reached the VPN port.",
            )
            "auth_ok" -> AuditDescription(
                "Authenticated $peer",
                keyText(event.fingerprint, keyLabels),
                AuditKind.Allowed,
            )
            "auth_unknown" -> AuditDescription(
                "Unknown key offered by $peer",
                "The key is not paired. " + shortFingerprint(event.fingerprint),
                AuditKind.Denied,
            )
            "ip_denied" -> AuditDescription(
                "Denied $peer",
                ipReason(event.detail),
                AuditKind.Denied,
            )
            "app_denied" -> AuditDescription(
                "Denied $peer",
                appReason(event.detail),
                AuditKind.Denied,
            )
            "call_allowed" -> AuditDescription(
                "Allowed ${serviceName(event.service)} from $peer",
                callDetail(event, rules, allowed = true),
                AuditKind.Allowed,
            )
            "call_denied" -> AuditDescription(
                "Blocked ${serviceName(event.service)} from $peer",
                callDetail(event, rules, allowed = false),
                AuditKind.Denied,
            )
            "call_prompted" -> AuditDescription(
                "Approval requested by $peer",
                callSummary(event.service, event.fields),
            )
            "call_timeout" -> AuditDescription(
                "Approval timed out for $peer",
                callSummary(event.service, event.fields).ifBlank { "The call was denied." },
                AuditKind.Denied,
            )
            "proxy_error" -> AuditDescription(
                if (event.peer.isBlank()) "Relay error" else "Relay error with $peer",
                proxyReason(event.detail),
                AuditKind.Error,
            )
            "audit_suppressed" -> AuditDescription(
                "Rate limit reached",
                event.detail.ifBlank { "Some unauthenticated attempts were not recorded." },
            )
            else -> AuditDescription(
                event.event.replace('_', ' '),
                listOf(event.peer, event.detail).filter { it.isNotBlank() }.joinToString(" · "),
            )
        }
    }

    /** One line for the Copy action; the visible rows split title and detail. */
    fun line(
        event: AuditEvent,
        keyLabels: Map<String, String> = emptyMap(),
        rules: List<CallRule> = emptyList(),
    ): String {
        val description = describe(event, keyLabels, rules)
        return fullTime(event.time) + "  " + description.title +
            if (description.detail.isNotBlank()) " — " + description.detail else ""
    }

    /** The command or transfer a call carries, without the service prefix. */
    fun callSummary(service: String, fields: Map<String, String>): String {
        if (service == "sync") {
            val direction = when (fields["direction"]) {
                "send", "SEND" -> "push"
                "recv", "RECV" -> "pull"
                else -> fields["direction"].orEmpty()
            }
            return listOf(direction, fields["path"].orEmpty())
                .filter { it.isNotBlank() }
                .joinToString(" ")
        }
        val argv = fields.entries.mapNotNull { (key, value) ->
            val index = argvKey.matchEntire(key)?.groupValues?.get(1)?.toIntOrNull()
                ?: return@mapNotNull null
            index to value
        }.sortedBy { it.first }.map { it.second }
        return argv.joinToString(" ") { quote(it) }
    }

    private fun serviceName(service: String): String = when (service) {
        "shell" -> "shell"
        "sync" -> "file transfer"
        "abb_exec" -> "app shell"
        "stls" -> "TLS ADB"
        else -> service.ifBlank { "ADB call" }
    }

    private fun callDetail(event: AuditEvent, rules: List<CallRule>, allowed: Boolean): String {
        val summary = callSummary(event.service, event.fields)
        val reason = if (allowed) allowReason(event.rule, rules) else denyReason(event, rules)
        return listOf(summary, reason).filter { it.isNotBlank() }.joinToString(" · ")
    }

    private fun allowReason(rule: String, rules: List<CallRule>): String = when {
        rule.isBlank() -> ""
        rule == "prompt" -> "approved on the phone"
        else -> "allowed by " + rulePhrase(rule, rules)
    }

    private fun denyReason(event: AuditEvent, rules: List<CallRule>): String = when (val rule = event.rule) {
        "" -> event.detail.ifBlank { "not allowed" }
        "unauthenticated" -> "not authenticated"
        "prompt" -> "approval not given"
        "prompt-cap" -> "too many pending approvals"
        "app" -> "app allowlist"
        "builtin:unsupported" -> "unsupported service"
        "builtin:protected-port" -> "protected app port"
        "builtin:protected-path" -> "protected path"
        "builtin:relative-path" -> "relative path"
        "builtin:interactive" -> "interactive shell"
        "builtin:nul" -> "NUL in the service name"
        "sync:malformed" -> "malformed file transfer"
        else -> "blocked by " + rulePhrase(rule, rules)
    }

    private fun rulePhrase(id: String, rules: List<CallRule>): String {
        val rule = rules.firstOrNull { it.id == id } ?: return "rule $id"
        return rule.note.ifBlank { "your ${rule.service} rule" }
    }

    private fun ipReason(detail: String): String = when (detail) {
        "local address" -> "The VPN port was reached from the phone itself."
        "not routed through the VPN" -> "The reply to this address would not go through the VPN."
        "not in the IP allowlist" -> "The address is not in the IP allowlist."
        else -> detail.ifBlank { "The address is not in the IP allowlist." }
    }

    private fun appReason(detail: String): String = when {
        detail.startsWith("foreground=") -> {
            "Foreground app " + detail.removePrefix("foreground=") +
                " is not in the app allowlist."
        }
        detail == "screen off" -> "The screen is off, so no app is in the foreground."
        detail == "phone locked" -> "The phone is locked, so no app is in the foreground."
        detail == "usage access missing" -> {
            "Usage access is missing, so the allowlist cannot see the foreground app."
        }
        else -> "No allowed app was in the foreground."
    }

    private fun proxyReason(detail: String): String = when (detail) {
        "authentication timed out" -> "No ADB authentication within the timeout."
        "connection limit reached" -> "This machine already has the maximum open sessions."
        "the client did not start with A_CNXN" -> "The connection did not start an ADB handshake."
        "held stream overflow" -> "A call waiting for approval sent too much data."
        "accept failed" -> "The VPN listener stopped accepting connections."
        else -> if (detail.startsWith("stream id ")) {
            "An ADB stream id was reused; the connection was closed."
        } else {
            detail.ifBlank { "The relay closed the connection." }
        }
    }

    private fun keyText(fingerprint: String, keyLabels: Map<String, String>): String {
        val short = shortFingerprint(fingerprint)
        val label = keyLabels[fingerprint]
        return when {
            label != null && short.isNotEmpty() -> "Key: $label ($short)"
            short.isNotEmpty() -> "Key: $short"
            else -> ""
        }
    }

    private fun shortFingerprint(fingerprint: String): String = when {
        fingerprint.isBlank() -> ""
        fingerprint.length <= 24 -> fingerprint
        else -> fingerprint.take(21) + "…"
    }

    private fun quote(value: String): String =
        if (value.any(Char::isWhitespace)) "\"" + value + "\"" else value
}
