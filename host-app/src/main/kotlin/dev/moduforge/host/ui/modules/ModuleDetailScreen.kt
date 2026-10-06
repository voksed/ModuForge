package dev.moduforge.host.ui.modules

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.moduforge.core.module.ModuleLogSink
import dev.moduforge.core.module.ModuleManager
import dev.moduforge.core.module.ModuleRecord
import dev.moduforge.core.module.ModuleRegistry
import dev.moduforge.core.module.ModuleRuntime
import dev.moduforge.host.runtime.ModuleUiStore
import dev.moduforge.sdk.ui.UiEvent
import dev.moduforge.sdk.ui.UiNode
import dev.moduforge.core.module.ModuleState
import dev.moduforge.core.permission.GrantRecord
import dev.moduforge.core.permission.GrantStore
import dev.moduforge.core.permission.PermissionBroker
import dev.moduforge.host.R
import dev.moduforge.host.runtime.ModuleLogLine
import dev.moduforge.host.runtime.ModuleLogs
import dev.moduforge.host.ui.descriptionRes
import dev.moduforge.host.ui.labelRes
import dev.moduforge.host.ui.titleRes
import dev.moduforge.sandbox.ModuleInstaller
import dev.moduforge.sdk.Capability
import dev.moduforge.sdk.ModuleRuntimeKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * @property module null when the module is not (or no longer) installed.
 * @property busy a lifecycle operation is in progress; controls are disabled meanwhile.
 */
data class ModuleDetailState(
    val module: ModuleRecord?,
    val grants: Map<Capability, GrantRecord>,
    val log: List<ModuleLogLine>,
    val ui: UiNode?,
    val busy: Boolean,
)

