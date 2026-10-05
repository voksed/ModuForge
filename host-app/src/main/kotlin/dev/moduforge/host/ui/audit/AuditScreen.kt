package dev.moduforge.host.ui.audit

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.moduforge.core.audit.AuditEvent
import dev.moduforge.core.audit.AuditLog
import dev.moduforge.host.R
import dev.moduforge.host.ui.labelRes
import dev.moduforge.host.ui.titleRes
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

@HiltViewModel
class AuditViewModel @Inject constructor(audit: AuditLog) : ViewModel() {

    /** Newest first; null until the first read completes. */
    val events: StateFlow<List<AuditEvent>?> =
        audit.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@Composable
fun AuditScreen(viewModel: AuditViewModel = hiltViewModel()) {
    val events = viewModel.events.collectAsStateWithLifecycle().value ?: return
    if (events.isEmpty()) {
        Text(
            stringResource(R.string.audit_empty),
            modifier = Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val timeFormat = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM) }

    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        items(events, key = { it.id }) { event ->
            AuditRow(event, timeFormat.format(Date(event.timestampMs)))
            HorizontalDivider()
        }
    }
}

@Composable
private fun AuditRow(event: AuditEvent, time: String) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        val capability = event.capability
        Text(
            if (capability == null) {
                stringResource(event.type.labelRes)
            } else {
                stringResource(event.type.labelRes) + ": " + stringResource(capability.titleRes)
            },
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            "$time · ${event.moduleId}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        event.target?.let {
            Text(stringResource(R.string.audit_target, it), style = MaterialTheme.typography.bodySmall)
        }
        if (event.detail.isNotBlank()) {
            Text(event.detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}
