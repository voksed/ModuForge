package dev.moduforge.host.ui.editor

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
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
import dev.moduforge.host.ui.titleRes
import dev.moduforge.sandbox.ModuleInstaller
import dev.moduforge.host.ui.languageRes
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

/** Source handed to the editor from outside a navigation argument, e.g. an imported `.lua` file. */
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
 * @property detected permissions the code uses; they are declared whether or not they are selected.
 */
data class EditorState(
    val loading: Boolean = true,
    val moduleId: String? = null,
    val name: String = "",
    val runtime: ModuleRuntimeKind = ModuleRuntimeKind.LUA,
    val source: String = "",
    val selected: Set<Capability> = emptySet(),
    val detected: Set<Capability> = emptySet(),
    val saving: Boolean = false,
    val problem: String? = null,
)

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
        viewModelScope.launch {
            val record = moduleId?.let { registry.find(it) }
            val draft = if (moduleId == null && templateKey == null) drafts.take() else null
            val template = ModuleTemplates.ALL.firstOrNull { it.key == templateKey }
            val source = when {
                record != null -> installer.readLocalSource(record.id).orEmpty()
                draft != null -> draft.source
                else -> template?.script.orEmpty()
            }
            val runtime = record?.manifest?.runtime ?: draft?.runtime ?: template?.runtime ?: ModuleRuntimeKind.LUA
            _state.value = EditorState(
                loading = false,
                moduleId = record?.id,
                name = record?.manifest?.name ?: draft?.name.orEmpty(),
                runtime = runtime,
                source = source,
                selected = record?.manifest?.permissions?.toSet() ?: template?.permissions?.keys.orEmpty(),
                detected = ScriptAnalyzer.detectPermissions(source, runtime),
            )
        }
    }

    fun setName(name: String) = _state.update { it.copy(name = name, problem = null) }

    fun setSource(source: String) =
        _state.update { it.copy(source = source, detected = ScriptAnalyzer.detectPermissions(source, it.runtime), problem = null) }

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
            val manifest = LocalModules.manifest(id, current.name, current.source, current.selected, previous?.manifest, current.runtime)
            when (val result = installer.saveLocal(manifest, current.source)) {
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

    companion object {
        const val ARG_MODULE_ID = "moduleId"
        const val ARG_TEMPLATE = "template"
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditorScreen(onSaved: (Saved) -> Unit, viewModel: EditorViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.saved.collect(onSaved) }
    if (state.loading) return

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

        OutlinedTextField(
            value = state.source,
            onValueChange = viewModel::setSource,
            label = { Text(stringResource(R.string.editor_code, stringResource(state.runtime.languageRes))) },
            textStyle = TextStyle(fontFamily = FontFamily.Monospace),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
            minLines = 14,
            modifier = Modifier.fillMaxWidth(),
        )

        state.problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        val ready = state.name.isNotBlank() && state.source.isNotBlank() && !state.saving
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
