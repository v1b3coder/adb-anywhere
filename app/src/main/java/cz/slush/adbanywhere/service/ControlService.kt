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
import cz.slush.adbanywhere.data.AdbLifecycle
import cz.slush.adbanywhere.data.AdbState
import cz.slush.adbanywhere.data.AndroidPubKey
import cz.slush.adbanywhere.data.AppKeyStore
import cz.slush.adbanywhere.data.DeveloperKey
import cz.slush.adbanywhere.data.Endpoint
import cz.slush.adbanywhere.data.PairingCode
import cz.slush.adbanywhere.data.Prefs
import cz.slush.adbanywhere.data.WirelessDebuggingGuard
import cz.slush.adbanywhere.firewall.AuditEvent
import cz.slush.adbanywhere.firewall.AuditLog
import cz.slush.adbanywhere.firewall.CallRule
import cz.slush.adbanywhere.firewall.FirewallConfigStore
import cz.slush.adbanywhere.firewall.FirewallPolicy
import cz.slush.adbanywhere.firewall.FirewallProxy
import cz.slush.adbanywhere.firewall.FirewallState
import cz.slush.adbanywhere.firewall.ForegroundAppMonitor
import cz.slush.adbanywhere.firewall.PromptDecision
import cz.slush.adbanywhere.firewall.PromptRequest
import cz.slush.adbanywhere.firewall.PromptResponse
import cz.slush.adbanywhere.ui.CallPromptActivity
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import org.json.JSONObject

