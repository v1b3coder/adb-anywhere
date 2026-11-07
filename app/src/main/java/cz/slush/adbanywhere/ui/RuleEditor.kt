/*
 * RuleEditor - the custom filter editor. It starts with every field fixed and
 * lets the user unlock the fields that may vary. The service is always fixed, the
 * call arguments are shown in call order, and the derived fields (command, raw,
 * interactive, metacharacters) are read-only. The Save/Cancel footer is outside the
 * scrolling field list so it stays visible.
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
    rule: CallRule? = null,
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
            RuleEditorContent(
                call = call,
                rule = rule,
                onDismiss = onDismiss,
                onSave = onSave,
            )
        }
    }
}

@Composable
fun RuleEditorContent(
    call: AdbCall,
    onDismiss: () -> Unit,
    onSave: (CallRule) -> Unit,
    modifier: Modifier = Modifier,
    rule: CallRule? = null,
) {
    val source = rule?.original ?: call
    var action by remember(rule) { mutableStateOf(rule?.action ?: CallAction.ALLOW) }
    var fixed by remember(rule, source) {
        mutableStateOf(
            if (rule != null) {
                rule.predicates.mapNotNull { it.field }.toSet() - "service"
            } else {
                source.fields.keys - "service"
            },
        )
    }
    var pathGlob by remember(rule) {
        mutableStateOf(
            rule?.predicates?.any { it is CallPredicate.Glob && it.field == "path" } == true,
        )
    }
    var prefixMode by remember(rule) { mutableStateOf(rule?.rawShell == true) }
    var prefixLength by remember(rule, source) {
        mutableStateOf(
            rule?.prefixLength(source) ?: minOf(2, source.argv.orEmpty().size),
        )
    }
    val argv = remember(source) { source.argv.orEmpty() }
    val derived = remember { setOf("command", "raw", "interactive", "metacharacters") }
    val fields = remember(source) {
        source.fields.filterKeys { it != "service" }
            .toList()
            .sortedWith(compareBy({ fieldOrder(it.first) }, { it.first }))
    }
    val prefix = remember(argv, prefixLength) {
        if (argv.isEmpty()) {
            ""
        } else {
            argv.take(prefixLength).joinToString(" ") +
                if (prefixLength < argv.size) " " else ""
        }
    }
    val prefixAvailable = source.service in setOf("shell", "exec") &&
        !source.metacharacters && argv.isNotEmpty()
    Column(modifier.fillMaxWidth()) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    if (rule == null) "Custom filter" else "Edit filter",
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            item {
                Text(
                    "Service: ${source.service} (always fixed)",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            item {
                Text(
                    source.raw,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            item {
                if (prefixAvailable) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = prefixMode,
                            onCheckedChange = { prefixMode = it },
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Prefix capability",
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Text(
                                "Pins the first argv tokens. Top-level shell operators " +
                                    "are rejected; quoted sh -c '...' is allowed and runs " +
                                    "as the app's UID.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    Text(
                        if (source.metacharacters) {
                            "Top-level shell operators: only an exact rule is available."
                        } else {
                            "Prefix capability is available for shell and exec calls."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (prefixMode) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            enabled = prefixLength > 1,
                            onClick = { prefixLength = (prefixLength - 1).coerceAtLeast(1) },
                        ) { Text("−") }
                        Text(
                            "First $prefixLength token(s)",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        OutlinedButton(
                            enabled = prefixLength < argv.size,
                            onClick = {
                                prefixLength = (prefixLength + 1).coerceAtMost(argv.size)
                            },
                        ) { Text("+") }
                    }
                }
                item {
                    Text(
                        "Prefix: \"$prefix\"",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            } else {
                items(fields.filter { it.first !in derived }) { (name, value) ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = name in fixed,
                            enabled = !source.metacharacters,
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
                if ("path" in source.fields) {
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
            if (!prefixMode && fixed.isEmpty()) {
                item {
                    Text(
                        "This rule matches every call of ${source.service}.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            item {
                Text(
                    preview(source, action, fixed, pathGlob, prefixMode, prefix),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (!prefixMode) {
                items(fields.filter { it.first in derived }) { (name, value) ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "$name (derived)",
                                style = MaterialTheme.typography.labelMedium,
                            )
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
                enabled = !prefixMode || prefix.isNotBlank(),
                onClick = {
                    val predicates = if (prefixMode) {
                        listOf(
                            CallPredicate.Exact("service", source.service),
                            CallPredicate.Prefix("command", prefix),
                        )
                    } else {
                        listOf(CallPredicate.Exact("service", source.service)) +
                            fixed.mapNotNull { name ->
                                val value = source.fields[name] ?: return@mapNotNull null
                                if (name == "path" && pathGlob) {
                                    CallPredicate.Glob(name, value)
                                } else {
                                    CallPredicate.Exact(name, value)
                                }
                            }
                    }
                    onSave(
                        CallRule(
                            id = rule?.id ?: java.util.UUID.randomUUID().toString(),
                            action = action,
                            service = source.service,
                            predicates = predicates,
                            note = rule?.note ?: "",
                            createdAt = rule?.createdAt ?: System.currentTimeMillis(),
                            lastUsed = rule?.lastUsed ?: 0L,
                            rawShell = prefixMode,
                            original = source,
                        ),
                    )
                },
            ) { Text(if (rule == null) "Save rule" else "Save changes") }
        }
    }
}

private fun CallRule.prefixLength(call: AdbCall): Int {
    val prefix = predicates.filterIsInstance<CallPredicate.Prefix>()
        .firstOrNull { it.field == "command" || it.field == "raw" }
        ?.value
        ?: return minOf(2, call.argv.orEmpty().size)
    val count = prefix.trim().split(' ').count { it.isNotBlank() }
    return count.coerceIn(1, call.argv.orEmpty().size.coerceAtLeast(1))
}

/** Call order: argv, path/port/specs, then derived fields last. */
private fun fieldOrder(name: String): Int = when {
    name.startsWith("argv[") -> name.removePrefix("argv[").removeSuffix("]").toIntOrNull() ?: 1000
    name == "path" -> 2000
    name == "direction" -> 2001
    name == "port" -> 2002
    name == "remote" -> 2003
    name == "local" -> 2004
    name == "interactive" -> 3000
    name == "metacharacters" -> 3001
    name == "command" -> 3002
    name == "raw" -> 3003
    else -> 2500
}

private fun preview(
    call: AdbCall,
    action: CallAction,
    fixed: Set<String>,
    pathGlob: Boolean,
    prefixMode: Boolean,
    prefix: String,
): String = buildString {
    append(action.name)
    append(' ')
    append(call.service)
    if (prefixMode) {
        append(" where\n  command starts with ")
        append('"')
        append(prefix)
        append('"')
        append('\n')
        return@buildString
    }
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
