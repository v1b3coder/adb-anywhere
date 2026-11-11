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
    /**
     * Opens the daemon side for one session from the client's first A_CNXN. The
     * service passes the pinned TLS tunnel; tests pass a plain socket.
     */
    private val upstream: (loopbackPort: Int, clientCnxn: ByteArray) -> Socket = ::plainUpstream,
    /** The paired keys, so the relay can attribute a verified signature. */
    private val knownKeys: () -> List<KnownKey> = { emptyList() },
    /**
     * False for a peer the VPN does not route: binding the VPN address does not
     * stop a LAN host from sending to it, so the source must be a VPN source.
     */
    private val peerRouted: (InetAddress) -> Boolean = { true },
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
    // A stopped proxy must not publish a snapshot over the state of the relay that
    // replaced it. The flag is checked under `lock` in publish().
    @Volatile
    private var stopped = true
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
        stopped = false
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
        stopped = true
        runCatching { server?.close() }
        server = null
        listening = false
        closeAll()
        publish(force = true)
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
            if (!localListener && !peerRouted(peer)) {
                audit.record(
                    AuditEvent(
                        event = "ip_denied",
                        peer = peer.hostAddress ?: "",
                        detail = "not routed through the VPN",
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
            // An unauthenticated peer must not hold a slot forever: every read
            // times out, and the relay closes the transport when it has not
            // authenticated by an absolute deadline, so trickled bytes do not help.
            runCatching { client.soTimeout = PRE_AUTH_TIMEOUT_MS }
            val connection = Connection(client, peer)
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
        if (config().appGraceMs <= 0) {
            closeIdle()
            return
        }
        if (graceJob?.isActive == true) return
        graceJob = scope.launch {
            delay(config().appGraceMs)
            if (config().appAllowlistEnabled && !appAllowed()) closeIdle()
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

    /**
     * The grace close. A connection with a visible prompt stays until the prompt
     * is answered, otherwise answering it would close the call; its own
     * prompt's end re-runs this check. Other connections close now, so another
     * peer's prompt cannot keep them alive.
     */
    private fun closeIdle() {
        val snapshot = synchronized(lock) { connections.toList() }
        snapshot.filter { it.pendingPrompts == 0 }.forEach { it.close() }
        publish()
    }

    private fun closeAll() {
        val snapshot = synchronized(lock) { connections.toList() }
        snapshot.forEach { it.close() }
        synchronized(lock) {
            connections.clear()
            perPeer.clear()
        }
        publish()
    }

    private fun publish(force: Boolean = false) {
        // The snapshot and the write are one critical section: two connections
        // closing at once must not publish their counts out of order, and a stopped
        // proxy must not overwrite the state of the relay that replaced it.
        synchronized(lock) {
            if (stopped && !force) return
            onState(
                FirewallState(
                    enabled = config().enabled,
                    listening = listening,
                    listenAddress = if (listening) "${vpnIp ?: ""}:$port" else "",
                    activeSessions = connections.size,
                    lastError = lastError,
                ),
            )
        }
    }

    private inner class Connection(
        private val client: Socket,
        private val peer: InetAddress,
    ) {
        private val closed = AtomicBoolean(false)
        private val started = System.currentTimeMillis()
        @Volatile
        private var upstreamSocket: Socket? = null
        @Volatile
        private var relay: AdbRelay? = null

        val pendingPrompts: Int get() = relay?.pendingPrompts ?: 0

        fun start() {
            audit.record(AuditEvent(event = "connection_open", peer = peer.hostAddress ?: ""))
            scope.launch(kotlinx.coroutines.Dispatchers.IO) { open() }
        }

        /**
         * The client speaks first. Its A_CNXN opens the daemon side, so the
         * daemon sees the developer's own banner and the TLS upgrade happens on
         * the relay's hop only.
         */
        private fun open() {
            try {
                val first = AdbFrameCodec.read(client.getInputStream(), AdbFrameCodec.PRE_AUTH_MAX_PAYLOAD)
                if (first == null || first.command != AdbFrameCodec.A_CNXN) {
                    audit.record(
                        AuditEvent(event = "proxy_error", peer = peer.hostAddress ?: "", detail = "the client did not start with A_CNXN"),
                    )
                    close()
                    return
                }
                val server = upstream(loopbackPort, first.raw)
                upstreamSocket = server
                if (closed.get()) {
                    runCatching { server.close() }
                    return
                }
                val remaining = (PRE_AUTH_TIMEOUT_MS - (System.currentTimeMillis() - started)).coerceAtLeast(1)
                val relay = AdbRelay(
                    client.getInputStream(),
                    client.getOutputStream(),
                    server.getInputStream(),
                    server.getOutputStream(),
                    policy,
                    audit,
                    peer.hostAddress ?: "",
                    connectionAllowed = { !config().appAllowlistEnabled || appAllowed() },
                    onAuthenticated = { runCatching { client.soTimeout = 0 } },
                    knownKeys = knownKeys,
                    authTimeoutMs = remaining,
                    onPromptFinished = { onPromptFinished() },
                    onClose = { close() },
                )
                this.relay = relay
                relay.start()
            } catch (e: Exception) {
                audit.record(
                    AuditEvent(event = "proxy_error", peer = peer.hostAddress ?: "", detail = e.message ?: "upstream failed"),
                )
                close()
            }
        }

        fun close() {
            if (!closed.compareAndSet(false, true)) return
            relay?.close()
            runCatching { client.close() }
            runCatching { upstreamSocket?.close() }
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

        /** A plain upstream for tests: connect and forward the client's A_CNXN. */
        fun plainUpstream(loopbackPort: Int, clientCnxn: ByteArray): Socket {
            val socket = Socket()
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress("127.0.0.1", loopbackPort), 3000)
            socket.getOutputStream().apply {
                write(clientCnxn)
                flush()
            }
            return socket
        }
    }
}
