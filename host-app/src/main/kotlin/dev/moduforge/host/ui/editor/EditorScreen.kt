package dev.moduforge.host.ui.editor

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.moduforge.core.authoring.LocalModules
import dev.moduforge.core.authoring.ModuleTemplates
import dev.moduforge.core.authoring.ScriptAnalyzer
import dev.moduforge.core.module.InstallResult
import dev.moduforge.core.module.ModuleManager
import dev.moduforge.core.module.ModuleRegistry
import dev.moduforge.core.module.ModuleState
import dev.moduforge.core.permission.PermissionBroker
import dev.moduforge.host.R
import dev.moduforge.host.ui.languageRes
import dev.moduforge.host.ui.titleRes
import dev.moduforge.sandbox.ModuleInstaller
import dev.moduforge.sdk.Capability
import dev.moduforge.sdk.ModuleRuntimeKind
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Source handed to the editor from outside a navigation argument, e.g. an imported script file. */
@Singleton
class EditorDrafts @Inject constructor() {
    class Draft(val name: String, val source: String, val runtime: ModuleRuntimeKind)

    private var pending: Draft? = null

    fun offer(draft: Draft) {
        pending = draft
    }

    fun take(): Draft? = pending.also { pending = null }
}

/** @property startRequested the user asked to run the module, and it still has to be asked for permissions. */
data class Saved(val moduleId: String, val startRequested: Boolean)

/**
 * @property moduleId null while the module has never been saved.
 * @property files sources by path; the entry script is always present and comes first.
 * @property current file shown in the code field.
 * @property detected permissions the code uses; they are declared whether or not they are selected.
 * @property jumpToLine 1-based line of [current] to put the cursor on when the editor opens.
 */
data class EditorState(
    val loading: Boolean = true,
    val moduleId: String? = null,
    val name: String = "",
    val runtime: ModuleRuntimeKind = ModuleRuntimeKind.LUA,
    val files: Map<String, String> = emptyMap(),
    val current: String = "",
    val selected: Set<Capability> = emptySet(),
    val detected: Set<Capability> = emptySet(),
    val jumpToLine: Int? = null,
    val saving: Boolean = false,
    val problem: String? = null,
) {
    val entry: String get() = LocalModules.entryFor(runtime)
    val source: String get() = files[current].orEmpty()
}

