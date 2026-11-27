/**
 * Theme — Top-level Material 3 theme wrapper supporting dynamic color and dark/light schemes.
 *
 * @remarks
 * Memoizes the resolved `ColorScheme` on `(context, darkTheme, dynamicColor)` because Material You
 * wallpaper palette extraction is non-trivial and would otherwise re-run across recompositions.
 *
 * @see Typography
 */
package com.quandot.musicplayer.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// ═══ Color Schemes ═══
internal val DARK_COLOR_SCHEME = darkColorScheme(
    primary = M3DarkPrimary,
    onPrimary = M3DarkOnPrimary,
    primaryContainer = M3DarkPrimaryContainer,
    onPrimaryContainer = M3DarkOnPrimaryContainer,
    secondary = M3DarkSecondary,
    onSecondary = M3DarkOnSecondary,
    secondaryContainer = M3DarkSecondaryContainer,
    onSecondaryContainer = M3DarkOnSecondaryContainer,
    tertiary = M3DarkTertiary,
    onTertiary = M3DarkOnTertiary,
    tertiaryContainer = M3DarkTertiaryContainer,
    onTertiaryContainer = M3DarkOnTertiaryContainer,
    background = M3DarkSurface,
    onBackground = M3DarkOnSurface,
    surface = M3DarkSurface,
    onSurface = M3DarkOnSurface,
    surfaceContainerLow = M3DarkSurfaceContainerLow,
    surfaceContainer = M3DarkSurfaceContainer,
    surfaceContainerHigh = M3DarkSurfaceContainerHigh,
    surfaceContainerHighest = M3DarkSurfaceContainerHighest,
    surfaceVariant = M3DarkSurfaceVariant,
    onSurfaceVariant = M3DarkOnSurfaceVariant,
    outline = M3DarkOutline,
    outlineVariant = M3DarkOutlineVariant
)

internal val LIGHT_COLOR_SCHEME = lightColorScheme(
    primary = M3LightPrimary,
    onPrimary = M3LightOnPrimary,
    primaryContainer = M3LightPrimaryContainer,
    onPrimaryContainer = M3LightOnPrimaryContainer,
    secondary = M3LightSecondary,
    onSecondary = M3LightOnSecondary,
    secondaryContainer = M3LightSecondaryContainer,
    onSecondaryContainer = M3LightOnSecondaryContainer,
    tertiary = M3LightTertiary,
    onTertiary = M3LightOnTertiary,
    tertiaryContainer = M3LightTertiaryContainer,
    onTertiaryContainer = M3LightOnTertiaryContainer,
    background = M3LightSurface,
    onBackground = M3LightOnSurface,
    surface = M3LightSurface,
    onSurface = M3LightOnSurface,
    surfaceContainerLow = M3LightSurfaceContainerLow,
    surfaceContainer = M3LightSurfaceContainer,
    surfaceContainerHigh = M3LightSurfaceContainerHigh,
    surfaceContainerHighest = M3LightSurfaceContainerHighest,
    surfaceVariant = M3LightSurfaceVariant,
    onSurfaceVariant = M3LightOnSurfaceVariant,
    outline = M3LightOutline,
    outlineVariant = M3LightOutlineVariant
)

// ═══ Public Composable ═══
@Composable
fun MusicPlayerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    amoledMode: Boolean = false,
    songSeedColorArgb: Int? = null,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = remember(context, darkTheme, dynamicColor, amoledMode, songSeedColorArgb) {
        val baseScheme = when {
            songSeedColorArgb != null -> {
                MaterialYouColorEngine.buildColorScheme(songSeedColorArgb, darkTheme)
            }
            dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }
            darkTheme -> DARK_COLOR_SCHEME
            else -> LIGHT_COLOR_SCHEME
        }
        if (!amoledMode) {
            baseScheme
        } else if (darkTheme) {
            baseScheme.copy(
                background = Color.Black,
                onBackground = Color.White,
                surface = Color.Black,
                onSurface = Color.White,
                surfaceDim = Color.Black,
                surfaceBright = Color(0xFF1A1A1A),
                surfaceContainerLowest = Color.Black,
                surfaceContainerLow = Color(0xFF0A0A0A),
                surfaceContainer = Color(0xFF111111),
                surfaceContainerHigh = Color(0xFF181818),
                surfaceContainerHighest = Color(0xFF222222),
                surfaceVariant = Color(0xFF1E1E1E),
                onSurfaceVariant = Color(0xFFDADADA),
                outlineVariant = Color(0xFF2E2E2E)
            )
        } else {
            baseScheme.copy(
                background = Color.White,
                onBackground = Color.Black,
                surface = Color.White,
                onSurface = Color.Black,
                surfaceDim = Color(0xFFF2F2F2),
                surfaceBright = Color.White,
                surfaceContainerLowest = Color.White,
                surfaceContainerLow = Color(0xFFFAFAFA),
                surfaceContainer = Color(0xFFF4F4F4),
                surfaceContainerHigh = Color(0xFFEEEEEE),
                surfaceContainerHighest = Color(0xFFE6E6E6),
                surfaceVariant = Color(0xFFEAEAEA),
                onSurfaceVariant = Color(0xFF262626),
                outlineVariant = Color(0xFFD0D0D0)
            )
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
