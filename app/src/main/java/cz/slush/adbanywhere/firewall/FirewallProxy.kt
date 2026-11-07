/*
 * FirewallProxy - accepts the developer machine on the VPN address and relays
 * each connection to adbd on loopback. It owns the IP allowlist, the app allowlist
 * and the live session grace timer; the relay owns the call policy.
 */
package cz.slush.adbanywhere.firewall

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

class FirewallProxy(
    private val scope: CoroutineScope,
    private val config: () -> FirewallConfig,
    private val monitor: ForegroundAppMonitor?,
    private val policy: RelayPolicy,
    private val audit: AuditLog,
    private val onIpUsed: (String) -> Unit = {},
    private val onAppUsed: (String) -> Unit = {},
    private val pendingPrompts: () -> Int = { 0 },
    private val onState: (FirewallState) -> Unit,
) {
    private val lock = Any()
    private val connections = mutableSetOf<Connection>()
    private val perPeer = mutableMapOf<String, Int>()
    private var server: ServerSocket? = null
    private var acceptJob: Job? = null
    private var monitorJob: Job? = null
    private var graceJob: Job? = null
    private var vpnIp: String? = null
    private var port = 0
    private var loopbackPort = 0

    // Written by start()/stop() and by the accept loop when it fails, read by the
    // service and the watchdog on other threads. The service uses it to decide whether
    // the relay must be started or reused, so the read must see the last write.
    @Volatile
    private var listening = false
    private var localListener = false
    private var lastError = ""

    val activeSessions: Int get() = synchronized(lock) { connections.size }
    val isListening: Boolean get() = listening

    /** True when this instance is already the relay the caller wants. */
    fun isListeningOn(vpnIp: String, port: Int, loopbackPort: Int): Boolean =
        listening && this.vpnIp == vpnIp && this.port == port && this.loopbackPort == loopbackPort

    fun start(vpnIp: String, port: Int, loopbackPort: Int) {
        stop()
        this.vpnIp = vpnIp
        this.port = port
        this.loopbackPort = loopbackPort
        this.localListener = runCatching {
            InetAddress.getByName(vpnIp).isLoopbackAddress
        }.getOrDefault(false)
        val socket = ServerSocket()
        // The relay is restarted on every firewall toggle and settings apply.
        // The previous server socket can be in TIME_WAIT; reuseAddress lets the
        // new bind take the port instead of failing for up to a minute.
        socket.reuseAddress = true
        try {
            socket.bind(InetSocketAddress(vpnIp, port), 8)
        } catch (e: Exception) {
            runCatching { socket.close() }
            throw e
        }
        server = socket
        listening = true
        lastError = ""
        acceptJob = scope.launch(Dispatchers.IO) { acceptLoop(socket) }
        monitorJob = scope.launch {
            monitor?.state?.collect { onForegroundChanged(it) }
        }
        publish()
    }

    fun stop() {
        acceptJob?.cancel()
        monitorJob?.cancel()
        graceJob?.cancel()
        acceptJob = null
        monitorJob = null
        graceJob = null
        runCatching { server?.close() }
        server = null
        listening = false
        closeAll()
        publish()
    }

    fun rebind(vpnIp: String?) {
        if (vpnIp.isNullOrBlank()) {
            stop()
            return
        }
        if (vpnIp == this.vpnIp && listening) return
        start(vpnIp, port, loopbackPort)
    }

    fun onPromptFinished() {
        monitor?.state?.value?.let { onForegroundChanged(it) }
    }

    /** Re-publish the live state after a setup that reused this listener. */
    fun publishState() = publish()

    fun fail(message: String) {
        lastError = message
        audit.record(AuditEvent(event = "proxy_error", detail = message))
        publish()
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (true) {
            val client = try {
                socket.accept()
            } catch (e: Exception) {
                if (listening) {
                    listening = false
                    fail(e.message ?: "accept failed")
                }
                break
            }
            runCatching { client.tcpNoDelay = true }
            val peer = client.inetAddress
            val own = ownVpn()
            if ((!localListener && (peer.isLoopbackAddress || peer.isLinkLocalAddress)) ||
                (!localListener && own != null && peer.address.contentEquals(own.address))
            ) {
                audit.record(
                    AuditEvent(
                        event = "ip_denied",
                        peer = peer.hostAddress ?: "",
                        detail = "local address",
                    ),
                )
                runCatching { client.close() }
                continue
            }
            val config = config()
            if (config.ipAllowlistEnabled) {
                val allowlist = IpAllowlist(config.ipAllowlist)
                if (!allowlist.allows(peer, own)) {
                    audit.record(AuditEvent(event = "ip_denied", peer = peer.hostAddress ?: ""))
                    runCatching { client.close() }
                    continue
                }
                allowlist.matching(peer).forEach { onIpUsed(it.format()) }
            }
            if (config.appAllowlistEnabled) {
                val foreground = monitor?.state?.value?.foreground
                if (foreground == null || foreground !in config.appAllowlist) {
                    audit.record(
                        AuditEvent(
                            event = "app_denied",
                            peer = peer.hostAddress ?: "",
                            detail = "target app is not foreground",
                        ),
                    )
                    runCatching { client.close() }
                    continue
                }
                onAppUsed(foreground)
            }
            val peerKey = peer.hostAddress ?: ""
            if (activeSessions >= MAX_CONNECTIONS ||
                (synchronized(lock) { perPeer[peerKey] ?: 0 }) >= MAX_PER_PEER
            ) {
                audit.record(
                    AuditEvent(
                        event = "proxy_error",
                        peer = peerKey,
                        detail = "connection limit reached",
                    ),
                )
                runCatching { client.close() }
                continue
            }
            // An unauthenticated peer must not hold a slot forever: the socket
            // read times out until the server's A_CNXN proves the transport is
            // authenticated, then the timeout is cleared for the session.
            runCatching { client.soTimeout = PRE_AUTH_TIMEOUT_MS }
            val connection = try {
                Connection(client, peer)
            } catch (e: Exception) {
                runCatching { client.close() }
                audit.record(
                    AuditEvent(
                        event = "proxy_error",
                        peer = peer.hostAddress ?: "",
                        detail = e.message ?: "upstream failed",
                    ),
                )
                null
            } ?: continue
            synchronized(lock) {
                connections.add(connection)
                perPeer[peerKey] = (perPeer[peerKey] ?: 0) + 1
            }
            connection.start()
            publish()
        }
    }

    private fun onForegroundChanged(state: ForegroundState) {
        if (!config().appAllowlistEnabled) {
            graceJob?.cancel()
            graceJob = null
            return
        }
        if (appAllowed()) {
            graceJob?.cancel()
            graceJob = null
            return
        }
        if (policy.promptPending()) {
            graceJob?.cancel()
            graceJob = null
            return
        }
        if (config().appGraceMs <= 0) {
            closeAll()
            return
        }
        if (graceJob?.isActive == true) return
        graceJob = scope.launch {
            delay(config().appGraceMs)
            if (config().appAllowlistEnabled && !appAllowed() && !policy.promptPending()) {
                closeAll()
            }
        }
    }

    private fun appAllowed(): Boolean {
        val state = monitor?.state?.value ?: return false
        // A revoked UsageStats appop must fail closed: without a foreground
        // signal the last known app is not evidence that an allowed app is open.
        if (!state.usageAvailable) return false
        val foreground = state.foreground ?: return false
        return foreground in config().appAllowlist
    }

    private fun ownVpn(): InetAddress? =
        vpnIp?.let { runCatching { InetAddress.getByName(it) }.getOrNull() }

    private fun closeAll() {
        val snapshot = synchronized(lock) { connections.toList() }
        snapshot.forEach { it.close() }
        synchronized(lock) {
            connections.clear()
            perPeer.clear()
        }
        publish()
    }

    private fun publish() {
        onState(
            FirewallState(
                enabled = config().enabled,
                listening = listening,
                listenAddress = if (listening) "${vpnIp ?: ""}:$port" else "",
                activeSessions = activeSessions,
                pendingPrompts = pendingPrompts(),
                lastError = lastError,
            ),
        )
    }

    private inner class Connection(
        private val client: Socket,
        private val peer: InetAddress,
    ) {
        private val upstream = Socket()
        private val relay: AdbRelay
        private val closed = AtomicBoolean(false)

        init {
            runCatching { upstream.tcpNoDelay = true }
            upstream.connect(InetSocketAddress("127.0.0.1", loopbackPort), 3000)
            relay = AdbRelay(
                client.getInputStream(),
                client.getOutputStream(),
                upstream.getInputStream(),
                upstream.getOutputStream(),
                policy,
                audit,
                peer.hostAddress ?: "",
                { !config().appAllowlistEnabled || appAllowed() },
                { runCatching { client.soTimeout = 0 } },
                { close() },
            )
        }

        fun start() {
            audit.record(AuditEvent(event = "connection_open", peer = peer.hostAddress ?: ""))
            relay.start()
        }

        fun close() {
            if (!closed.compareAndSet(false, true)) return
            relay.close()
            runCatching { client.close() }
            runCatching { upstream.close() }
            synchronized(lock) {
                connections.remove(this)
                val count = (perPeer[peer.hostAddress ?: ""] ?: 1) - 1
                if (count <= 0) {
                    perPeer.remove(peer.hostAddress ?: "")
                } else {
                    perPeer[peer.hostAddress ?: ""] = count
                }
            }
            publish()
        }
    }

    companion object {
        private const val MAX_CONNECTIONS = 8
        private const val MAX_PER_PEER = 2
        private const val PRE_AUTH_TIMEOUT_MS = 10_000
    }
}
