package dev.moduforge.host.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme
import dev.moduforge.host.appearance.Appearance
import dev.moduforge.host.appearance.CornerStyle
import dev.moduforge.host.appearance.PaletteKind
import dev.moduforge.host.appearance.ThemeMode

/** Appearance in effect, for screens whose layout depends on it. */
val LocalAppearance = staticCompositionLocalOf { Appearance() }

/** True when the system can supply colours taken from the wallpaper. */
val wallpaperColorsAvailable: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

@Composable
fun ModuForgeTheme(appearance: Appearance, content: @Composable () -> Unit) {
    val dark = when (appearance.mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val colors = remember(appearance.wallpaperColors, appearance.accent, appearance.palette, appearance.pureBlack, dark, context) {
        val scheme = if (appearance.wallpaperColors && wallpaperColorsAvailable) {
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else {
            dynamicColorScheme(seedColor = Color(appearance.accent), isDark = dark, isAmoled = false, style = appearance.palette.style)
        }
        if (dark && appearance.pureBlack) scheme.withBlackBackground() else scheme
    }
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalAppearance provides appearance,
        LocalDensity provides Density(density.density, density.fontScale * appearance.textScale),
    ) {
        MaterialTheme(colorScheme = colors, shapes = appearance.corners.shapes, content = content)
    }
}

private val PaletteKind.style: PaletteStyle
    get() = when (this) {
        PaletteKind.TONAL -> PaletteStyle.TonalSpot
        PaletteKind.VIBRANT -> PaletteStyle.Vibrant
        PaletteKind.EXPRESSIVE -> PaletteStyle.Expressive
        PaletteKind.NEUTRAL -> PaletteStyle.Neutral
        PaletteKind.MONOCHROME -> PaletteStyle.Monochrome
        PaletteKind.FIDELITY -> PaletteStyle.Fidelity
    }

private val CornerStyle.shapes: Shapes
    get() = when (this) {
        CornerStyle.SHARP -> Shapes(
            extraSmall = RoundedCornerShape(2.dp),
            small = RoundedCornerShape(2.dp),
            medium = RoundedCornerShape(4.dp),
            large = RoundedCornerShape(4.dp),
            extraLarge = RoundedCornerShape(6.dp),
        )
        CornerStyle.STANDARD -> Shapes()
        CornerStyle.ROUND -> Shapes(
            extraSmall = RoundedCornerShape(8.dp),
            small = RoundedCornerShape(14.dp),
            medium = RoundedCornerShape(20.dp),
            large = RoundedCornerShape(28.dp),
            extraLarge = RoundedCornerShape(36.dp),
        )
    }

/** Replaces the dark surfaces with true black, keeping raised containers distinguishable. */
private fun ColorScheme.withBlackBackground(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0B0B0B),
    surfaceContainer = Color(0xFF121212),
)
