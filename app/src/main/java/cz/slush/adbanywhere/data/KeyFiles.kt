/*
 * KeyFiles - builds the daemon key and policy files. Pure functions so the
 * format is unit-tested without a device.
 */
package cz.slush.adbanywhere.data

object KeyFiles {
    fun keysFile(app: AndroidPubKey.Key, developers: List<DeveloperKey>): String {
        val lines = mutableListOf(app.keyLine())
        val seen = mutableSetOf(app.fingerprint)
        developers.forEach { developer ->
            if (seen.add(developer.fingerprint)) lines += developer.keyLine
        }
        return lines.joinToString("\n", postfix = "\n")
    }

    fun policyFile(app: AndroidPubKey.Key, developers: List<DeveloperKey>): String {
        val lines = mutableListOf("${app.fingerprint} app")
        val seen = mutableSetOf(app.fingerprint)
        developers.forEach { developer ->
            if (seen.add(developer.fingerprint)) lines += "${developer.fingerprint} developer"
        }
        return lines.joinToString("\n", postfix = "\n")
    }

    private fun AndroidPubKey.Key.keyLine(): String =
        AndroidPubKey.encodeLine(
            java.security.KeyFactory.getInstance("RSA").generatePublic(
                java.security.spec.RSAPublicKeySpec(modulus, exponent),
            ) as java.security.interfaces.RSAPublicKey,
            "app",
        )
}
