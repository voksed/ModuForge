package dev.moduforge.host.ui.editor

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import dev.moduforge.core.authoring.ProjectFiles
import dev.moduforge.core.authoring.ScriptAnalyzer
import dev.moduforge.core.module.InstallResult
import dev.moduforge.core.module.ModuleManager
import dev.moduforge.core.module.ModuleRegistry
import dev.moduforge.core.module.ModuleState
import dev.moduforge.core.permission.PermissionBroker
import dev.moduforge.host.R
import dev.moduforge.host.runtime.ModuleLogLine
import dev.moduforge.host.runtime.ModuleLogs
import dev.moduforge.host.ui.labelRes
import dev.moduforge.host.ui.languageRes
import dev.moduforge.host.ui.modules.StartPermissionsDialog
import dev.moduforge.host.ui.titleRes
import dev.moduforge.sandbox.ModuleInstaller
import dev.moduforge.sdk.Capability
import dev.moduforge.sdk.ModuleManifest
import dev.moduforge.sdk.ModuleRuntimeKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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

/**
 * @property moduleId null while the module has never been saved.
 * @property files sources by path; the entry script is always present.
 * @property folders folders that hold no file yet.
 * @property folder folder the "new file" and "new folder" buttons act in; empty for the module root.
 * @property expanded folders opened in the tree.
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
    val folders: Set<String> = emptySet(),
    val folder: String = "",
    val expanded: Set<String> = emptySet(),
    val current: String = "",
    val selected: Set<Capability> = emptySet(),
    val detected: Set<Capability> = emptySet(),
    val jumpToLine: Int? = null,
    val saving: Boolean = false,
    val problem: String? = null,
) {
    val entry: String get() = LocalModules.entryFor(runtime)
    val source: String get() = files[current].orEmpty()
    val tree: ProjectFiles.Tree get() = ProjectFiles.Tree(files, folders)
}

/** Permissions the module still lacks, offered to the user before it starts. */
class StartPrompt(val manifest: ModuleManifest, val missing: List<Capability>)

@HiltViewModel
class EditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    drafts: EditorDrafts,
    private val registry: ModuleRegistry,
    private val manager: ModuleManager,
    private val installer: ModuleInstaller,
    private val broker: PermissionBroker,
    private val logs: ModuleLogs,
) : ViewModel() {

    private val _state = MutableStateFlow(EditorState())
    val state: StateFlow<EditorState> = _state

    private val moduleIds = _state.map { it.moduleId }.distinctUntilChanged()

    /** Output of the module being edited, oldest line first. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val output: StateFlow<List<ModuleLogLine>> = moduleIds
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else logs.observe(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Where the module is in its lifecycle; null while it has not been saved. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val runState: StateFlow<ModuleState?> = moduleIds
        .flatMapLatest { id -> if (id == null) flowOf(null) else registry.observe(id).map { it?.state } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _startPrompt = MutableStateFlow<StartPrompt?>(null)
    val startPrompt: StateFlow<StartPrompt?> = _startPrompt

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
                folder = ProjectFiles.folderOf(ProjectFiles.Tree(files), current),
                expanded = ProjectFiles.parentsOf(current).toSet(),
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

    fun selectFile(path: String) = _state.update {
        if (path !in it.files) return@update it
        it.copy(
            current = path,
            folder = ProjectFiles.folderOf(it.tree, path),
            expanded = it.expanded + ProjectFiles.parentsOf(path),
            jumpToLine = null,
        )
    }

    /** Opens or closes a folder and makes it the place where new files and folders are made. */
    fun toggleFolder(path: String) = _state.update {
        it.copy(folder = path, expanded = if (path in it.expanded) it.expanded - path else it.expanded + path)
    }

    /** Makes the module root the place for new files and folders. */
    fun selectRoot() = _state.update { it.copy(folder = "") }

    /**
     * Adds an empty source file in the selected folder and opens it. The language's extension
     * is added when missing.
     *
     * @return false when the name is not usable or already taken.
     */
    fun addFile(input: String): Boolean {
        val state = _state.value
        val path = ProjectFiles.newFile(state.tree, input, state.folder, state.runtime) ?: return false
        _state.update {
            it.copy(
                files = it.files + (path to ""),
                current = path,
                folder = ProjectFiles.folderOf(ProjectFiles.Tree(it.files + (path to ""), it.folders), path),
                expanded = it.expanded + ProjectFiles.parentsOf(path),
                jumpToLine = null,
            )
        }
        return true
    }

    /** @return false when the name is not usable or already taken. */
    fun addFolder(input: String): Boolean {
        val state = _state.value
        val path = ProjectFiles.newFolder(state.tree, input, state.folder) ?: return false
        _state.update {
            it.copy(folders = it.folders + path, folder = path, expanded = it.expanded + ProjectFiles.parentsOf(path) + path)
        }
        return true
    }

    /** Removes a file, or a folder with everything in it; the entry script stays. */
    fun delete(path: String) = _state.update {
        val tree = ProjectFiles.delete(it.tree, path, it.entry) ?: return@update it
        val current = if (it.current in tree.files) it.current else it.entry
        it.copy(
            files = tree.files,
            folders = tree.folders,
            current = current,
            folder = it.folder.takeIf { folder -> folder.isEmpty() || folder in tree.allFolders } ?: ProjectFiles.folderOf(tree, current),
            detected = detect(tree.files, it.runtime),
            jumpToLine = null,
        )
    }

    /**
     * Moves or renames a file or a folder to [to], a path from the module root.
     *
     * @return false when the move is refused.
     */
    fun move(from: String, to: String): Boolean {
        val state = _state.value
        val target = to.trim().removePrefix("/")
        val tree = ProjectFiles.move(state.tree, from, target, state.runtime, state.entry) ?: return false
        fun follow(path: String) = if (path == from || path.startsWith("$from/")) target + path.removePrefix(from) else path
        _state.update {
            it.copy(
                files = tree.files,
                folders = tree.folders,
                current = follow(it.current),
                folder = follow(it.folder),
                expanded = it.expanded.mapTo(mutableSetOf(), ::follow) + ProjectFiles.parentsOf(target),
            )
        }
        return true
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
                    _state.update { it.copy(saving = false, moduleId = id) }
                    if (run) {
                        logs.clear(id)
                        manager.setEnabled(id, true)
                        // Permissions are granted only by the user, here or on the module's screen.
                        val missing = broker.grantableUpFront(id)
                        if (missing.isEmpty()) manager.start(id) else _startPrompt.value = StartPrompt(manifest, missing)
                    }
                }
            }
        }
    }

    /** Continues a run held by [startPrompt], granting what the user selected. */
    fun confirmStart(selected: Set<Capability>) {
        val id = _state.value.moduleId ?: return
        _startPrompt.value = null
        viewModelScope.launch {
            broker.grantByUser(id, selected)
            manager.start(id)
        }
    }

    fun cancelStart() {
        _startPrompt.value = null
    }

    fun stop() {
        val id = _state.value.moduleId ?: return
        viewModelScope.launch { manager.stop(id) }
    }

    fun clearOutput() {
        _state.value.moduleId?.let(logs::clear)
    }

    fun exportOutput(): String = _state.value.moduleId?.let(logs::export).orEmpty()

    private fun detect(files: Map<String, String>, runtime: ModuleRuntimeKind): Set<Capability> =
        ScriptAnalyzer.detectPermissions(files.values.joinToString("\n"), runtime)

    companion object {
        const val ARG_MODULE_ID = "moduleId"
        const val ARG_TEMPLATE = "template"
        const val ARG_FILE = "file"
        const val ARG_LINE = "line"
    }
}

