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

object CallRules {
    fun evaluate(call: AdbCall, config: FirewallConfig): CallVerdict {
        if (!config.callPolicyEnabled) {
            return CallVerdict(CallDecision.ALLOW)
        }
        if (AdbCallParser.isUnsupported(call.service)) {
            return CallVerdict(CallDecision.DENY, "builtin:unsupported")
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
