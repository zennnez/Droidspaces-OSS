package com.droidspaces.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

val AMOLED_BLACK = Color(0xFF000000)

/** Dark mode read off the scheme itself, so AMOLED and dynamic colour need no second hook. */
val ColorScheme.isDark: Boolean
    get() = background.luminance() < 0.5f

/* Material 3 has no warning role and tertiary follows the palette, so on a green
 * palette a warning would look like success. This is the M3 extended-colour answer:
 * a fixed amber pair at the usual container tones (90 on light, 30 on dark). */
val ColorScheme.warningContainer: Color
    get() = if (isDark) Color(0xFF5D4200) else Color(0xFFFFDF9E)

val ColorScheme.onWarningContainer: Color
    get() = if (isDark) Color(0xFFFFDF9E) else Color(0xFF261A00)

/**
 * Pre-defined accent color palettes for the app, mimicking Android's
 * "Wallpaper & style" palette picker.
 *
 * Each palette defines primary, secondary, and tertiary colors for both
 * light and dark modes. A palette taken from a real Material You scheme also
 * carries that whole scheme in [fixedLight] and [fixedDark], used as is instead
 * of the blended surfaces the others get, and its own [swatch] colours.
 */
enum class ThemePalette(
    val displayName: String,
    val primaryLight: Color,
    val secondaryLight: Color,
    val tertiaryLight: Color,
    val primaryDark: Color,
    val secondaryDark: Color,
    val tertiaryDark: Color,
    val fixedLight: ColorScheme? = null,
    val fixedDark: ColorScheme? = null,
    // Primary, secondary and tertiary for the picker swatch, the same in both modes. A
    // Material You scheme's own accents are dark tones in light mode and pale in dark, with
    // muted secondary and tertiary, so as a preview they read flat. These are the scheme's
    // hues at tone 70 with the chroma pushed up (60, 36, 48 in HCT).
    val swatch: List<Color>? = null,
) {
    // The Material You scheme One UI built from a gold wallpaper, seed #CFA369 in the
    // Tonal Spot style, read off the phone's system_* colour resources
    SAND(
        displayName = "Sand",
        swatch = listOf(Color(0xFFEA9A00), Color(0xFFD7A157), Color(0xFF8BB85D)),
        primaryLight = Color(0xFF805611),   // Gold
        secondaryLight = Color(0xFF705B40), // Tan
        tertiaryLight = Color(0xFF52643F),  // Sage
        primaryDark = Color(0xFFF5BC6F),    // Light Gold
        secondaryDark = Color(0xFFDEC2A1),  // Light Tan
        tertiaryDark = Color(0xFFB9CDA0),   // Light Sage
        fixedLight = lightColorScheme(
            primary = Color(0xFF805611), onPrimary = Color(0xFFFFFFFF),
            primaryContainer = Color(0xFFFFDDB4), onPrimaryContainer = Color(0xFF291800),
            inversePrimary = Color(0xFFF5BC6F),
            secondary = Color(0xFF705B40), onSecondary = Color(0xFFFFFFFF),
            secondaryContainer = Color(0xFFFBDEBC), onSecondaryContainer = Color(0xFF271904),
            tertiary = Color(0xFF52643F), onTertiary = Color(0xFFFFFFFF),
            tertiaryContainer = Color(0xFFD5EABB), onTertiaryContainer = Color(0xFF111F03),
            background = Color(0xFFFFF8F4), onBackground = Color(0xFF211B13),
            surface = Color(0xFFFFF8F4), onSurface = Color(0xFF211B13),
            surfaceVariant = Color(0xFFF0E0D0), onSurfaceVariant = Color(0xFF4F4539),
            inverseSurface = Color(0xFF362F27), inverseOnSurface = Color(0xFFFCEFE2),
            outline = Color(0xFF817567), outlineVariant = Color(0xFFD3C4B4),
            surfaceBright = Color(0xFFFFF8F4), surfaceDim = Color(0xFFE5D8CC),
            surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFFFF1E5),
            surfaceContainer = Color(0xFFF9ECDF), surfaceContainerHigh = Color(0xFFF3E6DA),
            surfaceContainerHighest = Color(0xFFEDE0D4),
        ),
        fixedDark = darkColorScheme(
            primary = Color(0xFFF5BC6F), onPrimary = Color(0xFF452B00),
            primaryContainer = Color(0xFF633F00), onPrimaryContainer = Color(0xFFFFDDB4),
            inversePrimary = Color(0xFF805611),
            secondary = Color(0xFFDEC2A1), onSecondary = Color(0xFF3E2D16),
            secondaryContainer = Color(0xFF56432B), onSecondaryContainer = Color(0xFFFBDEBC),
            tertiary = Color(0xFFB9CDA0), onTertiary = Color(0xFF253515),
            tertiaryContainer = Color(0xFF3B4C29), onTertiaryContainer = Color(0xFFD5EABB),
            background = Color(0xFF18120B), onBackground = Color(0xFFEDE0D4),
            surface = Color(0xFF18120B), onSurface = Color(0xFFEDE0D4),
            surfaceVariant = Color(0xFF4F4539), onSurfaceVariant = Color(0xFFD3C4B4),
            inverseSurface = Color(0xFFEDE0D4), inverseOnSurface = Color(0xFF362F27),
            outline = Color(0xFF9C8F80), outlineVariant = Color(0xFF4F4539),
            surfaceBright = Color(0xFF3F3830), surfaceDim = Color(0xFF18120B),
            surfaceContainerLowest = Color(0xFF130D07), surfaceContainerLow = Color(0xFF211B13),
            surfaceContainer = Color(0xFF251F17), surfaceContainerHigh = Color(0xFF302921),
            surfaceContainerHighest = Color(0xFF3B342B),
        ),
    ),
    CATPPUCCIN(
        displayName = "Catppuccin",
        primaryLight = Color(0xFF8AADF4),   // Blue
        secondaryLight = Color(0xFFB7BDF8), // Lavender
        tertiaryLight = Color(0xFFA6DA95),  // Green
        primaryDark = Color(0xFF7DC4E4),    // Sky
        secondaryDark = Color(0xFFF5BDE6),  // Pink
        tertiaryDark = Color(0xFFA6DA95)    // Green
    ),
    OCEAN(
        displayName = "Ocean",
        primaryLight = Color(0xFF0277BD),   // Deep Blue
        secondaryLight = Color(0xFF00ACC1), // Cyan
        tertiaryLight = Color(0xFF26A69A),  // Teal
        primaryDark = Color(0xFF4FC3F7),    // Light Blue
        secondaryDark = Color(0xFF4DD0E1),  // Light Cyan
        tertiaryDark = Color(0xFF80CBC4)    // Light Teal
    ),
    FOREST(
        displayName = "Forest",
        primaryLight = Color(0xFF2E7D32),   // Deep Green
        secondaryLight = Color(0xFF558B2F), // Olive Green
        tertiaryLight = Color(0xFF8D6E63),  // Brown
        primaryDark = Color(0xFF81C784),    // Light Green
        secondaryDark = Color(0xFFA5D6A7),  // Pale Green
        tertiaryDark = Color(0xFFBCAAA4)    // Light Brown
    ),
    SUNSET(
        displayName = "Sunset",
        primaryLight = Color(0xFFD84315),   // Deep Orange
        secondaryLight = Color(0xFFF4511E), // Orange-Red
        tertiaryLight = Color(0xFFFFB300),  // Amber
        primaryDark = Color(0xFFFF8A65),    // Light Orange
        secondaryDark = Color(0xFFFF8A80),  // Light Coral
        tertiaryDark = Color(0xFFFFD54F)    // Light Amber
    ),
    AMETHYST(
        displayName = "Amethyst",
        primaryLight = Color(0xFF6A1B9A),   // Deep Purple
        secondaryLight = Color(0xFF8E24AA), // Purple
        tertiaryLight = Color(0xFFAD1457),  // Deep Pink
        primaryDark = Color(0xFFCE93D8),    // Light Purple
        secondaryDark = Color(0xFFBA68C8),  // Medium Purple
        tertiaryDark = Color(0xFFF48FB1)    // Light Pink
    ),
    SAKURA(
        displayName = "Sakura",
        primaryLight = Color(0xFFD81B60),   // Pink
        secondaryLight = Color(0xFFEC407A), // Rose
        tertiaryLight = Color(0xFF7E57C2),  // Violet
        primaryDark = Color(0xFFF48FB1),    // Light Pink
        secondaryDark = Color(0xFFF8BBD0),  // Pale Pink
        tertiaryDark = Color(0xFFB39DDB)    // Light Violet
    );

    companion object {
        fun fromName(name: String): ThemePalette =
            entries.find { it.name == name } ?: CATPPUCCIN
    }
}
