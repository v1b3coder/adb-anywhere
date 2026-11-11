/*
 * KeyFiles - builds the daemon key file. Pure functions so the format is
 * unit-tested without a device.
 */
package cz.slush.adbanywhere.data

object KeyFiles {
    /**
     * The --tls-clients file: only the app's daemon key may complete the TLS
     * handshake on the loopback listener, so no other local app can even reach
     * ADB auth there. Developer keys authenticate inside that channel (relay)
     * or on the plain VPN listener.
     */
    fun tlsClientsFile(app: AndroidPubKey.Key): String = app.keyLine() + "\n"

    fun keysFile(app: AndroidPubKey.Key, developers: List<DeveloperKey>): String {
        val lines = mutableListOf(app.keyLine())
        val seen = mutableSetOf(app.fingerprint)
        developers.forEach { developer ->
            if (seen.add(developer.fingerprint)) lines += developer.keyLine
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
