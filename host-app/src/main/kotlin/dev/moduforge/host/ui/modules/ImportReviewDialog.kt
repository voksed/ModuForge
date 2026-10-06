package dev.moduforge.host.ui.modules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.moduforge.host.R
import dev.moduforge.host.ui.descriptionRes
import dev.moduforge.host.ui.labelRes
import dev.moduforge.host.ui.titleRes
import dev.moduforge.sandbox.PackageInspection

/**
 * Shown before a package from a file is installed: who signed it, what it says about itself
 * and every permission it may later ask for. Installing grants none of them.
 */
@Composable
fun ImportReviewDialog(
    inspection: PackageInspection.Ready,
    signerConfirmed: Boolean,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
) {
    val manifest = inspection.manifest
    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Text(
                if (inspection.installedVersion == null) {
                    stringResource(R.string.import_title, manifest.name)
                } else {
                    stringResource(R.string.import_update_title, manifest.name)
                },
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column {
                    Text(manifest.id, style = MaterialTheme.typography.bodySmall)
                    Text(
                        stringResource(
                            R.string.module_version_author,
                            manifest.version,
                            manifest.author.ifBlank { stringResource(R.string.module_author_unknown) },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        stringResource(R.string.import_runtime, manifest.runtime.name.lowercase()),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                inspection.installedVersion?.let { installed ->
                    Text(stringResource(R.string.import_update_versions, installed, manifest.version))
                }
                Labelled(R.string.import_signer_label) {
                    Text(
                        inspection.signer.chunked(4).joinToString(" "),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        stringResource(if (signerConfirmed) R.string.import_signer_confirmed else R.string.import_signer_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (signerConfirmed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (manifest.description.isNotBlank()) {
                    Labelled(R.string.import_description_label) { Text(manifest.description) }
                }
                Labelled(R.string.import_permissions_label) {
                    if (manifest.permissions.isEmpty()) {
                        Text(stringResource(R.string.module_permissions_none))
                    }
                    manifest.permissions.forEach { capability ->
                        Column {
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
                    }
                    Text(
                        stringResource(R.string.import_permissions_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onInstall) {
                Text(stringResource(if (inspection.installedVersion == null) R.string.import_install else R.string.import_update))
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.import_cancel)) } },
    )
}

@Composable
private fun Labelled(labelRes: Int, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(labelRes), style = MaterialTheme.typography.labelLarge)
        content()
    }
}
