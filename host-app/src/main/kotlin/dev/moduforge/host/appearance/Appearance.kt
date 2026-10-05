package dev.moduforge.host.appearance

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** How a full colour scheme is derived from the accent colour. */
enum class PaletteKind { TONAL, VIBRANT, EXPRESSIVE, NEUTRAL, MONOCHROME, FIDELITY }

enum class CornerStyle { SHARP, STANDARD, ROUND }

/**
 * Everything the user can change about how the host looks.
 *
 * @property wallpaperColors take the colours from the system wallpaper (Android 12+) instead of [accent].
 * @property accent ARGB seed of the colour scheme.
 * @property pureBlack use a true black background in the dark theme.
 * @property textScale multiplier on top of the system font size.
 * @property compactList show modules as dense rows instead of cards.
 */
data class Appearance(
    val mode: ThemeMode = ThemeMode.SYSTEM,
    val wallpaperColors: Boolean = true,
    val accent: Int = DEFAULT_ACCENT,
    val palette: PaletteKind = PaletteKind.TONAL,
    val pureBlack: Boolean = false,
    val corners: CornerStyle = CornerStyle.STANDARD,
    val textScale: Float = 1f,
    val compactList: Boolean = false,
    val navigationLabels: Boolean = true,
) {
    companion object {
        const val DEFAULT_ACCENT = 0xFF1F6A52.toInt()
        const val MIN_TEXT_SCALE = 0.85f
        const val MAX_TEXT_SCALE = 1.3f

        /** Accent colours offered as one-tap choices. */
        val PRESET_ACCENTS = listOf(
            0xFF1F6A52, 0xFF2962FF, 0xFF6750A4, 0xFFD81B60, 0xFFE53935, 0xFFF57C00, 0xFFF9A825, 0xFF00838F, 0xFF546E7A,
        ).map { it.toInt() }

        /** Parses `#RRGGBB` or `RRGGBB`; null when the text is not a colour. */
        fun parseAccent(text: String): Int? {
            val hex = text.trim().removePrefix("#")
            if (hex.length != 6) return null
            return hex.toIntOrNull(16)?.let { it or 0xFF000000.toInt() }
        }

        fun formatAccent(accent: Int): String = "#%06X".format(accent and 0xFFFFFF)
    }
}

/** Persists [Appearance] and publishes changes to the UI. */
@Singleton
class AppearanceStore @Inject constructor(@ApplicationContext context: Context) {

    private val preferences = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    private val _appearance = MutableStateFlow(read())

    val appearance: StateFlow<Appearance> = _appearance.asStateFlow()

    fun update(change: (Appearance) -> Appearance) {
        val next = change(_appearance.value).let {
            it.copy(textScale = it.textScale.coerceIn(Appearance.MIN_TEXT_SCALE, Appearance.MAX_TEXT_SCALE))
        }
        _appearance.value = next
        preferences.edit {
            putString(KEY_MODE, next.mode.name)
            putBoolean(KEY_WALLPAPER, next.wallpaperColors)
            putInt(KEY_ACCENT, next.accent)
            putString(KEY_PALETTE, next.palette.name)
            putBoolean(KEY_BLACK, next.pureBlack)
            putString(KEY_CORNERS, next.corners.name)
            putFloat(KEY_TEXT, next.textScale)
            putBoolean(KEY_COMPACT, next.compactList)
            putBoolean(KEY_LABELS, next.navigationLabels)
        }
    }

    fun reset() = update { Appearance() }

    private fun read(): Appearance {
        val defaults = Appearance()
        return Appearance(
            mode = enumOr(preferences.getString(KEY_MODE, null), defaults.mode),
            wallpaperColors = preferences.getBoolean(KEY_WALLPAPER, defaults.wallpaperColors),
            accent = preferences.getInt(KEY_ACCENT, defaults.accent),
            palette = enumOr(preferences.getString(KEY_PALETTE, null), defaults.palette),
            pureBlack = preferences.getBoolean(KEY_BLACK, defaults.pureBlack),
            corners = enumOr(preferences.getString(KEY_CORNERS, null), defaults.corners),
            textScale = preferences.getFloat(KEY_TEXT, defaults.textScale).coerceIn(Appearance.MIN_TEXT_SCALE, Appearance.MAX_TEXT_SCALE),
            compactList = preferences.getBoolean(KEY_COMPACT, defaults.compactList),
            navigationLabels = preferences.getBoolean(KEY_LABELS, defaults.navigationLabels),
        )
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: fallback

    private companion object {
        const val KEY_MODE = "mode"
        const val KEY_WALLPAPER = "wallpaperColors"
        const val KEY_ACCENT = "accent"
        const val KEY_PALETTE = "palette"
        const val KEY_BLACK = "pureBlack"
        const val KEY_CORNERS = "corners"
        const val KEY_TEXT = "textScale"
        const val KEY_COMPACT = "compactList"
        const val KEY_LABELS = "navigationLabels"
    }
}