@HiltViewModel
class EditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    drafts: EditorDrafts,
    private val registry: ModuleRegistry,
    private val manager: ModuleManager,
    private val installer: ModuleInstaller,
    private val broker: PermissionBroker,
) : ViewModel() {

    private val _state = MutableStateFlow(EditorState())
    val state: StateFlow<EditorState> = _state

    private val _saved = Channel<Saved>(Channel.BUFFERED)

    /** Modules the editor finished with; the screen leaves when one arrives. */
    val saved = _saved.receiveAsFlow()

    init {
        val moduleId: String? = savedStateHandle[ARG_MODULE_ID]
        val templateKey: String? = savedStateHandle[ARG_TEMPLATE]
        val file: String? = savedStateHandle[ARG_FILE]
        val line: Int = savedStateHandle[ARG_LINE] ?: 0
        viewModelScope.launch {
            val record = moduleId?.let { registry.find(it) }
            val draft = if (moduleId == null && templateKey == null) drafts.take() else null
            val template = ModuleTemplates.ALL.firstOrNull { it.key == templateKey }
            val runtime = record?.manifest?.runtime ?: draft?.runtime ?: template?.runtime ?: ModuleRuntimeKind.LUA
            val entry = LocalModules.entryFor(runtime)
            val stored = record?.let { installer.readLocalFiles(it.id) }.orEmpty()
            val entrySource = stored[entry] ?: draft?.source ?: template?.script.orEmpty()
            val files = linkedMapOf(entry to entrySource) + (stored - entry).toSortedMap()
            val current = file?.takeIf { it in files } ?: entry
            _state.value = EditorState(
                loading = false,
                moduleId = record?.id,
                name = record?.manifest?.name ?: draft?.name.orEmpty(),
                runtime = runtime,
                files = files,
                current = current,
                selected = record?.manifest?.permissions?.toSet() ?: template?.permissions?.keys.orEmpty(),
                detected = detect(files, runtime),
                jumpToLine = line.takeIf { it > 0 && current == (file ?: entry) },
            )
        }
    }

    fun setName(name: String) = _state.update { it.copy(name = name, problem = null) }

    fun setSource(source: String) = _state.update {
        val files = it.files + (it.current to source)
        it.copy(files = files, detected = detect(files, it.runtime), problem = null)
    }

    fun selectFile(name: String) = _state.update { if (name in it.files) it.copy(current = name, jumpToLine = null) else it }

    /**
     * Adds an empty source file and opens it. The language's extension is added when missing.
     *
     * @return false when the name is not usable or already taken.
     */
    fun addFile(name: String): Boolean {
        val state = _state.value
        val extension = state.entry.substringAfterLast('.')
        val path = name.trim().removePrefix("./").let { if (LocalModules.runtimeForFile(it) == null) "$it.$extension" else it }
        if (!LocalModules.isSourceFileName(path, state.runtime) || path in state.files) return false
        _state.update { it.copy(files = it.files + (path to ""), current = path, jumpToLine = null) }
        return true
    }

    /** Removes the current file; the entry script cannot be removed. */
    fun deleteCurrentFile() = _state.update {
        if (it.current == it.entry) return@update it
        val files = it.files - it.current
        it.copy(files = files, current = it.entry, detected = detect(files, it.runtime), jumpToLine = null)
    }

    fun toggle(capability: Capability) =
        _state.update { it.copy(selected = if (capability in it.selected) it.selected - capability else it.selected + capability) }

    /** Stores the module, stopping it first when it is running. With [run] it is also enabled and started. */
    fun save(run: Boolean) {
        val current = _state.value
        if (current.saving) return
        _state.update { it.copy(saving = true, problem = null) }
        viewModelScope.launch {
            val previous = current.moduleId?.let { registry.find(it) }
            val installed = registry.observeAll().first().map { it.id }.toSet()
            val id = previous?.id ?: LocalModules.idFor(current.name) { it in installed }
            if (previous?.state == ModuleState.RUNNING) manager.stop(id, "stopped to save new code")
            val allSources = current.files.values.joinToString("\n")
            val manifest = LocalModules.manifest(id, current.name, allSources, current.selected, previous?.manifest, current.runtime)
            when (val result = installer.saveLocal(manifest, current.files)) {
                is InstallResult.Rejected ->
                    _state.update { it.copy(saving = false, problem = result.problems.joinToString("; ")) }
                is InstallResult.Installed -> {
                    var askBeforeStart = false
                    if (run) {
                        manager.setEnabled(id, true)
                        // Permissions are never granted from here; a module that still lacks some
                        // is started from its own screen, where the user is asked.
                        askBeforeStart = broker.grantableUpFront(id).isNotEmpty()
                        if (!askBeforeStart) manager.start(id)
                    }
                    _saved.send(Saved(id, askBeforeStart))
                }
            }
        }
    }

    private fun detect(files: Map<String, String>, runtime: ModuleRuntimeKind): Set<Capability> =
        ScriptAnalyzer.detectPermissions(files.values.joinToString("\n"), runtime)

    companion object {
        const val ARG_MODULE_ID = "moduleId"
        const val ARG_TEMPLATE = "template"
        const val ARG_FILE = "file"
        const val ARG_LINE = "line"
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditorScreen(onSaved: (Saved) -> Unit, viewModel: EditorViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.saved.collect(onSaved) }
    if (state.loading) return

    // The field owns cursor and selection; it is rebuilt only when another file is opened.
    var field by remember(state.current) {
        val jump = state.jumpToLine?.let { TextRange(offsetOfLine(state.source, it)) }
        mutableStateOf(TextFieldValue(state.source, jump ?: TextRange(state.source.length)))
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(state.current, state.jumpToLine) {
        if (state.jumpToLine != null) focus.requestFocus()
    }
    var addingFile by remember { mutableStateOf(false) }
    if (addingFile) {
        NewFileDialog(
            extension = state.entry.substringAfterLast('.'),
            onAdd = { name -> viewModel.addFile(name).also { added -> if (added) addingFile = false } },
            onCancel = { addingFile = false },
        )
    }

    Column(
        modifier = Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = state.name,
            onValueChange = viewModel::setName,
            label = { Text(stringResource(R.string.editor_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.editor_permissions), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LocalModules.AVAILABLE.forEach { capability ->
                    val used = capability in state.detected
                    FilterChip(
                        selected = used || capability in state.selected,
                        onClick = { viewModel.toggle(capability) },
                        enabled = !used,
                        label = { Text(stringResource(capability.titleRes)) },
                    )
                }
            }
            Text(
                stringResource(R.string.editor_permissions_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.editor_code, stringResource(state.runtime.languageRes)),
                style = MaterialTheme.typography.titleSmall,
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.files.keys.forEach { name ->
                    FilterChip(selected = name == state.current, onClick = { viewModel.selectFile(name) }, label = { Text(name) })
                }
                AssistChip(onClick = { addingFile = true }, label = { Text(stringResource(R.string.editor_add_file)) })
            }
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                snippets(state.runtime).forEach { snippet ->
                    AssistChip(
                        onClick = {
                            field = insertAtCursor(field, snippet.code)
                            viewModel.setSource(field.text)
                        },
                        label = { Text(snippet.label) },
                    )
                }
            }
        }

        CodeEditor(
            value = field,
            onValueChange = {
                field = it
                viewModel.setSource(it.text)
            },
            runtime = state.runtime,
            modifier = Modifier.focusRequester(focus),
        )
        if (state.current != state.entry) {
            TextButton(onClick = viewModel::deleteCurrentFile) {
                Text(stringResource(R.string.editor_delete_file, state.current))
            }
        }

        state.problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        val ready = state.name.isNotBlank() && state.files[state.entry].orEmpty().isNotBlank() && !state.saving
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { viewModel.save(run = true) }, enabled = ready) {
                Text(stringResource(R.string.editor_save_run))
            }
            OutlinedButton(onClick = { viewModel.save(run = false) }, enabled = ready) {
                Text(stringResource(R.string.editor_save))
            }
        }
        Text(
            stringResource(R.string.editor_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Asks for the name of a new source file. [onAdd] returns false when the name was refused. */
@Composable
private fun NewFileDialog(extension: String, onAdd: (String) -> Boolean, onCancel: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var refused by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.editor_add_file)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        refused = false
                    },
                    label = { Text(stringResource(R.string.editor_file_name, extension)) },
                    isError = refused,
                    singleLine = true,
                )
                Text(
                    stringResource(if (refused) R.string.editor_file_invalid else R.string.editor_file_hint, extension),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (refused) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { refused = !onAdd(name) }, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.editor_add_file))
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.import_cancel)) } },
    )
}
