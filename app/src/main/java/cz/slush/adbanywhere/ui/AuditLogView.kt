/*
 * AuditLogView - the firewall decision log as a sticky-bottom list: oldest at the
 * top, newest at the bottom, and it follows new entries only while the user is
 * already at the bottom.
 */
package cz.slush.adbanywhere.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cz.slush.adbanywhere.firewall.AuditEvent
import cz.slush.adbanywhere.firewall.AuditKind
import cz.slush.adbanywhere.firewall.AuditText
import cz.slush.adbanywhere.firewall.CallRule

@Composable
fun AuditLogList(
    events: List<AuditEvent>,
    keyLabels: Map<String, String>,
    rules: List<CallRule>,
    modifier: Modifier = Modifier,
) {
    val state = rememberLazyListState()
    var follow by remember { mutableStateOf(true) }
    // A scroll that ends at the bottom re-arms following; one that ends higher
    // stops it, so reading history is never interrupted by a new decision.
    LaunchedEffect(state) {
        snapshotFlow { state.isScrollInProgress }.collect { scrolling ->
            if (!scrolling) {
                val info = state.layoutInfo
                follow = info.totalItemsCount > 0 &&
                    info.visibleItemsInfo.lastOrNull()?.index == info.totalItemsCount - 1
            }
        }
    }
    LaunchedEffect(events.size) {
        if (follow && events.isNotEmpty() && !state.isScrollInProgress) {
            state.scrollToItem(events.lastIndex)
        }
    }
    if (events.isEmpty()) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                "No decisions yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LazyColumn(
        state = state,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(vertical = 4.dp),
    ) {
        items(events) { event ->
            AuditLogRow(event, keyLabels, rules)
        }
    }
}

@Composable
private fun AuditLogRow(
    event: AuditEvent,
    keyLabels: Map<String, String>,
    rules: List<CallRule>,
) {
    val description = remember(event, keyLabels, rules) {
        AuditText.describe(event, keyLabels, rules)
    }
    val tint = when (description.kind) {
        AuditKind.Allowed -> MaterialTheme.colorScheme.primary
        AuditKind.Denied, AuditKind.Error -> MaterialTheme.colorScheme.error
        AuditKind.Info -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            AuditText.time(event.time),
            modifier = Modifier.padding(top = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                description.title,
                style = MaterialTheme.typography.bodyMedium,
                color = tint,
            )
            if (description.detail.isNotBlank()) {
                Text(
                    description.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
