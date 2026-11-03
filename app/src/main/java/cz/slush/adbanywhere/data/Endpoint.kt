/*
 * Endpoint - address selection and formatting for the daemon listeners.
 */
package cz.slush.adbanywhere.data

data class VpnInterface(val name: String, val addresses: List<String>)

object Endpoint {
    const val LOOPBACK = "127.0.0.1"

    fun listenSpec(loopbackPort: Int, vpnIp: String?, vpnPort: Int): String {
        val loopback = "tcp:$LOOPBACK:$loopbackPort"
        return if (vpnIp.isNullOrBlank()) loopback else "$loopback,tcp:$vpnIp:$vpnPort"
    }

    fun display(vpnIp: String?, vpnPort: Int): String =
        if (vpnIp.isNullOrBlank()) "not available yet" else "$vpnIp:$vpnPort"

    fun chooseVpnAddress(interfaces: List<VpnInterface>): String? {
        val preferred = interfaces.filter { vpn ->
            vpn.name.startsWith("tun") || vpn.name.startsWith("ppp") ||
                vpn.name.startsWith("wg") || vpn.name.startsWith("utun")
        }
        return preferred.asSequence()
            .flatMap { it.addresses.asSequence() }
            .firstOrNull { isUsableIpv4(it) }
            ?.substringBefore('/')
    }

    fun isUsableIpv4(address: String): Boolean {
        val parts = address.substringBefore('/').split('.')
        if (parts.size != 4) return false
        val octets = parts.map { it.toIntOrNull() ?: return false }
        if (octets.any { it !in 0..255 }) return false
        if (octets[0] == 0 || octets[0] == 127 || octets[0] == 255) return false
        return true
    }
}
