/*
 * RelayPolicy - the relay's view of policy. The relay never authenticates; it
 * only asks for a verdict, a prompt and a rule to persist.
 */
package cz.slush.adbanywhere.firewall

enum class PromptDecision { BLOCK, ALLOW_ONCE, ALWAYS_ALLOW, CUSTOM }

data class PromptResponse(
    val decision: PromptDecision,
    val rule: CallRule? = null,
) {
    /**
     * The effective verdict. A custom filter follows its rule's action, so a
     * `Deny` filter denies the call instead of allowing it.
     */
    val allowsCall: Boolean
        get() = when (decision) {
            PromptDecision.ALLOW_ONCE, PromptDecision.ALWAYS_ALLOW -> true
            PromptDecision.CUSTOM -> rule?.action == CallAction.ALLOW
            PromptDecision.BLOCK -> false
        }
}

data class PromptRequest(
    val id: String,
    val call: AdbCall,
    val peer: String,
    /** The key the transport authenticated with, when the relay saw one. */
    val fingerprint: String = "",
    /** The developer key label for [fingerprint], resolved by the service. */
    val label: String = "",
    /** Absolute time the prompt is denied, so the card can show a countdown. */
    val deadline: Long = System.currentTimeMillis() + 30_000L,
)

interface RelayPolicy {
    fun evaluate(call: AdbCall): CallVerdict
    suspend fun prompt(
        call: AdbCall,
        peer: String,
        fingerprint: String,
        timeoutMs: Long,
    ): PromptResponse?
    fun onRule(rule: CallRule)
    fun promptPending(): Boolean
}

class FirewallPolicy(
    private val config: () -> FirewallConfig,
    private val prompt: suspend (PromptRequest, Long) -> PromptResponse?,
    private val pending: () -> Boolean,
    private val saveRule: (CallRule) -> Unit,
    private val audit: (AuditEvent) -> Unit,
    private val onRuleUsed: (String) -> Unit = {},
) : RelayPolicy {
    override fun evaluate(call: AdbCall): CallVerdict {
        val config = config()
        val verdict = CallRules.evaluate(call, config)
        verdict.ruleId
            ?.takeIf { id -> config.callRules.any { it.id == id } }
            ?.let(onRuleUsed)
        return verdict
    }

    override suspend fun prompt(
        call: AdbCall,
        peer: String,
        fingerprint: String,
        timeoutMs: Long,
    ): PromptResponse? {
        val request = PromptRequest(
            id = java.util.UUID.randomUUID().toString(),
            call = call,
            peer = peer,
            fingerprint = fingerprint,
            deadline = System.currentTimeMillis() + timeoutMs,
        )
        audit(
            AuditEvent(
                event = "call_prompted",
                peer = peer,
                fingerprint = fingerprint,
                service = call.service,
                fields = call.fields,
            ),
        )
        val response = prompt(request, timeoutMs)
        val now = System.currentTimeMillis()
        when (response?.decision) {
            // A truncated call cannot be pinned exactly, so "Always allow" only
            // allows this call instead of persisting a rule that would match more.
            PromptDecision.ALWAYS_ALLOW ->
                if (!call.truncated) {
                    saveRule(CallRule.forCall(call, CallAction.ALLOW).copy(lastUsed = now))
                }
            PromptDecision.CUSTOM -> response.rule?.let { saveRule(it.copy(lastUsed = now)) }
            else -> Unit
        }
        return response
    }

    override fun onRule(rule: CallRule) = saveRule(rule)

    override fun promptPending(): Boolean = pending()
}