@HiltViewModel
class ModuleDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    registry: ModuleRegistry,
    grants: GrantStore,
    private val logs: ModuleLogs,
    uiStore: ModuleUiStore,
    private val runtime: ModuleRuntime,
    private val manager: ModuleManager,
    private val installer: ModuleInstaller,
    private val broker: PermissionBroker,
) : ViewModel() {

    private val moduleId: String = checkNotNull(savedStateHandle[ARG_MODULE_ID])
    private val busy = MutableStateFlow(false)

    /** Null until the first read completes. */
    val state: StateFlow<ModuleDetailState?> =
        combine(
            registry.observe(moduleId),
            grants.observe(moduleId),
            logs.observe(moduleId),
            uiStore.observe(moduleId),
            busy,
        ) { module, granted, log, ui, busy ->
            ModuleDetailState(module, granted.associateBy { it.capability }, log, ui, busy)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setEnabled(enabled: Boolean) = lifecycle { manager.setEnabled(moduleId, enabled) }

    fun setAutoStart(enabled: Boolean) {
        viewModelScope.launch { manager.setAutoStart(moduleId, enabled) }
    }

    private val _startPrompt = MutableStateFlow<List<Capability>?>(null)

    /** Permissions to offer before the module starts; null when no question is pending. */
    val startPrompt: StateFlow<List<Capability>?> = _startPrompt

    /** Starts the module, first offering the declared permissions it does not hold yet. */
    fun start() {
        viewModelScope.launch {
            val missing = broker.grantableUpFront(moduleId)
            if (missing.isEmpty()) launchModule() else _startPrompt.value = missing
        }
    }

    /** Continues a start held by [startPrompt], granting what the user selected. */
    fun confirmStart(selected: Set<Capability>) {
        _startPrompt.value = null
        lifecycle {
            broker.grantByUser(moduleId, selected)
            manager.start(moduleId)
        }
    }

    fun cancelStart() {
        _startPrompt.value = null
    }

    private fun launchModule() = lifecycle { manager.start(moduleId) }

    fun stop() = lifecycle { manager.stop(moduleId) }

    fun uninstall() = lifecycle {
        if (installer.uninstall(moduleId)) logs.clear(moduleId)
    }

    /** The module output as text, for sharing. */
    fun exportLog(): String = logs.export(moduleId)

    fun clearLog() = logs.clear(moduleId)

    /** Not serialized with other operations: killing must work while a callback is hanging. */
    fun kill() {
        viewModelScope.launch { manager.kill(moduleId) }
    }

    fun onUiEvent(event: UiEvent) = runtime.sendUiEvent(moduleId, event)

    fun revoke(capability: Capability) {
        viewModelScope.launch { broker.revoke(moduleId, capability) }
    }

    private fun lifecycle(operation: suspend () -> Unit) {
        if (!busy.compareAndSet(expect = false, update = true)) return
        viewModelScope.launch {
            try {
                operation()
            } finally {
                busy.value = false
            }
        }
    }

    init {
        // Arriving from the editor's "save and run": continue with the start the user asked for.
        if (savedStateHandle.get<Boolean>(ARG_START) == true) {
            savedStateHandle[ARG_START] = false
            start()
        }
    }

    companion object {
        const val ARG_MODULE_ID = "moduleId"
        const val ARG_START = "start"
    }
}

@Composable
fun ModuleDetailScreen(onGone: () -> Unit, onEdit: (String) -> Unit, viewModel: ModuleDetailViewModel = hiltViewModel()) {
    val state = viewModel.state.collectAsStateWithLifecycle().value ?: return
    val module = state.module

    if (module == null) {
        LaunchedEffect(Unit) { onGone() }
        Text(stringResource(R.string.module_not_found), modifier = Modifier.padding(16.dp))
        return
    }

    viewModel.startPrompt.collectAsStateWithLifecycle().value?.let { missing ->
        StartPermissionsDialog(module.manifest, missing, onStart = viewModel::confirmStart, onCancel = viewModel::cancelStart)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "header") { Header(module, state.busy, onEnabledChange = viewModel::setEnabled) }

        item(key = "run") {
            RunControls(module.state, state.busy, viewModel::start, viewModel::stop, viewModel::kill)
        }
        if (module.signer == null && module.manifest.runtime == ModuleRuntimeKind.LUA) {
            item(key = "edit") {
                OutlinedButton(onClick = { onEdit(module.id) }) { Text(stringResource(R.string.module_edit)) }
            }
        }
        if (Capability.BACKGROUND_EXECUTION in module.manifest.permissions) {
            item(key = "autostart") { AutoStartSwitch(module.autoStart, viewModel::setAutoStart) }
        }

        state.ui?.let { root ->
            item(key = "ui") { ModuleUiCard(module.manifest.name, root, viewModel::onUiEvent) }
        }

        item(key = "permissions-header") {
            Text(stringResource(R.string.module_permissions_header), style = MaterialTheme.typography.titleMedium)
        }
        if (module.manifest.permissions.isEmpty()) {
            item(key = "permissions-none") {
                Text(
                    stringResource(R.string.module_permissions_none),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(module.manifest.permissions, key = { it.name }) { capability ->
            PermissionCard(capability, state.grants[capability], onRevoke = { viewModel.revoke(capability) })
        }

        item(key = "log") {
            val context = LocalContext.current
            val title = stringResource(R.string.module_log_share_title, module.manifest.name)
            LogCard(
                lines = state.log,
                onShare = {
                    val send = Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_SUBJECT, title)
                        .putExtra(Intent.EXTRA_TEXT, viewModel.exportLog())
                    context.startActivity(Intent.createChooser(send, title))
                },
                onClear = viewModel::clearLog,
            )
        }

        item(key = "uninstall") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(
                    onClick = viewModel::uninstall,
                    enabled = module.state == ModuleState.INSTALLED && !state.busy,
                ) {
                    Text(stringResource(R.string.module_uninstall))
                }
                Text(
                    stringResource(R.string.module_uninstall_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Header(module: ModuleRecord, busy: Boolean, onEnabledChange: (Boolean) -> Unit) {
    val manifest = module.manifest
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(manifest.name, style = MaterialTheme.typography.headlineSmall)
        Text(manifest.id, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            stringResource(
                R.string.module_version_author,
                manifest.version,
                manifest.author.ifBlank { stringResource(R.string.module_author_unknown) },
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        val enabled = module.state != ModuleState.INSTALLED
        val switchable = module.state != ModuleState.RUNNING && !busy
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().toggleable(
                value = enabled,
                enabled = switchable,
                role = Role.Switch,
                onValueChange = onEnabledChange,
            ),
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.module_enabled_switch), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.module_enabled_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = enabled, enabled = switchable, onCheckedChange = null)
        }
    }
}

@Composable
private fun AutoStartSwitch(enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().toggleable(value = enabled, role = Role.Switch, onValueChange = onChange),
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.module_autostart_switch), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.module_autostart_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = enabled, onCheckedChange = null)
    }
}

@Composable
private fun RunControls(state: ModuleState, busy: Boolean, onStart: () -> Unit, onStop: () -> Unit, onKill: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(state.labelRes), style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onStart, enabled = state == ModuleState.ENABLED && !busy) {
                Text(stringResource(R.string.module_start))
            }
            OutlinedButton(onClick = onStop, enabled = state == ModuleState.RUNNING && !busy) {
                Text(stringResource(R.string.module_stop))
            }
            OutlinedButton(onClick = onKill, enabled = state == ModuleState.RUNNING) {
                Text(stringResource(R.string.module_kill))
            }
        }
        Text(
            stringResource(R.string.module_run_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PermissionCard(capability: Capability, grant: GrantRecord?, onRevoke: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(capability.titleRes), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(capability.sensitivity.labelRes) + " · " +
                    stringResource(if (grant != null) R.string.grant_granted else R.string.grant_not_granted),
                style = MaterialTheme.typography.labelLarge,
                color = if (grant != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(stringResource(capability.descriptionRes), style = MaterialTheme.typography.bodyMedium)
            if (grant != null && grant.authorizedTargets.isNotEmpty()) {
                Text(
                    stringResource(R.string.grant_targets, grant.authorizedTargets.sorted().joinToString()),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (grant != null) {
                TextButton(onClick = onRevoke) { Text(stringResource(R.string.grant_revoke)) }
            }
        }
    }
}

@Composable
private fun LogCard(lines: List<ModuleLogLine>, onShare: () -> Unit, onClear: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.module_log_header), style = MaterialTheme.typography.titleMedium)
        if (lines.isEmpty()) {
            Text(stringResource(R.string.module_log_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onShare) { Text(stringResource(R.string.module_log_share)) }
                TextButton(onClick = onClear) { Text(stringResource(R.string.module_log_clear)) }
            }
        }
        lines.takeLast(VISIBLE_LOG_LINES).forEach { line ->
            Text(
                line.message,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = if (line.level == ModuleLogSink.Level.ERROR) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }
    }
}

private const val VISIBLE_LOG_LINES = 100