/** Width from which the file tree gets the room of a desktop editor. */
private val WIDE = 600.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditorScreen(onOpenModule: (String) -> Unit, viewModel: EditorViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val output by viewModel.output.collectAsStateWithLifecycle()
    val runState by viewModel.runState.collectAsStateWithLifecycle()
    val prompt by viewModel.startPrompt.collectAsStateWithLifecycle()
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

    var showTree by rememberSaveable { mutableStateOf(true) }
    var terminalSize by rememberSaveable { mutableStateOf(1) }
    var dialog by remember { mutableStateOf<EditorDialog?>(null) }
    val imeVisible = WindowInsets.isImeVisible

    prompt?.let { StartPermissionsDialog(it.manifest, it.missing, onStart = viewModel::confirmStart, onCancel = viewModel::cancelStart) }
    dialog?.let { current ->
        EditorDialogs(current, state, viewModel, onDone = { dialog = null })
    }

    BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
        val wide = maxWidth >= WIDE
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                OutlinedTextField(
                    value = state.name,
                    onValueChange = viewModel::setName,
                    label = { Text(stringResource(R.string.editor_name)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { dialog = EditorDialog.Permissions }) {
                    Text(stringResource(R.string.editor_permissions_button, (state.detected + state.selected).size))
                }
            }

            val ready = state.name.isNotBlank() && state.files[state.entry].orEmpty().isNotBlank() && !state.saving
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = { showTree = !showTree }) { Text(stringResource(R.string.editor_files)) }
                OutlinedButton(onClick = { viewModel.save(run = false) }, enabled = ready) { Text(stringResource(R.string.editor_save)) }
                if (runState == ModuleState.RUNNING) {
                    Button(onClick = viewModel::stop) { Text(stringResource(R.string.editor_stop)) }
                } else {
                    Button(onClick = { viewModel.save(run = true) }, enabled = ready) { Text(stringResource(R.string.editor_run)) }
                }
                state.moduleId?.let { id ->
                    TextButton(onClick = { onOpenModule(id) }) { Text(stringResource(R.string.editor_open_module)) }
                }
            }
            state.problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

            Row(Modifier.weight(1f).fillMaxWidth()) {
                if (showTree) {
                    FileTreePanel(
                        rows = ProjectFiles.rows(state.tree, state.expanded),
                        current = state.current,
                        folder = state.folder,
                        entry = state.entry,
                        onOpen = viewModel::selectFile,
                        onToggleFolder = viewModel::toggleFolder,
                        onRoot = viewModel::selectRoot,
                        onNewFile = { dialog = EditorDialog.NewFile },
                        onNewFolder = { dialog = EditorDialog.NewFolder },
                        onRename = { dialog = EditorDialog.Rename(it) },
                        onDelete = { dialog = EditorDialog.Delete(it) },
                        modifier = Modifier.width(if (wide) 220.dp else 136.dp).fillMaxHeight(),
                    )
                    VerticalDivider(Modifier.padding(horizontal = 6.dp))
                }
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        state.current + "  ·  " + stringResource(state.runtime.languageRes),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        CodeEditor(
                            value = field,
                            onValueChange = {
                                field = it
                                viewModel.setSource(it.text)
                            },
                            runtime = state.runtime,
                            minHeight = 160.dp,
                            modifier = Modifier.focusRequester(focus),
                        )
                    }
                }
            }

            HorizontalDivider()
            TerminalPanel(
                lines = output,
                runState = runState,
                size = if (imeVisible) 0 else terminalSize,
                onCycleSize = { terminalSize = (terminalSize + 1) % 3 },
                onClear = viewModel::clearOutput,
                shareText = viewModel::exportOutput,
                onLocation = { file, line ->
                    viewModel.selectFile(file)
                    field = TextFieldValue(viewModel.state.value.source, TextRange(offsetOfLine(viewModel.state.value.source, line)))
                },
            )
        }
    }
}

