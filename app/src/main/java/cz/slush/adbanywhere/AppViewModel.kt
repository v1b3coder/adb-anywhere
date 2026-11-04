/*
 * AppViewModel - UI actions and the developer-key list. The service owns the
 * actual ADB work; this class only edits state and asks the service to apply it.
 */
package cz.slush.adbanywhere

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cz.slush.adbanywhere.data.AndroidPubKey
import cz.slush.adbanywhere.data.AppKeyStore
import cz.slush.adbanywhere.data.DeveloperKey
import cz.slush.adbanywhere.data.Prefs
import cz.slush.adbanywhere.service.AppLog
import cz.slush.adbanywhere.service.ControlBus
import cz.slush.adbanywhere.service.ControlService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = Prefs(application)
    private val keyStore = AppKeyStore(application)

    val state = ControlBus.state
    val logs = ControlBus.logs
    val appLog = AppLog.entries
    val diagnosticLog = AppLog.enabled

    /** The user's last explicit start or stop. Opening the app never overrides it. */
    val userWantsRunning: Boolean get() = prefs.userWantsRunning

    private val _keys = MutableStateFlow(DeveloperKey.list(prefs.developerKeysJson))
    val keys: StateFlow<List<DeveloperKey>> = _keys

    private val _appFingerprint = MutableStateFlow("")
    val appFingerprint: StateFlow<String> = _appFingerprint

    private val _keepSystemAdbOff = MutableStateFlow(prefs.keepSystemAdbOff)
    val keepSystemAdbOff: StateFlow<Boolean> = _keepSystemAdbOff

    init {
        viewModelScope.launch(Dispatchers.IO) {
            _appFingerprint.value = keyStore.publicKey.fingerprint
        }
    }

    fun start() {
        AppLog.info("ui", "start requested")
        ControlService.start(getApplication(), ControlService.ACTION_START)
    }

    fun pair(code: String) {
        AppLog.info("ui", "pair requested")
        ControlService.start(getApplication(), ControlService.ACTION_PAIR, code)
    }

    fun restart() {
        AppLog.info("ui", "restart requested")
        ControlService.start(getApplication(), ControlService.ACTION_RESTART)
    }

    fun refreshLogs() {
        AppLog.info("ui", "adb log refresh requested")
        ControlService.start(getApplication(), ControlService.ACTION_LOGS)
    }

    fun postPairingNotification() {
        AppLog.info("ui", "pairing notification requested")
        ControlService.start(getApplication(), ControlService.ACTION_NOTIFY_PAIR)
    }

    fun stop() {
        AppLog.info("ui", "stop requested")
        ControlService.start(getApplication(), ControlService.ACTION_STOP)
    }

    /** Turns the app's own event log on or off. Off is the default. */
    fun setDiagnosticLog(enabled: Boolean) {
        prefs.diagnosticLog = enabled
        AppLog.setEnabled(enabled)
    }

    /**
     * Applies a change to the daemon only while the user wants it running. The
     * preference is already stored, so a stopped app applies it on the next start.
     */
    private fun applyIfRunning(action: String) {
        if (prefs.userWantsRunning) {
            ControlService.start(getApplication(), action)
        } else {
            AppLog.info("ui", "change stored; adb is stopped")
        }
    }

    fun addDeveloperKey(text: String, label: String): String? {
        val parsed = AndroidPubKey.parse(text) ?: return "That does not look like an ADB public key."
        if (parsed.bits != 2048) return "Only RSA-2048 keys are supported."
        val current = _keys.value
        if (current.any { it.fingerprint == parsed.fingerprint }) return "That key is already paired."
        val key = DeveloperKey(parsed, label.ifBlank { parsed.label.ifBlank { "developer" } })
        val updated = current + key
        prefs.developerKeysJson = DeveloperKey.store(updated)
        _keys.value = updated
        AppLog.info("keys", "added ${key.label} ${parsed.fingerprint.take(16)}")
        applyIfRunning(ControlService.ACTION_APPLY_KEYS)
        return null
    }

    /** Renames a paired machine. The label is only a comment in the key file. */
    fun renameDeveloperKey(fingerprint: String, label: String) {
        val clean = DeveloperKey.sanitize(label)
        val updated = _keys.value.map {
            if (it.fingerprint == fingerprint) it.copy(label = clean) else it
        }
        prefs.developerKeysJson = DeveloperKey.store(updated)
        _keys.value = updated
        AppLog.info("keys", "renamed ${fingerprint.take(16)} to $clean")
        applyIfRunning(ControlService.ACTION_APPLY_KEYS)
    }

    fun removeDeveloperKey(fingerprint: String) {
        val updated = _keys.value.filterNot { it.fingerprint == fingerprint }
        prefs.developerKeysJson = DeveloperKey.store(updated)
        _keys.value = updated
        AppLog.info("keys", "removed ${fingerprint.take(16)}; restarting to close sessions")
        // Revocation must close live sessions, so the service restarts the daemon
        // instead of only rewriting the key file (DESIGN.md §8).
        applyIfRunning(ControlService.ACTION_REVOKE_KEYS)
    }

    fun setKeepSystemAdbOff(enabled: Boolean) {
        prefs.keepSystemAdbOff = enabled
        _keepSystemAdbOff.value = enabled
        AppLog.info("settings", "keep system adb off=$enabled")
        applyIfRunning(ControlService.ACTION_APPLY_SETTINGS)
    }

    fun setPorts(loopback: Int, vpn: Int): String? {
        if (loopback !in 1024..65535) return "The loopback port must be between 1024 and 65535."
        if (vpn !in 1024..65535) return "The VPN port must be between 1024 and 65535."
        if (loopback == vpn) return "The two ports must be different."
        prefs.loopbackPort = loopback
        prefs.vpnPort = vpn
        AppLog.info("settings", "ports loopback=$loopback vpn=$vpn")
        applyIfRunning(ControlService.ACTION_APPLY_SETTINGS)
        return null
    }

    fun rememberTlsPort(port: Int) {
        if (port in 1024..65535) prefs.lastTlsPort = port
    }

    fun setTlsPort(port: Int): String? {
        if (port !in 1024..65535) return "The port must be between 1024 and 65535."
        prefs.lastTlsPort = port
        AppLog.info("settings", "system adb TLS port=$port")
        if (!prefs.userWantsRunning) return null
        val intent = android.content.Intent(getApplication(), ControlService::class.java)
            .setAction(ControlService.ACTION_SET_TLS_PORT)
            .putExtra(ControlService.EXTRA_PORT, port)
        androidx.core.content.ContextCompat.startForegroundService(getApplication(), intent)
        return null
    }

    fun setRemoteDir(path: String) {
        if (path.isBlank() || !path.startsWith("/data/local/tmp/")) return
        prefs.remoteDir = path
        AppLog.info("settings", "remote dir=$path")
    }

    fun setDebugBootstrap(
        host: String,
        bootstrapPort: Int,
        remoteDir: String,
        loopbackPort: Int,
        vpnPort: Int,
    ) {
        if (host.isBlank() || bootstrapPort !in 1024..65535) return
        if (remoteDir.startsWith("/data/local/tmp/")) prefs.remoteDir = remoteDir
        if (loopbackPort in 1024..65535 && vpnPort in 1024..65535 &&
            loopbackPort != vpnPort
        ) {
            prefs.loopbackPort = loopbackPort
            prefs.vpnPort = vpnPort
        }
        prefs.bootstrapHost = host
        prefs.bootstrapPort = bootstrapPort
        AppLog.info(
            "debug",
            "bootstrap host=$host port=$bootstrapPort dir=${prefs.remoteDir} " +
                "loop=${prefs.loopbackPort} vpn=${prefs.vpnPort}",
        )
        ControlService.start(getApplication(), ControlService.ACTION_START)
    }

    fun setBootstrap(host: String, port: Int) {
        if (host.isBlank() || port !in 1024..65535) return
        prefs.bootstrapHost = host
        prefs.bootstrapPort = port
        AppLog.info("debug", "bootstrap host=$host port=$port")
        ControlService.start(getApplication(), ControlService.ACTION_START)
    }

    fun appKeyText(): String = keyStore.publicKeyB64

    fun runDebugShell(command: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val manager = cz.slush.adbanywhere.adb.AdbManager(getApplication(), keyStore)
            if (!manager.connectTo("127.0.0.1", prefs.loopbackPort)) {
                AppLog.error("debug", "shell connect to 127.0.0.1:${prefs.loopbackPort} failed")
                return@launch
            }
            try {
                val result = cz.slush.adbanywhere.adb.Shell(manager).exec(command)
                AppLog.info(
                    "debug",
                    "shell rc=${result.exitCode} out=${result.stdout.trim()} err=${result.stderr.trim()}",
                )
            } finally {
                manager.disconnectQuietly()
            }
        }
    }

    fun requestBatteryExemption(activity: Activity) {
        AppLog.info("ui", "battery exemption requested")
        val intent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${activity.packageName}"),
        )
        runCatching { activity.startActivity(intent) }
    }

}
