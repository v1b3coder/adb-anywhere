/*
 * RuleEditor - the custom filter editor. It starts with every field fixed and
 * lets the user unlock the fields that may vary. A broad rule is labelled.
 * The Save/Cancel footer is outside the scrolling field list so it stays visible.
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
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cz.slush.adbanywhere.firewall.AdbCall
import cz.slush.adbanywhere.firewall.CallAction
import cz.slush.adbanywhere.firewall.CallPredicate
import cz.slush.adbanywhere.firewall.CallRule

@Composable
fun RuleEditorDialog(
    call: AdbCall,
    onDismiss: () -> Unit,
    onSave: (CallRule) -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 6.dp,
        ) {
            RuleEditorContent(call = call, onDismiss = onDismiss, onSave = onSave)
        }
    }
}

@Composable
fun RuleEditorContent(
    call: AdbCall,
    onDismiss: () -> Unit,
    onSave: (CallRule) -> Unit,
    modifier: Modifier = Modifier,
) {
    var action by remember { mutableStateOf(CallAction.ALLOW) }
    var fixed by remember { mutableStateOf(call.fields.keys.toSet()) }
    var pathGlob by remember { mutableStateOf(false) }
    val fields = remember(call) {
        call.fields.toList().sortedBy { it.first }
    }
    Column(modifier.fillMaxWidth()) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    "Custom filter",
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            item {
                Text(
                    call.raw,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            items(fields) { (name, value) ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = name in fixed,
                        enabled = !call.metacharacters,
                        onCheckedChange = { checked ->
                            fixed = if (checked) fixed + name else fixed - name
                        },
                    )
                    Column(Modifier.weight(1f)) {
                        Text(name, style = MaterialTheme.typography.labelMedium)
                        Text(
                            value,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if ("path" in call.fields) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = pathGlob,
                            onCheckedChange = { pathGlob = it },
                        )
                        Text("Match the path as a glob")
                    }
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { action = CallAction.ALLOW },
                    ) { Text("Allow") }
                    OutlinedButton(
                        onClick = { action = CallAction.DENY },
                    ) { Text("Deny") }
                }
            }
            if (fixed.isEmpty()) {
                item {
                    Text(
                        "This rule matches every call of ${call.service}.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            item {
                Text(
                    preview(call, action, fixed, pathGlob),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onDismiss) { Text("Cancel") }
            Button(
                onClick = {
                    val predicates = fixed.mapNotNull { name ->
                        val value = call.fields[name] ?: return@mapNotNull null
                        if (name == "path" && pathGlob) {
                            CallPredicate.Glob(name, value)
                        } else {
                            CallPredicate.Exact(name, value)
                        }
                    }
                    onSave(
                        CallRule(
                            id = java.util.UUID.randomUUID().toString(),
                            action = action,
                            service = call.service,
                            predicates = predicates,
                        ),
                    )
                },
            ) { Text("Save rule") }
        }
    }
}

private fun preview(
    call: AdbCall,
    action: CallAction,
    fixed: Set<String>,
    pathGlob: Boolean,
): String = buildString {
    append(action.name)
    append(' ')
    append(call.service)
    append(" where\n")
    fixed.forEach { name ->
        append("  ")
        append(name)
        append(if (name == "path" && pathGlob) " matches " else " = ")
        append(call.fields[name] ?: "")
        append('\n')
    }
    if (fixed.isEmpty()) append("  every call of this service\n")
}
