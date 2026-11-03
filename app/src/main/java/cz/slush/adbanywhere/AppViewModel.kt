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

    fun start() = ControlService.start(getApplication(), ControlService.ACTION_START)

    fun pair(code: String) = ControlService.start(getApplication(), ControlService.ACTION_PAIR, code)

    fun restart() = ControlService.start(getApplication(), ControlService.ACTION_RESTART)

    fun refreshLogs() = ControlService.start(getApplication(), ControlService.ACTION_LOGS)

    fun postPairingNotification() =
        ControlService.start(getApplication(), ControlService.ACTION_NOTIFY_PAIR)

    fun stop() = ControlService.start(getApplication(), ControlService.ACTION_STOP)

    fun addDeveloperKey(text: String, label: String): String? {
        val parsed = AndroidPubKey.parse(text) ?: return "That does not look like an ADB public key."
        if (parsed.bits != 2048) return "Only RSA-2048 keys are supported."
        val current = _keys.value
        if (current.any { it.fingerprint == parsed.fingerprint }) return "That key is already paired."
        val key = DeveloperKey(parsed, label.ifBlank { parsed.label.ifBlank { "developer" } })
        val updated = current + key
        prefs.developerKeysJson = DeveloperKey.store(updated)
        _keys.value = updated
        ControlService.start(getApplication(), ControlService.ACTION_APPLY_KEYS)
        return null
    }

    fun removeDeveloperKey(fingerprint: String) {
        val updated = _keys.value.filterNot { it.fingerprint == fingerprint }
        prefs.developerKeysJson = DeveloperKey.store(updated)
        _keys.value = updated
        ControlService.start(getApplication(), ControlService.ACTION_APPLY_KEYS)
    }

    fun setKeepSystemAdbOff(enabled: Boolean) {
        prefs.keepSystemAdbOff = enabled
        _keepSystemAdbOff.value = enabled
        ControlService.start(getApplication(), ControlService.ACTION_APPLY_SETTINGS)
    }

    fun setPorts(loopback: Int, vpn: Int): String? {
        if (loopback !in 1024..65535) return "The loopback port must be between 1024 and 65535."
        if (vpn !in 1024..65535) return "The VPN port must be between 1024 and 65535."
        if (loopback == vpn) return "The two ports must be different."
        prefs.loopbackPort = loopback
        prefs.vpnPort = vpn
        ControlService.start(getApplication(), ControlService.ACTION_APPLY_SETTINGS)
        return null
    }

    fun setTlsPort(port: Int): String? {
        if (port !in 1024..65535) return "The port must be between 1024 and 65535."
        prefs.lastTlsPort = port
        val intent = android.content.Intent(getApplication(), ControlService::class.java)
            .setAction(ControlService.ACTION_SET_TLS_PORT)
            .putExtra(ControlService.EXTRA_PORT, port)
        androidx.core.content.ContextCompat.startForegroundService(getApplication(), intent)
        return null
    }

    fun setRemoteDir(path: String) {
        if (path.isBlank() || !path.startsWith("/data/local/tmp/")) return
        prefs.remoteDir = path
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
        android.util.Log.i(
            "AdbAnywhere",
            "debug bootstrap host=$host port=$bootstrapPort dir=${prefs.remoteDir} " +
                "loop=${prefs.loopbackPort} vpn=${prefs.vpnPort}",
        )
        ControlService.start(getApplication(), ControlService.ACTION_START)
    }

    fun setBootstrap(host: String, port: Int) {
        if (host.isBlank() || port !in 1024..65535) return
        prefs.bootstrapHost = host
        prefs.bootstrapPort = port
        ControlService.start(getApplication(), ControlService.ACTION_START)
    }

    fun appKeyText(): String = keyStore.publicKeyB64

    fun runDebugShell(command: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val manager = cz.slush.adbanywhere.adb.AdbManager(getApplication(), keyStore)
            if (!manager.connectTo("127.0.0.1", prefs.loopbackPort)) {
                android.util.Log.i("AdbAnywhere", "debug shell connect failed")
                return@launch
            }
            try {
                val result = cz.slush.adbanywhere.adb.Shell(manager).exec(command)
                android.util.Log.i(
                    "AdbAnywhere",
                    "debug shell rc=${result.exitCode} out=${result.stdout.trim()} err=${result.stderr.trim()}",
                )
            } finally {
                manager.disconnectQuietly()
            }
        }
    }

    fun requestBatteryExemption(activity: Activity) {
        val intent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${activity.packageName}"),
        )
        runCatching { activity.startActivity(intent) }
    }

}
