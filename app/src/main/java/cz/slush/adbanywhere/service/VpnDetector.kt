/*
 * VpnDetector - finds the IPv4 address a VPN assigned to this phone. The daemon
 * binds its developer listener to this address only.
 */
package cz.slush.adbanywhere.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import cz.slush.adbanywhere.BuildConfig
import cz.slush.adbanywhere.data.Endpoint
import cz.slush.adbanywhere.data.Prefs
import cz.slush.adbanywhere.data.VpnInterface
import java.net.Inet4Address
import java.net.InetAddress

object VpnDetector {
    /**
     * The VPN network's address. It comes from the VPN transport the platform
     * reports, never from a generic interface scan: that scan returns the Wi-Fi
     * or cellular address when no VPN is up, and the daemon would bind a plain
     * ADB listener on the LAN (docs/DESIGN.md §8). A VPN that
     * ConnectivityManager does not report is a missing platform contract, not a
     * reason to guess an address.
     */
    fun currentVpnAddress(context: Context): String? {
        // The override is a debug harness for the emulator, never a release
        // input: a release build must not honour a stored override.
        val override = if (BuildConfig.DEBUG) {
            runCatching { Prefs(context).debugVpnIp }.getOrNull()
        } else {
            null
        }
        if (!override.isNullOrBlank()) return override
        return runCatching { fromConnectivityManager(context) }.getOrNull()
    }

    private fun fromConnectivityManager(context: Context): String? {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val interfaces = mutableListOf<VpnInterface>()
        for (network in manager.allNetworks) {
            val capabilities = manager.getNetworkCapabilities(network) ?: continue
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            val properties = manager.getLinkProperties(network) ?: continue
            val name = properties.interfaceName ?: "vpn"
            val addresses = properties.linkAddresses.mapNotNull { it.address.hostAddress }
            interfaces += VpnInterface(name, addresses)
        }
        return Endpoint.chooseVpnAddress(interfaces)
    }

    /**
     * The relay's source check (docs/FIREWALL.md §5): the peer must be a host the
     * phone reaches through the VPN, not a LAN host that sent to the VPN address.
     */
    fun routesPeer(context: Context, peer: InetAddress): Boolean {
        if (BuildConfig.DEBUG && runCatching { Prefs(context).debugVpnIp }.getOrNull().orEmpty().isNotBlank()) {
            return true
        }
        if (peer !is Inet4Address) return false
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val vpnRoutes = mutableListOf<Endpoint.Prefix>()
        val localSubnets = mutableListOf<Endpoint.Prefix>()
        var vpnSeen = false
        for (network in manager.allNetworks) {
            val capabilities = manager.getNetworkCapabilities(network) ?: continue
            val properties = manager.getLinkProperties(network) ?: continue
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                vpnSeen = true
                properties.routes.forEach { route ->
                    val destination = route.destination
                    val address = destination.address
                    if (address is Inet4Address) {
                        vpnRoutes += Endpoint.Prefix(Endpoint.ipv4(address.address), destination.prefixLength)
                    }
                }
            } else {
                properties.linkAddresses.forEach { link ->
                    val address = link.address
                    if (address is Inet4Address && link.prefixLength in 1..32) {
                        localSubnets += Endpoint.Prefix(Endpoint.ipv4(address.address), link.prefixLength)
                    }
                }
            }
        }
        if (!vpnSeen) return false
        return Endpoint.peerRoutedThroughVpn(Endpoint.ipv4(peer.address), vpnRoutes, localSubnets)
    }
}
