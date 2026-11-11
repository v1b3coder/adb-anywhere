/*
 * CallPromptActivity - hosts one pending ADB call approval. It is not exported,
 * never shows over the keyguard, and finishes when the call is gone.
 */
package cz.slush.adbanywhere.ui

import android.os.Bundle
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cz.slush.adbanywhere.service.ControlBus
import cz.slush.adbanywhere.service.ControlService

class CallPromptActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(false)
        // While the prompt is visible no other app's overlay is drawn at all, so
        // nothing can cover the command text or the buttons (Android 12+).
        window?.setHideOverlayWindows(true)
        // A malicious overlay must not be able to forward a tap to the Allow
        // buttons through an obscured window.
        window?.decorView?.setFilterTouchesWhenObscured(true)
        setContent {
            AdbAnywhereTheme {
                PromptHost()
            }
        }
    }

    /** Drops a touch that passed through, or next to, another window. */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val obscured = MotionEvent.FLAG_WINDOW_IS_OBSCURED or MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED
        if (event.flags and obscured != 0) return false
        return super.dispatchTouchEvent(event)
    }

    @Composable
    private fun PromptHost() {
        val pending by ControlBus.pendingPrompt.collectAsStateWithLifecycle()
        val expected = remember { intent.getStringExtra(EXTRA_PROMPT_ID) }
        val request = pending
        if (request == null || (expected != null && expected != request.id)) {
            LaunchedEffect(Unit) { finish() }
            return
        }
        CallPrompt(
            request,
            onDecision = { decision, rule ->
                ControlService.startPromptResponse(
                    this,
                    request.id,
                    decision,
                    rule?.toJson()?.toString(),
                )
                finish()
            },
        )
    }

    companion object {
        const val EXTRA_PROMPT_ID = "prompt_id"
    }
}
