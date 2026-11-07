/*
 * FirewallConfigStore - reads and writes the firewall configuration. The service
 * and the UI both go through this store, so the persisted shape stays in one place.
 */
package cz.slush.adbanywhere.firewall

import cz.slush.adbanywhere.data.Prefs
import org.json.JSONArray
import org.json.JSONObject

class FirewallConfigStore(private val prefs: Prefs) {
    fun load(): FirewallConfig = synchronized(lock) { loadLocked() }

    fun save(config: FirewallConfig) = synchronized(lock) { saveLocked(config) }

    fun update(block: (FirewallConfig) -> FirewallConfig): FirewallConfig =
        synchronized(lock) {
            val current = loadLocked()
            val updated = block(current)
            if (updated != current) saveLocked(updated)
            updated
        }

    fun addRule(rule: CallRule) = update { it.copy(callRules = it.callRules + rule) }

    fun removeRule(id: String) = update {
        it.copy(callRules = it.callRules.filterNot { rule -> rule.id == id })
    }

    fun addIp(network: IpNetwork) = update {
        it.copy(ipAllowlist = it.ipAllowlist + network)
    }

    fun removeIp(cidr: String) = update {
        it.copy(
            ipAllowlist = it.ipAllowlist.filterNot { network -> network.format() == cidr },
            ipLastUsed = it.ipLastUsed - cidr,
        )
    }

    fun addApp(packageName: String) = update {
        it.copy(appAllowlist = it.appAllowlist + packageName)
    }

    fun removeApp(packageName: String) = update {
        it.copy(
            appAllowlist = it.appAllowlist - packageName,
            appLastUsed = it.appLastUsed - packageName,
        )
    }

    fun markIpUsed(cidr: String, time: Long = System.currentTimeMillis()) = update { config ->
        if (config.ipAllowlist.none { it.format() == cidr }) return@update config
        config.copy(ipLastUsed = config.ipLastUsed + (cidr to time))
    }

    fun markAppUsed(packageName: String, time: Long = System.currentTimeMillis()) = update { config ->
        if (packageName !in config.appAllowlist) return@update config
        config.copy(appLastUsed = config.appLastUsed + (packageName to time))
    }

    fun markRuleUsed(id: String, time: Long = System.currentTimeMillis()) = update { config ->
        if (config.callRules.none { it.id == id }) return@update config
        config.copy(
            callRules = config.callRules.map { rule ->
                if (rule.id == id) rule.copy(lastUsed = time) else rule
            },
        )
    }

    private fun loadLocked(): FirewallConfig = FirewallConfig(
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
        ipLastUsed = parseLastUsed(prefs.firewallIpLastUsedJson),
        appLastUsed = parseLastUsed(prefs.firewallAppLastUsedJson),
    )

    private fun saveLocked(config: FirewallConfig) {
        prefs.firewallEnabled = config.enabled
        prefs.firewallIpAllowlistEnabled = config.ipAllowlistEnabled
        prefs.firewallIpAllowlistJson =
            JSONArray(config.ipAllowlist.map { it.format() }).toString()
        prefs.firewallIpLastUsedJson = JSONObject(config.ipLastUsed).toString()
        prefs.firewallAppAllowlistEnabled = config.appAllowlistEnabled
        prefs.firewallAppAllowlistJson = JSONArray(config.appAllowlist.toList()).toString()
        prefs.firewallAppLastUsedJson = JSONObject(config.appLastUsed).toString()
        prefs.firewallAppGraceMs = config.appGraceMs
        prefs.firewallCallPolicyEnabled = config.callPolicyEnabled
        prefs.firewallCallRulesJson =
            JSONArray(config.callRules.map { it.toJson() }).toString()
        prefs.firewallPromptTimeoutMs = config.promptTimeoutMs
        prefs.firewallRedactAudit = config.redactAudit
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

    private fun parseLastUsed(text: String): Map<String, Long> = runCatching {
        val json = JSONObject(text)
        val map = linkedMapOf<String, Long>()
        json.keys().forEach { key ->
            val value = json.optLong(key, 0L)
            if (value > 0) map[key] = value
        }
        map
    }.getOrDefault(emptyMap())

    companion object {
        // The service records last-use timestamps while the UI saves configuration.
        // One process-wide lock keeps a load/modify/save from overwriting the other.
        private val lock = Any()
    }
}