class ControlService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val setupMutex = Mutex()
    private lateinit var prefs: Prefs
    private lateinit var keys: AppKeyStore
    private lateinit var system: SystemAdb
    private lateinit var daemon: DaemonManager
    private lateinit var firewallStore: FirewallConfigStore
    private lateinit var auditLog: AuditLog
    private var firewallProxy: FirewallProxy? = null
    private var foregroundMonitor: ForegroundAppMonitor? = null
    private val prompts = mutableMapOf<String, CompletableDeferred<PromptResponse?>>()
    private var notificationJob: Job? = null
    private var watchdogJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var defaultCallback: ConnectivityManager.NetworkCallback? = null
    private var foregroundStarted = false
    private val networkChanges = MutableStateFlow(0)

    override fun onCreate() {
        super.onCreate()
        AppLog.info("service", "created")
        prefs = Prefs(this)
        keys = AppKeyStore(this)
        system = SystemAdb(this, keys, prefs)
        daemon = DaemonManager(this, prefs)
        firewallStore = FirewallConfigStore(prefs)
        auditLog = AuditLog(
            File(createDeviceProtectedStorageContext().filesDir, "firewall-audit.jsonl"),
        ) { firewallStore.load().redactAudit }
        ControlBus.audit = auditLog.entries
        ControlBus.firewallConfig.value = firewallStore.load()
        foregroundMonitor = ForegroundAppMonitor(this) {
            (firewallProxy?.activeSessions ?: 0) > 0 || prompts.isNotEmpty()
        }

        ControlBus.update {
            it.copy(
                loopbackPort = prefs.loopbackPort,
                vpnPort = prefs.vpnPort,
                keyCount = DeveloperKey.list(prefs.developerKeysJson).size + 1,
                endpoint = prefs.lastVpnIp.takeIf { ip -> ip.isNotBlank() }
                    ?.let { ip -> Endpoint.display(ip, prefs.vpnPort) } ?: "",
                firewall = FirewallState(enabled = firewallStore.load().enabled),
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
        AppLog.info("service", "intent ${intent?.action ?: "resume-after-restart"}")
        when (intent?.action) {
            null -> resumeAfterRestart()
            ACTION_START -> {
                prefs.userWantsRunning = true
                AppLog.info("service", "user asked to run")
                scope.launch { setup(enableSystemAdb = true) }
            }
            // Keys and settings are persisted either way; a stopped app applies them
            // on the next start instead of deploying a daemon the user turned off.
            ACTION_APPLY_KEYS, ACTION_APPLY_SETTINGS -> {
                if (prefs.userWantsRunning) {
                    AppLog.info("service", "applying the stored change")
                    scope.launch { setup() }
                } else {
                    AppLog.info("service", "change stored; adb is stopped")
                }
            }
            ACTION_RESTART -> {
                prefs.userWantsRunning = true
                AppLog.info("service", "restart requested")
                scope.launch { setup(forceRestart = true, enableSystemAdb = true) }
            }
            // A revocation must terminate sessions that were authenticated with the
            // removed key, so it restarts the daemon like an explicit restart. The UI
            // only sends it while the daemon runs.
            ACTION_REVOKE_KEYS -> {
                AppLog.info("service", "key revocation; restarting to close sessions")
                scope.launch { setup(forceRestart = true, enableSystemAdb = true) }
            }
            // The pairing notification asks for the code first and the port
            // second, so each reply arrives as its own intent. A reply with only
            // the code starts the port step instead of pairing.
            ACTION_PAIR -> {
                val codeText = intent.getStringExtra(EXTRA_CODE)
                val portText = intent.getStringExtra(EXTRA_PAIR_PORT)
                val code = codeText?.let { PairingCode.parseCode(it) }
                val port = portText?.let { PairingCode.parsePort(it) }
                when {
                    code == null -> {
                        AppLog.warn("pair", "pairing code is not six digits")
                        postPairingNotification(
                            "The code is the six-digit number in Android's dialog.",
                        )
                    }
                    port == null -> {
                        if (portText != null) AppLog.warn("pair", "pairing port is not a port")
                        postPairingPortNotification(
                            code,
                            if (portText != null) "The port is the number under the IP address." else "",
                        )
                    }
                    else -> {
                        // Pairing is the first setup step, so it ends in a running
                        // daemon: the user asked for it explicitly.
                        prefs.userWantsRunning = true
                        scope.launch { pair(PairingCode(code, port)) }
                    }
                }
            }
            ACTION_WATCHDOG -> if (!resumeAfterRestart()) scope.launch { watchdog() }
            ACTION_LOGS -> scope.launch { refreshLogs() }
            ACTION_NOTIFY_PAIR -> {
                AppLog.info("service", "pairing notification requested")
                postPairingNotification()
            }
            ACTION_SET_TLS_PORT -> {
                val port = intent.getIntExtra(EXTRA_PORT, 0)
                if (port in 1024..65535) {
                    prefs.lastTlsPort = port
                    AppLog.info("settings", "system adb TLS port=$port")
                    scope.launch { setup() }
                } else {
                    AppLog.warn("settings", "rejected system adb TLS port=$port")
                }
            }
            ACTION_CLEAR_AUDIT -> {
                auditLog.clear()
                AppLog.info("firewall", "audit log cleared")
            }
            ACTION_PROMPT_RESPONSE -> {
                val id = intent.getStringExtra(EXTRA_PROMPT_ID)
                val decision = intent.getStringExtra(EXTRA_PROMPT_DECISION)
                val rule = intent.getStringExtra(EXTRA_PROMPT_RULE)?.let {
                    runCatching { CallRule.fromJson(JSONObject(it)) }.getOrNull()
                }
                val response = when (decision) {
                    "block" -> PromptResponse(PromptDecision.BLOCK)
                    "once" -> PromptResponse(PromptDecision.ALLOW_ONCE)
                    "always" -> PromptResponse(PromptDecision.ALWAYS_ALLOW)
                    "custom" -> rule?.let { PromptResponse(PromptDecision.CUSTOM, it) }
                    else -> null
                }
                if (id != null) prompts[id]?.complete(response)
            }
            ACTION_STOP -> {
                prefs.userWantsRunning = false
                AppLog.info("service", "stop requested")
                stopEverything()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        AppLog.info("service", "destroyed")
        stopFirewall()
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
        if (!prefs.userWantsRunning) {
            AppLog.info("service", "not resumed; the user stopped adb")
            return false
        }
        if (!prefs.setupComplete || ControlBus.state.value.phase != Phase.Idle) {
            AppLog.info("service", "not resumed; phase=${ControlBus.state.value.phase}")
            return false
        }
        AppLog.info("service", "resuming after a process restart")
        scope.launch { setup() }
        return true
    }

    private suspend fun setup(
        forceRestart: Boolean = false,
        enableSystemAdb: Boolean = false,
    ) {
        setupMutex.withLock {
            val previous = ControlBus.state.value
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
            var adbStateBefore: AdbState? = null
            val firewallConfig = firewallStore.load()
            try {
                val vpnIp = VpnDetector.currentVpnAddress(this)
                val exposeVpn = !firewallConfig.enabled
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
                        firewall = FirewallState(enabled = firewallConfig.enabled),
                    )
                }

                step(0, StepStatus.Active, "Connecting to Android's wireless debugging")
                val daemonAlive = withContext(Dispatchers.IO) { probeDaemon() }
                AppLog.info("setup", "custom adb alive=$daemonAlive dir=${prefs.remoteDir}")
                if (!daemonAlive && prefs.setupComplete &&
                    ContextCompat.checkSelfPermission(
                        this,
                        android.Manifest.permission.WRITE_SECURE_SETTINGS,
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    // The one-time grant persists, so the app can re-open the
                    // bootstrap path after a reboot without a pre-existing app shell.
                    cleanupRequired = true
                    AppLog.info("setup", "bootstrap: opening system wireless debugging")
                    val current = SystemSettings.adbState(this)
                    val wifiDebuggingEnabled = if (enableSystemAdb) {
                        adbStateBefore = current
                        val desired = AdbLifecycle.start(
                            current = current,
                            patchedAdbdAlive = daemonAlive,
                            wifiConnected = SystemSettings.isWifiConnected(this),
                        )
                        SystemSettings.applyAdbState(this, desired)
                        AppLog.info(
                            "setup",
                            "start: adb_enabled ${current.adbEnabled} -> ${desired.adbEnabled}, " +
                                "adb_wifi_enabled ${current.adbWifiEnabled} -> ${desired.adbWifiEnabled}",
                        )
                        desired.adbWifiEnabled != 0
                    } else if (SystemSettings.isWifiConnected(this)) {
                        SystemSettings.setAdbWifiEnabled(this, true)
                        AppLog.info("setup", "bootstrap: adb_wifi_enabled=1")
                        true
                    } else {
                        false
                    }
                    // The framework disables wireless debugging on its timeout;
                    // 0 keeps the bootstrap path open after the app backgrounds.
                    if (!SystemSettings.ensureNoAdbConnectionTimeout(this)) {
                        AppLog.warn("setup", "adb_allowed_connection_time=0 was not accepted")
                    }
                    if (wifiDebuggingEnabled) {
                        awaitSystemWirelessDebugging()
                        ControlBus.update { it.copy(message = "Preparing…") }
                    }
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
                val firewallChanged = firewallConfig.enabled != previous.firewall.enabled
                val restart = forceRestart || !daemonAlive || portsChanged ||
                    (exposeVpn && vpnChanged) || firewallChanged
                AppLog.info(
                    "setup",
                    "deploy: adbAlive=$daemonAlive vpnChanged=$vpnChanged " +
                        "portsChanged=$portsChanged firewallChanged=$firewallChanged " +
                        "restart=$restart",
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
                        exposeVpn,
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
                        exposeVpn,
                    )
                }
                step(1, StepStatus.Done, "Custom adb running")

                step(2, StepStatus.Active, "Verifying the listeners")
                verifyDaemon(vpnIp, exposeVpn)
                AppLog.info("setup", "listeners verified")
                step(
                    2,
                    StepStatus.Done,
                    when {
                        !exposeVpn -> "Loopback verified; the firewall owns the VPN listener"
                        vpnIp == null -> "Loopback verified; VPN is not up yet"
                        else -> "Loopback and $vpnIp:${prefs.vpnPort} verified"
                    },
                )

                step(3, StepStatus.Active, "Turning system wireless debugging off")
                harden()
                step(3, StepStatus.Done, "System wireless debugging off")

                step(4, StepStatus.Active, "Starting the firewall relay")
                if (firewallConfig.enabled && vpnIp != null) {
                    if (firewallChanged || vpnChanged || portsChanged ||
                        firewallProxy?.isListening != true
                    ) {
                        startFirewall(vpnIp)
                    }
                    verifyFirewall()
                    step(4, StepStatus.Done, "Firewall relay on $vpnIp:${prefs.vpnPort}")
                } else if (firewallConfig.enabled) {
                    stopFirewall()
                    ControlBus.update {
                        it.copy(
                            firewall = FirewallState(
                                enabled = true,
                                listening = false,
                                lastError = "VPN is down",
                            ),
                        )
                    }
                    step(4, StepStatus.Done, "VPN is down; no external listener")
                } else {
                    stopFirewall()
                    step(4, StepStatus.Done, "Firewall is off")
                }

                prefs.setupComplete = true
                AppLog.info("setup", "running; endpoint=${Endpoint.display(vpnIp, prefs.vpnPort)}")
                ControlBus.update {
                    it.copy(
                        phase = Phase.Running,
                        endpoint = Endpoint.display(vpnIp, prefs.vpnPort),
                        vpnIp = vpnIp,
                        message = when {
                            firewallConfig.enabled && vpnIp == null ->
                                "VPN is down. The firewall cannot expose the custom adb."
                            firewallConfig.enabled ->
                                "Firewall is on. Developer machines connect through $vpnIp:${prefs.vpnPort}."
                            vpnIp == null ->
                                "VPN is down. Only the app can reach the custom adb; connect the VPN to expose it."
                            else -> "Ready for developer machines."
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
                    abortSetup(adbStateBefore)?.let { warning ->
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
     * Undoes a failed setup attempt: stops the patched daemon. It deliberately
     * leaves the system wireless debugging alone: setup did not reach the point
     * where the patched daemon is verified, and turning it off here would cut the
     * very connection the retry needs.
     */
    private suspend fun abortSetup(adbStateBefore: AdbState?): String? =
        withContext(Dispatchers.IO + NonCancellable) {
            val context = this@ControlService
            runCatching { stopFirewall() }
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
            adbStateBefore?.let { previous ->
                runCatching { SystemSettings.applyAdbState(context, previous) }
                    .onFailure { AppLog.warn("setup", "cleanup cannot restore adb: ${it.message}") }
            }
            AppLog.warn(
                "setup",
                "cleanup stopped=$stopped listening=$daemonListening",
            )
            if (daemonListening) {
                "Cleanup incomplete: the custom adb still listens on " +
                    "127.0.0.1:${prefs.loopbackPort}. Turn wireless debugging off in " +
                    "Settings, or reboot."
            } else {
                null
            }
        }

    /**
     * Android starts the TLS listener only after the user confirms its "allow
     * wireless debugging on this network?" prompt, which it shows on every new
     * network and then writes adb_wifi_enabled=0 again. Wait for the published
     * port instead of racing the prompt and failing the whole setup.
     */
    private suspend fun awaitSystemWirelessDebugging(timeoutMs: Long = SYSTEM_ADB_WAIT_MS) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var asked = false
        while (System.currentTimeMillis() < deadline) {
            val port = system.tlsPort()
            if (port != null && SystemSettings.isLocalPortOpen(port)) {
                AppLog.info("setup", "system wireless debugging is up on 127.0.0.1:$port")
                return
            }
            if (!asked) {
                asked = true
                ControlBus.update {
                    it.copy(message = "Confirm Android's wireless debugging prompt…")
                }
            }
            delay(250)
        }
        AppLog.warn("setup", "system wireless debugging is not up after $timeoutMs ms")
    }

    private suspend fun pair(code: PairingCode) {
        AppLog.info("pair", "started on 127.0.0.1:${code.port}")
        NotificationManagerCompat.from(this).cancel(Notifications.PAIRING_ID)
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

    private suspend fun verifyDaemon(vpnIp: String?, exposeVpn: Boolean) = withContext(Dispatchers.IO) {
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

        if (exposeVpn && vpnIp != null) {
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

    private fun startFirewall(vpnIp: String) {
        val config = firewallStore.load()
        if (!config.enabled) return
        firewallProxy?.stop()
        firewallProxy = null
        foregroundMonitor?.start()
        val policy = FirewallPolicy(
            config = { firewallStore.load() },
            prompt = ::promptForCall,
            pending = { prompts.isNotEmpty() },
            saveRule = {
                firewallStore.addRule(it)
                ControlBus.firewallConfig.value = firewallStore.load()
            },
            audit = { auditLog.record(it) },
        )
        val proxy = FirewallProxy(
            scope = scope,
            config = { firewallStore.load() },
            monitor = foregroundMonitor,
            policy = policy,
            audit = auditLog,
            onState = { state -> ControlBus.update { it.copy(firewall = state) } },
        )
        proxy.start(vpnIp, prefs.vpnPort, prefs.loopbackPort)
        firewallProxy = proxy
        AppLog.info("firewall", "listening on $vpnIp:${prefs.vpnPort}")
    }

    private fun stopFirewall() {
        firewallProxy?.stop()
        firewallProxy = null
        foregroundMonitor?.stop()
        prompts.values.forEach { it.cancel() }
        prompts.clear()
        ControlBus.pendingPrompt.value = null
        ControlBus.update {
            it.copy(firewall = FirewallState(enabled = firewallStore.load().enabled))
        }
    }

    private fun verifyFirewall() {
        if (firewallProxy?.isListening != true) {
            throw IOException("The firewall relay did not bind ${prefs.vpnPort}.")
        }
    }

    private suspend fun promptForCall(
        request: PromptRequest,
        timeoutMs: Long,
    ): PromptResponse? {
        if (prompts.size >= MAX_PROMPTS) return null
        val foreground = foregroundMonitor?.state?.value
        if (foreground != null && (!foreground.interactive || foreground.locked)) {
            auditLog.record(
                AuditEvent(
                    event = "call_denied",
                    peer = request.peer,
                    service = request.call.service,
                    fields = request.call.fields,
                    rule = "screen-off",
                ),
            )
            return null
        }
        val deferred = CompletableDeferred<PromptResponse?>()
        prompts[request.id] = deferred
        ControlBus.pendingPrompt.value = request
        ControlBus.update { it.copy(firewall = it.firewall.copy(pendingPrompts = prompts.size)) }
        postPromptNotification(request)
        runCatching {
            startActivity(
                Intent(this, CallPromptActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(CallPromptActivity.EXTRA_PROMPT_ID, request.id),
            )
        }
        val response = withTimeoutOrNull(timeoutMs) { deferred.await() }
        prompts.remove(request.id)
        ControlBus.pendingPrompt.value = null
        ControlBus.update { it.copy(firewall = it.firewall.copy(pendingPrompts = prompts.size)) }
        firewallProxy?.onPromptFinished()
        if (response == null) {
            auditLog.record(
                AuditEvent(
                    event = "call_timeout",
                    peer = request.peer,
                    service = request.call.service,
                    fields = request.call.fields,
                ),
            )
        }
        return response
    }

    private fun postPromptNotification(request: PromptRequest) {
        val intent = Intent(this, CallPromptActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(CallPromptActivity.EXTRA_PROMPT_ID, request.id)
        val pending = PendingIntent.getActivity(
            this,
            request.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = "${request.call.service} from ${request.peer}\n${request.call.raw.take(300)}"
        val notification = NotificationCompat.Builder(this, Notifications.CHANNEL_FIREWALL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("ADB call needs approval")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .addAction(0, "Review", pending)
            .build()
        postNotification(Notifications.PROMPT_ID, notification)
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

    /**
     * D5: turns the system wireless debugging off after the patched adbd is
     * running. It never stops the system adbd: on devices where init kills the
     * adbd cgroup, stopping it would also kill the patched adbd.
     */
    private suspend fun harden() = withContext(Dispatchers.IO) {
        ensureWifiDebuggingOff()
        AppLog.info("harden", "system wireless debugging off")
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
        val firewall = firewallStore.load()
        if (firewall.enabled) {
            val vpnIp = withContext(Dispatchers.IO) {
                VpnDetector.currentVpnAddress(this@ControlService)
            }
            if (vpnIp == null) {
                if (firewallProxy != null) stopFirewall()
                ControlBus.update {
                    it.copy(firewall = FirewallState(enabled = true, lastError = "VPN is down"))
                }
            } else if (firewallProxy?.isListening != true) {
                AppLog.warn("watchdog", "firewall relay is down; restarting")
                runCatching { startFirewall(vpnIp) }.onFailure {
                    AppLog.error("watchdog", "firewall restart failed: ${it.message}")
                }
            }
        } else if (firewallProxy != null) {
            stopFirewall()
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
        if (!changed) return
        if (firewallStore.load().enabled) {
            val vpnIp = withContext(Dispatchers.IO) {
                VpnDetector.currentVpnAddress(this@ControlService)
            }
            if (vpnIp == null) {
                stopFirewall()
                ControlBus.update {
                    it.copy(
                        vpnIp = null,
                        firewall = FirewallState(enabled = true, lastError = "VPN is down"),
                    )
                }
            } else {
                runCatching {
                    val proxy = firewallProxy
                    if (proxy != null) proxy.rebind(vpnIp) else startFirewall(vpnIp)
                }.onFailure {
                    fail(it.message ?: "Firewall rebind failed")
                }
                ControlBus.update { it.copy(vpnIp = vpnIp) }
            }
        } else {
            setup()
        }
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
                stopFirewall()
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
                // Stop is the explicit point where the app releases the system adbd.
                val adbStopped = withContext(Dispatchers.IO) {
                    runCatching { SystemSettings.applyAdbState(this@ControlService, AdbLifecycle.stop()) }
                }
                if (adbStopped.isFailure) {
                    fail(
                        "Could not turn the system adb off: " +
                            (adbStopped.exceptionOrNull()?.message ?: "unknown error"),
                    )
                    return@withLock
                }
                AppLog.info("service", "system adb off")
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
        SetupStep("Verify the listeners"),
        SetupStep("Turn system wireless debugging off"),
        SetupStep("Start the firewall relay"),
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
        // A system listener that is reachable over the network bypasses the
        // custom adb's key policy. It is a security warning, not a transient
        // status, so it stays in the ongoing notification as well as in the UI.
        val warnings = state.adbStatus?.warnings().orEmpty()
        val title = if (warnings.isNotEmpty()) {
            "ADB Anywhere needs attention"
        } else {
            when (state.phase) {
                Phase.Running -> "ADB Anywhere is running"
                Phase.NeedsPairing -> "Pair with Android's wireless debugging"
                Phase.Pairing -> "Pairing with Android's wireless debugging"
                Phase.Deploying -> "Setting up ADB Anywhere"
                Phase.Error -> "ADB Anywhere needs attention"
                Phase.Stopped -> "ADB Anywhere is stopped"
                Phase.Idle -> "ADB Anywhere"
            }
        }
        val text = buildList {
            addAll(warnings)
            if (state.firewall.enabled) {
                add(
                    if (state.firewall.listening) {
                        "Firewall: ${state.firewall.activeSessions} sessions, " +
                            "${state.firewall.pendingPrompts} prompts"
                    } else {
                        "Firewall: ${state.firewall.lastError.ifBlank { "not listening" }}"
                    },
                )
            }
            when {
                state.error.isNotEmpty() -> add(state.error)
                state.phase == Phase.Running && state.endpoint.isNotEmpty() &&
                    state.endpoint != "not available yet" -> add(state.endpoint)
                state.message.isNotEmpty() -> add(state.message)
                else -> add("Tap to open")
            }
        }.joinToString("\n")
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
            builder.addAction(0, "Stop", servicePendingIntent(ACTION_STOP))
        }
        if (state.phase == Phase.NeedsPairing || state.phase == Phase.Error) {
            builder.addAction(0, "Pair", contentPendingIntent())
        }
        return builder.build()
    }

    /** Step 1: the six-digit code from Android's pairing dialog. */
    private fun postPairingNotification(error: String = "") {
        val text = buildString {
            if (error.isNotEmpty()) append(error).append("\n\n")
            append("Open Settings → Developer options → Wireless debugging → Pair device ")
            append("with pairing code. Keep that dialog open, then reply with the six-digit code.")
        }
        val input = RemoteInput.Builder(INPUT_PAIR_CODE)
            .setLabel("Pairing code")
            .build()
        val action = NotificationCompat.Action.Builder(
            android.R.drawable.ic_dialog_info,
            "Enter code",
            pairReplyPendingIntent(ACTION_PAIR_CODE_REPLY, 10, null),
        ).addRemoteInput(input).build()
        postPairing("Pair ADB Anywhere (1/2)", text, action)
    }

    /** Step 2: the port under the IP address in the same dialog. */
    private fun postPairingPortNotification(code: String, error: String = "") {
        val text = buildString {
            if (error.isNotEmpty()) append(error).append("\n\n")
            append("Now reply with the port under the IP address in the same dialog.")
        }
        val input = RemoteInput.Builder(INPUT_PAIR_PORT)
            .setLabel("Pairing port")
            .build()
        val action = NotificationCompat.Action.Builder(
            android.R.drawable.ic_dialog_info,
            "Enter port",
            pairReplyPendingIntent(ACTION_PAIR_PORT_REPLY, 12, code),
        ).addRemoteInput(input).build()
        postPairing("Pair ADB Anywhere (2/2)", text, action)
    }

    private fun postPairing(
        title: String,
        text: String,
        action: NotificationCompat.Action,
    ) {
        val notification = NotificationCompat.Builder(this, Notifications.CHANNEL_PAIRING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
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

    private fun pairReplyPendingIntent(action: String, requestCode: Int, code: String?): PendingIntent {
        val intent = Intent(this, PairReplyReceiver::class.java).setAction(action)
        if (code != null) intent.putExtra(EXTRA_CODE, code)
        return PendingIntent.getBroadcast(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }

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
        private const val ACTION_PAIR_CODE_REPLY = "cz.slush.adbanywhere.PAIR_CODE_REPLY"
        private const val ACTION_PAIR_PORT_REPLY = "cz.slush.adbanywhere.PAIR_PORT_REPLY"
        const val ACTION_RESTART = "cz.slush.adbanywhere.RESTART"
        const val ACTION_REVOKE_KEYS = "cz.slush.adbanywhere.REVOKE_KEYS"
        const val ACTION_WATCHDOG = "cz.slush.adbanywhere.WATCHDOG"
        const val ACTION_LOGS = "cz.slush.adbanywhere.LOGS"
        const val ACTION_APPLY_KEYS = "cz.slush.adbanywhere.APPLY_KEYS"
        const val ACTION_APPLY_SETTINGS = "cz.slush.adbanywhere.APPLY_SETTINGS"
        const val ACTION_NOTIFY_PAIR = "cz.slush.adbanywhere.NOTIFY_PAIR"
        const val ACTION_SET_TLS_PORT = "cz.slush.adbanywhere.SET_TLS_PORT"
        const val ACTION_PROMPT_RESPONSE = "cz.slush.adbanywhere.PROMPT_RESPONSE"
        const val ACTION_CLEAR_AUDIT = "cz.slush.adbanywhere.CLEAR_AUDIT"
        const val ACTION_STOP = "cz.slush.adbanywhere.STOP"
        private const val NETWORK_SETTLE_MS = 1_500L
        private const val SYSTEM_ADB_WAIT_MS = 15_000L
        private const val MAX_PROMPTS = 8
        const val EXTRA_CODE = "code"
        const val EXTRA_PAIR_PORT = "pair_port"
        const val EXTRA_PORT = "port"
        const val EXTRA_PROMPT_ID = "prompt_id"
        const val EXTRA_PROMPT_DECISION = "prompt_decision"
        const val EXTRA_PROMPT_RULE = "prompt_rule"
        const val INPUT_PAIR_CODE = "pair_code"
        const val INPUT_PAIR_PORT = "pair_port"

        fun start(context: Context, action: String) {
            send(context, Intent(context, ControlService::class.java).setAction(action))
        }

        fun startPair(context: Context, code: String, port: String?) {
            val intent = Intent(context, ControlService::class.java)
                .setAction(ACTION_PAIR)
                .putExtra(EXTRA_CODE, code)
            if (port != null) intent.putExtra(EXTRA_PAIR_PORT, port)
            send(context, intent)
        }

        fun startPromptResponse(
            context: Context,
            id: String,
            decision: String,
            rule: String? = null,
        ) {
            val intent = Intent(context, ControlService::class.java)
                .setAction(ACTION_PROMPT_RESPONSE)
                .putExtra(EXTRA_PROMPT_ID, id)
                .putExtra(EXTRA_PROMPT_DECISION, decision)
            if (rule != null) intent.putExtra(EXTRA_PROMPT_RULE, rule)
            send(context, intent)
        }

        private fun send(context: Context, intent: Intent) {
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (_: Exception) {
                runCatching { context.startService(intent) }
            }
        }
    }
}
