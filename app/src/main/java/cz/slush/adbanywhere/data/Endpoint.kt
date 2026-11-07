/*
 * Endpoint - address selection and formatting for the daemon listeners.
 */
package cz.slush.adbanywhere.data

data class VpnInterface(val name: String, val addresses: List<String>)

object Endpoint {
    const val LOOPBACK = "127.0.0.1"

    fun listenSpec(loopbackPort: Int, vpnIp: String?, vpnPort: Int, exposeVpn: Boolean = true): String {
        val loopback = "tcp:$LOOPBACK:$loopbackPort"
        return if (!exposeVpn || vpnIp.isNullOrBlank()) loopback else "$loopback,tcp:$vpnIp:$vpnPort"
    }

    fun display(vpnIp: String?, vpnPort: Int): String =
        if (vpnIp.isNullOrBlank()) "not available yet" else "$vpnIp:$vpnPort"

    /**
     * The VPN network is identified by ConnectivityManager; its interface name is
     * not a reliable signal (a VPN may use vpn0 or a branded name), so every
     * usable IPv4 address is a candidate.
     */
    fun chooseVpnAddress(interfaces: List<VpnInterface>): String? =
        interfaces.asSequence()
            .flatMap { it.addresses.asSequence() }
            .firstOrNull { isUsableIpv4(it) }
            ?.substringBefore('/')

    fun isUsableIpv4(address: String): Boolean {
        val parts = address.substringBefore('/').split('.')
        if (parts.size != 4) return false
        val octets = parts.map { it.toIntOrNull() ?: return false }
        if (octets.any { it !in 0..255 }) return false
        val first = octets[0]
        if (first == 0 || first == 127 || first == 255) return false
        if (first == 169 && octets[1] == 254) return false
        if (first in 224..239) return false
        if (octets.all { it == 255 }) return false
        return true
    }
}
