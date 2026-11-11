/*
 * CallPrompt - the approval card for one pending ADB call. It is either a dialog
 * in CallPromptActivity or an inline card hosted by the floating overlay. It
 * never shows over the keyguard and always resolves to Block when dismissed.
 *
 * Only the activity can allow a call (docs/DESIGN.md §9). The activity hides
 * every non-system overlay while it is visible, so another app cannot cover the
 * command text or forward a tap. The floating card is itself an app overlay and
 * cannot hide other overlays, so it offers only Review (which opens the
 * activity) and Block: a covered card can at worst deny a call.
 */
package cz.slush.adbanywhere.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cz.slush.adbanywhere.firewall.CallRule
import cz.slush.adbanywhere.firewall.PromptRequest
import kotlinx.coroutines.delay

@Composable
fun CallPrompt(
    request: PromptRequest,
    onDecision: (String, CallRule?) -> Unit,
    inline: Boolean = false,
) {
    var custom by remember { mutableStateOf(false) }
    // The card shows how long the call is still held, and the relay denies it
    // when the countdown reaches zero.
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(request.deadline) {
        while (System.currentTimeMillis() < request.deadline) {
            now = System.currentTimeMillis()
            delay(1_000L)
        }
        now = System.currentTimeMillis()
    }
    val remaining = ((request.deadline - now) / 1000L).coerceAtLeast(0L)
    // A truncated service string cannot be pinned exactly, so "Always allow"
    // would create a rule that matches more calls than the one shown.
    val canSaveRule = !request.call.truncated
    if (custom && !inline) {
        RuleEditorDialog(
            call = request.call,
            onDismiss = { custom = false },
            onSave = { rule -> onDecision("custom", rule) },
        )
        return
    }
    val card: @Composable () -> Unit = {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(if (inline) 16.dp else 24.dp),
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "ADB call needs approval",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    request.call.service,
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    request.call.raw,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "from ${request.peer}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (request.label.isNotBlank()) {
                        "Key: ${request.label} (${request.fingerprint.take(16)})"
                    } else if (request.fingerprint.isNotBlank()) {
                        "Key: ${request.fingerprint.take(16)} (not paired)"
                    } else {
                        "No key offered yet: this peer has not authenticated."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Denies in ${remaining}s",
                    style = MaterialTheme.typography.labelLarge,
                )
                if (inline) {
                    Button(
                        onClick = { onDecision("review", null) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Review") }
                    OutlinedButton(
                        onClick = { onDecision("block", null) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Block") }
                    return@Column
                }
                Button(
                    onClick = { onDecision("once", null) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Allow once") }
                Button(
                    onClick = { onDecision("always", null) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = canSaveRule,
                ) { Text("Always allow this exact call") }
                OutlinedButton(
                    onClick = { custom = true },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = canSaveRule,
                ) { Text("Custom filter") }
                OutlinedButton(
                    onClick = { onDecision("block", null) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Block") }
                TextButton(
                    onClick = { onDecision("block", null) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Dismiss denies the call") }
            }
        }
    }
    if (inline) {
        card()
    } else {
        Dialog(
            onDismissRequest = { onDecision("block", null) },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            RefuseObscuredTouches()
            card()
        }
    }
}

/**
 * A Compose Dialog is its own window, so the activity's decor-view filter does
 * not cover it. This sets the filter on the dialog's own views: a touch that
 * passed through another app's window is dropped.
 */
@Composable
fun RefuseObscuredTouches() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.filterTouchesWhenObscured = true
        view.rootView?.filterTouchesWhenObscured = true
        onDispose { }
    }
}
