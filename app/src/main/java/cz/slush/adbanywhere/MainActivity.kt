/*
 * MainActivity - Compose host. It starts the foreground control service and
 * handles the debug-only pairing shortcut used by the end-to-end tests.
 */
package cz.slush.adbanywhere

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import cz.slush.adbanywhere.data.PairingCode
import cz.slush.adbanywhere.service.AppLog
import cz.slush.adbanywhere.service.ControlService
import cz.slush.adbanywhere.ui.AdbAnywhereTheme
import cz.slush.adbanywhere.ui.AppRoot

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AdbAnywhereTheme {
                AppRoot(viewModel)
            }
        }
        // The daemon only runs while the user wants it: opening the app re-checks
        // a running daemon but never overrides an explicit stop.
        if (viewModel.userWantsRunning) {
            AppLog.info("app", "opened; user wants running, re-checking")
            ControlService.start(this, ControlService.ACTION_START)
        } else {
            AppLog.info("app", "opened; adb is stopped by the user")
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (!BuildConfig.DEBUG) return
        intent?.getIntExtra("tlsPort", 0)?.takeIf { it > 0 }?.let(viewModel::rememberTlsPort)
        intent?.getStringExtra("pair")?.let { PairingCode.parse(it) }?.let(viewModel::pair)
        // The app only starts the daemon on an explicit start, so the harness
        // presses the same two buttons the user does.
        if (intent?.getBooleanExtra("start", false) == true) viewModel.start()
        if (intent?.getBooleanExtra("stop", false) == true) viewModel.stop()
        if (intent?.hasExtra("firewall") == true) {
            viewModel.setFirewallEnabled(intent.getBooleanExtra("firewall", false))
        }
        intent?.getStringExtra("firewallIp")?.let { ip ->
            Log.i("AdbAnywhere", "debug firewall ip result=${viewModel.addIp(ip) ?: "ok"}")
        }
        if (intent?.hasExtra("firewallIpAllowlist") == true) {
            viewModel.setIpAllowlistEnabled(intent.getBooleanExtra("firewallIpAllowlist", false))
        }
        intent?.getStringExtra("firewallApp")?.let(viewModel::addApp)
        if (intent?.hasExtra("firewallAppAllowlist") == true) {
            viewModel.setAppAllowlistEnabled(intent.getBooleanExtra("firewallAppAllowlist", false))
        }
        if (intent?.hasExtra("firewallCallPolicy") == true) {
            viewModel.setCallPolicyEnabled(intent.getBooleanExtra("firewallCallPolicy", false))
        }
        intent?.getStringExtra("addkey")?.let { key ->
            val error = viewModel.addDeveloperKey(key, intent.getStringExtra("addlabel") ?: "test")
            Log.i("AdbAnywhere", "debug addkey result=${error ?: "ok"}")
        }
        intent?.getStringExtra("shellcmd")?.let(viewModel::runDebugShell)
        intent?.getStringExtra("bootstrapHost")?.let { host ->
            viewModel.setDebugBootstrap(
                host = host,
                bootstrapPort = intent.getIntExtra("bootstrapPort", 0),
                remoteDir = intent.getStringExtra("remoteDir") ?: "",
                loopbackPort = intent.getIntExtra("loopbackPort", 0),
                vpnPort = intent.getIntExtra("vpnPort", 0),
            )
        }
        if (intent?.getBooleanExtra("logkey", false) == true) {
            Log.i("AdbAnywhere", "appkey=${viewModel.appKeyText()}")
        }
    }
}