/** Dialogs of the editor; only one is open at a time. */
internal sealed interface EditorDialog {
    data object Permissions : EditorDialog
    data object NewFile : EditorDialog
    data object NewFolder : EditorDialog
    data class Rename(val path: String) : EditorDialog
    data class Delete(val path: String) : EditorDialog
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditorDialogs(dialog: EditorDialog, state: EditorState, viewModel: EditorViewModel, onDone: () -> Unit) {
    when (dialog) {
        EditorDialog.Permissions -> AlertDialog(
            onDismissRequest = onDone,
            title = { Text(stringResource(R.string.editor_permissions)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
            },
            confirmButton = { TextButton(onClick = onDone) { Text(stringResource(R.string.editor_done)) } },
        )
        EditorDialog.NewFile -> NameDialog(
            title = R.string.editor_add_file,
            label = stringResource(R.string.editor_file_name, state.entry.substringAfterLast('.')),
            hint = stringResource(R.string.editor_file_hint, state.entry.substringAfterLast('.')),
            error = stringResource(R.string.editor_file_invalid, state.entry.substringAfterLast('.')),
            location = state.folder,
            initial = "",
            confirm = R.string.editor_add_file,
            onConfirm = viewModel::addFile,
            onDone = onDone,
        )
        EditorDialog.NewFolder -> NameDialog(
            title = R.string.editor_add_folder,
            label = stringResource(R.string.editor_folder_name),
            hint = stringResource(R.string.editor_folder_hint),
            error = stringResource(R.string.editor_folder_invalid),
            location = state.folder,
            initial = "",
            confirm = R.string.editor_add_folder,
            onConfirm = viewModel::addFolder,
            onDone = onDone,
        )
        is EditorDialog.Rename -> NameDialog(
            title = R.string.editor_rename,
            label = stringResource(R.string.editor_new_path),
            hint = stringResource(R.string.editor_rename_hint),
            error = stringResource(R.string.editor_rename_invalid),
            location = null,
            initial = dialog.path,
            confirm = R.string.editor_rename,
            onConfirm = { viewModel.move(dialog.path, it) },
            onDone = onDone,
        )
        is EditorDialog.Delete -> AlertDialog(
            onDismissRequest = onDone,
            title = { Text(stringResource(R.string.editor_delete_title, dialog.path)) },
            text = {
                val folder = dialog.path in state.tree.allFolders
                val count = state.files.keys.count { it.startsWith(dialog.path + "/") }
                Text(if (folder) stringResource(R.string.editor_delete_folder_body, count) else stringResource(R.string.editor_delete_file_body))
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(dialog.path)
                    onDone()
                }) { Text(stringResource(R.string.editor_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = onDone) { Text(stringResource(R.string.import_cancel)) } },
        )
    }
}

/**
 * Asks for a name. [onConfirm] returns false when the name was refused; the dialog then stays
 * open and explains. [location] is the folder the name is relative to, or null when it is a full path.
 */
@Composable
private fun NameDialog(
    title: Int,
    label: String,
    hint: String,
    error: String,
    location: String?,
    initial: String,
    confirm: Int,
    onConfirm: (String) -> Boolean,
    onDone: () -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    var refused by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(stringResource(title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (location != null) {
                    Text(
                        stringResource(R.string.editor_in_folder, location.ifEmpty { stringResource(R.string.editor_root) }),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        refused = false
                    },
                    label = { Text(label) },
                    isError = refused,
                    singleLine = true,
                )
                Text(
                    if (refused) error else hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (refused) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (onConfirm(name)) onDone() else refused = true
                },
                enabled = name.isNotBlank(),
            ) { Text(stringResource(confirm)) }
        },
        dismissButton = { TextButton(onClick = onDone) { Text(stringResource(R.string.import_cancel)) } },
    )
}
