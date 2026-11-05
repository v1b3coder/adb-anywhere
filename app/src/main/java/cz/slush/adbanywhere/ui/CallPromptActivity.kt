/*
 * CallPromptActivity - hosts one pending ADB call approval. It is not exported,
 * never shows over the keyguard, and finishes when the call is gone.
 */
package cz.slush.adbanywhere.ui

import android.os.Bundle
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
        setContent {
            AdbAnywhereTheme {
                PromptHost()
            }
        }
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
        CallPrompt(request) { decision, rule ->
            ControlService.startPromptResponse(
                this,
                request.id,
                decision,
                rule?.toJson()?.toString(),
            )
            finish()
        }
    }

    companion object {
        const val EXTRA_PROMPT_ID = "prompt_id"
    }
}
