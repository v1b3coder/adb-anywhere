/*
 * MainActivity - Compose host. It starts the foreground control service. It
 * reads no intent extras: the activity is exported as the launcher, so any app
 * can start it. The test hooks live in the DUMP-protected DebugReceiver.
 */
package cz.slush.adbanywhere

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
                // D25: an opt-in window flag, so the display timeout does not lock
                // the phone while this screen is watched. It lives on this window,
                // so it ends with it and needs no wake lock or permission.
                val keepScreenOn by viewModel.keepScreenOn.collectAsStateWithLifecycle()
                LaunchedEffect(keepScreenOn) {
                    if (keepScreenOn) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
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
