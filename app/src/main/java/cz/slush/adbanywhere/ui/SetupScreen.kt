/*
 * SetupScreen - the one-time pairing flow and setup progress.
 */
package cz.slush.adbanywhere.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import cz.slush.adbanywhere.service.ControlState
import cz.slush.adbanywhere.service.Phase

@Composable
fun SetupScreen(
    state: ControlState,
    onSetup: () -> Unit,
    onNotify: () -> Unit,
    onOpenDeveloperSettings: () -> Unit,
) {
    val busy = state.phase == Phase.Pairing || state.phase == Phase.Deploying

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            SectionCard("1. Pair with Android's wireless debugging") {
                Text(
                    "Android only lets this app deploy the custom adb after a one-time pairing. " +
                        "Nothing is exposed by this step; it only authorizes this app's key.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    PairingStep("1", "Open Settings → Developer options → Wireless debugging.")
                    PairingStep("2", "Tap “Pair device with pairing code” and keep that dialog open.")
                    PairingStep(
                        "3",
                        "Pull down the shade and reply to the ADB Anywhere notification.",
                    )
                }
                PairingReply()
                Text(
                    "Both numbers are in Android's pairing dialog. The notification reply keeps " +
                        "the app in the background, so the dialog stays open while it connects.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onNotify, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.NotificationsActive, contentDescription = null)
                    Text("Show pairing notification", modifier = Modifier.padding(start = 8.dp))
                }
                OutlinedButton(
                    onClick = onOpenDeveloperSettings,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.OpenInNew, contentDescription = null)
                    Text("Open developer settings", modifier = Modifier.padding(start = 8.dp))
                }
                if (state.phase == Phase.NeedsPairing || state.phase == Phase.Error) {
                    TextButton(onClick = onSetup) {
                        Text("Retry connection")
                    }
                }
                if (state.error.isNotEmpty()) {
                    Text(
                        state.error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        item {
            SectionCard("2. Deploy the custom adb") {
                Text(
                    "The app writes the custom adb and its key file to " +
                        "/data/local/tmp/adb-anywhere, starts it with the exact listeners and " +
                        "verifies shell access before it turns Android's wireless debugging off.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onSetup, enabled = !busy) {
                    Text(if (busy) "Working…" else "Set up now")
                }
            }
        }

        if (state.steps.isNotEmpty()) {
            item {
                SectionCard("Progress") {
                    state.steps.forEach { StepRow(it) }
                }
            }
        }

        item {
            SectionCard("Details") {
                KeyValueRow("Developer endpoint", state.endpoint.ifBlank { "not available yet" }, mono = true)
                KeyValueRow("App channel", "127.0.0.1:${state.loopbackPort}", mono = true)
                KeyValueRow("App key", state.appFingerprint.ifBlank { "generating…" }, mono = true)
                Text(
                    "If setup cannot reach Android's wireless debugging, connect to Wi-Fi once or " +
                        "run adb tcpip:${state.loopbackPort} over USB. The app never falls back " +
                        "to a proxy or a random port.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** One numbered step in the pairing instructions. */
@Composable
private fun PairingStep(number: String, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = CircleShape,
            modifier = Modifier.size(24.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    number,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** The exact reply the pairing notification expects. */
@Composable
private fun PairingReply() {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                "Reply in the notification",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "123456:37123",
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}
