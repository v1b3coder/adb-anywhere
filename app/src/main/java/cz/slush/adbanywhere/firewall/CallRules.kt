/*
 * CallRules - evaluates a normalized ADB call against the built-in denies and the
 * user's ordered rules. Rules first; a prompt is only for an unmatched call.
 */
package cz.slush.adbanywhere.firewall

enum class CallDecision { ALLOW, DENY, PROMPT }

data class CallVerdict(
    val decision: CallDecision,
    val ruleId: String? = null,
    val timeoutMs: Long = 30_000L,
)

/**
 * What the built-in denies protect: the app's own ports and its daemon files.
 * A peer limited by the call policy must not reach adbd past the relay
 * (`tcp:<loopback port>`, `tcp:5555`) or rewrite the daemon's key files and
 * scripts through sync.
 */
data class Protected(
    val ports: Set<Int> = emptySet(),
    val directory: String = "",
)

object CallRules {
    fun evaluate(call: AdbCall, config: FirewallConfig, protected: Protected = Protected()): CallVerdict {
        if (!config.callPolicyEnabled) {
            return CallVerdict(CallDecision.ALLOW)
        }
        if (AdbCallParser.isUnsupported(call.service)) {
            return CallVerdict(CallDecision.DENY, "builtin:unsupported")
        }
        if (call.service == "tcp" && (call.port == null || call.port in protected.ports)) {
            return CallVerdict(CallDecision.DENY, "builtin:protected-port")
        }
        if (call.service == "sync" && call.path != null && protected.directory.isNotEmpty() &&
            isUnder(normalize(call.path), protected.directory)
        ) {
            return CallVerdict(CallDecision.DENY, "builtin:protected-path")
        }
        if (call.service == "shell" && call.interactive) {
            val rule = config.callRules.firstOrNull { it.matches(call) }
            if (rule != null) return verdict(rule, config)
            return CallVerdict(
                decision = if (config.promptTimeoutMs > 0) {
                    CallDecision.PROMPT
                } else {
                    CallDecision.DENY
                },
                ruleId = "builtin:interactive",
                timeoutMs = config.promptTimeoutMs,
            )
        }
        val rule = config.callRules.firstOrNull { it.matches(call) }
        if (rule != null) return verdict(rule, config)
        return CallVerdict(
            decision = if (config.promptTimeoutMs > 0) CallDecision.PROMPT else CallDecision.DENY,
            timeoutMs = config.promptTimeoutMs,
        )
    }

    /** Lexical normalization: `//`, `/./` and `..` are resolved; symlinks are not. */
    internal fun normalize(path: String): String {
        val parts = ArrayDeque<String>()
        for (part in path.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> parts.removeLastOrNull()
                else -> parts.addLast(part)
            }
        }
        return "/" + parts.joinToString("/")
    }

    private fun isUnder(path: String, directory: String): Boolean {
        val dir = normalize(directory)
        return path == dir || path.startsWith("$dir/")
    }

    private fun verdict(rule: CallRule, config: FirewallConfig): CallVerdict =
        CallVerdict(
            decision = if (rule.action == CallAction.ALLOW) {
                CallDecision.ALLOW
            } else {
                CallDecision.DENY
            },
            ruleId = rule.id,
            timeoutMs = config.promptTimeoutMs,
        )
}
