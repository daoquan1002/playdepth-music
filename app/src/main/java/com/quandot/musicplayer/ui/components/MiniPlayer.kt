/**
 * MiniPlayer — Compact bottom playback bar docked above navigation chrome.
 *
 * @remarks
 * When `isActive` is false (covered by `NowPlayingSheet` or Settings), track/play state slots and
 * `progressFlow` collection freeze so background track switches never trigger offscreen
 * `AnimatedContent` transitions, bitmap decodes, or 60Hz progress recompositions.
 *
 * @see NowPlayingSheet
 */
package com.quandot.musicplayer.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

import com.quandot.musicplayer.R
import com.quandot.musicplayer.data.ArtResolutionBucket
import com.quandot.musicplayer.util.localizedArtistName

import com.quandot.musicplayer.model.Song

// ═══ Constants ═══
private const val DISABLED_ICON_ALPHA = 0.4f
private const val PLACEHOLDER_ICON_ALPHA = 0.6f

// ═══ Public Composable ═══
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MiniPlayer(
    song: Song?,
    isPlaying: Boolean,
    progressFlow: StateFlow<Float>,
    onTogglePlay: () -> Unit,
    onStopAndReset: () -> Unit = {},
    onPrevious: (() -> Unit)? = null,
    onNext: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trackDirection: Int = 1,
    isActive: Boolean = true,
    showPreviousButton: Boolean = false,
    contentWindowInsets: WindowInsets = WindowInsets(0, 0, 0, 0)
) {
    // Freeze MiniPlayer track updates while fully covered so skipping tracks in NowPlaying
    // never triggers offscreen AnimatedContent or bitmap work here.
    var displaySong by remember { mutableStateOf(song) }
    var displayPlaying by remember { mutableStateOf(isPlaying) }
    if (isActive) {
        displaySong = song
        displayPlaying = isPlaying
    }
    val activeSongSlot = displaySong
    val activePlayingSlot = displayPlaying

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RectangleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(contentWindowInsets)
                .height(64.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = if (showPreviousButton) 20.dp else 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Direct cover swap without transition animation avoids decode jitter on rapid skips
                if (activeSongSlot != null) {
                    AlbumArtImage(
                        song = activeSongSlot,
                        icon = Icons.Default.MusicNote,
                        bucket = ArtResolutionBucket.THUMBNAIL,
                        enableCrossfade = false,
                        modifier = Modifier.size(48.dp)
                    )
                } else {
                    Surface(
                        modifier = Modifier.size(48.dp),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.MusicNote,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = PLACEHOLDER_ICON_ALPHA),
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }

                // Direct metadata swap without crossfade prevents layout churn during rapid skips
                if (activeSongSlot != null) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = activeSongSlot.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = localizedArtistName(activeSongSlot.artist),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                } else {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.mini_no_song),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = stringResource(R.string.mini_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (showPreviousButton && onPrevious != null) {
                        IconButton(
                            onClick = onPrevious,
                            modifier = Modifier.size(44.dp),
                            enabled = activeSongSlot != null
                        ) {
                            Icon(
                                imageVector = Icons.Default.SkipPrevious,
                                contentDescription = stringResource(R.string.cd_previous),
                                tint = if (activeSongSlot != null) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = DISABLED_ICON_ALPHA)
                                },
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }

                    val toggleInteraction = remember { MutableInteractionSource() }
                    val haptic = LocalHapticFeedback.current
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .combinedClickable(
                                interactionSource = toggleInteraction,
                                indication = ripple(bounded = false, radius = 22.dp),
                                onClick = onTogglePlay,
                                onLongClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onStopAndReset()
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (activePlayingSlot) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (activePlayingSlot) stringResource(R.string.cd_pause) else stringResource(R.string.cd_play),
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(26.dp)
                        )
                    }

                    IconButton(
                        onClick = onNext,
                        modifier = Modifier.size(44.dp),
                        enabled = activeSongSlot != null
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = stringResource(R.string.cd_next),
                            tint = if (activeSongSlot != null) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = DISABLED_ICON_ALPHA)
                            },
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }

            // Isolated in a child composable so periodic progress emissions never recompose the row
            MiniPlayerProgressIndicator(
                progressFlow = progressFlow,
                isActive = isActive && activeSongSlot != null,
                hasActiveSong = activeSongSlot != null,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .align(Alignment.BottomCenter)
            )
        }
    }
}

// ═══ Sub-components ═══
@Composable
private fun MiniPlayerProgressIndicator(
    progressFlow: StateFlow<Float>,
    isActive: Boolean,
    hasActiveSong: Boolean,
    modifier: Modifier = Modifier
) {
    val activeFlow = remember(isActive, progressFlow) {
        if (isActive) progressFlow else MutableStateFlow(progressFlow.value)
    }
    val progress by activeFlow.collectAsStateWithLifecycle()
    LinearProgressIndicator(
        progress = { if (hasActiveSong) progress.coerceIn(0f, 1f) else 0f },
        modifier = modifier,
        color = MaterialTheme.colorScheme.primary,
        trackColor = MaterialTheme.colorScheme.surfaceVariant,
        drawStopIndicator = {}
    )
}
