/*
 * VpnDetector - finds the IPv4 address a VPN assigned to this phone. The daemon
 * binds its developer listener to this address only.
 */
package cz.slush.adbanywhere.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import cz.slush.adbanywhere.data.Endpoint
import cz.slush.adbanywhere.data.VpnInterface
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

object VpnDetector {
    fun currentVpnAddress(context: Context): String? {
        val fromManager = runCatching { fromConnectivityManager(context) }.getOrNull()
        if (!fromManager.isNullOrBlank()) return fromManager
        return runCatching { fromNetworkInterfaces() }.getOrNull()
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

    private fun fromNetworkInterfaces(): String? {
        val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            .map { iface ->
                VpnInterface(
                    iface.name ?: "",
                    Collections.list(iface.inetAddresses)
                        .filterIsInstance<Inet4Address>()
                        .mapNotNull { it.hostAddress },
                )
            }
        return Endpoint.chooseVpnAddress(interfaces)
    }
}
