/**
 * Type — Material 3 typography scale tuned for mixed Latin and Vietnamese diacritics.
 *
 * @remarks
 * Retaining `includeFontPadding = true` and `LineHeightStyle.Trim.None` across every text
 * role prevents stacked Vietnamese tone marks from clipping in compact single-line rows.
 *
 * @see MusicPlayerTheme
 */
package com.quandot.musicplayer.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

// ═══ Constants ═══
private val DEFAULT_LINE_HEIGHT_STYLE = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None
)

@Suppress("DEPRECATION")
private val DEFAULT_PLATFORM_STYLE = PlatformTextStyle(
    includeFontPadding = true
)

// ═══ Typography Scale ═══
val Typography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 30.sp,
        lineHeight = 42.sp,
        letterSpacing = 0.sp,
        platformStyle = DEFAULT_PLATFORM_STYLE,
        lineHeightStyle = DEFAULT_LINE_HEIGHT_STYLE
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 36.sp,
        letterSpacing = 0.sp,
        platformStyle = DEFAULT_PLATFORM_STYLE,
        lineHeightStyle = DEFAULT_LINE_HEIGHT_STYLE
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.sp,
        platformStyle = DEFAULT_PLATFORM_STYLE,
        lineHeightStyle = DEFAULT_LINE_HEIGHT_STYLE
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 30.sp,
        letterSpacing = 0.sp,
        platformStyle = DEFAULT_PLATFORM_STYLE,
        lineHeightStyle = DEFAULT_LINE_HEIGHT_STYLE
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.1.sp,
        platformStyle = DEFAULT_PLATFORM_STYLE,
        lineHeightStyle = DEFAULT_LINE_HEIGHT_STYLE
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.1.sp,
        platformStyle = DEFAULT_PLATFORM_STYLE,
        lineHeightStyle = DEFAULT_LINE_HEIGHT_STYLE
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.25.sp,
        platformStyle = DEFAULT_PLATFORM_STYLE,
        lineHeightStyle = DEFAULT_LINE_HEIGHT_STYLE
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 21.sp,
        letterSpacing = 0.15.sp,
        platformStyle = DEFAULT_PLATFORM_STYLE,
        lineHeightStyle = DEFAULT_LINE_HEIGHT_STYLE
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.2.sp,
        platformStyle = DEFAULT_PLATFORM_STYLE,
        lineHeightStyle = DEFAULT_LINE_HEIGHT_STYLE
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
        platformStyle = DEFAULT_PLATFORM_STYLE,
        lineHeightStyle = DEFAULT_LINE_HEIGHT_STYLE
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.3.sp,
        platformStyle = DEFAULT_PLATFORM_STYLE,
        lineHeightStyle = DEFAULT_LINE_HEIGHT_STYLE
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp,
        platformStyle = DEFAULT_PLATFORM_STYLE,
        lineHeightStyle = DEFAULT_LINE_HEIGHT_STYLE
    )
)
