package com.quandot.musicplayer.model

/**
 * Presentation style for the full-screen Now Playing player.
 *
 * - [MODERN]: Edge-to-edge frameless artwork feathered into an ambient background extracted from the cover.
 * - [CLASSIC]: Traditional Material 3 surface with a rounded, elevated album art card.
 */
enum class NowPlayingStyle {
    MODERN,
    CLASSIC;

    companion object {
        fun fromStoredValue(raw: String?): NowPlayingStyle {
            return entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: MODERN
        }
    }
}

/**
 * Edge feathering curve for album artwork in [NowPlayingStyle.MODERN].
 *
 * - [LINEAR]: Compact straight-line fade (10% top/bottom) preserving maximum artwork area.
 * - [CURVED]: Wide smoothstep S-curve fade (26% top/bottom) for ultra-soft blending.
 */
enum class NowPlayingBlendStyle {
    LINEAR,
    CURVED;

    companion object {
        fun fromStoredValue(raw: String?): NowPlayingBlendStyle {
            return entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: LINEAR
        }
    }
}

/**
 * Material 3 Expressive seekbar style for [NowPlayingSheet].
 *
 * - [SLIDER]: Standard M3 Expressive straight capsule-thumb slider.
 * - [WAVY]: M3 Expressive animated wavy active track that flattens smoothly when paused or scrubbing.
 */
enum class NowPlayingSeekbarStyle {
    SLIDER,
    WAVY;

    companion object {
        fun fromStoredValue(raw: String?): NowPlayingSeekbarStyle {
            return entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: SLIDER
        }
    }
}
