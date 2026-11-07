/*
 * FirewallScreen - status, IP allowlist, foreground-app allowlist, call policy
 * and the audit log. It only edits configuration and asks the service to apply it.
 */
package cz.slush.adbanywhere.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import cz.slush.adbanywhere.firewall.AppEntry
import cz.slush.adbanywhere.firewall.AuditEvent
import cz.slush.adbanywhere.firewall.CallRule
import cz.slush.adbanywhere.firewall.FirewallConfig
import cz.slush.adbanywhere.service.ControlState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun FirewallScreen(
    state: ControlState,
    config: FirewallConfig,
    audit: List<AuditEvent>,
    onToggleIpAllowlist: (Boolean) -> Unit,
    onAddIp: (String) -> String?,
    onRemoveIp: (String) -> Unit,
    onToggleAppAllowlist: (Boolean) -> Unit,
    onAddApp: (String) -> Unit,
    onRemoveApp: (String) -> Unit,
    onToggleCallPolicy: (Boolean) -> Unit,
    onSaveRule: (CallRule) -> Unit,
    onRemoveRule: (String) -> Unit,
    onSetAppGrace: (Long) -> Unit,
    onSetPromptTimeout: (Long) -> Unit,
    onToggleRedactAudit: (Boolean) -> Unit,
    onClearAudit: () -> Unit,
    onGrantUsageAccess: () -> Unit,
    onRequestOverlayAccess: () -> Unit,
    launcherApps: () -> List<AppEntry>,
) {
    var ip by remember { mutableStateOf("") }
    var ipError by remember { mutableStateOf("") }
    var grace by remember { mutableStateOf((config.appGraceMs / 1000).toString()) }
    var timeout by remember { mutableStateOf((config.promptTimeoutMs / 1000).toString()) }
    var showApps by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<CallRule?>(null) }
    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L)
            now = System.currentTimeMillis()
        }
    }
    LaunchedEffect(config.appGraceMs) { grace = (config.appGraceMs / 1000).toString() }
    LaunchedEffect(config.promptTimeoutMs) { timeout = (config.promptTimeoutMs / 1000).toString() }

    if (showApps) {
        // The package manager query and the per-app label loading take long enough
        // to drop frames, so they run off the main thread while the picker shows a
        // loader. Loading lazily also keeps the tab cheap when the picker is unused.
        LaunchedEffect(Unit) {
            apps = withContext(Dispatchers.IO) { launcherApps() }
        }
        AppPickerDialog(
            apps = apps,
            selfPackage = LocalContext.current.packageName,
            onDismiss = { showApps = false },
            onAdd = {
                onAddApp(it)
                showApps = false
            },
        )
    }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            SectionCard("Status") {
                KeyValueRow(
                    "Listener",
                    state.firewall.listenAddress.ifBlank { "not listening" },
                    mono = true,
                )
                KeyValueRow("Sessions", state.firewall.activeSessions.toString())
                KeyValueRow("Pending prompts", state.firewall.pendingPrompts.toString())
                if (state.firewall.lastError.isNotEmpty()) {
                    Text(
                        state.firewall.lastError,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        item {
            SectionCard("IP allowlist") {
                SwitchRow(
                    checked = config.ipAllowlistEnabled,
                    onCheckedChange = onToggleIpAllowlist,
                    description = "Only listed IPv4 addresses and CIDR ranges may connect. " +
                        "An empty list denies every peer.",
                )
                OutlinedTextField(
                    value = ip,
                    onValueChange = {
                        ip = it
                        ipError = ""
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("IPv4 address or CIDR") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                if (ipError.isNotEmpty()) {
                    Text(
                        ipError,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Button(
                    onClick = {
                        val failure = onAddIp(ip)
                        if (failure != null) {
                            ipError = failure
                        } else {
                            ip = ""
                        }
                    },
                ) { Text("Add address") }
                config.ipAllowlist.forEach { network ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Column(
                            Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                network.format(),
                                fontFamily = FontFamily.Monospace,
                            )
                            LastUsed(config.ipLastUsed[network.format()] ?: 0L, now)
                        }
                        IconButton(onClick = { onRemoveIp(network.format()) }) {
                            Icon(Icons.Rounded.Delete, contentDescription = "Remove ${network.format()}")
                        }
                    }
                }
            }
        }

        item {
            SectionCard("App allowlist") {
                SwitchRow(
                    checked = config.appAllowlistEnabled,
                    onCheckedChange = onToggleAppAllowlist,
                    description = "External ADB is available only while one of the selected " +
                        "apps is in the foreground. A ${config.appGraceMs / 1000}s grace " +
                        "period covers a short switch.",
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = { showApps = true }) { Text("Add app") }
                    OutlinedButton(onClick = onGrantUsageAccess) { Text("Grant usage access") }
                }
                config.appAllowlist.forEach { packageName ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Column(
                            Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                packageName,
                                fontFamily = FontFamily.Monospace,
                            )
                            LastUsed(config.appLastUsed[packageName] ?: 0L, now)
                        }
                        IconButton(onClick = { onRemoveApp(packageName) }) {
                            Icon(Icons.Rounded.Delete, contentDescription = "Remove $packageName")
                        }
                    }
                }
            }
        }

        item {
            SectionCard("Call policy") {
                SwitchRow(
                    checked = config.callPolicyEnabled,
                    onCheckedChange = onToggleCallPolicy,
                    description = "Rules first; an unmatched call asks. A shell is a " +
                        "capability, not a sandbox.",
                )
                FloatingPromptRow(onRequestOverlayAccess)
                OutlinedTextField(
                    value = grace,
                    onValueChange = { grace = it.filter(Char::isDigit) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("App grace period (seconds)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    value = timeout,
                    onValueChange = { timeout = it.filter(Char::isDigit) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Prompt timeout (seconds)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Button(
                    onClick = {
                        onSetAppGrace((grace.toLongOrNull() ?: 15) * 1000)
                        onSetPromptTimeout((timeout.toLongOrNull() ?: 30) * 1000)
                    },
                ) { Text("Save timers") }
                config.callRules.forEach { rule ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "${rule.action.name} ${rule.service}" +
                                        if (rule.rawShell) " (raw shell prefix)" else "",
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                LastUsed(
                                    rule.lastUsed,
                                    now,
                                    Modifier.padding(start = 8.dp),
                                )
                            }
                            Text(
                                rule.predicates.joinToString { predicate ->
                                    "${predicate.field}=${predicate.toJson().optString("value").ifBlank { "*" }}"
                                }.ifBlank { "every call of this service" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = { editing = rule }) {
                            Icon(Icons.Rounded.Edit, contentDescription = "Edit rule")
                        }
                        IconButton(onClick = { onRemoveRule(rule.id) }) {
                            Icon(Icons.Rounded.Delete, contentDescription = "Remove rule")
                        }
                    }
                }
            }
        }

        item {
            SectionCard("Audit log") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "The last ${audit.size} decisions. Stored on the device and " +
                            "bounded.",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onClearAudit) { Text("Clear") }
                }
                SwitchRow(
                    checked = config.redactAudit,
                    onCheckedChange = onToggleRedactAudit,
                    description = "Redact field values",
                )
                audit.takeLast(30).reversed().forEach { event ->
                    Text(
                        "${event.event} ${event.service} ${event.peer}".trim(),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }

    editing?.let { rule ->
        RuleEditorDialog(
            call = rule.original ?: rule.toCall(),
            rule = rule,
            onDismiss = { editing = null },
            onSave = { updated ->
                onSaveRule(updated)
                editing = null
            },
        )
    }
}

@Composable
private fun AppPickerDialog(
    apps: List<AppEntry>?,
    selfPackage: String,
    onDismiss: () -> Unit,
    onAdd: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var confirmSelf by remember { mutableStateOf(false) }
    val filtered = remember(apps, query) {
        apps.orEmpty().filter {
            query.isBlank() ||
                it.label.contains(query, ignoreCase = true) ||
                it.packageName.contains(query, ignoreCase = true)
        }
    }
    // The warning is only worth interrupting for once the user actually picks
    // this app, so it is a confirmation step rather than a banner over the list.
    if (confirmSelf) {
        AlertDialog(
            onDismissRequest = { confirmSelf = false },
            title = { Text("Allow ADB Anywhere itself?") },
            text = {
                Text(
                    "Allowing ADB Anywhere itself is insecure: external ADB stays " +
                        "open while this app is in front, not only while you watch " +
                        "the log. The approval dialog is not counted.",
                )
            },
            confirmButton = {
                TextButton(onClick = { onAdd(selfPackage) }) { Text("Allow") }
            },
            dismissButton = {
                TextButton(onClick = { confirmSelf = false }) { Text("Cancel") }
            },
        )
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Allow ADB for an app", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Search apps") },
                    singleLine = true,
                )
                if (apps == null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.padding(16.dp))
                        Text("Loading apps…", Modifier.padding(start = 12.dp))
                    }
                } else {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                        items(filtered) { app ->
                            TextButton(
                                onClick = {
                                    if (app.packageName == selfPackage) {
                                        confirmSelf = true
                                    } else {
                                        onAdd(app.packageName)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(
                                    Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Text(app.label)
                                    Text(
                                        app.packageName,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    }
}

@Composable
private fun LastUsed(lastUsed: Long, now: Long, modifier: Modifier = Modifier) {
    Text(
        relativeTime(lastUsed, now),
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun FloatingPromptRow(onRequestOverlayAccess: () -> Unit) {
    val context = LocalContext.current
    var canOverlay by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                canOverlay = Settings.canDrawOverlays(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text("Floating prompt", style = MaterialTheme.typography.labelLarge)
            Text(
                if (canOverlay) {
                    "The approval card floats above the current app. " +
                        "The notification stays as the fallback."
                } else {
                    "Grant Display over other apps to float the approval card. " +
                        "Without it the prompt uses the notification."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(onClick = onRequestOverlayAccess) {
            Text(if (canOverlay) "Settings" else "Allow display")
        }
    }
}
