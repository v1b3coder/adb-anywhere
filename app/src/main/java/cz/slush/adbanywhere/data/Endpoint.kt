/*
 * Endpoint - address selection and formatting for the daemon listeners.
 */
package cz.slush.adbanywhere.data

data class VpnInterface(val name: String, val addresses: List<String>)

object Endpoint {
    const val LOOPBACK = "127.0.0.1"

    /**
     * The daemon's listener set. The loopback listener is TLS-only (the app's
     * pinned channel and the relay's tunnel); the VPN listener is plain ADB for
     * developer machines and exists only while the firewall is off.
     */
    data class Listeners(val tls: String, val plain: String?) {
        override fun toString(): String =
            if (plain == null) "tls $tls" else "tls $tls, plain $plain"
    }

    fun listeners(loopbackPort: Int, vpnIp: String?, vpnPort: Int, exposeVpn: Boolean = true): Listeners =
        Listeners(
            tls = "tcp:$LOOPBACK:$loopbackPort",
            plain = if (!exposeVpn || vpnIp.isNullOrBlank()) null else "tcp:$vpnIp:$vpnPort",
        )

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

    /** An IPv4 prefix as (network, length); the relay's routing check uses these. */
    data class Prefix(val address: Int, val length: Int) {
        fun contains(ip: Int): Boolean {
            val mask = if (length == 0) 0 else -1 shl (32 - length)
            return (address and mask) == (ip and mask)
        }
    }

    /**
     * True when the phone routes replies to [peer] through the VPN. Binding the
     * VPN address does not stop a LAN host from sending to it (Linux accepts a
     * packet for any local address on any interface), but a TCP handshake needs
     * the reply to reach the peer. The reply follows the longest-prefix route:
     * when the best VPN route is more specific than every directly connected
     * non-VPN subnet that contains the peer, the reply goes into the tunnel and
     * a spoofed LAN source never completes the handshake. A VPN that reports no
     * routes is treated as a full tunnel (default route).
     */
    fun peerRoutedThroughVpn(peer: Int, vpnRoutes: List<Prefix>, localSubnets: List<Prefix>): Boolean {
        val routes = vpnRoutes.ifEmpty { listOf(Prefix(0, 0)) }
        val vpn = routes.filter { it.contains(peer) }.maxOfOrNull { it.length } ?: return false
        val local = localSubnets.filter { it.contains(peer) }.maxOfOrNull { it.length } ?: return true
        return vpn > local
    }

    fun ipv4(bytes: ByteArray): Int =
        ((bytes[0].toInt() and 0xff) shl 24) or ((bytes[1].toInt() and 0xff) shl 16) or
            ((bytes[2].toInt() and 0xff) shl 8) or (bytes[3].toInt() and 0xff)
}
