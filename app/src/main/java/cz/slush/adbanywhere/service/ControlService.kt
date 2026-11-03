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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import cz.slush.adbanywhere.MainActivity
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
        scope.launch {
            ControlBus.state.collect { state ->
                updateNotification(state)
            }
        }
        scope.launch {
            ControlBus.update { it.copy(appFingerprint = keys.publicKey.fingerprint) }
        }
        registerNetwork()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START, ACTION_APPLY_KEYS, ACTION_APPLY_SETTINGS -> {
                scope.launch { setup() }
            }
            ACTION_RESTART -> {
                scope.launch { setup(forceRestart = true) }
            }
            ACTION_PAIR -> {
                val code = intent.getStringExtra(EXTRA_CODE)
                if (code != null) scope.launch { pair(code) }
            }
            ACTION_WATCHDOG -> scope.launch { watchdog() }
            ACTION_NETWORK -> scope.launch { networkChanged() }
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

    private suspend fun setup(forceRestart: Boolean = false) {
        setupMutex.withLock {
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
                Log.i("AdbAnywhere", "setup daemonAlive=$daemonAlive dir=${prefs.remoteDir}")
                if (!daemonAlive && prefs.setupComplete &&
                    ContextCompat.checkSelfPermission(
                        this,
                        android.Manifest.permission.WRITE_SECURE_SETTINGS,
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    // The one-time grant persists, so the app can re-open the
                    // bootstrap path after a reboot without touching adb.
                    if (prefs.keepSystemAdbOff) SystemSettings.setAdbEnabled(this, true)
                    if (SystemSettings.isWifiConnected(this)) {
                        SystemSettings.setAdbWifiEnabled(this, true)
                    }
                    delay(900)
                }
                val systemAlive = if (daemonAlive) false
                else withContext(Dispatchers.IO) { system.ensureConnected() }
                Log.i("AdbAnywhere", "setup systemAlive=$systemAlive")
                if (!daemonAlive && !systemAlive) {
                    if (!prefs.paired) {
                        needsPairing()
                    } else {
                        fail(
                            "Android's wireless debugging is not reachable and the daemon is down. " +
                                "Connect to Wi-Fi once, or open Settings → Developer options → " +
                                "Wireless debugging and try again.",
                        )
                    }
                    return@withLock
                }
                step(
                    0,
                    StepStatus.Done,
                    if (daemonAlive) "Daemon is already reachable" else "Connected with the app key",
                )
                if (!daemonAlive && !system.grantSelfPermission()) {
                    fail(
                        "WRITE_SECURE_SETTINGS could not be granted. Grant it once with " +
                            "`adb shell pm grant ${packageName} android.permission.WRITE_SECURE_SETTINGS`.",
                    )
                    return@withLock
                }

                step(1, StepStatus.Active, "Deploying the patched adbd")
                val shell = if (daemonAlive) {
                    withContext(Dispatchers.IO) { daemonShell() }
                } else {
                    system.shell
                }
                val vpnChanged = vpnIp != previous.vpnIp
                val portsChanged = prefs.loopbackPort != previous.loopbackPort ||
                    prefs.vpnPort != previous.vpnPort
                val restart = forceRestart || !daemonAlive || vpnChanged || portsChanged
                if (daemonAlive && !restart) {
                    // The daemon reloads keys and policy on the next auth.
                    daemon.writeKeysAndPolicy(shell, appKey, developers)
                } else if (daemonAlive) {
                    daemon.restartDetached(
                        shell,
                        appKey,
                        developers,
                        vpnIp,
                        prefs.loopbackPort,
                        prefs.vpnPort,
                    )
                } else {
                    daemon.deployAndStart(
                        shell,
                        appKey,
                        developers,
                        vpnIp,
                        prefs.loopbackPort,
                        prefs.vpnPort,
                    )
                }
                step(1, StepStatus.Done, "Daemon running")

                step(2, StepStatus.Active, "Verifying loopback and VPN listeners")
                verifyDaemon(vpnIp)
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
                ControlBus.update {
                    it.copy(
                        phase = Phase.Running,
                        endpoint = Endpoint.display(vpnIp, prefs.vpnPort),
                        vpnIp = vpnIp,
                        message = if (vpnIp == null) {
                            "VPN is down. Only the app can reach the daemon; connect the VPN to expose it."
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
                Log.i("AdbAnywhere", "setup failed: ${e.message}", e)
                fail(e.message ?: "Setup failed")
            }
        }
    }

    private suspend fun pair(codeText: String) {
        val code = PairingCode.parse(codeText)
        if (code == null) {
            fail("Enter the pairing value as code:port, for example 123456:37123.")
            return
        }
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
            fail(
                "Pairing failed. Check that the code and port are current and that the pairing " +
                    "dialog is still open, then try again.",
            )
            return
        }
        prefs.paired = true
        ControlBus.update {
            it.copy(steps = listOf(SetupStep("Pair with Android's wireless debugging", StepStatus.Done, "Paired")))
        }
        setup()
    }

    private fun needsPairing() {
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
                "The daemon did not accept the app key on 127.0.0.1:${prefs.loopbackPort}.",
            )
        }
        try {
            val shell = Shell(loopback)
            val identity = shell.exec("id")
            if (!identity.stdout.contains("uid=2000") && !identity.stdout.contains("uid=0")) {
                throw IOException("The daemon returned an unexpected identity: ${identity.stdout.trim()}")
            }
        } finally {
            loopback.disconnectQuietly()
        }

        if (vpnIp != null) {
            val vpn = AdbManager(this@ControlService, keys)
            if (!vpn.connectTo(vpnIp, prefs.vpnPort)) {
                throw IOException("The daemon did not bind $vpnIp:${prefs.vpnPort}.")
            }
            try {
                val identity = Shell(vpn).exec("id")
                if (!identity.stdout.contains("uid=")) {
                    throw IOException("The daemon on $vpnIp did not answer.")
                }
            } finally {
                vpn.disconnectQuietly()
            }
        }
    }

    private suspend fun harden() = withContext(Dispatchers.IO) {
        val context = this@ControlService
        if (!SystemSettings.setAdbWifiEnabled(context, false)) {
            throw IOException("Could not turn system wireless debugging off. Check WRITE_SECURE_SETTINGS.")
        }
        if (prefs.keepSystemAdbOff) {
            if (prefs.originalAdbEnabled.isEmpty()) {
                prefs.originalAdbEnabled = SystemSettings.adbEnabled(context).toString()
            }
            if (!SystemSettings.setAdbEnabled(context, false)) {
                throw IOException("Could not turn the system adbd off. Check WRITE_SECURE_SETTINGS.")
            }
            if (SystemSettings.adbEnabled(context) != 0) {
                throw IOException("ADB_ENABLED=0 did not take effect. No random-port redirect is attempted.")
            }
        } else {
            if (prefs.originalAdbEnabled == "1" && SystemSettings.adbEnabled(context) != 1) {
                SystemSettings.setAdbEnabled(context, true)
                prefs.originalAdbEnabled = ""
            }
            val open = withContext(Dispatchers.IO) {
                runCatching { java.net.Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", 5555), 300) }; true }
                    .getOrDefault(false)
            }
            if (open) {
                ControlBus.update {
                    it.copy(
                        message = "Warning: the system adbd still listens on 127.0.0.1:5555. " +
                            "Enable \"Keep system adb off\" to close it.",
                    )
                }
            }
        }
    }

    private suspend fun daemonShell(): Shell = withContext(Dispatchers.IO) {
        val manager = AdbManager(this@ControlService, keys)
        if (!manager.connectTo("127.0.0.1", prefs.loopbackPort)) {
            throw IOException("The daemon is not reachable on 127.0.0.1:${prefs.loopbackPort}.")
        }
        Shell(manager)
    }

    private fun startWatchdogLoop() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (true) {
                delay(60_000)
                when (ControlBus.state.value.phase) {
                    Phase.Running -> watchdog()
                    Phase.Error -> if (prefs.setupComplete) setup()
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
            ControlBus.update { it.copy(message = "The daemon is down. Restarting…") }
            setup()
            return
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
                val still = withContext(Dispatchers.IO) {
                    runCatching {
                        Socket().use { it.connect(InetSocketAddress("127.0.0.1", 5555), 300) }
                        true
                    }.getOrDefault(false)
                }
                if (still) {
                    fail("ADB_ENABLED=0 did not stop the system adbd. No random-port redirect is attempted.")
                }
            }
        }
        scheduleWatchdog()
    }

    private suspend fun networkChanged() {
        val state = ControlBus.state.value
        if (state.phase != Phase.Running) return
        val vpnIp = withContext(Dispatchers.IO) { VpnDetector.currentVpnAddress(this@ControlService) }
        if (vpnIp != state.vpnIp) {
            prefs.lastVpnIp = vpnIp ?: ""
            setup()
        }
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
                Log.i("AdbAnywhere", "logs failed: ${it.message}")
                prefs.lastError.ifBlank { "No logs yet." }
            }
        }
        Log.i("AdbAnywhere", "logs length=${text.length}")
        ControlBus.logs.value = text
    }

    private fun stopEverything() {
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    val shell = daemonShell()
                    try {
                        daemon.stop(shell)
                    } finally {
                        shell.close()
                    }
                }
            }
            cancelWatchdog()
            watchdogJob?.cancel()
            ControlBus.update {
                it.copy(phase = Phase.Stopped, endpoint = "", message = "Stopped", error = "")
            }
            ServiceCompat.stopForeground(this@ControlService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun fail(message: String) {
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
        SetupStep("Deploy the patched adbd"),
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
        }
    }

    private fun updateNotification(state: ControlState) {
        if (!foregroundStarted) {
            startForeground(state)
            return
        }
        runCatching {
            NotificationManagerCompat.from(this).notify(
                Notifications.SERVICE_ID,
                buildServiceNotification(state),
            )
        }
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
            .setSmallIcon(android.R.drawable.ic_dialog_info)
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
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
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
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Pair ADB Anywhere")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(contentPendingIntent())
            .addAction(action)
            .build()
        runCatching { NotificationManagerCompat.from(this).notify(Notifications.PAIRING_ID, notification) }
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
            override fun onAvailable(network: Network) = notifyNetworkChange()
            override fun onLost(network: Network) = notifyNetworkChange()
            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) =
                notifyNetworkChange()
        }
        networkCallback = callback
        runCatching { manager.registerNetworkCallback(vpnRequest, callback) }

        val default = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = notifyNetworkChange()
            override fun onLost(network: Network) = notifyNetworkChange()
        }
        defaultCallback = default
        runCatching { manager.registerDefaultNetworkCallback(default) }
    }

    private fun notifyNetworkChange() {
        runCatching { startService(Intent(this, ControlService::class.java).setAction(ACTION_NETWORK)) }
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
        const val ACTION_WATCHDOG = "cz.slush.adbanywhere.WATCHDOG"
        const val ACTION_NETWORK = "cz.slush.adbanywhere.NETWORK"
        const val ACTION_LOGS = "cz.slush.adbanywhere.LOGS"
        const val ACTION_APPLY_KEYS = "cz.slush.adbanywhere.APPLY_KEYS"
        const val ACTION_APPLY_SETTINGS = "cz.slush.adbanywhere.APPLY_SETTINGS"
        const val ACTION_NOTIFY_PAIR = "cz.slush.adbanywhere.NOTIFY_PAIR"
        const val ACTION_SET_TLS_PORT = "cz.slush.adbanywhere.SET_TLS_PORT"
        const val ACTION_STOP = "cz.slush.adbanywhere.STOP"
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
