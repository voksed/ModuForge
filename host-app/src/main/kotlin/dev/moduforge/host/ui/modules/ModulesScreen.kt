package dev.moduforge.host.ui.modules

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.FilterChip
import dev.moduforge.core.authoring.LocalModules
import dev.moduforge.core.authoring.ModuleTemplate
import dev.moduforge.host.ui.languageRes
import dev.moduforge.sdk.ModuleRuntimeKind
import dev.moduforge.core.authoring.ModuleTemplates
import dev.moduforge.host.ui.editor.EditorDrafts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Button
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.moduforge.sandbox.ModuleInstaller
import dev.moduforge.sandbox.PackageInspection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import java.io.IOException
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.moduforge.core.module.InstallResult
import dev.moduforge.core.module.ModuleRecord
import dev.moduforge.core.module.ModuleRegistry
import dev.moduforge.host.R
import dev.moduforge.host.ui.labelRes
import dev.moduforge.host.ui.theme.LocalAppearance
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ModulesViewModel @Inject constructor(
    registry: ModuleRegistry,
    private val drafts: EditorDrafts,
    private val installer: ModuleInstaller,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _review = MutableStateFlow<PackageInspection.Ready?>(null)

    /** Verified package awaiting the user's decision, or null. */
    val review: StateFlow<PackageInspection.Ready?> = _review

    private val _sources = Channel<Unit>(Channel.BUFFERED)

    /** Signals that a picked script was handed to the editor and the editor should open. */
    val sources = _sources.receiveAsFlow()

    /**
     * Handles a file the user picked. A package is verified and put up for [review];
     * anything else is taken as a script and opened in the editor, where the user sees
     * the code before it becomes a module.
     */
    fun inspect(uri: Uri) {
        viewModelScope.launch {
            val script = withContext(Dispatchers.IO) { readScript(uri) }
            if (script != null) {
                drafts.offer(script)
                _sources.send(Unit)
                return@launch
            }
            val result = installer.inspect {
                context.contentResolver.openInputStream(uri) ?: throw IOException("cannot open $uri")
            }
            when (result) {
                is PackageInspection.Ready -> _review.getAndUpdate { result }?.let { installer.discard(it) }
                is PackageInspection.Rejected -> _rejections.send(result.reason)
            }
        }
    }

    /** The file as an editor draft, or null when it is a package (ZIP), unreadable, too large or not text. */
    private fun readScript(uri: Uri): EditorDrafts.Draft? = try {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(MAX_SCRIPT_BYTES + 1)
            var size = 0
            while (size < buffer.size) {
                val read = input.read(buffer, size, buffer.size - size)
                if (read < 0) break
                size += read
            }
            buffer.copyOf(size)
        }
        val isPackage = bytes != null && bytes.size >= 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
        if (bytes == null || isPackage || bytes.size > MAX_SCRIPT_BYTES || bytes.any { it == 0.toByte() }) {
            null
        } else {
            val fileName = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            EditorDrafts.Draft(
                name = fileName.orEmpty().substringBeforeLast('.'),
                source = bytes.decodeToString(),
                runtime = LocalModules.runtimeForFile(fileName.orEmpty()) ?: ModuleRuntimeKind.LUA,
            )
        }
    } catch (e: IOException) {
        null
    } catch (e: SecurityException) {
        null
    }

    fun confirmImport() {
        val inspected = _review.getAndUpdate { null } ?: return
        viewModelScope.launch {
            val result = installer.install(inspected)
            if (result is InstallResult.Rejected) _rejections.send(result.problems.joinToString("; "))
        }
    }

    fun cancelImport() {
        val inspected = _review.getAndUpdate { null } ?: return
        viewModelScope.launch { installer.discard(inspected) }
    }

    /** Null until the first read of the registry completes. */
    val modules: StateFlow<List<ModuleRecord>?> =
        registry.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _rejections = Channel<String>(Channel.BUFFERED)

    /** Reasons of failed installations. */
    val rejections = _rejections.receiveAsFlow()

    private companion object {
        /** A script has to fit into module storage limits and the editor. */
        const val MAX_SCRIPT_BYTES = 256 * 1024
    }
}

@Composable
fun ModulesScreen(
    onOpenModule: (String) -> Unit,
    onOpenEditor: (template: String?) -> Unit,
    onMessage: suspend (String) -> Unit,
    viewModel: ModulesViewModel = hiltViewModel(),
) {
    val modules = viewModel.modules.collectAsStateWithLifecycle().value ?: return
    var choosingTemplate by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.sources.collect { onOpenEditor(null) }
    }
    if (choosingTemplate) {
        TemplateDialog(
            onPick = { key ->
                choosingTemplate = false
                onOpenEditor(key)
            },
            onCancel = { choosingTemplate = false },
        )
    }

    val rejectedPrefix = stringResource(R.string.import_rejected)
    LaunchedEffect(viewModel, rejectedPrefix) {
        viewModel.rejections.collect { onMessage("$rejectedPrefix: $it") }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.inspect(uri)
    }
    viewModel.review.collectAsStateWithLifecycle().value?.let { inspection ->
        ImportReviewDialog(inspection, onInstall = viewModel::confirmImport, onCancel = viewModel::cancelImport)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (modules.isEmpty()) {
            item(key = "empty") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.modules_empty_title), style = MaterialTheme.typography.titleLarge)
                    Text(
                        stringResource(R.string.modules_empty_body),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        items(modules, key = { it.id }) { module ->
            ModuleCard(module, onClick = { onOpenModule(module.id) })
        }
        item(key = "create") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = { choosingTemplate = true }) {
                    Text(stringResource(R.string.create_button))
                }
                Text(
                    stringResource(R.string.create_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item(key = "import") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) {
                    Text(stringResource(R.string.import_button))
                }
                Text(
                    stringResource(R.string.import_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Lets the user choose what a new module starts from. */
@Composable
private fun TemplateDialog(onPick: (String) -> Unit, onCancel: () -> Unit) {
    var language by remember { mutableStateOf(LocalModules.RUNTIMES.first()) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.create_title)) },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LocalModules.RUNTIMES.forEach { runtime ->
                        FilterChip(
                            selected = runtime == language,
                            onClick = { language = runtime },
                            label = { Text(stringResource(runtime.languageRes)) },
                        )
                    }
                }
                ModuleTemplates.forRuntime(language).forEach { template ->
                    TextButton(onClick = { onPick(template.key) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(template.labelRes), modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.import_cancel)) } },
    )
}

private val ModuleTemplate.labelRes: Int
    get() = when (kind) {
        ModuleTemplates.TELEGRAM -> R.string.template_telegram
        ModuleTemplates.WATCHER -> R.string.template_watcher
        else -> R.string.template_empty
    }

@Composable
private fun ModuleCard(module: ModuleRecord, onClick: () -> Unit) {
    if (LocalAppearance.current.compactList) {
        Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp)) {
            Text(module.manifest.name, style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(module.state.labelRes) + " · " + module.id,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(module.manifest.name, style = MaterialTheme.typography.titleMedium)
            Text(
                module.id,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(module.state.labelRes) + " · " +
                    stringResource(R.string.modules_permissions_count, module.manifest.permissions.size),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
