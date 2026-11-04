/*
 * ControlService - foreground control plane. It pairs with the system adbd,
 * deploys the patched daemon, verifies the listeners and keeps them alive.
 */
package cz.slush.adbanywhere.service

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import cz.slush.adbanywhere.MainActivity
import cz.slush.adbanywhere.R
import cz.slush.adbanywhere.adb.AdbManager
import cz.slush.adbanywhere.adb.DaemonManager
import cz.slush.adbanywhere.adb.Shell
import cz.slush.adbanywhere.adb.SystemAdb
import cz.slush.adbanywhere.data.AndroidPubKey
import cz.slush.adbanywhere.data.AppKeyStore
import cz.slush.adbanywhere.data.DeveloperKey
import cz.slush.adbanywhere.data.Endpoint
import cz.slush.adbanywhere.data.PairingCode
import cz.slush.adbanywhere.data.Prefs
import cz.slush.adbanywhere.data.WirelessDebuggingGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

class ControlService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val setupMutex = Mutex()
    private lateinit var prefs: Prefs
    private lateinit var keys: AppKeyStore
    private lateinit var system: SystemAdb
    private lateinit var daemon: DaemonManager
    private var notificationJob: Job? = null
    private var watchdogJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var defaultCallback: ConnectivityManager.NetworkCallback? = null
    private var foregroundStarted = false
    private val networkChanges = MutableStateFlow(0)

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        keys = AppKeyStore(this)
        system = SystemAdb(this, keys, prefs)
        daemon = DaemonManager(this, prefs)

        ControlBus.update {
            it.copy(
                loopbackPort = prefs.loopbackPort,
                vpnPort = prefs.vpnPort,
                keyCount = DeveloperKey.list(prefs.developerKeysJson).size + 1,
                endpoint = prefs.lastVpnIp.takeIf { ip -> ip.isNotBlank() }
                    ?.let { ip -> Endpoint.display(ip, prefs.vpnPort) } ?: "",
            )
        }

        startForeground(ControlBus.state.value)
        notificationJob = scope.launch {
            ControlBus.state.collect { state ->
                updateNotification(state)
            }
        }
        scope.launch {
            ControlBus.update { it.copy(appFingerprint = keys.publicKey.fingerprint) }
        }
        scope.launch { refreshAdbStatus() }
        // A provider switch produces onLost and onAvailable separately. Coalesce
        // them and give the new tunnel time to settle before touching the daemon.
        scope.launch {
            networkChanges.dropWhile { it == 0 }.collect {
                AppLog.info("network", "change; settling for ${NETWORK_SETTLE_MS} ms")
                delay(NETWORK_SETTLE_MS)
                networkChanged()
            }
        }
        registerNetwork()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            null -> resumeAfterRestart()
            ACTION_START -> {
                prefs.userWantsRunning = true
                scope.launch { setup() }
            }
            // Keys and settings are persisted either way; a stopped app applies them
            // on the next start instead of deploying a daemon the user turned off.
            ACTION_APPLY_KEYS, ACTION_APPLY_SETTINGS -> {
                if (prefs.userWantsRunning) scope.launch { setup() }
            }
            ACTION_RESTART -> {
                prefs.userWantsRunning = true
                scope.launch { setup(forceRestart = true) }
            }
            // A revocation must terminate sessions that were authenticated with the
            // removed key, so it restarts the daemon like an explicit restart. The UI
            // only sends it while the daemon runs.
            ACTION_REVOKE_KEYS -> {
                scope.launch { setup(forceRestart = true) }
            }
            ACTION_PAIR -> {
                val code = intent.getStringExtra(EXTRA_CODE)
                // Pairing is the first setup step, so it ends in a running daemon:
                // the user asked for it explicitly.
                if (code != null) {
                    prefs.userWantsRunning = true
                    scope.launch { pair(code) }
                }
            }
            ACTION_WATCHDOG -> if (!resumeAfterRestart()) scope.launch { watchdog() }
            ACTION_LOGS -> scope.launch { refreshLogs() }
            ACTION_NOTIFY_PAIR -> postPairingNotification()
            ACTION_SET_TLS_PORT -> {
                val port = intent.getIntExtra(EXTRA_PORT, 0)
                if (port in 1024..65535) {
                    prefs.lastTlsPort = port
                    scope.launch { setup() }
                }
            }
            ACTION_STOP -> {
                prefs.userWantsRunning = false
                stopEverything()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        notificationJob?.cancel()
        watchdogJob?.cancel()
        scope.cancel()
        networkCallback?.let {
            runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) }
        }
        defaultCallback?.let {
            runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) }
        }
        super.onDestroy()
    }

    /**
     * The system restarted the service after a process kill (sticky restart with a
     * null intent, or a watchdog alarm): this process has no deployment yet, so run
     * the full setup instead of idling until the next alarm. Returns true when the
     * setup was started. A stopped app is never resumed: the user's last explicit
     * stop wins over both the sticky flag and the alarm.
     */
    private fun resumeAfterRestart(): Boolean {
        if (!prefs.userWantsRunning) return false
        if (!prefs.setupComplete || ControlBus.state.value.phase != Phase.Idle) return false
        scope.launch { setup() }
        return true
    }

    private suspend fun setup(forceRestart: Boolean = false) {
        setupMutex.withLock {
            AppLog.info("setup", "started; restart=$forceRestart")
            ControlBus.update {
                it.copy(
                    phase = Phase.Deploying,
                    error = "",
                    message = "Preparing…",
                    steps = initialSteps(),
                    loopbackPort = prefs.loopbackPort,
                    vpnPort = prefs.vpnPort,
                )
            }
            // A failed attempt must undo what it changed: it may have written the
            // bootstrap settings or reached a shell on the device.
            var cleanupRequired = false
            var adbEnabledBefore = -1
            try {
                val vpnIp = VpnDetector.currentVpnAddress(this)
                prefs.lastVpnIp = vpnIp ?: ""
                val developers = DeveloperKey.list(prefs.developerKeysJson)
                val appKey = keys.publicKey
                ControlBus.update {
                    it.copy(
                        vpnIp = vpnIp,
                        vpnPort = prefs.vpnPort,
                        loopbackPort = prefs.loopbackPort,
                        appFingerprint = appKey.fingerprint,
                        keyCount = developers.size + 1,
                    )
                }

                step(0, StepStatus.Active, "Connecting to Android's wireless debugging")
                val previous = ControlBus.state.value
                val daemonAlive = withContext(Dispatchers.IO) { probeDaemon() }
                AppLog.info("setup", "custom adb alive=$daemonAlive dir=${prefs.remoteDir}")
                if (!daemonAlive && prefs.setupComplete &&
                    ContextCompat.checkSelfPermission(
                        this,
                        android.Manifest.permission.WRITE_SECURE_SETTINGS,
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    // The one-time grant persists, so the app can re-open the
                    // bootstrap path after a reboot without touching adb.
                    cleanupRequired = true
                    AppLog.info("setup", "bootstrap: opening system wireless debugging")
                    if (prefs.keepSystemAdbOff) {
                        // Record the value this run replaces, so a failure can
                        // restore it and a later harden() keeps the real original.
                        if (prefs.originalAdbEnabled.isEmpty()) {
                            prefs.originalAdbEnabled = SystemSettings.adbEnabled(this).toString()
                        }
                        adbEnabledBefore = SystemSettings.adbEnabled(this)
                        SystemSettings.setAdbEnabled(this, true)
                        AppLog.info("setup", "bootstrap: adb_enabled $adbEnabledBefore -> 1")
                    }
                    if (SystemSettings.isWifiConnected(this)) {
                        SystemSettings.setAdbWifiEnabled(this, true)
                        AppLog.info("setup", "bootstrap: adb_wifi_enabled=1")
                    }
                    // The framework disables wireless debugging on its timeout;
                    // 0 keeps the bootstrap path open after the app backgrounds.
                    if (!SystemSettings.ensureNoAdbConnectionTimeout(this)) {
                        AppLog.warn("setup", "adb_allowed_connection_time=0 was not accepted")
                    }
                    delay(900)
                }
                val systemAlive = if (daemonAlive) false
                else withContext(Dispatchers.IO) { system.ensureConnected() }
                AppLog.info("setup", "system adb alive=$systemAlive")
                cleanupRequired = cleanupRequired || daemonAlive || systemAlive
                if (!daemonAlive && !systemAlive) {
                    if (!prefs.paired) {
                        needsPairing()
                    } else {
                        fail(
                            "Android's wireless debugging is not reachable and the custom adb is down. " +
                                "Connect to Wi-Fi once, or open Settings → Developer options → " +
                                "Wireless debugging and try again.",
                        )
                    }
                    return@withLock
                }
                step(
                    0,
                    StepStatus.Done,
                    if (daemonAlive) "Custom adb is already reachable" else "Connected with the app key",
                )
                if (!daemonAlive && !system.grantSelfPermission()) {
                    fail(
                        "WRITE_SECURE_SETTINGS could not be granted. Grant it once with " +
                            "`adb shell pm grant ${packageName} android.permission.WRITE_SECURE_SETTINGS`.",
                    )
                    return@withLock
                }

                if (!daemonAlive) AppLog.info("setup", "WRITE_SECURE_SETTINGS granted")

                step(1, StepStatus.Active, "Deploying the custom adb")
                val shell = if (daemonAlive) {
                    withContext(Dispatchers.IO) { daemonShell() }
                } else {
                    system.shell
                }
                val vpnChanged = vpnIp != previous.vpnIp
                val portsChanged = prefs.loopbackPort != previous.loopbackPort ||
                    prefs.vpnPort != previous.vpnPort
                val restart = forceRestart || !daemonAlive || vpnChanged || portsChanged
                AppLog.info(
                    "setup",
                    "deploy: adbAlive=$daemonAlive vpnChanged=$vpnChanged " +
                        "portsChanged=$portsChanged restart=$restart",
                )
                if (daemonAlive && !restart) {
                    // No key was revoked: the daemon reloads the key file on the
                    // next auth. Revocation sends ACTION_REVOKE_KEYS instead, so
                    // the restart above kills the sessions the key authenticated.
                    AppLog.info("adb", "rewriting keys; no restart")
                    daemon.writeKeys(shell, appKey, developers)
                } else if (daemonAlive) {
                    AppLog.info("adb", "restarting with the new keys and listeners")
                    daemon.restartDetached(
                        shell,
                        appKey,
                        developers,
                        vpnIp,
                        prefs.loopbackPort,
                        prefs.vpnPort,
                    )
                } else {
                    AppLog.info("adb", "deploying and starting")
                    daemon.deployAndStart(
                        shell,
                        appKey,
                        developers,
                        vpnIp,
                        prefs.loopbackPort,
                        prefs.vpnPort,
                    )
                }
                step(1, StepStatus.Done, "Custom adb running")

                step(2, StepStatus.Active, "Verifying loopback and VPN listeners")
                verifyDaemon(vpnIp)
                AppLog.info("setup", "listeners verified")
                step(
                    2,
                    StepStatus.Done,
                    if (vpnIp == null) "Loopback verified; VPN is not up yet"
                    else "Loopback and $vpnIp:${prefs.vpnPort} verified",
                )

                step(3, StepStatus.Active, "Turning system wireless debugging off")
                harden()
                step(3, StepStatus.Done, "System wireless debugging off")

                prefs.setupComplete = true
                AppLog.info("setup", "running; endpoint=${Endpoint.display(vpnIp, prefs.vpnPort)}")
                ControlBus.update {
                    it.copy(
                        phase = Phase.Running,
                        endpoint = Endpoint.display(vpnIp, prefs.vpnPort),
                        vpnIp = vpnIp,
                        message = if (vpnIp == null) {
                            "VPN is down. Only the app can reach the custom adb; connect the VPN to expose it."
                        } else {
                            "Ready for developer machines."
                        },
                        error = "",
                    )
                }
                scheduleWatchdog()
                startWatchdogLoop()
                refreshLogs()
            } catch (e: Exception) {
                AppLog.error("setup", "failed: ${e.message}", e)
                fail(e.message ?: "Setup failed")
            } finally {
                if (cleanupRequired && ControlBus.state.value.phase != Phase.Running) {
                    abortSetup(adbEnabledBefore)?.let { warning ->
                        val error = ControlBus.state.value.error
                        fail(if (error.isEmpty()) warning else "$error $warning")
                    }
                }
                // D11: read the platform state after the last write, so the UI
                // shows what is really set instead of the phase.
                refreshAdbStatus()
            }
        }
    }

    /**
     * Undoes a failed setup attempt: stops the patched daemon and restores the
     * ADB_ENABLED value the bootstrap replaced. It deliberately leaves the
     * system wireless debugging alone: setup did not reach the point where the
     * patched daemon is verified, and turning it off here would cut the very
     * connection the retry needs.
     */
    private suspend fun abortSetup(adbEnabledBefore: Int): String? =
        withContext(Dispatchers.IO + NonCancellable) {
            val context = this@ControlService
            val stopped = runCatching {
                val shell = runCatching { daemonShell() }.getOrNull() ?: system.shell
                try {
                    daemon.stop(shell)
                } finally {
                    shell.close()
                }
            }.onFailure {
                AppLog.warn("setup", "cleanup cannot stop adb: ${it.message}")
            }.isSuccess
            // The shell may be gone because the daemon already died; the port
            // is the authority on whether anything still runs.
            val daemonListening = runCatching {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", prefs.loopbackPort), 300) }
                true
            }.getOrDefault(false)
            if (adbEnabledBefore >= 0) {
                SystemSettings.setAdbEnabled(context, adbEnabledBefore == 1)
            }
            AppLog.warn(
                "setup",
                "cleanup stopped=$stopped listening=$daemonListening adb_enabled=$adbEnabledBefore",
            )
            if (daemonListening) {
                "Cleanup incomplete: the custom adb still listens on " +
                    "127.0.0.1:${prefs.loopbackPort}. Turn wireless debugging off in " +
                    "Settings, or reboot."
            } else {
                null
            }
        }

    private suspend fun pair(codeText: String) {
        val code = PairingCode.parse(codeText)
        if (code == null) {
            AppLog.error("pair", "pairing value is not code:port")
            fail("Enter the pairing value as code:port, for example 123456:37123.")
            return
        }
        AppLog.info("pair", "started on 127.0.0.1:${code.port}")
        ControlBus.update {
            it.copy(
                phase = Phase.Pairing,
                error = "",
                message = "Pairing with Android's wireless debugging…",
                steps = listOf(SetupStep("Pair with Android's wireless debugging", StepStatus.Active)),
            )
        }
        val paired = system.pair(code)
        if (!paired) {
            AppLog.error("pair", "pairing failed on 127.0.0.1:${code.port}")
            fail(
                "Pairing failed. Check that the code and port are current and that the pairing " +
                    "dialog is still open, then try again.",
            )
            return
        }
        prefs.paired = true
        AppLog.info("pair", "paired; starting setup")
        ControlBus.update {
            it.copy(steps = listOf(SetupStep("Pair with Android's wireless debugging", StepStatus.Done, "Paired")))
        }
        setup()
    }

    private fun needsPairing() {
        AppLog.warn("setup", "pairing required")
        ControlBus.update {
            it.copy(
                phase = Phase.NeedsPairing,
                error = "",
                message = "Pair with Android's wireless debugging to continue.",
                steps = initialSteps().mapIndexed { index, step ->
                    if (index == 0) step.copy(status = StepStatus.Failed, detail = "Not paired") else step
                },
            )
        }
        postPairingNotification()
    }

    private suspend fun probeDaemon(): Boolean = withContext(Dispatchers.IO) {
        val manager = AdbManager(this@ControlService, keys)
        val ok = manager.connectTo("127.0.0.1", prefs.loopbackPort)
        manager.disconnectQuietly()
        ok
    }

    private suspend fun verifyDaemon(vpnIp: String?) = withContext(Dispatchers.IO) {
        val loopback = AdbManager(this@ControlService, keys)
        if (!loopback.connectTo("127.0.0.1", prefs.loopbackPort)) {
            throw IOException(
                "The custom adb did not accept the app key on 127.0.0.1:${prefs.loopbackPort}.",
            )
        }
        try {
            val shell = Shell(loopback)
            val identity = shell.exec("id")
            if (!identity.stdout.contains("uid=2000") && !identity.stdout.contains("uid=0")) {
                throw IOException("The custom adb returned an unexpected identity: ${identity.stdout.trim()}")
            }
        } finally {
            loopback.disconnectQuietly()
        }

        if (vpnIp != null) {
            val vpn = AdbManager(this@ControlService, keys)
            if (!vpn.connectTo(vpnIp, prefs.vpnPort)) {
                throw IOException("The custom adb did not bind $vpnIp:${prefs.vpnPort}.")
            }
            try {
                val identity = Shell(vpn).exec("id")
                if (!identity.stdout.contains("uid=")) {
                    throw IOException("The custom adb on $vpnIp did not answer.")
                }
            } finally {
                vpn.disconnectQuietly()
            }
        }
    }

    /**
     * D5/D6: every call re-asserts adb_wifi_enabled=0 and verifies that the
     * system TLS listener is gone, not only that the setting reads off.
     */
    private suspend fun ensureWifiDebuggingOff() = withContext(Dispatchers.IO) {
        val context = this@ControlService
        WirelessDebuggingGuard(
            readSetting = { SystemSettings.adbWifiEnabled(context) },
            writeDisabled = { SystemSettings.setAdbWifiEnabled(context, false) },
            readTlsPort = { system.tlsPort() },
            isPortOpen = { port ->
                runCatching {
                    Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 300) }
                    true
                }.getOrDefault(false)
            },
            pause = { Thread.sleep(WirelessDebuggingGuard.RETRY_DELAY_MS) },
        ).ensureOff()
    }

    private suspend fun harden() = withContext(Dispatchers.IO) {
        val context = this@ControlService
        ensureWifiDebuggingOff()
        AppLog.info("harden", "system wireless debugging off")
        if (prefs.keepSystemAdbOff) {
            if (prefs.originalAdbEnabled.isEmpty()) {
                prefs.originalAdbEnabled = SystemSettings.adbEnabled(context).toString()
            }
            if (!SystemSettings.setAdbEnabled(context, false)) {
                throw IOException("Could not turn the system adb off. Check WRITE_SECURE_SETTINGS.")
            }
            if (SystemSettings.adbEnabled(context) != 0) {
                throw IOException("The system adb could not be turned off. No random-port redirect is attempted.")
            }
            AppLog.info("harden", "adb_enabled=0")
        } else {
            if (prefs.originalAdbEnabled == "1" && SystemSettings.adbEnabled(context) != 1) {
                SystemSettings.setAdbEnabled(context, true)
                prefs.originalAdbEnabled = ""
                AppLog.info("harden", "restored adb_enabled=1")
            }
        }
    }

    /**
     * D11: the platform ADB state the UI reports. The 5555 probe is blocking
     * I/O, so it never runs on the main thread. The service re-reads it after
     * every write and on every watchdog tick.
     */
    private suspend fun refreshAdbStatus() = withContext(Dispatchers.IO) {
        val status = SystemSettings.status(this@ControlService)
        val previous = ControlBus.state.value.adbStatus
        if (previous != status) {
            AppLog.info(
                "state",
                "adb_enabled=${status.adbEnabled} adb_wifi_enabled=${status.adbWifiEnabled} " +
                    "port5555=${if (status.port5555Open) "open" else "closed"}",
            )
        }
        ControlBus.update { it.copy(adbStatus = status) }
    }

    private suspend fun daemonShell(): Shell = withContext(Dispatchers.IO) {
        val manager = AdbManager(this@ControlService, keys)
        if (!manager.connectTo("127.0.0.1", prefs.loopbackPort)) {
            throw IOException("The custom adb is not reachable on 127.0.0.1:${prefs.loopbackPort}.")
        }
        Shell(manager)
    }

    private fun startWatchdogLoop() {
        watchdogJob?.cancel()
        AppLog.info("watchdog", "loop started")
        watchdogJob = scope.launch {
            while (true) {
                delay(60_000)
                when (ControlBus.state.value.phase) {
                    Phase.Running -> watchdog()
                    Phase.Error -> if (prefs.setupComplete) scope.launch { setup() }
                    else -> Unit
                }
            }
        }
    }

    private suspend fun watchdog() {
        val state = ControlBus.state.value
        if (state.phase != Phase.Running) return
        val alive = withContext(Dispatchers.IO) {
            runCatching {
                val manager = AdbManager(this@ControlService, keys)
                val ok = manager.connectTo("127.0.0.1", prefs.loopbackPort)
                manager.disconnectQuietly()
                ok
            }.getOrDefault(false)
        }
        if (!alive) {
            AppLog.warn("watchdog", "custom adb is down; restarting")
            ControlBus.update { it.copy(message = "The custom adb is down. Restarting…") }
            // setup() replaces the watchdog loop; run it outside this loop job so
            // cancelling the old loop cannot cancel the setup that replaces it.
            scope.launch { setup() }
            return
        }
        if (vpnAddressChanged()) {
            AppLog.info("watchdog", "vpn address changed; restarting setup")
            scope.launch { setup() }
            return
        }
        // D6: every tick keeps D5, not only the first setup.
        try {
            ensureWifiDebuggingOff()
        } catch (e: IOException) {
            AppLog.error("watchdog", "wireless debugging guard failed: ${e.message}")
            fail(e.message ?: "System wireless debugging is still on.")
            scheduleWatchdog()
            return
        }
        if (!SystemSettings.ensureNoAdbConnectionTimeout(this)) {
            AppLog.warn("watchdog", "adb_allowed_connection_time=0 was not accepted")
        }
        if (prefs.keepSystemAdbOff) {
            val open = withContext(Dispatchers.IO) {
                runCatching {
                    val shell = daemonShell()
                    val open5555 = shell.probe("127.0.0.1", 5555)
                    if (open5555) {
                        shell.exec("settings put global adb_enabled 0")
                    }
                    shell.close()
                    open5555
                }.getOrDefault(false)
            }
            if (open) {
                AppLog.warn("watchdog", "system adb listens on 5555; turning it off")
                val still = withContext(Dispatchers.IO) {
                    runCatching {
                        Socket().use { it.connect(InetSocketAddress("127.0.0.1", 5555), 300) }
                        true
                    }.getOrDefault(false)
                }
                if (still) {
                    fail("The system adb could not be stopped. No random-port redirect is attempted.")
                }
            }
        }
        refreshAdbStatus()
        AppLog.info("watchdog", "ok")
        scheduleWatchdog()
    }

    /**
     * A provider switch produces onLost for the old network and onAvailable for
     * the new one, sometimes while a setup is still running. setup() is serialized
     * and compares the address again, so the event is not dropped here. Pairing and
     * a stopped service do not own a daemon; the next setup reads the address.
     */
    private suspend fun networkChanged() {
        val phase = ControlBus.state.value.phase
        when (phase) {
            Phase.Idle, Phase.Stopped, Phase.NeedsPairing, Phase.Pairing -> {
                AppLog.info("network", "ignored in phase=$phase")
                return
            }
            else -> Unit
        }
        val changed = vpnAddressChanged()
        AppLog.info("network", "settled; vpn address changed=$changed")
        if (changed) setup()
    }

    private suspend fun vpnAddressChanged(): Boolean {
        val vpnIp = withContext(Dispatchers.IO) {
            VpnDetector.currentVpnAddress(this@ControlService)
        }
        if (vpnIp == ControlBus.state.value.vpnIp) return false
        AppLog.info(
            "network",
            "vpn address ${ControlBus.state.value.vpnIp ?: "none"} -> ${vpnIp ?: "none"}",
        )
        prefs.lastVpnIp = vpnIp ?: ""
        return true
    }

    private suspend fun refreshLogs() {
        val text = withContext(Dispatchers.IO) {
            runCatching {
                val shell = daemonShell()
                try {
                    shell.exec("tail -n 160 ${shell.quote(daemon.logPath)}").stdout
                } finally {
                    shell.close()
                }
            }.getOrElse {
                AppLog.error("logs", "adb log read failed: ${it.message}")
                prefs.lastError.ifBlank { "No logs yet." }
            }
        }
        AppLog.info("logs", "adb log read; ${text.length} chars")
        ControlBus.logs.value = text
    }

    /**
     * Stops the daemon and the service. It takes the setup mutex so a stop that
     * arrives during a deployment cannot race the deployment and leave an orphaned
     * daemon behind. The loopback port is the authority on whether the stop worked.
     */
    private fun stopEverything() {
        AppLog.info("service", "stopping adb and the service")
        scope.launch {
            setupMutex.withLock {
                withContext(Dispatchers.IO) {
                    runCatching {
                        val shell = daemonShell()
                        try {
                            daemon.stop(shell)
                        } finally {
                            shell.close()
                        }
                    }.onFailure {
                        AppLog.warn("service", "stop cannot reach adb: ${it.message}")
                    }
                }
                // The daemon kills itself through its own shell, so the socket can
                // take a moment to close. Give it the same grace as stop().
                val stillListening = daemonListening()
                cancelWatchdog()
                watchdogJob?.cancel()
                // The system adb state outlives the daemon: keep showing it.
                refreshAdbStatus()
                // The notification collector must not re-post the stopped state
                // after the foreground notification is removed below.
                notificationJob?.cancel()
                AppLog.info("service", "stopped; adb listening=$stillListening")
                ControlBus.update {
                    it.copy(
                        phase = Phase.Stopped,
                        endpoint = "",
                        error = "",
                        message = if (stillListening) {
                            "Stopped, but the custom adb still listens on " +
                                "127.0.0.1:${prefs.loopbackPort}. Turn wireless debugging off " +
                                "in Settings, or reboot."
                        } else {
                            "Stopped"
                        },
                    )
                }
                ServiceCompat.stopForeground(this@ControlService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    /** The loopback port is the authority on whether the daemon still runs. */
    private suspend fun daemonListening(): Boolean = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + 2_000
        var open = true
        while (open && System.currentTimeMillis() < deadline) {
            open = runCatching {
                Socket().use {
                    it.connect(InetSocketAddress("127.0.0.1", prefs.loopbackPort), 300)
                }
                true
            }.getOrDefault(false)
            if (open) Thread.sleep(50)
        }
        open
    }

    private fun fail(message: String) {
        AppLog.error("setup", message)
        prefs.lastError = message
        ControlBus.update {
            it.copy(
                phase = Phase.Error,
                error = message,
                message = "",
                steps = it.steps.map { step ->
                    if (step.status == StepStatus.Active) step.copy(status = StepStatus.Failed) else step
                },
            )
        }
    }

    private fun step(index: Int, status: StepStatus, detail: String = "") {
        ControlBus.update {
            it.copy(
                steps = it.steps.mapIndexed { i, step ->
                    if (i == index) step.copy(status = status, detail = detail) else step
                },
            )
        }
    }

    private fun initialSteps() = listOf(
        SetupStep("Connect to Android's wireless debugging"),
        SetupStep("Deploy the custom adb"),
        SetupStep("Verify loopback and VPN listeners"),
        SetupStep("Turn system wireless debugging off"),
    )

    private fun startForeground(state: ControlState) {
        val notification = buildServiceNotification(state)
        runCatching {
            ServiceCompat.startForeground(
                this,
                Notifications.SERVICE_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
            foregroundStarted = true
        }.onFailure {
            AppLog.error("service", "startForeground failed", it)
        }
    }

    private fun updateNotification(state: ControlState) {
        if (!foregroundStarted) {
            startForeground(state)
            return
        }
        // The foreground notification is exempt from POST_NOTIFICATIONS; updating it is not.
        postNotification(Notifications.SERVICE_ID, buildServiceNotification(state))
    }

    /**
     * POST_NOTIFICATIONS is runtime-grantable from API 33. The check stays next to the
     * call so the guard is visible to lint; runCatching covers a revocation in between.
     */
    private fun postNotification(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        runCatching { NotificationManagerCompat.from(this).notify(id, notification) }
    }

    private fun buildServiceNotification(state: ControlState): Notification {
        val title = when (state.phase) {
            Phase.Running -> "ADB Anywhere is running"
            Phase.NeedsPairing -> "Pair with Android's wireless debugging"
            Phase.Pairing -> "Pairing with Android's wireless debugging"
            Phase.Deploying -> "Setting up ADB Anywhere"
            Phase.Error -> "ADB Anywhere needs attention"
            Phase.Stopped -> "ADB Anywhere is stopped"
            Phase.Idle -> "ADB Anywhere"
        }
        val text = when {
            state.error.isNotEmpty() -> state.error
            state.phase == Phase.Running && state.endpoint.isNotEmpty() -> state.endpoint
            state.message.isNotEmpty() -> state.message
            else -> "Tap to open"
        }
        val builder = NotificationCompat.Builder(this, Notifications.CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentPendingIntent())
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, "Open", contentPendingIntent())
        if (state.phase == Phase.Running) {
            builder.addAction(0, "Restart", servicePendingIntent(ACTION_RESTART))
        }
        if (state.phase == Phase.NeedsPairing || state.phase == Phase.Error) {
            builder.addAction(0, "Pair", contentPendingIntent())
        }
        return builder.build()
    }

    private fun postPairingNotification() {
        val text = "Open Settings → Developer options → Wireless debugging → Pair device " +
            "with pairing code. Then reply here with code:port."
        val input = RemoteInput.Builder(EXTRA_PAIR_INPUT)
            .setLabel("Pairing code and port")
            .build()
        val action = NotificationCompat.Action.Builder(
            android.R.drawable.ic_dialog_info,
            "Enter code:port",
            pairReplyPendingIntent(),
        ).addRemoteInput(input).build()
        val notification = NotificationCompat.Builder(this, Notifications.CHANNEL_PAIRING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Pair ADB Anywhere")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(contentPendingIntent())
            .addAction(action)
            .build()
        postNotification(Notifications.PAIRING_ID, notification)
    }

    private fun contentPendingIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun servicePendingIntent(action: String): PendingIntent = PendingIntent.getService(
        this,
        action.hashCode(),
        Intent(this, ControlService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun pairReplyPendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        this,
        10,
        Intent(this, PairReplyReceiver::class.java).setAction(ACTION_PAIR_REPLY),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    private fun registerNetwork() {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return
        val vpnRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = notifyNetworkChange("vpn available")
            override fun onLost(network: Network) = notifyNetworkChange("vpn lost")
            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) =
                notifyNetworkChange("vpn link properties changed")
        }
        networkCallback = callback
        runCatching { manager.registerNetworkCallback(vpnRequest, callback) }

        val default = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = notifyNetworkChange("default network available")
            override fun onLost(network: Network) = notifyNetworkChange("default network lost")
        }
        defaultCallback = default
        runCatching { manager.registerDefaultNetworkCallback(default) }
    }

    private fun notifyNetworkChange(event: String) {
        AppLog.info("network", event)
        networkChanges.update { it + 1 }
    }

    private fun scheduleWatchdog() {
        val alarm = getSystemService(AlarmManager::class.java) ?: return
        val pending = PendingIntent.getBroadcast(
            this,
            11,
            Intent(this, WatchdogReceiver::class.java).setAction(ACTION_WATCHDOG),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        runCatching {
            alarm.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + 15 * 60 * 1000L,
                pending,
            )
        }.onFailure {
            AppLog.warn("watchdog", "alarm not scheduled: ${it.message}")
        }
    }

    private fun cancelWatchdog() {
        val alarm = getSystemService(AlarmManager::class.java) ?: return
        val pending = PendingIntent.getBroadcast(
            this,
            11,
            Intent(this, WatchdogReceiver::class.java).setAction(ACTION_WATCHDOG),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        runCatching { alarm.cancel(pending) }
    }

    companion object {
        const val ACTION_START = "cz.slush.adbanywhere.START"
        const val ACTION_PAIR = "cz.slush.adbanywhere.PAIR"
        const val ACTION_PAIR_REPLY = "cz.slush.adbanywhere.PAIR_REPLY"
        const val ACTION_RESTART = "cz.slush.adbanywhere.RESTART"
        const val ACTION_REVOKE_KEYS = "cz.slush.adbanywhere.REVOKE_KEYS"
        const val ACTION_WATCHDOG = "cz.slush.adbanywhere.WATCHDOG"
        const val ACTION_LOGS = "cz.slush.adbanywhere.LOGS"
        const val ACTION_APPLY_KEYS = "cz.slush.adbanywhere.APPLY_KEYS"
        const val ACTION_APPLY_SETTINGS = "cz.slush.adbanywhere.APPLY_SETTINGS"
        const val ACTION_NOTIFY_PAIR = "cz.slush.adbanywhere.NOTIFY_PAIR"
        const val ACTION_SET_TLS_PORT = "cz.slush.adbanywhere.SET_TLS_PORT"
        const val ACTION_STOP = "cz.slush.adbanywhere.STOP"
        private const val NETWORK_SETTLE_MS = 1_500L
        const val EXTRA_CODE = "code"
        const val EXTRA_PORT = "port"
        const val EXTRA_PAIR_INPUT = "pair_input"

        fun start(context: Context, action: String, extra: String? = null) {
            val intent = Intent(context, ControlService::class.java).setAction(action)
            if (extra != null) intent.putExtra(EXTRA_CODE, extra)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (_: Exception) {
                runCatching { context.startService(intent) }
            }
        }
    }
}
