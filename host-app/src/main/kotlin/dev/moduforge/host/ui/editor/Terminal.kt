package dev.moduforge.host.ui.editor

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.moduforge.core.module.ModuleLogSink
import dev.moduforge.core.module.ModuleState
import dev.moduforge.host.R
import dev.moduforge.host.runtime.ModuleLogLine
import dev.moduforge.host.ui.labelRes
import dev.moduforge.host.ui.modules.sourceLocation

private val HEIGHTS = listOf(0.dp, 150.dp, 300.dp)

/**
 * What the module writes while it runs, below the code. A line that names a place in the code
 * (`main.lua:12`) is a link: it opens that file at that line.
 *
 * @param size 0 shows only the header, 1 a short panel, 2 a tall one.
 */
@Composable
internal fun TerminalPanel(
    lines: List<ModuleLogLine>,
    runState: ModuleState?,
    size: Int,
    onCycleSize: () -> Unit,
    onClear: () -> Unit,
    shareText: () -> String,
    onLocation: (file: String, line: Int) -> Unit,
) {
    val context = LocalContext.current
    val title = stringResource(R.string.terminal_title)
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable(onClick = onCycleSize)) {
            Text(
                text = title + (runState?.let { " · " + stringResource(it.labelRes) } ?: ""),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f).padding(vertical = 8.dp),
            )
            if (size > 0) {
                TextButton(onClick = onClear, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(stringResource(R.string.module_log_clear)) }
                TextButton(
                    onClick = {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, shareText())
                        context.startActivity(Intent.createChooser(send, title))
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) { Text(stringResource(R.string.module_log_share)) }
            }
            Text(if (size == 0) "▴" else "▾", modifier = Modifier.padding(horizontal = 8.dp))
        }
        if (size > 0) {
            val listState = rememberLazyListState()
            LaunchedEffect(lines.size) {
                if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
            }
            SelectionContainer {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(HEIGHTS[size.coerceIn(0, HEIGHTS.lastIndex)])
                        .background(MaterialTheme.colorScheme.surfaceContainerLowest),
                    contentPadding = PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (lines.isEmpty()) {
                        item {
                            Text(
                                stringResource(R.string.terminal_empty),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(lines) { line ->
                        val place = sourceLocation(line.message)
                        Text(
                            text = line.message,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            maxLines = 12,
                            overflow = TextOverflow.Ellipsis,
                            color = when (line.level) {
                                ModuleLogSink.Level.ERROR -> MaterialTheme.colorScheme.error
                                ModuleLogSink.Level.WARN -> MaterialTheme.colorScheme.tertiary
                                ModuleLogSink.Level.INFO -> if (line.message.startsWith("[host]")) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface
                            },
                            modifier = if (place == null) Modifier else Modifier.clickable { onLocation(place.first, place.second) },
                        )
                    }
                }
            }
        }
    }
}
