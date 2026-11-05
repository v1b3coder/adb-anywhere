/*
 * CallPrompt - the approval screen for one pending ADB call. It never shows over
 * the keyguard, and it always resolves to Block when dismissed.
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cz.slush.adbanywhere.firewall.CallRule
import cz.slush.adbanywhere.firewall.PromptRequest

@Composable
fun CallPrompt(
    request: PromptRequest,
    onDecision: (String, CallRule?) -> Unit,
) {
    var custom by remember { mutableStateOf(false) }
    if (custom) {
        RuleEditorDialog(
            call = request.call,
            onDismiss = { custom = false },
            onSave = { rule -> onDecision("custom", rule) },
        )
        return
    }
    Dialog(
        onDismissRequest = { onDecision("block", null) },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
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
                Button(
                    onClick = { onDecision("once", null) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Allow once") }
                Button(
                    onClick = { onDecision("always", null) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Always allow this exact call") }
                OutlinedButton(
                    onClick = { custom = true },
                    modifier = Modifier.fillMaxWidth(),
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
}
