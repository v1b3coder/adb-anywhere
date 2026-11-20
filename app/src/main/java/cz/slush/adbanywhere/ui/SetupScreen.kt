/*
 * SetupScreen - the one-time pairing flow, its progress and its result.
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
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cz.slush.adbanywhere.service.ControlState
import cz.slush.adbanywhere.service.Phase

@Composable
fun SetupScreen(
    state: ControlState,
    onSetup: () -> Unit,
    onNotify: () -> Unit,
    onOpenDeveloperSettings: () -> Unit,
    onDone: () -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            SetupStatusCard(state, onSetup, onNotify, onDone)
        }

        // The steps describe the pairing that is still ahead. After the reply,
        // Android's dialog is gone and so is the work they describe.
        if (state.pairingInstructionsVisible) {
            item {
                PairingInstructionsCard(onOpenDeveloperSettings)
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
            SetupDetailsCard(state)
        }
    }
}

/**
 * The one card that says where the flow stands and what the next action is. It is
 * the whole result view: a pairing failure, the deploy, the end state and a generic
 * error each have their own title, icon and action here.
 */
@Composable
private fun SetupStatusCard(
    state: ControlState,
    onSetup: () -> Unit,
    onNotify: () -> Unit,
    onDone: () -> Unit,
) {
    val failed = state.phase == Phase.Error ||
        (state.phase == Phase.NeedsPairing && state.error.isNotEmpty())
    val busy = state.phase == Phase.Pairing || state.phase == Phase.Deploying
    val container = when {
        failed -> MaterialTheme.colorScheme.errorContainer
        state.phase == Phase.Running -> MaterialTheme.colorScheme.primaryContainer
        state.phase == Phase.NeedsPairing -> MaterialTheme.colorScheme.tertiaryContainer
        busy -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val onContainer = when {
        failed -> MaterialTheme.colorScheme.onErrorContainer
        state.phase == Phase.Running -> MaterialTheme.colorScheme.onPrimaryContainer
        state.phase == Phase.NeedsPairing -> MaterialTheme.colorScheme.onTertiaryContainer
        busy -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Surface(color = onContainer.copy(alpha = 0.14f), shape = CircleShape) {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(14.dp).size(26.dp),
                            strokeWidth = 3.dp,
                            color = onContainer,
                        )
                    } else {
                        Icon(
                            when {
                                failed -> Icons.Rounded.ErrorOutline
                                state.phase == Phase.Running -> Icons.Rounded.CheckCircle
                                state.phase == Phase.NeedsPairing -> Icons.Outlined.Link
                                else -> Icons.Rounded.PlayArrow
                            },
                            contentDescription = null,
                            modifier = Modifier.padding(12.dp).size(30.dp),
                            tint = onContainer,
                        )
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        setupTitle(state),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = onContainer,
                    )
                    Text(
                        setupBody(state),
                        style = MaterialTheme.typography.bodyMedium,
                        color = onContainer.copy(alpha = 0.86f),
                    )
                }
            }

            // Pairing and deploying own their own progress; a button would only
            // offer a second way to start what is already running.
            if (!busy) {
                when (state.phase) {
                    Phase.Running -> Button(onClick = onDone) {
                        Icon(Icons.Rounded.CheckCircle, contentDescription = null)
                        Text("Done", modifier = Modifier.padding(start = 8.dp))
                    }
                    Phase.NeedsPairing -> Button(onClick = onNotify) {
                        Icon(Icons.Outlined.NotificationsActive, contentDescription = null)
                        Text("Show pairing notification", modifier = Modifier.padding(start = 8.dp))
                    }
                    Phase.Error -> Button(onClick = onSetup) {
                        Text("Try again")
                    }
                    Phase.Stopped -> Button(onClick = onSetup) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                        Text("Start", modifier = Modifier.padding(start = 8.dp))
                    }
                    else -> Button(onClick = onSetup) {
                        Text("Set up now")
                    }
                }
            }
        }
    }
}

private fun setupTitle(state: ControlState): String = when {
    state.phase == Phase.NeedsPairing && state.error.isNotEmpty() -> "Pairing failed"
    state.phase == Phase.Error -> "Setup failed"
    state.phase == Phase.Running -> "Setup complete"
    state.phase == Phase.NeedsPairing -> "Pair your phone"
    state.phase == Phase.Pairing -> "Pairing…"
    state.phase == Phase.Deploying -> "Setting up…"
    state.phase == Phase.Stopped -> "Stopped"
    else -> "Not set up yet"
}

private fun setupBody(state: ControlState): String = when {
    // A failed pairing and a generic failure both put the reason here; only the
    // phase decides whether the pairing steps below are the recovery.
    state.phase == Phase.NeedsPairing && state.error.isNotEmpty() -> state.error
    state.phase == Phase.Error -> state.error.ifBlank { "Setup stopped. Press Try again." }
    state.phase == Phase.Running -> state.endpoint
        .takeIf { it.isNotBlank() && it != "not available yet" }
        ?.let { "The custom adb is listening on $it." }
        ?: "The custom adb is running."
    state.phase == Phase.NeedsPairing ->
        "Reply to the ADB Anywhere notification with the code and port from Android's " +
            "pairing dialog."
    state.phase == Phase.Pairing ->
        "The app is connecting to Android's wireless debugging. Keep the pairing dialog open."
    state.phase == Phase.Deploying ->
        "The app is deploying and verifying the custom adb. Android's wireless debugging " +
            "is turned off last."
    state.phase == Phase.Stopped -> "Start ADB Anywhere to deploy the custom adb."
    else -> "Android only lets this app deploy the custom adb after a one-time pairing. " +
        "Nothing is exposed by this step; it only authorizes this app's key."
}

/** The pairing steps and the exact reply the notification expects. */
@Composable
private fun PairingInstructionsCard(onOpenDeveloperSettings: () -> Unit) {
    SectionCard("Pair with Android's wireless debugging") {
        PairingStep("1", "Open Settings → Developer options → Wireless debugging.")
        PairingStep("2", "Tap “Pair device with pairing code” and keep that dialog open.")
        PairingStep("3", "Pull down the shade and reply to the ADB Anywhere notification.")
        PairingReply()
        Text(
            "Both numbers are in Android's pairing dialog. The notification reply keeps " +
                "the app in the background, so the dialog stays open while it connects.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = onOpenDeveloperSettings,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Outlined.OpenInNew, contentDescription = null)
            Text("Open developer settings", modifier = Modifier.padding(start = 8.dp))
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

/** What the deploy writes and where, plus the connection facts and the recovery note. */
@Composable
private fun SetupDetailsCard(state: ControlState) {
    SectionCard("Details") {
        Text(
            "The app writes the custom adb and its key file to /data/local/tmp/adb-anywhere, " +
                "starts it with the exact listeners and verifies shell access before it turns " +
                "Android's wireless debugging off.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        KeyValueRow("Developer endpoint", state.endpoint.ifBlank { "not available yet" }, mono = true)
        KeyValueRow("App channel", "127.0.0.1:${state.loopbackPort}", mono = true)
        KeyValueRow("App key", state.appFingerprint.ifBlank { "generating…" }, mono = true)
        Text(
            "If setup cannot reach Android's wireless debugging, connect to Wi-Fi once or " +
                "run adb tcpip 5555 over USB. The app never falls back to a proxy or a " +
                "random port.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
