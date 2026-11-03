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
        ControlService.start(this, ControlService.ACTION_START)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (!BuildConfig.DEBUG) return
        intent?.getStringExtra("pair")?.let(viewModel::pair)
        intent?.getStringExtra("addkey")?.let { key ->
            val error = viewModel.addDeveloperKey(key, intent.getStringExtra("addlabel") ?: "test")
            Log.i("AdbAnywhere", "debug addkey result=${error ?: "ok"}")
        }
    }
}
