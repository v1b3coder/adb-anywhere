/*
 * HomeScreen - the status hero, endpoint, setup steps and security summary.
 */
package cz.slush.adbanywhere.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cz.slush.adbanywhere.data.AdbStatus
import cz.slush.adbanywhere.service.ControlState
import cz.slush.adbanywhere.service.Phase

@Composable
fun HomeScreen(
    state: ControlState,
    keepSystemAdbOff: Boolean,
    onSetup: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onCopy: (String) -> Unit,
    onRestart: () -> Unit,
    onPair: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { StatusHero(state, onCopy, onSetup, onStart, onStop, onRestart, onPair) }
        if (state.steps.isNotEmpty()) {
            item {
                SectionCard("Setup progress") {
                    state.steps.forEach { StepRow(it) }
                }
            }
        }
        item {
            SectionCard("Security") {
                SecurityRow(state.adbStatus, keepSystemAdbOff)
                OutlinedButton(onClick = onOpenSettings) {
                    Icon(Icons.Rounded.Settings, contentDescription = null)
                    Text("Open settings", modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
        item {
            SectionCard("How it works") {
                Text(
                    "Android's wireless debugging is only used once, to deploy the patched daemon. " +
                        "After that the app keeps it off and the daemon binds loopback plus your VPN address.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Developer machines connect to the VPN address with their own paired keys. " +
                        "No plain ADB is exposed on Wi-Fi.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusHero(
    state: ControlState,
    onCopy: (String) -> Unit,
    onSetup: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
    onPair: () -> Unit,
) {
    val container = when (state.phase) {
        Phase.Running -> MaterialTheme.colorScheme.primaryContainer
        Phase.Error -> MaterialTheme.colorScheme.errorContainer
        Phase.NeedsPairing -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    val onContainer = when (state.phase) {
        Phase.Running -> MaterialTheme.colorScheme.onPrimaryContainer
        Phase.Error -> MaterialTheme.colorScheme.onErrorContainer
        Phase.NeedsPairing -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    }
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Box(
            Modifier.background(
                Brush.linearGradient(
                    listOf(container, MaterialTheme.colorScheme.surfaceContainerHigh),
                ),
            ),
        ) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Surface(color = onContainer.copy(alpha = 0.14f), shape = CircleShape) {
                        Icon(
                            when (state.phase) {
                                Phase.Running -> Icons.Rounded.CheckCircle
                                Phase.Error -> Icons.Rounded.ErrorOutline
                                Phase.NeedsPairing -> Icons.Outlined.Link
                                Phase.Stopped -> Icons.Rounded.Stop
                                else -> Icons.Rounded.PlayArrow
                            },
                            contentDescription = null,
                            modifier = Modifier.padding(12.dp).size(30.dp),
                            tint = onContainer,
                        )
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            phaseTitle(state.phase),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = onContainer,
                        )
                        Text(
                            state.error.ifBlank { state.message.ifBlank { phaseSubtitle(state.phase) } },
                            style = MaterialTheme.typography.bodyMedium,
                            color = onContainer.copy(alpha = 0.86f),
                        )
                    }
                }

                if (state.phase == Phase.Running && state.endpoint.isNotBlank()) {
                    Surface(
                        color = onContainer.copy(alpha = 0.10f),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "adb connect",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = onContainer.copy(alpha = 0.7f),
                                )
                                Text(
                                    state.endpoint,
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = onContainer,
                                )
                            }
                            IconButton(onClick = { onCopy(state.endpoint) }) {
                                Icon(
                                    Icons.Outlined.ContentCopy,
                                    contentDescription = "Copy endpoint",
                                    tint = onContainer,
                                )
                            }
                        }
                    }
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // The daemon is only up while the user wants it: a stop is
                    // reachable in every phase that owns the service.
                    if (state.phase != Phase.Idle && state.phase != Phase.Stopped) {
                        FilledTonalIconButton(onClick = onStop) {
                            Icon(Icons.Rounded.Stop, contentDescription = "Stop")
                        }
                    }
                    when (state.phase) {
                        Phase.Running -> {
                            FilledTonalIconButton(onClick = onRestart) {
                                Icon(Icons.Rounded.Refresh, contentDescription = "Restart")
                            }
                            Button(onClick = onSetup) {
                                Text("Re-check")
                            }
                        }
                        Phase.NeedsPairing -> {
                            Button(onClick = onPair) {
                                Text("Pair now")
                            }
                            FilledTonalButton(onClick = onSetup) {
                                Text("Retry")
                            }
                        }
                        Phase.Error -> {
                            Button(onClick = onSetup) {
                                Text("Try again")
                            }
                        }
                        Phase.Deploying, Phase.Pairing -> {
                            FilledTonalButton(onClick = onSetup, enabled = false) {
                                Text("Working…")
                            }
                        }
                        Phase.Stopped -> {
                            Button(onClick = onStart) {
                                Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                                Text("Start", modifier = Modifier.padding(start = 8.dp))
                            }
                        }
                        Phase.Idle -> {
                            Button(onClick = onSetup) {
                                Text("Set up")
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * D11/§8: the security row reports the platform ADB state, never the daemon
 * phase, so it cannot claim "off" while the system listener is on. The warnings
 * are persistent: they survive every later status message.
 */
@Composable
private fun SecurityRow(status: AdbStatus?, keepSystemAdbOff: Boolean) {
    val warnings = status?.warnings(keepSystemAdbOff).orEmpty()
    val off = status != null && warnings.isEmpty()
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            when {
                status == null -> Icons.Outlined.WarningAmber
                off -> Icons.Rounded.CheckCircle
                else -> Icons.Rounded.ErrorOutline
            },
            contentDescription = null,
            tint = when {
                status == null -> MaterialTheme.colorScheme.onSurfaceVariant
                off -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.error
            },
        )
        Column(Modifier.weight(1f)) {
            Text(
                when {
                    status == null -> "System ADB state is not read yet"
                    off -> "System wireless debugging is off"
                    else -> "System ADB needs attention"
                },
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                when {
                    status == null -> "Run setup to read adb_enabled and the 5555 probe."
                    off -> "The patched daemon is the only ADB endpoint."
                    else -> "Open Settings to fix it."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    warnings.forEach { warning ->
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                Icons.Outlined.WarningAmber,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
            Text(
                warning,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

fun phaseTitle(phase: Phase): String = when (phase) {
    Phase.Running -> "ADB is reachable"
    Phase.NeedsPairing -> "Pair your phone"
    Phase.Pairing -> "Pairing…"
    Phase.Deploying -> "Setting up…"
    Phase.Error -> "Needs attention"
    Phase.Stopped -> "Stopped"
    Phase.Idle -> "Not set up yet"
}

fun phaseSubtitle(phase: Phase): String = when (phase) {
    Phase.Running -> "The daemon is listening on the VPN address."
    Phase.NeedsPairing -> "Use Android's wireless debugging once to authorize this app."
    Phase.Pairing -> "Keep the pairing dialog open."
    Phase.Deploying -> "Deploying and verifying the daemon."
    Phase.Error -> "Fix the problem below and try again."
    Phase.Stopped -> "Start ADB Anywhere when you need it."
    Phase.Idle -> "Tap Set up to start."
}
