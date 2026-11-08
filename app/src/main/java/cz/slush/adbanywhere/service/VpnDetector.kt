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
}
