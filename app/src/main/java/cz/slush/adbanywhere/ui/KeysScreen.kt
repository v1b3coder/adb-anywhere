/*
 * KeysScreen - paired developer machines and the add-key flow (paste or QR).
 */
package cz.slush.adbanywhere.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import cz.slush.adbanywhere.data.AndroidPubKey
import cz.slush.adbanywhere.data.DeveloperKey

@Composable
fun KeysScreen(
    keys: List<DeveloperKey>,
    onRemove: (String) -> Unit,
    onAdd: (String, String) -> String?,
) {
    var addOpen by remember { mutableStateOf(false) }
    var removeTarget by remember { mutableStateOf<DeveloperKey?>(null) }

    Box(Modifier.fillMaxSize()) {
        if (keys.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.Outlined.QrCodeScanner,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "No developer machines yet",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Add the public key of each machine that may connect to this phone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = { addOpen = true }) {
                    Icon(Icons.Rounded.Add, contentDescription = null)
                    Text("Add developer key", modifier = Modifier.padding(start = 8.dp))
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(keys, key = { it.fingerprint }) { key ->
                    KeyCard(key, onRemove = { removeTarget = key })
                }
            }
            ExtendedFloatingActionButton(
                onClick = { addOpen = true },
                modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null)
                Text("Add key", modifier = Modifier.padding(start = 8.dp))
            }
        }
    }

    if (addOpen) {
        AddKeyDialog(
            onDismiss = { addOpen = false },
            onAdd = { text, label ->
                val error = onAdd(text, label)
                if (error == null) addOpen = false
                error
            },
        )
    }

    removeTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text("Remove ${target.label}?") },
            text = { Text("The machine will no longer be able to connect until it is paired again.") },
            confirmButton = {
                TextButton(onClick = {
                    onRemove(target.fingerprint)
                    removeTarget = null
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { removeTarget = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun AddKeyDialog(
    onDismiss: () -> Unit,
    onAdd: (String, String) -> String?,
) {
    var tab by remember { mutableIntStateOf(0) }
    var text by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    val parsed = remember(text) { AndroidPubKey.parse(text) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    "Add developer key",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                TabRow(selectedTabIndex = tab) {
                    Tab(
                        selected = tab == 0,
                        onClick = { tab = 0 },
                        text = { Text("Paste key") },
                    )
                    Tab(
                        selected = tab == 1,
                        onClick = { tab = 1 },
                        text = { Text("Scan QR") },
                    )
                }

                if (tab == 0) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = {
                            text = it
                            error = ""
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Public key") },
                        placeholder = { Text("Contents of ~/.android/adbkey.pub") },
                        minLines = 3,
                        isError = error.isNotEmpty(),
                        supportingText = {
                            Text("Paste the key, its base64 encoding, an ssh-rsa key or a PEM public key.")
                        },
                    )
                    OutlinedTextField(
                        value = label,
                        onValueChange = { label = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Label") },
                        singleLine = true,
                        placeholder = { Text("My laptop") },
                    )
                } else {
                    QrScanner(
                        onResult = {
                            text = it
                            error = ""
                        },
                        onError = { error = it },
                    )
                    Text(
                        "Point the camera at the QR code that contains the machine's public key.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (parsed != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Rounded.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    "Valid RSA-${parsed.bits} key",
                                    modifier = Modifier.padding(start = 8.dp),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                            }
                            FingerprintText(parsed.fingerprint)
                            if (parsed.label.isNotBlank()) {
                                Text(
                                    "From ${parsed.label}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                if (error.isNotEmpty()) {
                    Text(
                        error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Button(
                        onClick = {
                            val failure = onAdd(text, label)
                            if (failure != null) error = failure
                        },
                        enabled = parsed != null,
                    ) { Text("Add key") }
                }
            }
        }
    }
}
