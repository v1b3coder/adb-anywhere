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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import cz.slush.adbanywhere.BuildConfig
import cz.slush.adbanywhere.MainActivity
import cz.slush.adbanywhere.R
import cz.slush.adbanywhere.adb.AdbClient
import cz.slush.adbanywhere.adb.AdbMode
import cz.slush.adbanywhere.adb.AdbTls
import cz.slush.adbanywhere.adb.DaemonPortHeldException
import cz.slush.adbanywhere.adb.DaemonChannel
import cz.slush.adbanywhere.adb.DaemonManager
import cz.slush.adbanywhere.adb.DaemonTunnel
import cz.slush.adbanywhere.adb.Shell
import cz.slush.adbanywhere.adb.SystemAdb
import cz.slush.adbanywhere.data.AndroidPubKey
import cz.slush.adbanywhere.data.AppKeyStore
import cz.slush.adbanywhere.data.DaemonTlsKey
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
import cz.slush.adbanywhere.firewall.KnownKey
import cz.slush.adbanywhere.firewall.Protected
import cz.slush.adbanywhere.firewall.ForegroundAppMonitor
import cz.slush.adbanywhere.firewall.PromptDecision
import cz.slush.adbanywhere.firewall.PromptRequest
import cz.slush.adbanywhere.firewall.PromptResponse
import cz.slush.adbanywhere.ui.CallPromptActivity
import cz.slush.adbanywhere.ui.CallPromptOverlay
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
import java.net.BindException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject

class ControlService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val setupMutex = Mutex()
    private val lifecycleLock = Any()
    // One prompt is shown at a time: a second unmatched call waits for the
    // first to be answered or denied instead of silently replacing its card.
    private val promptMutex = Mutex()
    @Volatile
    private var watchdogBackoffMs = 0L
    @Volatile
    private var lastFailureRetryable = true

    // The relay is mutated by setup(), the watchdog and the network callback on
    // different threads.  Binding a port and publishing the proxy must be one critical
    // section, or two starts create two listeners and a stop leaves one behind.
    private val relayLock = Any()
    private var relayEpoch = 0
    private lateinit var prefs: Prefs
    // The system key bootstraps through Android's adbd; the daemon key and the
    // pinned TLS key are the only identities the patched daemon trusts (D20).
    private lateinit var keys: AppKeyStore
    private lateinit var daemonKeys: AppKeyStore
    private lateinit var tlsKey: DaemonTlsKey
    private lateinit var system: SystemAdb
    private lateinit var daemon: DaemonManager
    private lateinit var firewallStore: FirewallConfigStore
    private lateinit var auditLog: AuditLog
    private var firewallProxy: FirewallProxy? = null
    private var foregroundMonitor: ForegroundAppMonitor? = null
    private var promptOverlay: CallPromptOverlay? = null
    // Relay threads and the main thread add, answer and remove prompts, so the map
    // must be concurrent; the service also iterates it when it stops the relay.
    private val prompts =
        ConcurrentHashMap<String, CompletableDeferred<PromptResponse?>>()
    private var notificationJob: Job? = null
    private var watchdogJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var defaultCallback: ConnectivityManager.NetworkCallback? = null
    private var usbReceiver: android.content.BroadcastReceiver? = null
    private var foregroundStarted = false
    private val networkChanges = MutableStateFlow(0)

    override fun onCreate() {
        super.onCreate()
        AppLog.info("service", "created")
        prefs = Prefs(this)
        keys = AppKeyStore(this)
        daemonKeys = AppKeyStore(this, AppKeyStore.DAEMON)
        tlsKey = DaemonTlsKey(this)
        system = SystemAdb(this, keys, prefs)
        daemon = DaemonManager(this, prefs)
        firewallStore = FirewallConfigStore(prefs)
        auditLog = AuditLog(
            File(createDeviceProtectedStorageContext().filesDir, "firewall-audit.jsonl"),
        ) { firewallStore.load().redactAudit }
        ControlBus.audit = auditLog.entries
        ControlBus.firewallConfig.value = firewallStore.load()
        promptOverlay = CallPromptOverlay(this)
        foregroundMonitor = ForegroundAppMonitor(
            this,
            CallPromptActivity::class.java.name,
        ) {
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
        ensureNotificationCollector()
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
                // An explicit Start is the user's retry: give it a fresh
                // retry budget instead of the cap a previous failure set.
                resetBackoff()
                prefs.failureNeedsUser = false
                // A Stop followed immediately by a Start must not lose the new
                // start.  promoteForeground() and the stop cleanup share
                // lifecycleLock and the stop checks userWantsRunning under it.
                promoteForeground()
                // A fresh process or a failed setup can inherit a daemon from a
                // previous run. Kill it and start this process's own daemon; a
                // running daemon is already ours and is only re-checked.
                val forceRestart = ControlBus.state.value.phase in
                    setOf(Phase.Idle, Phase.Stopped, Phase.Error)
                scope.launch { setup(forceRestart = forceRestart, userInitiated = true) }
            }
            // Opening the app re-checks a daemon the user wants running. It is not
            // a Start: it never writes ADB_ENABLED and never clears a failure
            // that waits for the user.
            ACTION_RECHECK -> {
                if (!prefs.userWantsRunning) {
                    AppLog.info("service", "recheck skipped; the user stopped adb")
                } else if (prefs.failureNeedsUser && ControlBus.state.value.phase in setOf(Phase.Idle, Phase.Error)) {
                    promoteForeground()
                    ControlBus.update { it.copy(phase = Phase.Error, error = prefs.lastError, message = "") }
                } else {
                    promoteForeground()
                    val forceRestart = ControlBus.state.value.phase in
                        setOf(Phase.Idle, Phase.Stopped, Phase.Error)
                    scope.launch { setup(forceRestart = forceRestart) }
                }
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
                resetBackoff()
                prefs.failureNeedsUser = false
                promoteForeground()
                scope.launch { setup(forceRestart = true, userInitiated = true) }
            }
            // A revocation must terminate sessions that were authenticated with the
            // removed key, so it restarts the daemon like an explicit restart. The UI
            // only sends it while the daemon runs.
            ACTION_REVOKE_KEYS -> {
                AppLog.info("service", "key revocation; restarting to close sessions")
                scope.launch { setup(forceRestart = true) }
            }
            // One reply carries code:port from the pairing dialog. The debug
            // harness sends the code and port as separate extras; both paths reach
            // the same pair() call.
            ACTION_PAIR -> {
                // The notification reply is the one pairing path another app can
                // reach (a notification listener can fire the mutable reply
                // PendingIntent, even after the notification is gone). Accept it
                // only while a notification the app posted is current, once.
                if (intent.getBooleanExtra(EXTRA_FROM_NOTIFICATION, false) && !consumePairingReply()) {
                    AppLog.warn("pair", "ignored a pairing reply without a current pairing notification")
                    return START_STICKY
                }
                val codeText = intent.getStringExtra(EXTRA_CODE)
                val portText = intent.getStringExtra(EXTRA_PAIR_PORT)
                val code = codeText?.let { PairingCode.parseCode(it) }
                val port = portText?.let { PairingCode.parsePort(it) }
                val combined = codeText?.let { PairingCode.parse(it) }
                when {
                    code != null && port != null -> {
                        prefs.userWantsRunning = true
                        promoteForeground()
                        scope.launch { pair(PairingCode(code, port)) }
                    }
                    combined != null -> {
                        prefs.userWantsRunning = true
                        promoteForeground()
                        scope.launch { pair(combined) }
                    }
                    else -> {
                        AppLog.warn("pair", "pairing value is not code:port")
                        postPairingNotification("Enter code:port, for example 123456:37123.")
                    }
                }
            }
            ACTION_WATCHDOG -> if (!resumeAfterRestart()) scope.launch { watchdog() }
            ACTION_LOGS -> scope.launch { refreshLogs() }
            ACTION_NOTIFY_PAIR -> {
                AppLog.info("service", "pairing notification requested")
                postPairingNotification()
            }
            ACTION_APPLY_FIREWALL -> {
                prefs.userWantsRunning = true
                AppLog.info("firewall", "listener mode changed; restarting the daemon in place")
                promoteForeground()
                scope.launch { setup(forceRestart = true) }
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
                stopEverything(startId)
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        AppLog.info("service", "destroyed")
        hidePromptOverlay()
        stopFirewall("The service stopped")
        // The daemon is not ours to stop here, and a service the system
        // destroyed while the user still wants it running is resumed by the
        // sticky restart. Only an explicit stop leaves the Stopped phase.
        if (ControlBus.state.value.phase == Phase.Running && !prefs.userWantsRunning) {
            ControlBus.update {
                it.copy(phase = Phase.Stopped, endpoint = "", message = "The service stopped.")
            }
        }
        notificationJob?.cancel()
        watchdogJob?.cancel()
        scope.cancel()
        networkCallback?.let {
            runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) }
        }
        defaultCallback?.let {
            runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) }
        }
        usbReceiver?.let { runCatching { unregisterReceiver(it) } }
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
        if (ControlBus.state.value.phase != Phase.Idle) {
            AppLog.info("service", "not resumed; phase=${ControlBus.state.value.phase}")
            return false
        }
        // D6: a failure that needs the user waits for Try again, across a
        // process restart too; only a transient failure is retried on its own.
        if (prefs.failureNeedsUser) {
            AppLog.info("service", "not resumed; the last failure needs the user")
            ControlBus.update {
                it.copy(phase = Phase.Error, error = prefs.lastError, message = "")
            }
            return false
        }
        AppLog.info("service", "resuming after a process restart")
        // The daemon that survived the process kill is not this process's own.
        // awaitRestartScripts() makes the forced restart safe by waiting for a
        // detached restart from the previous process to finish first.
        scope.launch { setup(forceRestart = true) }
        return true
    }

    /**
     * [userInitiated] is an explicit Start, Restart or pairing. Only such a setup
     * may write ADB_ENABLED=1 (D5, I12): the watchdog, a reboot, a sticky restart
     * and a network change never turn the system adb back on behind the user.
     */
    private suspend fun setup(forceRestart: Boolean = false, userInitiated: Boolean = false) {
        setupMutex.withLock {
            // The last explicit user intent wins.  A setup that was queued
            // behind a Stop must not start a daemon after the user stopped.
            if (!prefs.userWantsRunning) {
                AppLog.info("setup", "skipped; the user stopped adb")
                return@withLock
            }
            // A stored port from an older build can sit in the ephemeral range
            // that Android's wireless debugging uses for its random TLS port, or on
            // the system adbd's plain 5555 listener. Fail with a clear
            // instruction instead of connecting to the wrong listener and
            // reporting "pairing required".
            Prefs.validatePorts(prefs.loopbackPort, prefs.vpnPort)?.let { failure ->
                fail("$failure Press Start again after saving the ports.", retryable = false)
                return@withLock
            }
            val previous = ControlBus.state.value
            AppLog.info("setup", "started; restart=$forceRestart")
            // A setup is often just a settings re-apply while the relay is already
            // listening.  Do not reset FirewallState.listening to false in that
            // case: the Home hero and the Firewall tab would disagree until the
            // relay is restarted.
            val relayListening = firewallProxy?.isListening == true
            ControlBus.update {
                it.copy(
                    phase = Phase.Deploying,
                    error = "",
                    message = "Preparing…",
                    steps = initialSteps(),
                    loopbackPort = prefs.loopbackPort,
                    vpnPort = prefs.vpnPort,
                    firewall = if (relayListening) {
                        it.firewall.copy(enabled = firewallStore.load().enabled)
                    } else {
                        FirewallState(enabled = firewallStore.load().enabled)
                    },
                )
            }
            // A failed attempt must undo what it changed: it may have written the
            // bootstrap settings or reached a shell on the device.
            var cleanupRequired = false
            // A daemon that was already running and only got its key file
            // rewritten must survive a later failure: killing it would drop the
            // loopback channel the user still has.
            var daemonStarted = false
            // Once the daemon is verified it is the user's channel: a later
            // failure (the relay cannot bind) must not kill it (§6 Listener modes 5).
            var daemonVerified = false
            var relayTouched = false
            val firewallConfig = firewallStore.load()
            // What the setup is allowed to change, so a failed attempt can put
            // the platform back instead of leaving the system TLS listener on.
            val adbStateBefore = withContext(Dispatchers.IO) {
                SystemSettings.state(this@ControlService)
            }
            // A pre-TLS daemon's migration channel; closed in the finally below.
            var legacyShell: Shell? = null
            try {
                val vpnIp = VpnDetector.currentVpnAddress(this)
                val exposeVpn = !firewallConfig.enabled
                prefs.lastVpnIp = vpnIp ?: ""
                val developers = DeveloperKey.list(prefs.developerKeysJson)
                val appKey = keys.publicKey
                val material = daemonMaterial()
                ControlBus.update {
                    it.copy(
                        vpnIp = vpnIp,
                        vpnPort = prefs.vpnPort,
                        loopbackPort = prefs.loopbackPort,
                        appFingerprint = appKey.fingerprint,
                        keyCount = developers.size + 1,
                    )
                }

                step(0, StepStatus.Active, "Connecting to Android's adb")
                val probe = withContext(Dispatchers.IO) { probeDaemon() }
                // A daemon from a build before the TLS channel speaks plain ADB on
                // loopback. It is migrated once through its own verified shell, so
                // an update over the relay does not cut the phone off.
                legacyShell = if (probe == DaemonProbe.Legacy) {
                    withContext(Dispatchers.IO) { legacyDaemonShell() }
                } else {
                    null
                }
                var daemonAlive = probe == DaemonProbe.Verified || legacyShell != null
                AppLog.info("setup", "custom adb probe=$probe alive=$daemonAlive dir=${prefs.remoteDir}")
                // A pre-TLS daemon trusts the system key over plain ADB. While it
                // runs, a plain signature on 5555 (which any app can bind) could be
                // relayed to it, so the bootstrap may only use TLS listeners (I15).
                val plainAllowed = SetupPolicy.plainSigningAllowed(probe)
                if (SetupPolicy.stopsBeforeBootstrap(probe, userInitiated)) {
                    // Something that is not the app's daemon answers on the
                    // loopback port with another identity. Only an explicit Start
                    // may redeploy over it (it stops every vendored daemon first);
                    // a boot, the watchdog or a network change must not reopen the
                    // system wireless debugging for it on every retry (§9).
                    fail(DaemonPortHeldException(prefs.loopbackPort).message!!, retryable = false)
                    return@withLock
                }
                val canWriteSettings = ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.WRITE_SECURE_SETTINGS,
                ) == PackageManager.PERMISSION_GRANTED
                // After pairing, the app key is in the system adbd's user_keys.
                // ADB_ENABLED=1 is necessary but not sufficient: it starts the
                // system adbd, but the app still needs a TCP shell.  Enabling ADB
                // is safe; it does not delete the paired key.  The opt-in switch
                // only controls whether Stop turns ADB off again.
                if (!daemonAlive && canWriteSettings && SystemSettings.adbEnabled(this) == 0) {
                    if (userInitiated) {
                        AppLog.info("setup", "system adb is off; enabling it")
                        SystemSettings.setAdbEnabled(this, true)
                        delay(500)
                    } else {
                        // The user may have turned USB debugging off on purpose;
                        // only an explicit Start turns it back on.
                        fail(
                            "USB debugging is off and the custom adb is down. " +
                                "Press Start to turn it back on.",
                            retryable = false,
                        )
                        return@withLock
                    }
                }
                // Try the system adbd's existing listeners first.  The plain 5555
                // listener and the TLS property both work on mobile data; wireless
                // debugging is only the fallback for the first shell after a reboot.
                var systemAlive = if (daemonAlive) false
                else withContext(Dispatchers.IO) { system.ensureConnected(plainAllowed) }
                if (!daemonAlive && !systemAlive && canWriteSettings &&
                    prefs.setupComplete && SystemSettings.isWifiConnected(this)
                ) {
                    // The one-time grant persists, so the app can re-open the
                    // wireless-debugging bootstrap after a reboot without a
                    // pre-existing app shell.  This is the Wi-Fi fallback, not the
                    // normal start: on mobile data the system adbd has no TCP
                    // listener and the app cannot open one without USB or root.
                    cleanupRequired = true
                    AppLog.info("setup", "bootstrap: opening system wireless debugging")
                    // Always write the setting, even when it already reads 1: the
                    // framework can have the setting on while its TLS server is down,
                    // and a skipped write leaves that state in place.  Never write
                    // ADB_ENABLED here: it restarts the system adbd and can drop
                    // the paired keys on some devices.
                    SystemSettings.setAdbWifiEnabled(this, true)
                    AppLog.info("setup", "bootstrap: adb_wifi_enabled=1")
                    // adb_allowed_connection_time is the authorization expiry
                    // (default 7 days) of every key the system adbd trusts. The
                    // app's system key is used only to bootstrap, so it would
                    // expire between reboots; 0 keeps it, for every key. This is
                    // a documented residual (docs/DESIGN.md §8).
                    if (!SystemSettings.ensureNoAdbConnectionTimeout(this)) {
                        AppLog.warn("setup", "adb_allowed_connection_time=0 was not accepted")
                    }
                    delay(900)
                    awaitSystemWirelessDebugging()
                    ControlBus.update { it.copy(message = "Preparing…") }
                    systemAlive = withContext(Dispatchers.IO) { system.ensureConnected(plainAllowed) }
                }
                // A stale paired flag is the most common pairing failure: the
                // app key is no longer in the system adbd, so a connect is
                // rejected with "ADB pairing is required". Drop the flag and
                // ask for a new code instead of failing the whole setup.
                if (!daemonAlive && !systemAlive && system.pairingRequired) {
                    AppLog.warn("setup", "the system adb rejected the app key; pairing required")
                    needsPairing()
                    return@withLock
                }
                if (firewallConfig.enabled && vpnIp != null &&
                    SystemSettings.isPortOpen(vpnIp, prefs.vpnPort) &&
                    firewallProxy?.isListening != true
                ) {
                    // The firewall needs the VPN port. Close the system plain
                    // listener only when the system adbd itself says it owns the
                    // port (I2); our own daemon is restarted loopback-only below,
                    // and anything else is a foreign listener the relay reports.
                    // `usb:` keeps the system adbd on USB. It removes the plain
                    // listener, so it may only run while the app reaches the
                    // system adbd over its TLS listener, which the restart keeps:
                    // that listener is the recovery path (I1).
                    val systemOwnsPort = system.ensureConnected(plainAllowed) &&
                        system.plainTcpPort() == prefs.vpnPort
                    if (systemOwnsPort && !system.connectedOverTls) {
                        fail(
                            "Android's plain adb listener holds the firewall port ${prefs.vpnPort} and is " +
                                "the only way the app reaches Android's adb. Run `adb usb` over USB, or " +
                                "change the VPN port in Settings, then press Start again.",
                            retryable = false,
                        )
                        return@withLock
                    }
                    if (systemOwnsPort) {
                        AppLog.info(
                            "firewall",
                            "port ${prefs.vpnPort} is the system plain listener; closing it",
                        )
                        if (system.closePlainListener()) {
                            Thread.sleep(500)
                            legacyShell?.close()
                            legacyShell = null
                            val again = withContext(Dispatchers.IO) { probeDaemon() }
                            if (again == DaemonProbe.Legacy) {
                                legacyShell = withContext(Dispatchers.IO) { legacyDaemonShell() }
                            }
                            daemonAlive = again == DaemonProbe.Verified || legacyShell != null
                            systemAlive = if (daemonAlive) false else system.ensureConnected(plainAllowed)
                        } else {
                            AppLog.warn("firewall", "could not close the system plain listener")
                        }
                    } else {
                        AppLog.info("firewall", "port ${prefs.vpnPort} is not the system plain listener")
                    }
                }
                AppLog.info("setup", "system adb alive=$systemAlive")
                // With the firewall on, the relay owns the VPN port and the custom
                // adbd must be loopback-only.  A daemon started before the
                // firewall was enabled still holds the VPN port even when the
                // persisted daemonFirewallMode already reads true.  The port is the
                // authority: restart the daemon loopback-only before the relay binds.
                // The relay itself listens on the VPN port when the firewall is on.
                // Only a listener that is not our relay can be the custom adbd or the
                // system plain listener; otherwise this check restarts the daemon and
                // waits for the relay to close a port it is supposed to own.
                val relayListening = firewallProxy?.isListening == true
                val vpnPortBusy = firewallConfig.enabled && daemonAlive && vpnIp != null &&
                    !relayListening && SystemSettings.isPortOpen(vpnIp, prefs.vpnPort)
                if (vpnPortBusy) {
                    AppLog.warn(
                        "firewall",
                        "the custom adb still listens on the firewall port; " +
                            "restarting it loopback-only",
                    )
                }
                cleanupRequired = cleanupRequired || daemonAlive || systemAlive
                if (!daemonAlive && !systemAlive) {
                    if (!prefs.paired) {
                        needsPairing()
                    } else {
                        fail(
                            "Android's adb is not reachable and the custom adb is down. " +
                                "The app key is paired, but the system adbd has no TCP listener. " +
                                "Connect Wi-Fi once, or enable USB debugging and run " +
                                "`adb tcpip 5555` over USB, then press Start again.",
                            retryable = false,
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
                        retryable = false,
                    )
                    return@withLock
                }

                if (!daemonAlive) AppLog.info("setup", "WRITE_SECURE_SETTINGS granted")

                val firewallChanged = firewallConfig.enabled != prefs.daemonFirewallMode
                if (firewallChanged) {
                    AppLog.info("firewall", "listener mode changed; stopping the relay first")
                    stopFirewall("Firewall mode changed")
                    relayTouched = true
                }

                step(1, StepStatus.Active, "Deploying the custom adb")
                val shell = when {
                    legacyShell != null -> legacyShell
                    daemonAlive -> withContext(Dispatchers.IO) { daemonShell() }
                    else -> system.shell
                }
                val vpnChanged = vpnIp != previous.vpnIp
                val portsChanged = prefs.loopbackPort != previous.loopbackPort ||
                    prefs.vpnPort != previous.vpnPort
                val restart = forceRestart || !daemonAlive || portsChanged ||
                    (exposeVpn && vpnChanged) || firewallChanged || vpnPortBusy ||
                    legacyShell != null
                // What this attempt starts or restarts is what a later failure
                // may stop; a daemon it leaves untouched stays.
                daemonStarted = !daemonAlive || restart
                AppLog.info(
                    "setup",
                    "deploy: adbAlive=$daemonAlive vpnChanged=$vpnChanged " +
                        "portsChanged=$portsChanged firewallChanged=$firewallChanged " +
                        "vpnPortBusy=$vpnPortBusy restart=$restart",
                )
                if (daemonAlive && !restart) {
                    // No key was revoked: the daemon reloads the key file on the
                    // next auth. Revocation sends ACTION_REVOKE_KEYS instead, so
                    // the restart above kills the sessions the key authenticated.
                    AppLog.info("adb", "rewriting keys; no restart")
                    daemon.writeKeys(shell, material)
                } else if (daemonAlive) {
                    AppLog.info("adb", "restarting with the new keys and listeners")
                    daemon.restartDetached(
                        shell,
                        material,
                        vpnIp,
                        prefs.loopbackPort,
                        prefs.vpnPort,
                        exposeVpn,
                    )
                } else {
                    AppLog.info("adb", "deploying and starting")
                    daemon.deployAndStart(
                        shell,
                        material,
                        vpnIp,
                        prefs.loopbackPort,
                        prefs.vpnPort,
                        exposeVpn,
                    )
                }
                prefs.daemonFirewallMode = firewallConfig.enabled
                step(1, StepStatus.Done, "Custom adb running")

                step(2, StepStatus.Active, "Verifying the listeners")
                verifyDaemon(vpnIp, exposeVpn)
                daemonVerified = true
                prefs.tlsDaemonDeployed = true
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

                // Start the relay while the system adbd is still up. If the bind
                // fails, the system listener is still a recovery path. Turning the
                // system wireless debugging off first would leave the phone with no
                // reachable ADB endpoint when the relay cannot bind.
                step(3, StepStatus.Active, "Starting the firewall relay")
                if (firewallConfig.enabled && vpnIp != null && !isVpnActive()) {
                    fail(
                        "ADB Anywhere is excluded from the VPN. Include it in the VPN app, " +
                            "then press Start again. The firewall relay runs in the app process.",
                        retryable = false,
                    )
                    return@withLock
                }
                if (firewallConfig.enabled && vpnIp != null &&
                    firewallProxy?.isListening != true
                ) {
                    waitForPortFree(vpnIp, prefs.vpnPort)
                }
                if (firewallConfig.enabled && vpnIp != null) {
                    if (firewallChanged || vpnChanged || portsChanged ||
                        firewallProxy?.isListening != true
                    ) {
                        startFirewall(vpnIp)
                        relayTouched = true
                    }
                    verifyFirewall()
                    firewallProxy?.publishState()
                    step(3, StepStatus.Done, "Firewall relay on $vpnIp:${prefs.vpnPort}")
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
                    step(3, StepStatus.Done, "VPN is down; no external listener")
                } else {
                    stopFirewall()
                    step(3, StepStatus.Done, "Firewall is off")
                }

                step(4, StepStatus.Active, "Turning system wireless debugging off")
                harden()
                step(4, StepStatus.Done, "System wireless debugging off")

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
                resetBackoff()
                scheduleWatchdog()
                startWatchdogLoop()
                refreshLogs()
            } catch (e: Exception) {
                AppLog.error("setup", "failed: ${e.message}", e)
                // A port another process holds stays held: retrying would reopen
                // the bootstrap on every watchdog tick, so it waits for the user.
                fail(e.message ?: "Setup failed", retryable = SetupPolicy.retryable(e))
            } finally {
                legacyShell?.close()
                if (cleanupRequired && ControlBus.state.value.phase != Phase.Running) {
                    abortSetup(adbStateBefore, daemonStarted && !daemonVerified, relayTouched)?.let { warning ->
                        val error = ControlBus.state.value.error
                        fail(
                            if (error.isEmpty()) warning else "$error $warning",
                            retryable = false,
                        )
                    }
                }
                // D11: read the platform state after the last write, so the UI
                // shows what is really set instead of the phase.
                refreshAdbStatus()
            }
        }
    }

    /**
     * Undoes a failed setup attempt: stops the patched daemon and the relay, and
     * puts the system wireless debugging back the way the setup found it. The
     * setup may have opened it for the bootstrap; leaving it on would expose the
     * system TLS listener on every interface while the custom adb is down.
     */
    private suspend fun abortSetup(
        adbStateBefore: SystemSettings.AdbState?,
        stopDaemon: Boolean,
        stopRelay: Boolean,
    ): String? =
        withContext(Dispatchers.IO + NonCancellable) {
            if (stopRelay) runCatching { stopFirewall("Setup failed") }
            // Only stop what this attempt started or restarted. A daemon that
            // was already running and only got its key file rewritten is still
            // the user's channel; a failure elsewhere must not kill it.
            val stopped = if (!stopDaemon) {
                AppLog.info("setup", "cleanup keeps the running custom adb")
                true
            } else {
                runCatching {
                    val shell = runCatching { daemonShell() }.getOrNull() ?: system.shell
                    try {
                        daemon.stop(shell)
                    } finally {
                        shell.close()
                    }
                }.onFailure {
                    AppLog.warn("setup", "cleanup cannot stop adb: ${it.message}")
                }.isSuccess
            }
            val warnings = mutableListOf<String>()
            // Restore the bootstrap only when the setup changed it. When it was
            // already on before the setup, the fallback stays as it was.
            val wifiBefore = adbStateBefore?.adbWifiEnabled
            if (wifiBefore == null || wifiBefore == 0) {
                val closed = runCatching {
                    WirelessDebuggingGuard(
                        readSetting = { SystemSettings.adbWifiEnabled(this@ControlService) },
                        writeDisabled = {
                            SystemSettings.setAdbWifiEnabled(this@ControlService, false)
                        },
                        readTlsPort = ::wirelessDebuggingPort,
                        isPortOpen = { port -> AdbClient.offersTls(Endpoint.LOOPBACK, port) },
                        pause = { Thread.sleep(WirelessDebuggingGuard.RETRY_DELAY_MS) },
                    ).ensureOff()
                }.onFailure {
                    AppLog.warn(
                        "setup",
                        "cleanup cannot close wireless debugging: ${it.message}",
                    )
                    warnings += "Turn wireless debugging off in Settings, or reboot."
                }
                if (closed.isSuccess) {
                    AppLog.info("setup", "cleanup closed system wireless debugging")
                }
            }
            // The shell may be gone because the daemon already died; the port
            // is the authority on whether anything still runs.
            val daemonListening = stopDaemon && runCatching {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", prefs.loopbackPort), 300) }
                true
            }.getOrDefault(false)
            AppLog.warn(
                "setup",
                "cleanup stopped=$stopped listening=$daemonListening",
            )
            if (daemonListening) {
                warnings += "Cleanup incomplete: the custom adb still listens on " +
                    "127.0.0.1:${prefs.loopbackPort}. Turn wireless debugging off in " +
                    "Settings, or reboot."
            }
            warnings.joinToString(" ").ifEmpty { null }
        }

    /**
     * Android starts the TLS listener only after the user confirms its "allow
     * wireless debugging on this network?" prompt, which it shows on every new
     * network and then writes adb_wifi_enabled=0 again. Wait for the published
     * port instead of racing the prompt and failing the whole setup.
     */
    private suspend fun awaitSystemWirelessDebugging(timeoutMs: Long = SYSTEM_ADB_WAIT_MS) {
        val deadline = System.currentTimeMillis() + timeoutMs
        val ports = LinkedHashSet<Int>()
        var nextDiscovery = 0L
        ControlBus.update {
            it.copy(message = "Confirm Android's wireless debugging prompt…")
        }
        // The advertisement appears a few seconds after the setting write, so
        // mDNS must be retried during the wait instead of resolved once before
        // the loop. Android 11-16 publish the port; AOSP main after Android 16
        // delivers it over the auth socket instead.
        while (System.currentTimeMillis() < deadline) {
            SystemAdb.systemTlsPort()?.let(ports::add)
            val now = System.currentTimeMillis()
            if (ports.none(SystemSettings::isLocalPortOpen) && now >= nextDiscovery) {
                nextDiscovery = now + MDNS_RETRY_MS
                ports += system.discoverTlsPorts(MDNS_TIMEOUT_MS)
            }
            val open = ports.firstOrNull(SystemSettings::isLocalPortOpen)
            if (open != null) {
                // Open is not verified: any app can advertise or bind a port.
                // Only SystemAdb.tryConnect remembers a port, after the proof (I16).
                AppLog.info("setup", "system wireless debugging is up on 127.0.0.1:$open")
                return
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
        resetBackoff()
        ControlBus.update {
            it.copy(steps = listOf(SetupStep("Pair with Android's wireless debugging", StepStatus.Done, "Paired")))
        }
        setup(userInitiated = true)
    }

    private fun needsPairing() {
        AppLog.warn("setup", "pairing required")
        prefs.paired = false
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


    /**
     * Who holds the loopback port. Verified: the TLS handshake matched the
     * pinned daemon key and ADB auth succeeded inside it. Legacy: a plain-ADB
     * daemon from a build before the TLS channel, never seen since this build
     * deployed one. Foreign: something else; it is never trusted or signed for.
     */
    private suspend fun probeDaemon(): DaemonProbe = withContext(Dispatchers.IO) {
        if (!SystemSettings.isLocalPortOpen(prefs.loopbackPort)) return@withContext DaemonProbe.Absent
        try {
            DaemonChannel.connect(this@ControlService, prefs.loopbackPort).close()
            DaemonProbe.Verified
        } catch (e: Exception) {
            SetupPolicy.classify(e, prefs.tlsDaemonDeployed).also {
                AppLog.warn("setup", "127.0.0.1:${prefs.loopbackPort} is not the app's daemon ($it): ${e.message}")
            }
        }
    }

    /**
     * The one-time migration channel to a pre-TLS daemon, which trusts the old
     * single app key (now the system key). It signs a plain token only when no
     * plain system listener exists to relay it to (127.0.0.1:5555 closed), and
     * the endpoint must then prove it runs as shell, so a squatter gets neither
     * a usable signature nor the deployment.
     */
    private fun legacyDaemonShell(): Shell? {
        if (SystemSettings.isLocalPortOpen(Prefs.PLAIN_ADB_PORT)) {
            AppLog.warn("setup", "legacy daemon not migrated in place: 5555 is open")
            return null
        }
        val client = runCatching {
            AdbClient.connect(Endpoint.LOOPBACK, prefs.loopbackPort, keys.adbIdentity, AdbMode.Plain)
        }.getOrElse {
            AppLog.warn("setup", "legacy daemon connect failed: ${it.message}")
            return null
        }
        val shell = Shell(client)
        if (!ShellProof.verify(this, shell)) {
            shell.close()
            return null
        }
        AppLog.info("setup", "migrating the pre-TLS daemon through its own shell")
        return shell
    }

    private suspend fun verifyDaemon(vpnIp: String?, exposeVpn: Boolean) = withContext(Dispatchers.IO) {
        val shell = try {
            daemonShell()
        } catch (e: Exception) {
            if (AdbTls.identityFailure(e) != null) throw DaemonPortHeldException(prefs.loopbackPort)
            throw IOException(
                "The custom adb on 127.0.0.1:${prefs.loopbackPort} did not present the app's " +
                    "pinned key (${e.message}). Another process may hold the port; " +
                    "change the ports in Settings or reboot.",
                e,
            )
        }
        try {
            val identity = shell.exec("id")
            if (!identity.stdout.contains("uid=2000") && !identity.stdout.contains("uid=0")) {
                throw IOException("The custom adb returned an unexpected identity: ${identity.stdout.trim()}")
            }
        } finally {
            shell.close()
        }
        // The daemon binds every listener or exits, so the verified daemon holds
        // the VPN port too. The probe only confirms the port is reachable; it
        // never signs a token on the VPN address.
        if (exposeVpn && vpnIp != null && !SystemSettings.isPortOpen(vpnIp, prefs.vpnPort)) {
            throw IOException("The custom adb did not bind $vpnIp:${prefs.vpnPort}.")
        }
    }

    private suspend fun restartStack() {
        setupMutex.withLock { stopStack() }
        setup(forceRestart = true)
    }

    private suspend fun stopStack() = withContext(Dispatchers.IO + NonCancellable) {
        stopFirewall()
        cancelWatchdog()
        watchdogJob?.cancel()
        val shell = runCatching { daemonShell() }.getOrNull()
        if (shell != null) {
            runCatching { daemon.stop(shell) }.onFailure {
                AppLog.warn("service", "stop before restart: ${it.message}")
            }
            shell.close()
        } else {
            runCatching { daemon.stop(system.shell) }
        }
        AppLog.info("service", "stack stopped for a clean restart")
    }

    private fun startFirewall(vpnIp: String) {
        val config = firewallStore.load()
        if (!config.enabled) return
        val epoch = synchronized(relayLock) { relayEpoch }
        var last: BindException? = null
        repeat(BIND_ATTEMPTS) { attempt ->
            // The bind and the field assignment are one critical section: a stop or a
            // newer start must not interleave with them, and two callers must not
            // both bind the VPN port.
            val bound = synchronized(relayLock) {
                // A stop ran while this start was retrying (VPN went down, the
                // service was destroyed, the firewall was toggled). Do not resurrect
                // the relay behind its back.
                if (relayEpoch != epoch) return
                // The watchdog can ask for a relay that is already up; reuse it
                // instead of dropping live sessions.
                if (firewallProxy?.isListeningOn(vpnIp, prefs.vpnPort, prefs.loopbackPort) == true) {
                    return
                }
                firewallProxy?.stop()
                firewallProxy = null
                foregroundMonitor?.start()
                val policy = FirewallPolicy(
                    config = { firewallStore.load() },
                    protected = {
                        Protected(
                            ports = setOf(prefs.loopbackPort, prefs.vpnPort, Prefs.PLAIN_ADB_PORT),
                            directory = prefs.remoteDir,
                        )
                    },
                    prompt = ::promptForCall,
                    pending = { prompts.isNotEmpty() },
                    saveRule = {
                        firewallStore.addRule(it)
                        ControlBus.firewallConfig.value = firewallStore.load()
                    },
                    audit = { auditLog.record(it) },
                    onRuleUsed = { id ->
                        ControlBus.firewallConfig.value = firewallStore.markRuleUsed(id)
                    },
                )
                val proxy = FirewallProxy(
                    scope = scope,
                    config = { firewallStore.load() },
                    monitor = foregroundMonitor,
                    policy = policy,
                    audit = auditLog,
                    onIpUsed = { cidr ->
                        ControlBus.firewallConfig.value = firewallStore.markIpUsed(cidr)
                    },
                    onAppUsed = { packageName ->
                        ControlBus.firewallConfig.value = firewallStore.markAppUsed(packageName)
                    },
                    upstream = { port, cnxn -> DaemonTunnel.open(this, port, cnxn) },
                    knownKeys = {
                        DeveloperKey.list(prefs.developerKeysJson).map {
                            KnownKey(it.fingerprint, it.key.toPublicKey())
                        }
                    },
                    peerRouted = { peer -> VpnDetector.routesPeer(this, peer) },
                    onState = { state ->
                        ControlBus.update { current ->
                            current.copy(firewall = current.firewall.withRelayState(state))
                        }
                    },
                )
                try {
                    proxy.start(vpnIp, prefs.vpnPort, prefs.loopbackPort)
                    firewallProxy = proxy
                    true
                } catch (e: BindException) {
                    // A bind failure is not always an occupied port: a stale VPN
                    // address fails with "Cannot assign requested address", and
                    // ten retries only delay the real message.
                    if (e.message.orEmpty().contains("assign", ignoreCase = true)) {
                        throw IOException(
                            "The VPN address $vpnIp is no longer assigned. " +
                                "Connect the VPN and press Start again.",
                            e,
                        )
                    }
                    last = e
                    false
                }
            }
            if (bound) {
                AppLog.info("firewall", "listening on $vpnIp:${prefs.vpnPort}")
                return
            }
            AppLog.warn(
                "firewall",
                "port ${prefs.vpnPort} is busy; retrying (${attempt + 1}/$BIND_ATTEMPTS)",
            )
            Thread.sleep(BIND_RETRY_MS)
        }
        throw IOException(
            "Port ${prefs.vpnPort} is still in use. Change the firewall port in " +
                "Settings, or close Android\'s legacy listener with `adb usb`.",
            last,
        )
    }

    /** Rebinds the relay without letting a concurrent stop or setup interleave. */
    private fun rebindFirewall(vpnIp: String) {
        // One bind attempt under the lock; the retrying start sleeps outside it,
        // so a stop from onDestroy never waits for the retry loop.
        val proxy = synchronized(relayLock) { firewallProxy }
        if (proxy == null) {
            startFirewall(vpnIp)
            return
        }
        try {
            synchronized(relayLock) {
                if (firewallProxy === proxy) proxy.rebind(vpnIp)
            }
        } catch (e: BindException) {
            synchronized(relayLock) { if (firewallProxy === proxy) firewallProxy = null }
            startFirewall(vpnIp)
        }
    }

    private fun isVpnActive(): Boolean {
        if (BuildConfig.DEBUG && prefs.debugVpnIp.isNotBlank()) return true
        val manager = getSystemService(ConnectivityManager::class.java) ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    private fun waitForPortFree(host: String, port: Int, timeoutMs: Long = 10_000L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!SystemSettings.isPortOpen(host, port)) return
            Thread.sleep(200)
        }
        AppLog.warn("firewall", "port $port is still in use after $timeoutMs ms")
    }

    private fun stopFirewall(reason: String = "") {
        synchronized(relayLock) {
            // Invalidate every in-flight start, so a retry loop that is still
            // sleeping cannot bind after this stop.
            relayEpoch++
            firewallProxy?.stop()
            firewallProxy = null
            foregroundMonitor?.stop()
            hidePromptOverlay()
            prompts.values.forEach { it.cancel() }
            prompts.clear()
            ControlBus.pendingPrompt.value = null
            ControlBus.update {
                it.copy(
                    firewall = FirewallState(
                        enabled = firewallStore.load().enabled,
                        lastError = reason,
                    ),
                )
            }
        }
    }

    private fun hidePromptOverlay() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            promptOverlay?.hide()
        } else {
            Handler(Looper.getMainLooper()).post { promptOverlay?.hide() }
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
        if (prompts.size >= MAX_PROMPTS) {
            auditLog.record(
                AuditEvent(
                    event = "call_denied",
                    peer = request.peer,
                    service = request.call.service,
                    fields = request.call.fields,
                    rule = "prompt-cap",
                ),
            )
            return null
        }
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
        // The prompt shows the key the transport authenticated with, so the
        // user can tell a paired machine from an unauthenticated peer.
        val label = DeveloperKey.list(prefs.developerKeysJson)
            .firstOrNull { it.fingerprint == request.fingerprint }
            ?.label
            ?: ""
        val deferred = CompletableDeferred<PromptResponse?>()
        prompts[request.id] = deferred
        try {
            // One card at a time: a second unmatched call waits for the first
            // to be answered instead of silently replacing it. The deadline is
            // the request's, so waiting in the queue counts against it and the
            // card's countdown is the real one.
            val remaining = request.deadline - System.currentTimeMillis()
            val response = if (remaining <= 0) null else withTimeoutOrNull(remaining) {
                promptMutex.withLock {
                    showAndAwait(request.copy(label = label), deferred)
                }
            }
            if (response == null && !deferred.isCompleted) {
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
        } finally {
            // A timeout, a cancelled relay or a stop must release the slot and
            // the single pending-prompt slot. Nothing here may suspend: a
            // cancelled coroutine would throw before the cleanup and leave
            // pending() true, which suspends the app-allowlist grace timer.
            prompts.remove(request.id)
            if (ControlBus.pendingPrompt.value?.id == request.id) {
                ControlBus.pendingPrompt.value = null
            }
            ControlBus.update {
                it.copy(firewall = it.firewall.copy(pendingPrompts = prompts.size))
            }
            hidePromptOverlay()
            NotificationManagerCompat.from(this).cancel(Notifications.PROMPT_ID)
            firewallProxy?.onPromptFinished()
        }
    }

    private suspend fun showAndAwait(
        request: PromptRequest,
        deferred: CompletableDeferred<PromptResponse?>,
    ): PromptResponse? {
        ControlBus.pendingPrompt.value = request
        ControlBus.update { it.copy(firewall = it.firewall.copy(pendingPrompts = prompts.size)) }
        // The overlay is the primary prompt: it floats above the current app
        // without a notification tap. The notification and activity stay as the
        // fallback when the app cannot draw overlays.
        val shown = withContext(Dispatchers.Main) {
            promptOverlay?.show(request) == true
        }
        AppLog.info("firewall", "prompt ${request.call.service} overlay=$shown")
        if (!shown) {
            postPromptNotification(request)
            runCatching {
                startActivity(
                    Intent(this, CallPromptActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(CallPromptActivity.EXTRA_PROMPT_ID, request.id),
                )
            }
        }
        // The caller's deadline bounds the wait and audits the timeout.
        return deferred.await()
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
            readTlsPort = ::wirelessDebuggingPort,
            isPortOpen = { port -> AdbClient.offersTls(Endpoint.LOOPBACK, port) },
            pause = { Thread.sleep(WirelessDebuggingGuard.RETRY_DELAY_MS) },
        ).ensureOff()
    }

    /**
     * The port the system TLS listener last used. The property is coredomain-only
     * on most releases, so the port remembered from the last verified bootstrap
     * is the evidence; the guard then checks that nothing there still answers
     * with A_STLS, which an unrelated socket that reused the port does not.
     */
    private fun wirelessDebuggingPort(): Int? =
        SystemAdb.systemTlsPort() ?: prefs.lastTlsPort.takeIf {
            it > 0 && it != prefs.loopbackPort && it != prefs.vpnPort
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
                    "port5555=${if (status.port5555Open) "open" else "closed"}" +
                    " wireless=${status.wirelessPort}",
            )
        }
        ControlBus.update { it.copy(adbStatus = status) }
    }

    private fun daemonMaterial() = DaemonManager.Material(
        daemonKeys.publicKey,
        DeveloperKey.list(prefs.developerKeysJson),
        tlsKey,
    )

    /** The pinned TLS channel to the daemon; throws when the port holder is not ours. */
    private suspend fun daemonShell(): Shell = withContext(Dispatchers.IO) {
        DaemonChannel.shell(this@ControlService, prefs.loopbackPort)
    }

    private fun startWatchdogLoop() {
        watchdogJob?.cancel()
        AppLog.info("watchdog", "loop started")
        watchdogJob = scope.launch {
            while (true) {
                // A repeated failure backs off instead of retrying every
                // minute, and a failure that needs the user (a missing VPN, a
                // port change, a missing permission) stops the retry: fail()
                // sets the backoff to the cap and the user presses Try again.
                delay(if (watchdogBackoffMs > 0L) watchdogBackoffMs else 60_000L)
                when (ControlBus.state.value.phase) {
                    Phase.Running -> watchdog()
                    Phase.Error -> {
                        // D6: a transient failure keeps retrying at the capped
                        // backoff; one that needs the user waits for Try again.
                        if (lastFailureRetryable && prefs.userWantsRunning) {
                            scope.launch { setup() }
                        } else {
                            AppLog.info("watchdog", "setup failed; waiting for the user")
                            return@launch
                        }
                    }
                    else -> return@launch
                }
            }
        }
    }

    private suspend fun watchdog() {
        // Re-arm before every early return: a failed setup or a restart must not
        // lose the 15-minute wakeup that survives the process.
        scheduleWatchdog()
        val state = ControlBus.state.value
        if (state.phase != Phase.Running) return
        val alive = probeDaemon() == DaemonProbe.Verified
        if (!alive) {
            AppLog.warn("watchdog", "custom adb is down; restarting")
            ControlBus.update { it.copy(message = "The custom adb is down. Restarting…") }
            // setup() replaces the watchdog loop; run it outside this loop job so
            // cancelling the old loop cannot cancel the setup that replaces it.
            scope.launch { setup() }
            return
        }
        if (vpnAddressChanged()) {
            AppLog.info("watchdog", "vpn address changed; reconciling")
            networkChanged()
            return
        }
        // Any process running as shell can edit the daemon's key files. The
        // device copy is never trusted: a mismatch is rewritten, the daemon is
        // restarted so sessions of an injected key end, and the user is told.
        val intact = withContext(Dispatchers.IO) {
            runCatching {
                val shell = daemonShell()
                try {
                    daemon.keysIntact(shell, daemonMaterial())
                } finally {
                    shell.close()
                }
            }.getOrElse {
                AppLog.warn("watchdog", "could not check the daemon key files: ${it.message}")
                null
            }
        }
        // An unchecked tick is not a passed one: it neither resets the backoff
        // nor counts as ok, and the next tick checks again.
        if (intact == null) return
        if (!intact) {
            AppLog.error("watchdog", "the daemon key files were changed on the device; restoring them")
            ControlBus.update {
                it.copy(message = "The custom adb's key files were changed on the device. Restored them and restarted.")
            }
            scope.launch { setup(forceRestart = true) }
            return
        }
        // D6: every tick keeps D5, not only the first setup.
        try {
            ensureWifiDebuggingOff()
        } catch (e: IOException) {
            AppLog.error("watchdog", "wireless debugging guard failed: ${e.message}")
            fail(e.message ?: "System wireless debugging is still on.")
            return
        }
        if (!SystemSettings.ensureNoAdbConnectionTimeout(this)) {
            AppLog.warn("watchdog", "adb_allowed_connection_time=0 was not accepted")
        }
        // The relay is only touched under the setup mutex and only while the user
        // wants adb running, so a Stop that ran meanwhile cannot be undone (I6, I11).
        setupMutex.withLock { if (prefs.userWantsRunning) watchdogRelay() }
        resetBackoff()
        refreshAdbStatus()
        AppLog.info("watchdog", "ok")
    }

    private suspend fun watchdogRelay() {
        val firewall = firewallStore.load()
        if (firewall.enabled) {
            val vpnIp = withContext(Dispatchers.IO) {
                VpnDetector.currentVpnAddress(this@ControlService)
            }
            when {
                vpnIp == null -> {
                    if (firewallProxy != null) stopFirewall("VPN is down")
                    ControlBus.update {
                        it.copy(
                            vpnIp = null,
                            endpoint = Endpoint.display(null, prefs.vpnPort),
                            message = "VPN is down. The firewall cannot expose the custom adb.",
                            firewall = FirewallState(enabled = true, lastError = "VPN is down"),
                        )
                    }
                }
                !isVpnActive() -> {
                    // The VPN is up but this app is not in it, so the relay
                    // cannot receive inbound packets. Do not start it: the fix
                    // is a VPN setting, not a retry.
                    if (firewallProxy != null) {
                        stopFirewall("ADB Anywhere is excluded from the VPN")
                    }
                    ControlBus.update {
                        it.copy(
                            vpnIp = vpnIp,
                            endpoint = Endpoint.display(vpnIp, prefs.vpnPort),
                            message = "ADB Anywhere is excluded from the VPN.",
                            firewall = FirewallState(
                                enabled = true,
                                lastError = "Excluded from the VPN",
                            ),
                        )
                    }
                }
                firewallProxy?.isListening != true -> {
                    AppLog.warn("watchdog", "firewall relay is down; restarting")
                    runCatching { startFirewall(vpnIp) }.onFailure {
                        AppLog.error("watchdog", "firewall restart failed: ${it.message}")
                        fail("The firewall relay is not listening: ${it.message}")
                        return
                    }
                }
            }
        } else if (firewallProxy != null) {
            stopFirewall()
        }
    }

    /** A repeated failure backs off exponentially, capped at 15 minutes. */
    private fun nextBackoff() {
        watchdogBackoffMs = if (watchdogBackoffMs <= 0L) {
            60_000L
        } else {
            minOf(watchdogBackoffMs * 2, MAX_BACKOFF_MS)
        }
    }

    private fun resetBackoff() {
        watchdogBackoffMs = 0L
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
            setupMutex.withLock {
                if (prefs.userWantsRunning) networkChangedFirewall()
            }
        } else {
            setup()
        }
    }

    /** The firewall-on network change: the daemon stays, the relay follows the VPN. */
    private suspend fun networkChangedFirewall() {
        run {
            val vpnIp = withContext(Dispatchers.IO) {
                VpnDetector.currentVpnAddress(this@ControlService)
            }
            when {
                vpnIp == null -> {
                    // The VPN is down: remove the VPN listener and keep the
                    // loopback channel. A relay on the old address would
                    // keep accepting peers the VPN no longer routes.
                    stopFirewall("VPN is down")
                    ControlBus.update {
                        it.copy(
                            vpnIp = null,
                            endpoint = Endpoint.display(null, prefs.vpnPort),
                            message = "VPN is down. The firewall cannot expose the custom adb.",
                            firewall = FirewallState(enabled = true, lastError = "VPN is down"),
                        )
                    }
                }
                !isVpnActive() -> {
                    // The VPN is up but this app is not in it: the relay
                    // cannot receive inbound packets, so do not bind it.
                    stopFirewall("ADB Anywhere is excluded from the VPN")
                    ControlBus.update {
                        it.copy(
                            vpnIp = vpnIp,
                            endpoint = Endpoint.display(vpnIp, prefs.vpnPort),
                            message = "ADB Anywhere is excluded from the VPN.",
                            firewall = FirewallState(
                                enabled = true,
                                lastError = "Excluded from the VPN",
                            ),
                        )
                    }
                }
                else -> {
                    runCatching { rebindFirewall(vpnIp) }.onFailure {
                        fail(it.message ?: "Firewall rebind failed")
                    }
                    ControlBus.update {
                        it.copy(
                            vpnIp = vpnIp,
                            endpoint = Endpoint.display(vpnIp, prefs.vpnPort),
                            message = "Firewall is on. Developer machines connect through " +
                                "$vpnIp:${prefs.vpnPort}.",
                        )
                    }
                }
            }
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
    private fun stopEverything(startId: Int) {
        AppLog.info("service", "stopping adb and the service")
        scope.launch {
            setupMutex.withLock {
                // A Start that arrived before this stop took the mutex wins: its
                // setup already ran or will run, and must keep its daemon.
                if (prefs.userWantsRunning) {
                    AppLog.info("service", "stop superseded by a newer start")
                    return@withLock
                }
                stopFirewall("Stopped")
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
                // The opt-in D5 ownership turns the system adb off on Stop.
                // Off is the default: Stop leaves Android's paired key and the
                // USB recovery path alone. Stop never writes ADB_ENABLED unless
                // the user turned this on.  A newer Start that arrived while
                // the stop was running wins: skip the cleanup and let its setup
                // re-promote the service and the daemon.
                // The daemon kills itself through its own shell, so the socket can
                // take a moment to close. Give it the same grace as stop().
                val stillListening = daemonListening()
                var superseded = false
                synchronized(lifecycleLock) {
                    if (prefs.userWantsRunning) {
                        superseded = true
                    } else {
                        if (prefs.turnSystemAdbOffOnStop) {
                            AppLog.info("service", "turning the system adb off")
                            val adb = SystemSettings.setAdbEnabled(this@ControlService, false)
                            val wifi = SystemSettings.setAdbWifiEnabled(this@ControlService, false)
                            if (!adb || !wifi) {
                                AppLog.warn("service", "could not turn the system adb off")
                            }
                        }
                        cancelWatchdog()
                        watchdogJob?.cancel()
                        // The notification collector must not re-post the
                        // stopped state after the foreground notification is
                        // removed below.
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
                        ServiceCompat.stopForeground(
                            this@ControlService,
                            ServiceCompat.STOP_FOREGROUND_REMOVE,
                        )
                        foregroundStarted = false
                        // startId scopes the stop to this request: a Start that
                        // arrived while the stop was running keeps the service
                        // alive and its setup re-promotes it.
                        stopSelf(startId)
                    }
                }
                if (superseded) {
                    AppLog.info("service", "stop superseded by a newer start")
                    return@withLock
                }
                refreshAdbStatus()
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

    private fun fail(message: String, retryable: Boolean = true) {
        AppLog.error("setup", message)
        prefs.lastError = message
        // A failure that needs the user (a missing VPN, a port change, a
        // missing permission) must not retry on a timer: the watchdog retries
        // only a transient failure, with a backoff capped at 15 minutes.
        if (retryable) nextBackoff() else watchdogBackoffMs = MAX_BACKOFF_MS
        lastFailureRetryable = retryable
        prefs.failureNeedsUser = !retryable
        // A failure must not lose the 15-minute wakeup: the watchdog loop may
        // be gone with the process, and an Error phase returns early.
        scheduleWatchdog()
        // A transient failure is retried in this process too, even when the
        // setup that failed never reached Running and started no loop.
        if (retryable && prefs.userWantsRunning && watchdogJob?.isActive != true) startWatchdogLoop()
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
        SetupStep("Connect to Android's adb"),
        SetupStep("Deploy the custom adb"),
        SetupStep("Verify the listeners"),
        SetupStep("Start the firewall relay"),
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

    private fun ensureNotificationCollector() {
        if (notificationJob?.isActive == true) return
        notificationJob = scope.launch {
            ControlBus.state.collect { state ->
                updateNotification(state)
            }
        }
    }

    /**
     * Re-promotes the service after a Stop removed its foreground notification.
     * The stop cleanup takes lifecycleLock and checks userWantsRunning under it,
     * so a Stop that runs after this start cannot undo the notification.
     */
    private fun promoteForeground() {
        synchronized(lifecycleLock) {
            ensureNotificationCollector()
            if (!foregroundStarted) startForeground(ControlBus.state.value)
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
        val firewallDown = state.firewallDown
        val title = if (warnings.isNotEmpty() || firewallDown) {
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
                add(firewallStatusLine(state.firewall))
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
            .setGroup(Notifications.GROUP_SERVICE)
            .setContentIntent(contentPendingIntent())
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, "Open", contentPendingIntent())
        if (state.phase == Phase.Running) {
            builder.addAction(0, "Stop", servicePendingIntent(ACTION_STOP))
        }
        if (state.phase == Phase.NeedsPairing) {
            builder.addAction(0, "Pair", contentPendingIntent())
        }
        return builder.build()
    }

    /** True once for the current pairing notification; see [Prefs.pairingArmedAt]. */
    private fun consumePairingReply(): Boolean {
        val armedAt = prefs.pairingArmedAt
        prefs.pairingArmedAt = 0L
        return SetupPolicy.pairingReplyAccepted(armedAt, System.currentTimeMillis())
    }

    /** One reply carries the code:port shown in Android's pairing dialog. */
    private fun postPairingNotification(error: String = "") {
        prefs.pairingArmedAt = System.currentTimeMillis()
        val text = buildString {
            if (error.isNotEmpty()) append(error).append("\n\n")
            append("Open Settings → Developer options → Wireless debugging → Pair device ")
            append("with pairing code. Keep that dialog open, then reply with code:port.")
        }
        val input = RemoteInput.Builder(EXTRA_PAIR_INPUT)
            .setLabel("Pairing code:port")
            .build()
        val action = NotificationCompat.Action.Builder(
            android.R.drawable.ic_dialog_info,
            "Enter code:port",
            pairReplyPendingIntent(),
        ).addRemoteInput(input).build()
        postPairing("Pair ADB Anywhere", text, action)
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
            .setOnlyAlertOnce(true)
            .setGroup(Notifications.GROUP_PAIRING)
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

        // §7 USB: a cable change can bring the system adbd back to TCP; re-read
        // its state so the 5555 warning appears without waiting for the tick.
        val usb = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                AppLog.info("usb", "usb state changed")
                scope.launch { refreshAdbStatus() }
            }
        }
        usbReceiver = usb
        runCatching {
            ContextCompat.registerReceiver(
                this,
                usb,
                android.content.IntentFilter(USB_STATE),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
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
        const val ACTION_RECHECK = "cz.slush.adbanywhere.RECHECK"
        const val ACTION_PAIR = "cz.slush.adbanywhere.PAIR"
        private const val ACTION_PAIR_REPLY = "cz.slush.adbanywhere.PAIR_REPLY"
        const val ACTION_RESTART = "cz.slush.adbanywhere.RESTART"
        const val ACTION_REVOKE_KEYS = "cz.slush.adbanywhere.REVOKE_KEYS"
        const val ACTION_WATCHDOG = "cz.slush.adbanywhere.WATCHDOG"
        const val ACTION_LOGS = "cz.slush.adbanywhere.LOGS"
        const val ACTION_APPLY_KEYS = "cz.slush.adbanywhere.APPLY_KEYS"
        const val ACTION_APPLY_SETTINGS = "cz.slush.adbanywhere.APPLY_SETTINGS"
        const val ACTION_NOTIFY_PAIR = "cz.slush.adbanywhere.NOTIFY_PAIR"
        const val ACTION_PROMPT_RESPONSE = "cz.slush.adbanywhere.PROMPT_RESPONSE"
        const val ACTION_CLEAR_AUDIT = "cz.slush.adbanywhere.CLEAR_AUDIT"
        const val ACTION_APPLY_FIREWALL = "cz.slush.adbanywhere.APPLY_FIREWALL"
        const val ACTION_STOP = "cz.slush.adbanywhere.STOP"
        private const val NETWORK_SETTLE_MS = 1_500L
        private const val USB_STATE = "android.hardware.usb.action.USB_STATE"
        private const val SYSTEM_ADB_WAIT_MS = 30_000L
        private const val MDNS_RETRY_MS = 5_000L
        private const val MDNS_TIMEOUT_MS = 2_000L
        private const val MAX_PROMPTS = 8
        private const val BIND_ATTEMPTS = 10
        private const val BIND_RETRY_MS = 500L
        private const val MAX_BACKOFF_MS = 15 * 60 * 1000L
        const val EXTRA_CODE = "code"
        const val EXTRA_PAIR_PORT = "pair_port"
        const val EXTRA_PROMPT_ID = "prompt_id"
        const val EXTRA_PROMPT_DECISION = "prompt_decision"
        const val EXTRA_PROMPT_RULE = "prompt_rule"
        const val EXTRA_PAIR_INPUT = "pair_input"
        private const val EXTRA_FROM_NOTIFICATION = "from_notification"

        fun start(context: Context, action: String) {
            send(context, Intent(context, ControlService::class.java).setAction(action))
        }

        fun startPair(context: Context, code: String, port: String?, fromNotification: Boolean = false) {
            val intent = Intent(context, ControlService::class.java)
                .setAction(ACTION_PAIR)
                .putExtra(EXTRA_CODE, code)
                .putExtra(EXTRA_FROM_NOTIFICATION, fromNotification)
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
