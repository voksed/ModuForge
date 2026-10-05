package dev.moduforge.host.ui.consent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.moduforge.host.R
import dev.moduforge.host.consent.PendingQuestion

/**
 * A module's question to the user. The host states who is asking and where the answer goes,
 * because the question text itself comes from the module.
 */
@Composable
fun InputDialog(question: PendingQuestion, onAnswer: (String?) -> Unit) {
    var text by remember(question) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { onAnswer(null) },
        title = { Text(stringResource(R.string.input_title, question.moduleName)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(question.question)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    visualTransformation = if (question.secret) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(keyboardType = if (question.secret) KeyboardType.Password else KeyboardType.Text),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(R.string.input_warning, question.moduleName, question.moduleId),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onAnswer(text) }, enabled = text.isNotEmpty()) {
                Text(stringResource(R.string.input_send))
            }
        },
        dismissButton = { TextButton(onClick = { onAnswer(null) }) { Text(stringResource(R.string.import_cancel)) } },
    )
}
