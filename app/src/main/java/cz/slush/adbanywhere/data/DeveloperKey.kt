/*
 * DeveloperKey - a paired developer machine's public key, label and role.
 */
package cz.slush.adbanywhere.data

import org.json.JSONArray
import org.json.JSONObject

data class DeveloperKey(
    val key: AndroidPubKey.Key,
    val label: String,
    val role: String = "developer",
    val addedAt: Long = System.currentTimeMillis(),
) {
    val fingerprint: String get() = key.fingerprint
    val keyLine: String get() = AndroidPubKey.encodeLine(
        java.security.KeyFactory.getInstance("RSA").generatePublic(
            java.security.spec.RSAPublicKeySpec(key.modulus, key.exponent),
        ) as java.security.interfaces.RSAPublicKey,
        sanitize(label),
    )

    fun toJson(): JSONObject = JSONObject().apply {
        put("key", java.util.Base64.getEncoder().encodeToString(key.raw))
        put("label", label)
        put("role", role)
        put("addedAt", addedAt)
    }

    companion object {
        /**
         * A label is a comment in a line-based key file written through a shell
         * command, so every control character (NUL, newline, tab, escape) becomes
         * a space and the length is bounded. A NUL would truncate the command in
         * adbd and break every later key-file write.
         */
        fun sanitize(label: String): String =
            label.map { if (it.isISOControl() || it == '\u2028' || it == '\u2029') ' ' else it }
                .joinToString("")
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(MAX_LABEL)
                .ifEmpty { "developer" }

        const val MAX_LABEL = 64

        fun fromJson(json: JSONObject): DeveloperKey? {
            return try {
                val raw = java.util.Base64.getDecoder().decode(json.getString("key"))
                val key = AndroidPubKey.decode(raw) ?: return null
                DeveloperKey(
                    key = key,
                    label = json.optString("label", "developer"),
                    role = json.optString("role", "developer"),
                    addedAt = json.optLong("addedAt", System.currentTimeMillis()),
                )
            } catch (_: Exception) {
                null
            }
        }

        fun list(json: String): List<DeveloperKey> = try {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { fromJson(array.getJSONObject(it)) }
        } catch (_: Exception) {
            emptyList()
        }

        fun store(keys: List<DeveloperKey>): String {
            val array = JSONArray()
            keys.forEach { array.put(it.toJson()) }
            return array.toString()
        }
    }
}
