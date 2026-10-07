package dev.moduforge.host.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.moduforge.host.BuildConfig
import dev.moduforge.host.R
import dev.moduforge.host.update.UpdateState

/** Checking for a newer version of the app, downloading it and starting its installation. */
@Composable
internal fun UpdateSection(
    state: UpdateState,
    checkOnStart: Boolean,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onCheckOnStartChange: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.update_title), style = MaterialTheme.typography.titleLarge)
        Text(
            stringResource(R.string.update_current, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.bodyMedium,
        )

        when (state) {
            UpdateState.Idle -> Unit
            UpdateState.Checking -> Text(stringResource(R.string.update_checking))
            UpdateState.UpToDate -> Text(stringResource(R.string.update_up_to_date), color = MaterialTheme.colorScheme.primary)
            is UpdateState.Available -> {
                Text(stringResource(R.string.update_available, state.update.version), style = MaterialTheme.typography.titleMedium)
                if (state.update.notes.isNotBlank()) Text(state.update.notes, style = MaterialTheme.typography.bodySmall)
                if (state.update.sizeBytes > 0) {
                    Text(
                        stringResource(R.string.update_size, state.update.sizeBytes / (1024 * 1024) + 1),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            is UpdateState.Downloading -> {
                Text(stringResource(R.string.update_downloading, state.update.version))
                if (state.progress >= 0f) {
                    LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            is UpdateState.Downloaded -> {
                Text(stringResource(R.string.update_downloaded, state.update.version), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.update_downloaded_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            is UpdateState.Failed -> Text(
                stringResource(
                    when (state.reason) {
                        UpdateState.Reason.NETWORK -> R.string.update_failed_network
                        UpdateState.Reason.DOWNLOAD -> R.string.update_failed_download
                        UpdateState.Reason.WRONG_APP -> R.string.update_failed_wrong_app
                        UpdateState.Reason.INSTALL_NOT_ALLOWED -> R.string.update_failed_not_allowed
                    },
                ) + state.detail?.let { " ($it)" }.orEmpty(),
                color = MaterialTheme.colorScheme.error,
            )
        }

        when (state) {
            is UpdateState.Available -> Button(onClick = onDownload) { Text(stringResource(R.string.update_download)) }
            is UpdateState.Downloaded -> Button(onClick = onInstall) { Text(stringResource(R.string.update_install)) }
            UpdateState.Checking, is UpdateState.Downloading -> Unit
            else -> OutlinedButton(onClick = onCheck) { Text(stringResource(R.string.update_check)) }
        }

        Toggle(R.string.update_on_start, R.string.update_on_start_hint, checkOnStart, onCheckOnStartChange)
    }
}
