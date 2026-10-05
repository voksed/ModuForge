package dev.moduforge.host.ui.consent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.moduforge.core.permission.ConsentDecision
import dev.moduforge.core.permission.ConsentPrompt
import dev.moduforge.host.R
import dev.moduforge.host.ui.descriptionRes
import dev.moduforge.host.ui.titleRes

/**
 * Consent prompt for one capability. Dismissing the dialog counts as a denial.
 * For targeted capabilities "Allow" stays disabled until authorization is confirmed.
 */
@Composable
fun ConsentDialog(prompt: ConsentPrompt, onDecision: (ConsentDecision) -> Unit) {
    var authorized by remember(prompt) { mutableStateOf(false) }
    val target = prompt.target

    AlertDialog(
        onDismissRequest = { onDecision(ConsentDecision.Deny) },
        title = { Text(stringResource(R.string.consent_title, stringResource(prompt.capability.titleRes))) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.consent_requester, prompt.moduleName, prompt.moduleId))
                Text(stringResource(prompt.capability.descriptionRes))
                if (prompt.rationale.isNotBlank()) {
                    Column {
                        Text(
                            stringResource(R.string.consent_rationale_label),
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Text(prompt.rationale, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (target != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.consent_intrusive_warning))
                            Text(
                                stringResource(R.string.consent_target_label, target),
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.toggleable(
                            value = authorized,
                            role = Role.Checkbox,
                            onValueChange = { authorized = it },
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = authorized, onCheckedChange = null)
                        Text(
                            stringResource(R.string.consent_authorization_checkbox),
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = target == null || authorized,
                onClick = { onDecision(ConsentDecision.Allow(targetAuthorizationConfirmed = target != null && authorized)) },
            ) {
                Text(stringResource(R.string.consent_allow))
            }
        },
        dismissButton = {
            TextButton(onClick = { onDecision(ConsentDecision.Deny) }) {
                Text(stringResource(R.string.consent_deny))
            }
        },
    )
}
