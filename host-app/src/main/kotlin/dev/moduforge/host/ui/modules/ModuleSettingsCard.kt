package dev.moduforge.host.ui.modules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.moduforge.core.authoring.ModuleSetting
import dev.moduforge.host.R

private const val SECRET_MASK = "••••••••"

/** Values the module keeps through its `config` library, each with a way to change it. */
@Composable
internal fun ModuleSettingsCard(settings: List<ModuleSetting>, enabled: Boolean, onChange: (key: String, value: String) -> Unit) {
    var editing by rememberSaveable { mutableStateOf<String?>(null) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.module_settings_header), style = MaterialTheme.typography.titleMedium)
            settings.forEach { setting ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(setting.label, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = when {
                                setting.value == null -> stringResource(R.string.module_setting_unset)
                                setting.secret -> SECRET_MASK
                                else -> setting.value.orEmpty()
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(onClick = { editing = setting.key }, enabled = enabled) {
                        Text(stringResource(R.string.module_setting_change))
                    }
                }
            }
            Text(
                stringResource(R.string.module_settings_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    settings.firstOrNull { it.key == editing }?.let { setting ->
        SettingDialog(
            setting = setting,
            onSave = { value ->
                editing = null
                onChange(setting.key, value)
            },
            onCancel = { editing = null },
        )
    }
}

@Composable
private fun SettingDialog(setting: ModuleSetting, onSave: (String) -> Unit, onCancel: () -> Unit) {
    // A secret is typed anew rather than shown: the screen may be watched.
    var text by remember(setting.key) { mutableStateOf(if (setting.secret) "" else setting.value.orEmpty()) }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(setting.label) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    visualTransformation = if (setting.secret) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        keyboardType = if (setting.secret) KeyboardType.Password else KeyboardType.Text,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(R.string.module_setting_empty_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text(stringResource(R.string.editor_save)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.import_cancel)) } },
    )
}
