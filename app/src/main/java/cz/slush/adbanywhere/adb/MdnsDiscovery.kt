/*
 * MdnsDiscovery - resolves the random wireless-debugging port from the local
 * mDNS advertisement. This is the primary discovery path on devices that do not
 * publish service.adb.tls.port.
 */
package cz.slush.adbanywhere.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

class MdnsDiscovery(private val context: Context) {
    suspend fun discover(serviceType: String, timeoutMs: Long = 2500): Int? =
        suspendCancellableCoroutine { continuation ->
            val nsd = context.getSystemService(NsdManager::class.java)
            if (nsd == null) {
                continuation.resume(null)
                return@suspendCancellableCoroutine
            }
            val finished = AtomicBoolean(false)
            var listener: NsdManager.DiscoveryListener? = null

            fun finish(port: Int?) {
                if (finished.compareAndSet(false, true)) {
                    listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
                    continuation.resume(port)
                }
            }

            listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) = Unit
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = finish(null)
                override fun onDiscoveryStopped(serviceType: String) = Unit
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
                override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    if (finished.get()) return
                    runCatching {
                        nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
                            override fun onServiceResolved(resolved: NsdServiceInfo) {
                                finish(resolved.port)
                            }
                        })
                    }
                }
            }

            val activeListener = listener
            runCatching {
                nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, activeListener)
            }.onFailure { finish(null) }

            Handler(Looper.getMainLooper()).postDelayed({ finish(null) }, timeoutMs)
            continuation.invokeOnCancellation {
                activeListener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
            }
        }
}
