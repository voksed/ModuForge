package dev.moduforge.host.ui.modules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.moduforge.host.R
import dev.moduforge.host.ui.descriptionRes
import dev.moduforge.host.ui.labelRes
import dev.moduforge.host.ui.titleRes
import dev.moduforge.sdk.Capability
import dev.moduforge.sdk.ModuleManifest

/**
 * Offers, in one place, every permission the module declares and does not hold yet, right
 * before it starts. Nothing is selected in advance; the module starts with whatever the user
 * switched on and can still ask for the rest while running.
 */
@Composable
fun StartPermissionsDialog(
    manifest: ModuleManifest,
    missing: List<Capability>,
    onStart: (Set<Capability>) -> Unit,
    onCancel: () -> Unit,
) {
    var selected by remember(missing) { mutableStateOf(emptySet<Capability>()) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.start_permissions_title, manifest.name)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.start_permissions_hint), style = MaterialTheme.typography.bodySmall)
                // Above the list, so that it stays in sight however long the descriptions are.
                TextButton(onClick = { selected = if (selected.size == missing.size) emptySet() else missing.toSet() }) {
                    Text(
                        stringResource(
                            if (selected.size == missing.size) R.string.start_permissions_none else R.string.start_permissions_all,
                        ),
                    )
                }
                missing.forEach { capability ->
                    val on = capability in selected
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().toggleable(
                            value = on,
                            role = Role.Switch,
                            onValueChange = { selected = if (it) selected + capability else selected - capability },
                        ),
                    ) {
                        Column(Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(
                                stringResource(capability.titleRes) + " · " + stringResource(capability.sensitivity.labelRes),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(stringResource(capability.descriptionRes), style = MaterialTheme.typography.bodySmall)
                            manifest.permissionReasons[capability]?.takeIf { it.isNotBlank() }?.let { reason ->
                                Text(
                                    stringResource(R.string.import_author_reason, reason),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Switch(checked = on, onCheckedChange = null)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onStart(selected) }) { Text(stringResource(R.string.module_start)) }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.import_cancel)) } },
    )
}
