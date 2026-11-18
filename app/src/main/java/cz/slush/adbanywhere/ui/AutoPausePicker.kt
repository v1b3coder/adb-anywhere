/*
 * AutoPausePicker - the idle timeout in hours and minutes. The one-tap presets in
 * Settings cover the common values; this wheel is for anything else.
 */
package cz.slush.adbanywhere.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cz.slush.adbanywhere.data.AutoPause
import kotlin.math.abs

private val WheelItemHeight = 44.dp

@Composable
fun AutoPausePicker(
    timeoutMs: Long,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
) {
    val stored = AutoPause.minutesOf(timeoutMs)
    var hours by remember { mutableIntStateOf(stored / 60) }
    var minutes by remember { mutableIntStateOf(stored % 60 / 5 * 5) }
    val total = hours * 60 + minutes
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Auto pause after") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    AutoPause.label(total),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    WheelHeader("hours", Modifier.weight(1f))
                    WheelHeader("minutes", Modifier.weight(1f))
                }
                // One tonal band across both wheels marks the value they pick.
                Box(
                    modifier = Modifier.fillMaxWidth().height(WheelItemHeight * 3),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier.fillMaxWidth()
                            .height(WheelItemHeight)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    )
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Wheel(
                            values = (0..AutoPause.MAX_MINUTES / 60).toList(),
                            initialValue = hours,
                            onSelected = { hours = it },
                            modifier = Modifier.weight(1f),
                        )
                        Wheel(
                            values = (0..55 step 5).toList(),
                            initialValue = minutes,
                            onSelected = { minutes = it },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Text(
                    "Five-minute steps. The clock restarts with every adb command.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = total > 0,
                onClick = { onConfirm(AutoPause.millisOf(total)) },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun WheelHeader(label: String, modifier: Modifier = Modifier) {
    Text(
        label,
        modifier = modifier,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

/**
 * One snapping wheel. The [WheelItemHeight] content padding is what lets the first
 * and the last value reach the centre band, so the centred item is the value the
 * caller gets, whatever the scroll position.
 */
@Composable
private fun Wheel(
    values: List<Int>,
    initialValue: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = rememberLazyListState(
        initialFirstVisibleItemIndex = values.indexOf(initialValue).coerceAtLeast(0),
    )
    val fling = rememberSnapFlingBehavior(lazyListState = state, snapPosition = SnapPosition.Center)
    val centered by remember(state) { derivedStateOf { state.centeredIndex() } }
    LaunchedEffect(centered) { values.getOrNull(centered)?.let(onSelected) }
    LazyColumn(
        state = state,
        flingBehavior = fling,
        modifier = modifier.fillMaxHeight(),
        contentPadding = PaddingValues(vertical = WheelItemHeight),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        itemsIndexed(values) { index, value ->
            Box(Modifier.height(WheelItemHeight), contentAlignment = Alignment.Center) {
                Text(
                    "$value",
                    style = MaterialTheme.typography.titleLarge,
                    color = if (index == centered) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                    },
                )
            }
        }
    }
}

/** The item closest to the viewport centre, which the snap lands on. */
private fun LazyListState.centeredIndex(): Int {
    val info = layoutInfo
    if (info.visibleItemsInfo.isEmpty()) return firstVisibleItemIndex
    val center = (info.viewportStartOffset + info.viewportEndOffset) / 2
    return info.visibleItemsInfo
        .minByOrNull { abs(it.offset + it.size / 2 - center) }
        ?.index ?: firstVisibleItemIndex
}
