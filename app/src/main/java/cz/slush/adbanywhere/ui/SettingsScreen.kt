/*
 * SettingsScreen - security, ports, battery and daemon controls.
 */
package cz.slush.adbanywhere.ui

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatterySaver
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cz.slush.adbanywhere.data.AdbStatus
import cz.slush.adbanywhere.service.ControlState
import cz.slush.adbanywhere.service.SystemSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(
    state: ControlState,
    firewallEnabled: Boolean,
    turnSystemAdbOffOnStop: Boolean,
    diagnosticLog: Boolean,
    appKey: String,
    onToggleFirewall: (Boolean) -> Unit,
    onToggleTurnSystemAdbOffOnStop: (Boolean) -> Unit,
    onToggleDiagnosticLog: (Boolean) -> Unit,
    onSavePorts: (Int, Int) -> String?,
    onBatteryExemption: (Activity) -> Unit,
    onCopy: (String) -> Unit,
) {
    val context = LocalContext.current
    var loopback by rememberSaveable { mutableStateOf(state.loopbackPort.toString()) }
    var vpn by rememberSaveable { mutableStateOf(state.vpnPort.toString()) }
    var portsError by remember { mutableStateOf("") }
    LaunchedEffect(state.loopbackPort, state.vpnPort) {
        loopback = state.loopbackPort.toString()
        vpn = state.vpnPort.toString()
    }
    var status by remember { mutableStateOf<AdbStatus?>(null) }
    var reload by remember { mutableStateOf(0) }
    LaunchedEffect(reload, state.phase) {
        status = withContext(Dispatchers.IO) { SystemSettings.status(context) }
    }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            SectionCard("Firewall") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Adds a proxy on the VPN port, IP and app allowlists, call " +
                            "policy and an audit log. The Firewall tab appears when this is on.",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Switch(
                        checked = firewallEnabled,
                        onCheckedChange = onToggleFirewall,
                    )
                }
            }
        }

        item {
            SectionCard("Battery") {
                Text(
                    "Allow ADB Anywhere to run in the background so the watchdog can restart " +
                        "the custom adb after Doze.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = { (context as? Activity)?.let(onBatteryExemption) },
                ) {
                    Icon(Icons.Outlined.BatterySaver, contentDescription = null)
                    Text("Allow background", modifier = Modifier.padding(start = 8.dp))
                }
            }
        }

        item {
            SectionCard("Ports") {
                Text(
                    "The loopback port is for the app. The VPN port is for developer machines. " +
                        "Both are stable across reboots. Use ports below 32768: Android's " +
                        "wireless debugging picks its random port from the ephemeral range.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = loopback,
                    onValueChange = {
                        loopback = it.filter(Char::isDigit)
                        portsError = ""
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Loopback port") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    value = vpn,
                    onValueChange = {
                        vpn = it.filter(Char::isDigit)
                        portsError = ""
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("VPN port") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                if (portsError.isNotEmpty()) {
                    Text(
                        portsError,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Button(
                    onClick = {
                        val failure = onSavePorts(
                            loopback.toIntOrNull() ?: 0,
                            vpn.toIntOrNull() ?: 0,
                        )
                        if (failure != null) portsError = failure
                    },
                ) { Text("Save ports") }
            }
        }

        item {
            SectionCard(
                "System adb",
                trailing = {
                    IconButton(onClick = { reload++ }) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Refresh the ADB state")
                    }
                },
            ) {
                KeyValueRow(
                    "App adb channel",
                    "127.0.0.1:${state.loopbackPort}",
                    mono = true,
                )
                val current = status
                if (current == null) {
                    Text(
                        "Reading the settings…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    KeyValueRow(
                        "ADB enabled",
                        if (current.adbEnabled == 0) "off" else "on",
                        mono = true,
                    )
                    KeyValueRow(
                        "Wireless debugging",
                        if (current.adbWifiEnabled == 0) "off" else "on",
                        mono = true,
                    )
                    KeyValueRow(
                        "Port 5555",
                        if (current.port5555Open) "open" else "closed",
                        mono = true,
                    )
                    current.warnings().forEach { warning ->
                        Text(
                            warning,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                Text(
                    "Wireless debugging is always turned off after the custom adb is verified. " +
                        "The switch below also lets Stop turn ADB itself off; it is off by " +
                        "default so Android keeps the paired key and the USB recovery path.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Turn system adb off on Stop\n" +
                            "On: Stop turns ADB off and Start turns it back on. Android may " +
                            "drop the paired key, so pair again on the next Start.",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Switch(
                        checked = turnSystemAdbOffOnStop,
                        onCheckedChange = onToggleTurnSystemAdbOffOnStop,
                    )
                }
            }
        }

        item {
            SectionCard("Diagnostic log") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Records the app's events, actions and applied changes in a bounded " +
                            "on-device file, shown in Logs → App. Off by default.",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Switch(
                        checked = diagnosticLog,
                        onCheckedChange = onToggleDiagnosticLog,
                    )
                }
            }
        }

        item {
            SectionCard("App key") {
                Text(
                    "This is the app's public key. It is the only key that can use the loopback channel.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FingerprintText(appKey.ifBlank { "generating…" }, Modifier.weight(1f))
                    IconButton(onClick = { if (appKey.isNotBlank()) onCopy(appKey) }) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy app key")
                    }
                }
            }
        }
    }
}
