package dev.moduforge.host.ui.settings

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.moduforge.host.R
import dev.moduforge.host.appearance.AppLanguage

/** The language of the app: the system's, English or Russian. The change applies at once. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LanguageSection() {
    val context = LocalContext.current
    var selected by remember { mutableStateOf(AppLanguage.get(context)) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.language_title), style = MaterialTheme.typography.titleLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val options = listOf(AppLanguage.SYSTEM to stringResource(R.string.language_system)) + AppLanguage.SUPPORTED
            options.forEach { (code, label) ->
                FilterChip(
                    selected = selected == code,
                    onClick = {
                        if (selected != code) {
                            selected = code
                            AppLanguage.set(context, code)
                            // The texts are read again with the new language.
                            (context as? Activity)?.recreate()
                        }
                    },
                    label = { Text(label) },
                )
            }
        }
    }
}
