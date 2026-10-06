package dev.moduforge.host.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.moduforge.host.R
import dev.moduforge.host.runtime.DeveloperState

/** Switches of developer mode and what to type on the computer to use it. */
@Composable
internal fun DeveloperSection(
    state: DeveloperState,
    addresses: () -> List<String>,
    onEnabledChange: (Boolean) -> Unit,
    onWifiChange: (Boolean) -> Unit,
    onRenewToken: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.developer_title), style = MaterialTheme.typography.titleLarge)
        Toggle(R.string.developer_enable, R.string.developer_enable_hint, state.enabled, onEnabledChange)
        if (!state.enabled) return@Column

        if (!state.listening) {
            Text(stringResource(R.string.developer_port_busy), color = MaterialTheme.colorScheme.error)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.developer_token), style = MaterialTheme.typography.titleSmall)
                SelectionContainer {
                    Text(state.token, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyLarge)
                }
            }
            OutlinedButton(onClick = onRenewToken) { Text(stringResource(R.string.developer_token_renew)) }
        }
        Toggle(R.string.developer_wifi, R.string.developer_wifi_hint, state.wifi, onWifiChange)

        val address = remember(state.wifi, state.listening) { if (state.wifi) addresses().firstOrNull() else null }
        val command = buildString {
            append("mfrg push")
            if (address != null) append(" --host ").append(address)
            append(" --token ").append(state.token)
        }
        Text(
            stringResource(if (state.wifi) R.string.developer_command_wifi else R.string.developer_command_usb),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SelectionContainer {
            Text(command, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
