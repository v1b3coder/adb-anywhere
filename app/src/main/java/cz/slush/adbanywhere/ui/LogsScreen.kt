/*
 * LogsScreen - two logs: the app's own events and the custom adb's output.
 */
package cz.slush.adbanywhere.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun LogsScreen(
    appLogs: String,
    daemonLogs: String,
    diagnosticLog: Boolean,
    onRefresh: () -> Unit,
    onCopy: (String) -> Unit,
) {
    // The app log is the reason the user opens this screen; adb stays one tap away.
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val text = if (tab == 0) appLogs else daemonLogs
    val vertical = rememberScrollState()
    val horizontal = rememberScrollState()
    // Follow the newest entry until the user scrolls up, then stay put.
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(text) {
        snapshotFlow { vertical.maxValue }.collect { max ->
            if (follow) vertical.scrollTo(max)
        }
    }
    LaunchedEffect(vertical) {
        snapshotFlow { vertical.value }.collect {
            if (vertical.value < vertical.maxValue - 8) follow = false
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(PaddingValues(16.dp)),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TabRow(selectedTabIndex = tab) {
            Tab(
                selected = tab == 0,
                onClick = { tab = 0 },
                text = { Text("App") },
            )
            Tab(
                selected = tab == 1,
                onClick = { tab = 1 },
                text = { Text("adb") },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (tab == 1) {
                FilledTonalButton(onClick = onRefresh) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null)
                    Text("Refresh", modifier = Modifier.padding(start = 8.dp))
                }
            }
            OutlinedButton(
                onClick = { onCopy(text) },
                enabled = text.isNotBlank(),
            ) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = null)
                Text("Copy", modifier = Modifier.padding(start = 8.dp))
            }
        }
        if (tab == 0 && !diagnosticLog) {
            Text(
                "Diagnostic logging is off. Turn it on in Settings → Diagnostic log.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SelectionContainer {
            Text(
                text.ifBlank {
                    if (tab == 0) {
                        "No app events yet."
                    } else {
                        "No adb log lines yet. Refresh after setup."
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(vertical)
                    .then(if (tab == 1) Modifier.horizontalScroll(horizontal) else Modifier),
                softWrap = tab == 0,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
