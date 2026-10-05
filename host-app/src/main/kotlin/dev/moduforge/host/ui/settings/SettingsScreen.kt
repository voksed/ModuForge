package dev.moduforge.host.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.moduforge.host.BuildConfig
import dev.moduforge.host.R
import dev.moduforge.host.appearance.Appearance
import dev.moduforge.host.appearance.AppearanceStore
import dev.moduforge.host.appearance.CornerStyle
import dev.moduforge.host.appearance.PaletteKind
import dev.moduforge.host.appearance.ThemeMode
import dev.moduforge.host.ui.theme.wallpaperColorsAvailable
import dev.moduforge.sdk.ModuForgeSdk
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(private val store: AppearanceStore) : ViewModel() {
    val appearance = store.appearance

    fun update(change: (Appearance) -> Appearance) = store.update(change)

    fun reset() = store.reset()
}

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val appearance by viewModel.appearance.collectAsStateWithLifecycle()
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        AppearanceSection(appearance, viewModel::update, viewModel::reset)
        HorizontalDivider()
        Section(R.string.settings_offline_title, R.string.settings_offline_body)
        Section(R.string.settings_responsible_title, R.string.settings_responsible_body)
        Text(
            stringResource(R.string.settings_versions, BuildConfig.VERSION_NAME, ModuForgeSdk.VERSION),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppearanceSection(appearance: Appearance, update: ((Appearance) -> Appearance) -> Unit, reset: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.appearance_title), style = MaterialTheme.typography.titleLarge)

        Choice(R.string.appearance_mode, ThemeMode.entries, appearance.mode, { it.labelRes }) { mode -> update { it.copy(mode = mode) } }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.appearance_color), style = MaterialTheme.typography.titleSmall)
            if (wallpaperColorsAvailable) {
                Toggle(R.string.appearance_wallpaper, R.string.appearance_wallpaper_hint, appearance.wallpaperColors) { on ->
                    update { it.copy(wallpaperColors = on) }
                }
            }
            val custom = !(appearance.wallpaperColors && wallpaperColorsAvailable)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Appearance.PRESET_ACCENTS.forEach { accent ->
                    Swatch(accent, selected = custom && accent == appearance.accent) {
                        update { it.copy(accent = accent, wallpaperColors = false) }
                    }
                }
            }
            var hex by remember(appearance.accent) { mutableStateOf(Appearance.formatAccent(appearance.accent)) }
            OutlinedTextField(
                value = hex,
                onValueChange = { text ->
                    hex = text
                    Appearance.parseAccent(text)?.let { accent -> update { it.copy(accent = accent, wallpaperColors = false) } }
                },
                label = { Text(stringResource(R.string.appearance_hex)) },
                isError = Appearance.parseAccent(hex) == null,
                singleLine = true,
            )
            Choice(R.string.appearance_palette, PaletteKind.entries, appearance.palette, { it.labelRes }, enabled = custom) { palette ->
                update { it.copy(palette = palette) }
            }
        }

        Toggle(R.string.appearance_black, R.string.appearance_black_hint, appearance.pureBlack) { on -> update { it.copy(pureBlack = on) } }

        Choice(R.string.appearance_corners, CornerStyle.entries, appearance.corners, { it.labelRes }) { corners ->
            update { it.copy(corners = corners) }
        }

        Column {
            Text(
                stringResource(R.string.appearance_text_size, (appearance.textScale * 100).toInt()),
                style = MaterialTheme.typography.titleSmall,
            )
            Slider(
                value = appearance.textScale,
                onValueChange = { scale -> update { it.copy(textScale = scale) } },
                valueRange = Appearance.MIN_TEXT_SCALE..Appearance.MAX_TEXT_SCALE,
                steps = 8,
            )
        }

        Toggle(R.string.appearance_compact, R.string.appearance_compact_hint, appearance.compactList) { on ->
            update { it.copy(compactList = on) }
        }
        Toggle(R.string.appearance_nav_labels, null, appearance.navigationLabels) { on -> update { it.copy(navigationLabels = on) } }

        OutlinedButton(onClick = reset) { Text(stringResource(R.string.appearance_reset)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Choice(
    @StringRes titleRes: Int,
    options: List<T>,
    selected: T,
    label: (T) -> Int,
    enabled: Boolean = true,
    onSelect: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(titleRes), style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                FilterChip(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    enabled = enabled,
                    label = { Text(stringResource(label(option))) },
                )
            }
        }
    }
}

@Composable
private fun Toggle(@StringRes titleRes: Int, @StringRes hintRes: Int?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(stringResource(titleRes), style = MaterialTheme.typography.titleSmall)
            if (hintRes != null) {
                Text(
                    stringResource(hintRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun Swatch(accent: Int, selected: Boolean, onClick: () -> Unit) {
    val name = Appearance.formatAccent(accent)
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Color(accent))
            .border(if (selected) 3.dp else 0.dp, if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent, CircleShape)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = name },
    )
}

@Composable
private fun Section(titleRes: Int, bodyRes: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(titleRes), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(bodyRes), style = MaterialTheme.typography.bodyMedium)
    }
}

private val ThemeMode.labelRes: Int
    get() = when (this) {
        ThemeMode.SYSTEM -> R.string.appearance_mode_system
        ThemeMode.LIGHT -> R.string.appearance_mode_light
        ThemeMode.DARK -> R.string.appearance_mode_dark
    }

private val PaletteKind.labelRes: Int
    get() = when (this) {
        PaletteKind.TONAL -> R.string.appearance_palette_tonal
        PaletteKind.VIBRANT -> R.string.appearance_palette_vibrant
        PaletteKind.EXPRESSIVE -> R.string.appearance_palette_expressive
        PaletteKind.NEUTRAL -> R.string.appearance_palette_neutral
        PaletteKind.MONOCHROME -> R.string.appearance_palette_monochrome
        PaletteKind.FIDELITY -> R.string.appearance_palette_fidelity
    }

private val CornerStyle.labelRes: Int
    get() = when (this) {
        CornerStyle.SHARP -> R.string.appearance_corners_sharp
        CornerStyle.STANDARD -> R.string.appearance_corners_standard
        CornerStyle.ROUND -> R.string.appearance_corners_round
    }
