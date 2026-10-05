package dev.moduforge.host.ui.modules

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.moduforge.host.R
import dev.moduforge.sdk.ui.TextStyle
import dev.moduforge.sdk.ui.UiEvent
import dev.moduforge.sdk.ui.UiNode

/**
 * Draws a module-supplied tree inside a frame that marks it as module content,
 * so it cannot be mistaken for host UI.
 */
@Composable
fun ModuleUiCard(moduleName: String, root: UiNode, onEvent: (UiEvent) -> Unit) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.module_ui_header, moduleName),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.tertiary,
            )
            Node(root, onEvent)
        }
    }
}

@Composable
private fun Node(node: UiNode, onEvent: (UiEvent) -> Unit) {
    when (node) {
        is UiNode.Column -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            node.children.forEach { Node(it, onEvent) }
        }

        is UiNode.Row -> Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            node.children.forEach { Node(it, onEvent) }
        }

        is UiNode.Text -> Text(
            node.text,
            style = when (node.style) {
                TextStyle.TITLE -> MaterialTheme.typography.titleLarge
                TextStyle.BODY, TextStyle.CODE -> MaterialTheme.typography.bodyMedium
                TextStyle.CAPTION -> MaterialTheme.typography.bodySmall
            },
            fontFamily = if (node.style == TextStyle.CODE) FontFamily.Monospace else null,
        )

        is UiNode.Button -> Button(onClick = { onEvent(UiEvent.Click(node.id)) }, enabled = node.enabled) {
            Text(node.label)
        }

        is UiNode.TextField -> Field(node, onEvent)
    }
}

private const val MAX_UNACKNOWLEDGED_EDITS = 64

/** Reconciliation state of one text field: what the module last sent and which edits it has not echoed yet. */
private class FieldSync(var lastFromModule: String) {
    val unacknowledged = ArrayDeque<String>()
}

/**
 * Keeps the typed text locally so that input stays responsive. The module answers every edit
 * with a tree that still carries an older value; such echoes are recognised and ignored, and
 * the module's value takes over only when it is one the user never typed.
 */
@Composable
private fun Field(node: UiNode.TextField, onEvent: (UiEvent) -> Unit) {
    var text by remember(node.id) { mutableStateOf(node.value) }
    val sync = remember(node.id) { FieldSync(node.value) }
    if (node.value != sync.lastFromModule) {
        sync.lastFromModule = node.value
        val echoed = sync.unacknowledged.indexOf(node.value)
        if (echoed >= 0) {
            repeat(echoed + 1) { sync.unacknowledged.removeFirst() }
        } else {
            sync.unacknowledged.clear()
            text = node.value
        }
    }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            sync.unacknowledged.addLast(it)
            if (sync.unacknowledged.size > MAX_UNACKNOWLEDGED_EDITS) sync.unacknowledged.removeFirst()
            onEvent(UiEvent.TextChanged(node.id, it))
        },
        label = if (node.label.isEmpty()) null else ({ Text(node.label) }),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}
