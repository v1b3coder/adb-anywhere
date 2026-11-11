/*
 * AppRoot - Material 3 navigation shell and the top-level actions.
 */
package cz.slush.adbanywhere.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.TileService
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cz.slush.adbanywhere.AppViewModel
import cz.slush.adbanywhere.service.Phase
import kotlinx.coroutines.launch

private enum class Screen(val label: String, val icon: ImageVector) {
    Home("Home", Icons.Outlined.Home),
    Setup("Setup", Icons.Outlined.Home),
    Keys("Keys", Icons.Outlined.VpnKey),
    Logs("Logs", Icons.Outlined.Article),
    Settings("Settings", Icons.Outlined.Settings),
    Firewall("Firewall", Icons.Outlined.Security),
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(viewModel: AppViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val keys by viewModel.keys.collectAsStateWithLifecycle()
    val firewall by viewModel.firewall.collectAsStateWithLifecycle()
    val audit by viewModel.audit.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val appLogs by viewModel.appLog.collectAsStateWithLifecycle()
    val diagnosticLog by viewModel.diagnosticLog.collectAsStateWithLifecycle()
    val turnSystemAdbOffOnStop by viewModel.turnSystemAdbOffOnStop.collectAsStateWithLifecycle()
    val appFingerprint by viewModel.appFingerprint.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var screen by rememberSaveable { mutableStateOf(Screen.Home) }

    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(screen) {
        if (screen == Screen.Logs) viewModel.refreshLogs()
    }
    LaunchedEffect(firewall.enabled) {
        if (screen == Screen.Firewall && !firewall.enabled) screen = Screen.Home
    }

    fun copy(text: String) {
        if (text.isBlank()) return
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText("ADB Anywhere", text))
        scope.launch { snackbar.showSnackbar("Copied $text") }
    }

    BackHandler(screen != Screen.Home) { screen = Screen.Home }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("ADB Anywhere", style = MaterialTheme.typography.titleLarge)
                        Text(
                            when (state.phase) {
                                Phase.Running -> state.endpoint.ifBlank { "running" }
                                Phase.NeedsPairing -> "pairing needed"
                                Phase.Deploying -> "setting up"
                                Phase.Pairing -> "pairing"
                                Phase.Error -> "needs attention"
                                Phase.Stopped -> "stopped"
                                Phase.Idle -> "not set up"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    Box(Modifier.padding(start = 16.dp, end = 4.dp)) {
                        PhaseDot(state.phase, size = 12)
                    }
                },
                actions = {
                    if (screen != Screen.Logs) {
                        androidx.compose.material3.IconButton(onClick = { screen = Screen.Logs }) {
                            Icon(Icons.Outlined.Article, contentDescription = "Logs")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                Screen.entries.filter {
                    it != Screen.Setup && (it != Screen.Firewall || firewall.enabled)
                }.forEach { item ->
                    NavigationBarItem(
                        selected = screen == item,
                        onClick = { screen = item },
                        icon = { Icon(item.icon, contentDescription = item.label) },
                        label = { Text(item.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (screen) {
                Screen.Home -> HomeScreen(
                    state = state,
                    onSetup = { screen = Screen.Setup; viewModel.start() },
                    onStart = viewModel::start,
                    onStop = viewModel::stop,
                    onCopy = ::copy,
                    onRestart = viewModel::restart,
                    onPair = { screen = Screen.Setup },
                    onOpenSettings = { openWirelessDebugging(context) },
                )
                Screen.Setup -> SetupScreen(
                    state = state,
                    onSetup = viewModel::start,
                    onNotify = viewModel::postPairingNotification,
                    onOpenDeveloperSettings = {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    },
                )
                Screen.Keys -> KeysScreen(
                    keys = keys,
                    onRemove = viewModel::removeDeveloperKey,
                    onRename = viewModel::renameDeveloperKey,
                    onAdd = viewModel::addDeveloperKey,
                    onRemoveAll = viewModel::removeAllDeveloperKeys,
                )
                Screen.Logs -> LogsScreen(
                    appLogs = appLogs,
                    daemonLogs = logs,
                    diagnosticLog = diagnosticLog,
                    onRefresh = viewModel::refreshLogs,
                    onCopy = ::copy,
                )
                Screen.Firewall -> FirewallScreen(
                    state = state,
                    config = firewall,
                    audit = audit,
                    onToggleIpAllowlist = viewModel::setIpAllowlistEnabled,
                    onAddIp = viewModel::addIp,
                    onRemoveIp = viewModel::removeIp,
                    onToggleAppAllowlist = viewModel::setAppAllowlistEnabled,
                    onAddApp = viewModel::addApp,
                    onRemoveApp = viewModel::removeApp,
                    onToggleCallPolicy = viewModel::setCallPolicyEnabled,
                    onRemoveRule = viewModel::removeRule,
                    onMoveRule = viewModel::moveRule,
                    onSetAppGrace = viewModel::setAppGraceMs,
                    onSetPromptTimeout = viewModel::setPromptTimeoutMs,
                    onToggleRedactAudit = viewModel::setRedactAudit,
                    onClearAudit = viewModel::clearAudit,
                    onCopyAudit = ::copy,
                    onGrantUsageAccess = viewModel::grantUsageAccess,
                    onSaveRule = viewModel::saveRule,
                    onRequestOverlayAccess = viewModel::requestOverlayAccess,
                    launcherApps = viewModel::launcherApps,
                )
                Screen.Settings -> SettingsScreen(
                    state = state,
                    firewallEnabled = firewall.enabled,
                    turnSystemAdbOffOnStop = turnSystemAdbOffOnStop,
                    diagnosticLog = diagnosticLog,
                    appKey = appFingerprint,
                    onToggleFirewall = viewModel::setFirewallEnabled,
                    onToggleTurnSystemAdbOffOnStop = viewModel::setTurnSystemAdbOffOnStop,
                    onToggleDiagnosticLog = viewModel::setDiagnosticLog,
                    onSavePorts = viewModel::setPorts,
                    onBatteryExemption = { viewModel.requestBatteryExemption(it) },
                    onCopy = ::copy,
                )
            }
        }
    }
}

/**
 * Opens Android's Wireless debugging page. Stock Settings has no public intent
 * for it, but its own Quick Settings tile long-press handler does: the developer
 * options activity turns this action plus the tile component into the page. A build
 * without that handler still lands in Developer options.
 */
private fun openWirelessDebugging(context: Context) {
    val wireless = Intent(TileService.ACTION_QS_TILE_PREFERENCES)
        .setPackage("com.android.settings")
        .putExtra(
            Intent.EXTRA_COMPONENT_NAME,
            ComponentName(
                "com.android.settings",
                "com.android.settings.development.qstile.DevelopmentTiles\$WirelessDebugging",
            ),
        )
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (runCatching { context.startActivity(wireless) }.isFailure) {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
