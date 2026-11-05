/*
 * MdnsDiscovery - resolves the random wireless-debugging ports from the local
 * mDNS advertisement. This is the fallback on devices that do not publish
 * service.adb.tls.port.
 */
package cz.slush.adbanywhere.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import cz.slush.adbanywhere.service.AppLog
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

class MdnsDiscovery(private val context: Context) {
    /**
     * NsdManager requires the DNS-SD service type to end with a dot. Without it
     * onStartDiscoveryFailed fires and the old code silently returned null.
     */
    suspend fun discoverAll(serviceType: String, timeoutMs: Long = 4000): List<Int> =
        suspendCancellableCoroutine { continuation ->
            val nsd = context.getSystemService(NsdManager::class.java)
            if (nsd == null) {
                AppLog.warn("system", "mDNS unavailable: no NsdManager")
                continuation.resume(emptyList())
                return@suspendCancellableCoroutine
            }
            val type = if (serviceType.endsWith(".")) serviceType else "$serviceType."
            val finished = AtomicBoolean(false)
            val ports = LinkedHashSet<Int>()
            var listener: NsdManager.DiscoveryListener? = null

            fun finish() {
                if (finished.compareAndSet(false, true)) {
                    listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
                    continuation.resume(ports.toList())
                }
            }

            listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) {
                    AppLog.info("system", "mDNS discovery started for $serviceType")
                }

                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    AppLog.warn("system", "mDNS discovery failed for $serviceType: $errorCode")
                    finish()
                }

                override fun onDiscoveryStopped(serviceType: String) = Unit
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
                override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    if (finished.get()) return
                    AppLog.info(
                        "system",
                        "mDNS found ${serviceInfo.serviceName} ${serviceInfo.serviceType}",
                    )
                    runCatching {
                        nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                                AppLog.warn("system", "mDNS resolve failed: $errorCode")
                            }

                            override fun onServiceResolved(resolved: NsdServiceInfo) {
                                AppLog.info(
                                    "system",
                                    "mDNS resolved ${resolved.serviceName} on " +
                                        "${resolved.host}:${resolved.port}",
                                )
                                ports += resolved.port
                            }
                        })
                    }.onFailure {
                        AppLog.warn("system", "mDNS resolve threw: ${it.message}")
                    }
                }
            }

            val activeListener = listener
            runCatching {
                nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, activeListener)
            }.onFailure {
                AppLog.warn("system", "mDNS discover threw: ${it.message}")
                finish()
            }

            Handler(Looper.getMainLooper()).postDelayed({ finish() }, timeoutMs)
            continuation.invokeOnCancellation {
                activeListener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
            }
        }

    suspend fun discover(serviceType: String, timeoutMs: Long = 4000): Int? =
        discoverAll(serviceType, timeoutMs).firstOrNull()
}
