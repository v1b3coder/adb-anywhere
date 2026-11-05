/*
 * RelayPolicy - the relay's view of policy. The relay never authenticates; it
 * only asks for a verdict, a prompt and a rule to persist.
 */
package cz.slush.adbanywhere.firewall

enum class PromptDecision { BLOCK, ALLOW_ONCE, ALWAYS_ALLOW, CUSTOM }

data class PromptResponse(
    val decision: PromptDecision,
    val rule: CallRule? = null,
)

data class PromptRequest(
    val id: String,
    val call: AdbCall,
    val peer: String,
)

interface RelayPolicy {
    fun evaluate(call: AdbCall): CallVerdict
    suspend fun prompt(call: AdbCall, peer: String, timeoutMs: Long): PromptResponse?
    fun onRule(rule: CallRule)
    fun promptPending(): Boolean
}

class FirewallPolicy(
    private val config: () -> FirewallConfig,
    private val prompt: suspend (PromptRequest, Long) -> PromptResponse?,
    private val pending: () -> Boolean,
    private val saveRule: (CallRule) -> Unit,
    private val audit: (AuditEvent) -> Unit,
) : RelayPolicy {
    override fun evaluate(call: AdbCall): CallVerdict =
        CallRules.evaluate(call, config())

    override suspend fun prompt(
        call: AdbCall,
        peer: String,
        timeoutMs: Long,
    ): PromptResponse? {
        val request = PromptRequest(
            id = java.util.UUID.randomUUID().toString(),
            call = call,
            peer = peer,
        )
        audit(
            AuditEvent(
                event = "call_prompted",
                peer = peer,
                service = call.service,
                fields = call.fields,
            ),
        )
        val response = prompt(request, timeoutMs)
        when (response?.decision) {
            PromptDecision.ALWAYS_ALLOW -> saveRule(CallRule.forCall(call, CallAction.ALLOW))
            PromptDecision.CUSTOM -> response.rule?.let(saveRule)
            else -> Unit
        }
        return response
    }

    override fun onRule(rule: CallRule) = saveRule(rule)

    override fun promptPending(): Boolean = pending()
}
