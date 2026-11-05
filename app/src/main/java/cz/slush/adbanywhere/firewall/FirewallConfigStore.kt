/*
 * FirewallConfigStore - reads and writes the firewall configuration. The service
 * and the UI both go through this store, so the persisted shape stays in one place.
 */
package cz.slush.adbanywhere.firewall

import cz.slush.adbanywhere.data.Prefs
import org.json.JSONArray
import org.json.JSONObject

class FirewallConfigStore(private val prefs: Prefs) {
    fun load(): FirewallConfig = FirewallConfig(
        enabled = prefs.firewallEnabled,
        ipAllowlistEnabled = prefs.firewallIpAllowlistEnabled,
        ipAllowlist = parseIps(prefs.firewallIpAllowlistJson),
        appAllowlistEnabled = prefs.firewallAppAllowlistEnabled,
        appAllowlist = parseApps(prefs.firewallAppAllowlistJson),
        appGraceMs = prefs.firewallAppGraceMs,
        callPolicyEnabled = prefs.firewallCallPolicyEnabled,
        callRules = parseRules(prefs.firewallCallRulesJson),
        promptTimeoutMs = prefs.firewallPromptTimeoutMs,
        redactAudit = prefs.firewallRedactAudit,
    )

    fun save(config: FirewallConfig) {
        prefs.firewallEnabled = config.enabled
        prefs.firewallIpAllowlistEnabled = config.ipAllowlistEnabled
        prefs.firewallIpAllowlistJson =
            JSONArray(config.ipAllowlist.map { it.format() }).toString()
        prefs.firewallAppAllowlistEnabled = config.appAllowlistEnabled
        prefs.firewallAppAllowlistJson = JSONArray(config.appAllowlist.toList()).toString()
        prefs.firewallAppGraceMs = config.appGraceMs
        prefs.firewallCallPolicyEnabled = config.callPolicyEnabled
        prefs.firewallCallRulesJson =
            JSONArray(config.callRules.map { it.toJson() }).toString()
        prefs.firewallPromptTimeoutMs = config.promptTimeoutMs
        prefs.firewallRedactAudit = config.redactAudit
    }

    fun update(block: (FirewallConfig) -> FirewallConfig) {
        save(block(load()))
    }

    fun addRule(rule: CallRule) = update { it.copy(callRules = it.callRules + rule) }

    fun removeRule(id: String) = update {
        it.copy(callRules = it.callRules.filterNot { rule -> rule.id == id })
    }

    fun addIp(network: IpNetwork) = update {
        it.copy(ipAllowlist = it.ipAllowlist + network)
    }

    fun removeIp(cidr: String) = update {
        it.copy(ipAllowlist = it.ipAllowlist.filterNot { network -> network.format() == cidr })
    }

    fun addApp(packageName: String) = update {
        it.copy(appAllowlist = it.appAllowlist + packageName)
    }

    fun removeApp(packageName: String) = update {
        it.copy(appAllowlist = it.appAllowlist - packageName)
    }

    private fun parseIps(text: String): List<IpNetwork> = runCatching {
        val array = JSONArray(text)
        (0 until array.length()).mapNotNull { IpNetwork.parse(array.optString(it)) }
    }.getOrDefault(emptyList())

    private fun parseApps(text: String): Set<String> = runCatching {
        val array = JSONArray(text)
        (0 until array.length()).mapNotNull {
            array.optString(it).takeIf(String::isNotBlank)
        }.toSet()
    }.getOrDefault(emptySet())

    private fun parseRules(text: String): List<CallRule> = runCatching {
        val array = JSONArray(text)
        (0 until array.length()).mapNotNull {
            CallRule.fromJson(array.getJSONObject(it))
        }
    }.getOrDefault(emptyList())
}
