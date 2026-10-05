package dev.moduforge.host.ui.modules

import android.content.Context
import android.net.Uri
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
    private val installer: ModuleInstaller,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _review = MutableStateFlow<PackageInspection.Ready?>(null)

    /** Verified package awaiting the user's decision, or null. */
    val review: StateFlow<PackageInspection.Ready?> = _review

    /** Verifies a package the user picked; a valid one is put up for [review]. */
    fun inspect(uri: Uri) {
        viewModelScope.launch {
            val result = installer.inspect {
                context.contentResolver.openInputStream(uri) ?: throw IOException("cannot open $uri")
            }
            when (result) {
                is PackageInspection.Ready -> _review.getAndUpdate { result }?.let { installer.discard(it) }
                is PackageInspection.Rejected -> _rejections.send(result.reason)
            }
        }
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
}

@Composable
fun ModulesScreen(
    onOpenModule: (String) -> Unit,
    onMessage: suspend (String) -> Unit,
    viewModel: ModulesViewModel = hiltViewModel(),
) {
    val modules = viewModel.modules.collectAsStateWithLifecycle().value ?: return

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
        item(key = "import") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = { picker.launch(arrayOf("*/*")) }) {
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
