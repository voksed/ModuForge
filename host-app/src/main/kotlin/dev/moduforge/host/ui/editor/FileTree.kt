package dev.moduforge.host.ui.editor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.moduforge.core.authoring.ProjectFiles
import dev.moduforge.host.R

/**
 * Folders and files of the module as an indented list. A tap opens a file or a folder; a long
 * press opens a menu to rename, delete, or make something inside a folder.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FileTreePanel(
    rows: List<ProjectFiles.Row>,
    current: String,
    folder: String,
    entry: String,
    onOpen: (String) -> Unit,
    onToggleFolder: (String) -> Unit,
    onRoot: () -> Unit,
    onNewFile: () -> Unit,
    onNewFolder: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.editor_files),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f).combinedClickable(onClick = onRoot),
            )
            TextButton(onClick = onNewFile, contentPadding = TIGHT) { Text("+" + stringResource(R.string.editor_file_short)) }
            TextButton(onClick = onNewFolder, contentPadding = TIGHT) { Text("+" + stringResource(R.string.editor_folder_short)) }
        }
        Text(
            stringResource(R.string.editor_in_folder, folder.ifEmpty { stringResource(R.string.editor_root) }),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        LazyColumn(Modifier.fillMaxWidth()) {
            items(rows, key = { (if (it.isFolder) "d:" else "f:") + it.path }) { row ->
                TreeRow(
                    row = row,
                    selected = if (row.isFolder) row.path == folder else row.path == current,
                    expandedFolder = row.isFolder && rows.any { it.path.startsWith(row.path + "/") },
                    canChange = row.path != entry && !entry.startsWith(row.path + "/"),
                    onClick = { if (row.isFolder) onToggleFolder(row.path) else onOpen(row.path) },
                    onRename = { onRename(row.path) },
                    onDelete = { onDelete(row.path) },
                    onNewFile = {
                        onToggleFolderIfClosed(row, rows, onToggleFolder)
                        onNewFile()
                    },
                    onNewFolder = {
                        onToggleFolderIfClosed(row, rows, onToggleFolder)
                        onNewFolder()
                    },
                )
            }
        }
    }
}

/** Makes a closed folder the place for new items without leaving it open when it was shut. */
private fun onToggleFolderIfClosed(row: ProjectFiles.Row, rows: List<ProjectFiles.Row>, select: (String) -> Unit) {
    if (row.isFolder && rows.none { it.path.startsWith(row.path + "/") }) select(row.path)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TreeRow(
    row: ProjectFiles.Row,
    selected: Boolean,
    expandedFolder: Boolean,
    canChange: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onNewFile: () -> Unit,
    onNewFolder: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp)
                .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)
                .combinedClickable(onClick = onClick, onLongClick = { menu = true })
                .padding(start = (row.depth * 12).dp + 4.dp, end = 4.dp),
        ) {
            Text(
                text = if (row.isFolder) (if (expandedFolder) "▾ " else "▸ ") + row.name else row.name,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (row.isFolder || selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (row.isFolder) {
                DropdownMenuItem(text = { Text(stringResource(R.string.editor_add_file)) }, onClick = { menu = false; onNewFile() })
                DropdownMenuItem(text = { Text(stringResource(R.string.editor_add_folder)) }, onClick = { menu = false; onNewFolder() })
            }
            if (canChange) {
                DropdownMenuItem(text = { Text(stringResource(R.string.editor_rename)) }, onClick = { menu = false; onRename() })
                DropdownMenuItem(text = { Text(stringResource(R.string.editor_delete)) }, onClick = { menu = false; onDelete() })
            }
        }
    }
}

private val TIGHT = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 0.dp)
