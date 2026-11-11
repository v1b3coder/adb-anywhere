/*
 * MainActivity - Compose host. It starts the foreground control service. It
 * reads no intent extras: the activity is exported as the launcher, so any app
 * can start it. The test hooks live in the DUMP-protected DebugReceiver.
 */
package cz.slush.adbanywhere

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
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
            ControlService.start(this, ControlService.ACTION_RECHECK)
        } else {
            AppLog.info("app", "opened; adb is stopped by the user")
        }
    }
}
