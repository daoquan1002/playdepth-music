/**
 * AdaptiveLayout — Responsive layout metrics, CompositionLocals, and edge-to-edge WindowInsets helpers.
 *
 * @remarks
 * Grid breakpoints subtract the NavigationRail width so multi-column grids size against the
 * actual content pane rather than full window width. `systemSafeInsets` intentionally omits
 * IME insets so opening the soft keyboard does not reflow non-input chrome, while
 * `LocalHasLeftRail` suppresses Start insets on child panes to avoid double-padding beside the rail.
 *
 * @see MusicPlayerTheme
 */
package com.quandot.musicplayer.ui.theme

import android.content.res.Configuration
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// ═══ Constants ═══
private const val MIN_LOGICAL_DP = 320
private const val TABLET_SMALLEST_WIDTH_DP = 600
private const val EXPANDED_WIDTH_DP = 840
private const val NAVIGATION_RAIL_WIDTH_DP = 80
private const val SHORT_LANDSCAPE_HEIGHT_DP = 520
private const val COMPACT_HEIGHT_DP = 750
private const val DEFAULT_BOTTOM_CONTENT_PADDING_DP = 160
private const val TABLET_HORIZONTAL_PADDING_DP = 24
private const val PHONE_HORIZONTAL_PADDING_DP = 16
private const val MAX_LIST_CONTENT_WIDTH_DP = 900

// ═══ Layout Models & CompositionLocals ═══
/**
 * Shared responsive metrics across phones (portrait/landscape), foldables, and tablets.
 */
@Immutable
data class AdaptiveLayoutInfo(
    val screenWidthDp: Int,
    val screenHeightDp: Int,
    val isLandscape: Boolean,
    val isShortLandscape: Boolean,
    val isCompactHeight: Boolean,
    val isTabletOrExpanded: Boolean,
    val useNavigationRail: Boolean,
    val coverGridColumns: Int,
    val compactCardColumns: Int,
    val horizontalPadding: Dp,
    val maxListContentWidth: Dp
)

/**
 * Total bottom clearance (in Dp) required by scrollable lists and fast scrollers to remain
 * unobstructed above the MiniPlayer (+ bottom NavigationBar in portrait) and system navigation bar.
 */
val LocalBottomContentPadding = compositionLocalOf { DEFAULT_BOTTOM_CONTENT_PADDING_DP.dp }

/**
 * Whether a NavigationRail currently occupies the left (Start) screen edge and already handles
 * horizontal Start cutout/navigation insets.
 */
val LocalHasLeftRail = compositionLocalOf { false }

/**
 * Stable [State] reporting whether the host Activity is currently in the foreground (`STARTED`).
 * Reading inside leaf animation gates avoids recomposing parent trees on app backgrounding.
 */
val LocalIsUiForeground = staticCompositionLocalOf<State<Boolean>> {
    mutableStateOf(true)
}

/**
 * Whether the enclosing tab/overlay slot is currently visible on screen (not parked off-screen or
 * occluded by a full-screen overlay), used to freeze off-screen infinite animations.
 */
val LocalScreenVisible = compositionLocalOf { true }

// ═══ Responsive Metrics ═══
@Composable
fun rememberAdaptiveLayoutInfo(): AdaptiveLayoutInfo {
    val config = LocalConfiguration.current
    return remember(config.orientation, config.screenWidthDp, config.screenHeightDp) {
        val w = config.screenWidthDp.coerceAtLeast(MIN_LOGICAL_DP)
        val h = config.screenHeightDp.coerceAtLeast(MIN_LOGICAL_DP)
        val isLandscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE || w > h
        val smallestWidth = minOf(w, h)
        val isTabletOrExpanded = smallestWidth >= TABLET_SMALLEST_WIDTH_DP || (w >= EXPANDED_WIDTH_DP && h >= 480)
        val useNavigationRail = isLandscape || w >= TABLET_SMALLEST_WIDTH_DP
        val contentWidth = if (useNavigationRail) (w - NAVIGATION_RAIL_WIDTH_DP).coerceAtLeast(MIN_LOGICAL_DP) else w

        val coverCols = when {
            contentWidth >= 960 -> 5
            contentWidth >= 720 -> 4
            contentWidth >= 500 -> 3
            else -> 2
        }
        val compactCols = when {
            contentWidth >= 780 -> 4
            contentWidth >= 500 -> 3
            else -> 2
        }

        AdaptiveLayoutInfo(
            screenWidthDp = w,
            screenHeightDp = h,
            isLandscape = isLandscape,
            isShortLandscape = isLandscape && h < SHORT_LANDSCAPE_HEIGHT_DP,
            isCompactHeight = h < COMPACT_HEIGHT_DP,
            isTabletOrExpanded = isTabletOrExpanded,
            useNavigationRail = useNavigationRail,
            coverGridColumns = coverCols,
            compactCardColumns = compactCols,
            horizontalPadding = if (isTabletOrExpanded) TABLET_HORIZONTAL_PADDING_DP.dp else PHONE_HORIZONTAL_PADDING_DP.dp,
            maxListContentWidth = if (isTabletOrExpanded) MAX_LIST_CONTENT_WIDTH_DP.dp else Dp.Unspecified
        )
    }
}

// ═══ Window Insets Helpers ═══
/**
 * Full-bleed safe insets combining system bars (status/navigation/caption) and display cutouts
 * without IME keyboard inflation.
 */
@Composable
fun systemSafeInsets(): WindowInsets {
    return WindowInsets.systemBars.union(WindowInsets.displayCutout)
}

@Composable
fun paneTopInsets(): WindowInsets {
    return systemSafeInsets().only(WindowInsetsSides.Top)
}

/**
 * Safe insets for top-anchored content panes (Home, Library, Detail screens): respects status bars,
 * top display cutouts / camera islands, and any End cutout or side nav bar (plus Start when no rail).
 */
@Composable
fun paneTopHorizontalInsets(
    hasLeftRail: Boolean = LocalHasLeftRail.current
): WindowInsets {
    val sides = if (hasLeftRail) {
        WindowInsetsSides.Top + WindowInsetsSides.End
    } else {
        WindowInsetsSides.Top + WindowInsetsSides.Horizontal
    }
    return systemSafeInsets().only(sides)
}

/**
 * Safe horizontal insets for pane content bodies below an already top-inset TopAppBar.
 */
@Composable
fun paneHorizontalInsets(
    hasLeftRail: Boolean = LocalHasLeftRail.current
): WindowInsets {
    val sides = if (hasLeftRail) {
        WindowInsetsSides.End
    } else {
        WindowInsetsSides.Horizontal
    }
    return systemSafeInsets().only(sides)
}

/**
 * Computes edge-to-edge PaddingValues combining horizontal safe insets with the layout's base
 * horizontal margin so scrolling containers can draw full-bleed under transparent system bars.
 */
@Composable
fun paneContentPadding(
    baseHorizontal: Dp,
    top: Dp = 0.dp,
    bottom: Dp = 0.dp,
    hasLeftRail: Boolean = LocalHasLeftRail.current
): PaddingValues {
    val layoutDir = LocalLayoutDirection.current
    val insetPadding = paneHorizontalInsets(hasLeftRail).asPaddingValues()
    return PaddingValues(
        start = insetPadding.calculateStartPadding(layoutDir) + baseHorizontal,
        end = insetPadding.calculateEndPadding(layoutDir) + baseHorizontal,
        top = top,
        bottom = bottom
    )
}
