/*
 * FirewallModels - normalized ADB calls, rules and configuration. The relay and
 * the UI share these types; nothing here touches Android or sockets.
 */
package cz.slush.adbanywhere.firewall

import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetAddress

data class IpNetwork(val address: Int, val prefix: Int) {
    fun contains(peer: Int): Boolean {
        val mask = if (prefix == 0) 0 else -1 shl (32 - prefix)
        return (address and mask) == (peer and mask)
    }

    fun format(): String {
        val a = (address ushr 24) and 0xff
        val b = (address ushr 16) and 0xff
        val c = (address ushr 8) and 0xff
        val d = address and 0xff
        return "$a.$b.$c.$d/$prefix"
    }

    companion object {
        private val IPV4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})(?:/(\d{1,2}))?$""")

        fun parse(text: String): IpNetwork? {
            val match = IPV4.matchEntire(text.trim()) ?: return null
            val octets = (1..4).map { match.groupValues[it].toIntOrNull() ?: return null }
            if (octets.any { it !in 0..255 }) return null
            val prefix = match.groupValues[5].ifEmpty { "32" }.toIntOrNull() ?: return null
            if (prefix !in 0..32) return null
            val address = (octets[0] shl 24) or (octets[1] shl 16) or (octets[2] shl 8) or octets[3]
            return IpNetwork(address, prefix)
        }
    }
}

enum class CallAction { ALLOW, DENY }

sealed interface CallPredicate {
    val field: String

    data class Exact(override val field: String, val value: String) : CallPredicate
    data class Prefix(override val field: String, val value: String) : CallPredicate
    data class Glob(override val field: String, val pattern: String) : CallPredicate
    data class AnyValue(override val field: String) : CallPredicate

    fun toJson(): JSONObject {
        val type = when (this) {
            is Exact -> "exact"
            is Prefix -> "prefix"
            is Glob -> "glob"
            is AnyValue -> "any"
            else -> "any"
        }
        val json = JSONObject()
        json.put("type", type)
        json.put("field", field)
        when (this) {
            is Exact -> json.put("value", value)
            is Prefix -> json.put("value", value)
            is Glob -> json.put("value", pattern)
            is AnyValue -> Unit
            else -> Unit
        }
        return json
    }

    companion object {
        fun fromJson(json: JSONObject): CallPredicate? {
            val field = json.optString("field").ifBlank { return null }
            return when (json.optString("type")) {
                "exact" -> Exact(field, json.optString("value"))
                "prefix" -> Prefix(field, json.optString("value"))
                "glob" -> Glob(field, json.optString("value"))
                "any" -> AnyValue(field)
                else -> null
            }
        }
    }
}

data class AdbCall(
    val service: String,
    val raw: String,
    val fields: Map<String, String>,
    val argv: List<String>? = null,
    val path: String? = null,
    val direction: String? = null,
    val port: Int? = null,
    val interactive: Boolean = false,
    val metacharacters: Boolean = false,
) {
    fun withSync(path: String, direction: String): AdbCall = copy(
        path = path,
        direction = direction,
        fields = fields + mapOf("path" to path, "direction" to direction),
    )
}

data class CallRule(
    val id: String,
    val action: CallAction,
    val service: String,
    val predicates: List<CallPredicate>,
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis(),
) {
    fun matches(call: AdbCall): Boolean {
        if (service != "*" && service != call.service) return false
        if (call.metacharacters && predicates.none {
                it is CallPredicate.Exact &&
                    it.field == "metacharacters" &&
                    it.value == "true"
            }
        ) {
            return false
        }
        return predicates.all { predicate ->
            val value = call.fields[predicate.field]
            when (predicate) {
                is CallPredicate.AnyValue -> true
                is CallPredicate.Exact -> value == predicate.value
                is CallPredicate.Prefix -> value != null && value.startsWith(predicate.value)
                is CallPredicate.Glob -> value != null &&
                    Glob.matches(predicate.pattern, value, predicate.field == "path")
            }
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("action", action.name.lowercase())
        put("service", service)
        put("note", note)
        put("createdAt", createdAt)
        val array = JSONArray()
        predicates.forEach { array.put(it.toJson()) }
        put("predicates", array)
    }

    companion object {
        fun fromJson(json: JSONObject): CallRule? {
            val id = json.optString("id").ifBlank { return null }
            val action = when (json.optString("action")) {
                "allow" -> CallAction.ALLOW
                "deny" -> CallAction.DENY
                else -> return null
            }
            val service = json.optString("service").ifBlank { "*" }
            val predicates = runCatching {
                val array = json.optJSONArray("predicates") ?: JSONArray()
                (0 until array.length()).mapNotNull {
                    CallPredicate.fromJson(array.getJSONObject(it))
                }
            }.getOrDefault(emptyList())
            return CallRule(
                id = id,
                action = action,
                service = service,
                predicates = predicates,
                note = json.optString("note"),
                createdAt = json.optLong("createdAt", System.currentTimeMillis()),
            )
        }

        fun forCall(call: AdbCall, action: CallAction, note: String = ""): CallRule =
            CallRule(
                id = java.util.UUID.randomUUID().toString(),
                action = action,
                service = call.service,
                predicates = call.fields.map { (field, value) ->
                    CallPredicate.Exact(field, value)
                },
                note = note,
            )
    }
}

private object Glob {
    fun matches(pattern: String, value: String, path: Boolean): Boolean {
        val regex = buildString {
            append('^')
            var index = 0
            while (index < pattern.length) {
                val char = pattern[index]
                when {
                    char == '*' && index + 1 < pattern.length && pattern[index + 1] == '*' -> {
                        append(".*")
                        index++
                    }
                    char == '*' -> append(if (path) "[^/]*" else ".*")
                    char == '?' -> append(if (path) "[^/]" else ".")
                    else -> append(Regex.escape(char.toString()))
                }
                index++
            }
            append('$')
        }
        return Regex(regex).matches(value)
    }
}

data class FirewallConfig(
    val enabled: Boolean = false,
    val ipAllowlistEnabled: Boolean = false,
    val ipAllowlist: List<IpNetwork> = emptyList(),
    val appAllowlistEnabled: Boolean = false,
    val appAllowlist: Set<String> = emptySet(),
    val appGraceMs: Long = 15_000L,
    val callPolicyEnabled: Boolean = false,
    val callRules: List<CallRule> = emptyList(),
    val promptTimeoutMs: Long = 30_000L,
    val redactAudit: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("enabled", enabled)
        put("ipAllowlistEnabled", ipAllowlistEnabled)
        put("ipAllowlist", JSONArray(ipAllowlist.map { it.format() }))
        put("appAllowlistEnabled", appAllowlistEnabled)
        put("appAllowlist", JSONArray(appAllowlist.toList()))
        put("appGraceMs", appGraceMs)
        put("callPolicyEnabled", callPolicyEnabled)
        put("callRules", JSONArray(callRules.map { it.toJson() }))
        put("promptTimeoutMs", promptTimeoutMs)
        put("redactAudit", redactAudit)
    }

    companion object {
        fun fromJson(json: JSONObject): FirewallConfig {
            val ips = runCatching {
                val array = json.optJSONArray("ipAllowlist") ?: JSONArray()
                (0 until array.length()).mapNotNull {
                    IpNetwork.parse(array.optString(it))
                }
            }.getOrDefault(emptyList())
            val apps = runCatching {
                val array = json.optJSONArray("appAllowlist") ?: JSONArray()
                (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
                    .toSet()
            }.getOrDefault(emptySet())
            val rules = runCatching {
                val array = json.optJSONArray("callRules") ?: JSONArray()
                (0 until array.length()).mapNotNull {
                    CallRule.fromJson(array.getJSONObject(it))
                }
            }.getOrDefault(emptyList())
            return FirewallConfig(
                enabled = json.optBoolean("enabled"),
                ipAllowlistEnabled = json.optBoolean("ipAllowlistEnabled"),
                ipAllowlist = ips,
                appAllowlistEnabled = json.optBoolean("appAllowlistEnabled"),
                appAllowlist = apps,
                appGraceMs = json.optLong("appGraceMs", 15_000L),
                callPolicyEnabled = json.optBoolean("callPolicyEnabled"),
                callRules = rules,
                promptTimeoutMs = json.optLong("promptTimeoutMs", 30_000L),
                redactAudit = json.optBoolean("redactAudit"),
            )
        }
    }
}

class IpAllowlist(private val networks: List<IpNetwork>) {
    fun allows(peer: InetAddress, ownVpn: InetAddress?): Boolean {
        if (peer !is Inet4Address) return false
        if (peer.isLoopbackAddress || peer.isLinkLocalAddress) return false
        if (ownVpn != null && peer.address.contentEquals(ownVpn.address)) return false
        val value = ipv4ToInt(peer)
        return networks.any { it.contains(value) }
    }

    private fun ipv4ToInt(address: InetAddress): Int {
        val bytes = address.address
        return ((bytes[0].toInt() and 0xff) shl 24) or
            ((bytes[1].toInt() and 0xff) shl 16) or
            ((bytes[2].toInt() and 0xff) shl 8) or
            (bytes[3].toInt() and 0xff)
    }
}
