/**
 * WelcomeScreen — Material 3 onboarding permission screen shown before entering MainActivity.
 *
 * @remarks
 * Separates the scrollable text-only hero + permission checklist from the bottom-pinned Start CTA
 * bar so compact portrait phones, short landscape screens, and tablets all keep the Start button
 * reachable inside edge-to-edge safe insets without double-padding or clipping. Storage access is
 * required to unlock Start; notification access is optional.
 *
 * @see WelcomeActivity
 * @see PermissionHelper
 */
package com.quandot.musicplayer.ui.screens

// External SDK / Jetpack / third-party libraries
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Internal modules
import com.quandot.musicplayer.R
import com.quandot.musicplayer.util.WelcomePermissionState

// Domain models & theme tokens (last)
import com.quandot.musicplayer.ui.theme.rememberAdaptiveLayoutInfo
import com.quandot.musicplayer.ui.theme.systemSafeInsets

// ═══ Constants ═══
private const val CONTENT_MAX_WIDTH_DP = 560
private const val COLOR_ANIM_DURATION_MS = 260

// ═══ Types ═══
enum class SettingsRedirectReason {
    STORAGE,
    NOTIFICATION
}

private data class StorageSubStepItem(
    val label: String,
    val granted: Boolean
)

// ═══ Welcome Screen ═══
@Composable
fun WelcomeScreen(
    permissionState: WelcomePermissionState,
    pendingSettingsReason: SettingsRedirectReason?,
    onRequestStoragePermission: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onStartApp: () -> Unit,
    onConfirmOpenSettings: () -> Unit,
    onDismissSettingsDialog: () -> Unit,
    modifier: Modifier = Modifier
) {
    val layout = rememberAdaptiveLayoutInfo()
    val safeInsets = systemSafeInsets()
    val layoutDir = LocalLayoutDirection.current
    val horizontalSafePad = safeInsets.only(WindowInsetsSides.Horizontal).asPaddingValues()
    val safeStart = horizontalSafePad.calculateStartPadding(layoutDir) + layout.horizontalPadding
    val safeEnd = horizontalSafePad.calculateEndPadding(layoutDir) + layout.horizontalPadding

    val storageStatus = permissionState.storage
    val isAndroid11Plus = storageStatus.sdkInt >= Build.VERSION_CODES.R
    val isAndroid13Plus = storageStatus.sdkInt >= Build.VERSION_CODES.TIRAMISU

    val storageSubSteps = when {
        isAndroid13Plus -> listOf(
            StorageSubStepItem(
                label = stringResource(R.string.welcome_storage_step_photos_videos),
                granted = storageStatus.photosAndVideosGranted
            ),
            StorageSubStepItem(
                label = stringResource(R.string.welcome_storage_step_music_audio),
                granted = storageStatus.musicAndAudioGranted
            ),
            StorageSubStepItem(
                label = stringResource(R.string.welcome_storage_step_all_files),
                granted = storageStatus.allFilesAccessGranted
            )
        )
        isAndroid11Plus -> listOf(
            StorageSubStepItem(
                label = stringResource(R.string.welcome_storage_step_media_legacy),
                granted = storageStatus.photosAndVideosGranted
            ),
            StorageSubStepItem(
                label = stringResource(R.string.welcome_storage_step_all_files),
                granted = storageStatus.allFilesAccessGranted
            )
        )
        else -> emptyList()
    }

    val storageActionLabel = when {
        storageStatus.isFullyGranted -> stringResource(R.string.welcome_perm_granted)
        storageStatus.completedStepCount > 0 -> stringResource(R.string.welcome_perm_continue)
        else -> stringResource(R.string.welcome_perm_grant)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        // Scrollable welcome hero & permission cards
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .windowInsetsPadding(safeInsets.only(WindowInsetsSides.Top))
                .verticalScroll(rememberScrollState())
                .padding(
                    start = safeStart,
                    end = safeEnd,
                    top = if (layout.isShortLandscape) 20.dp else 44.dp,
                    bottom = 20.dp
                ),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = CONTENT_MAX_WIDTH_DP.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.Start
            ) {
                WelcomeHeroHeader(compact = layout.isShortLandscape)

                Spacer(modifier = Modifier.height(if (layout.isShortLandscape) 20.dp else 32.dp))

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    PermissionActionCard(
                        icon = Icons.Rounded.FolderOpen,
                        title = stringResource(R.string.welcome_perm_storage_title),
                        description = if (isAndroid11Plus) {
                            stringResource(R.string.welcome_perm_storage_desc_r_plus)
                        } else {
                            stringResource(R.string.welcome_perm_storage_desc_legacy)
                        },
                        isGranted = storageStatus.isFullyGranted,
                        actionLabel = storageActionLabel,
                        optionalBadge = null,
                        subSteps = storageSubSteps,
                        onClick = onRequestStoragePermission
                    )

                    PermissionActionCard(
                        icon = Icons.Rounded.NotificationsActive,
                        title = stringResource(R.string.welcome_perm_notification_title),
                        description = stringResource(R.string.welcome_perm_notification_desc),
                        isGranted = permissionState.notificationGranted,
                        actionLabel = if (permissionState.notificationGranted) {
                            stringResource(R.string.welcome_perm_granted)
                        } else {
                            stringResource(R.string.welcome_perm_grant)
                        },
                        optionalBadge = stringResource(R.string.welcome_perm_optional_badge),
                        subSteps = emptyList(),
                        onClick = onRequestNotificationPermission
                    )
                }
            }
        }

        // Bottom-pinned Start CTA area
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(safeInsets.only(WindowInsetsSides.Bottom))
                    .padding(
                        start = safeStart,
                        end = safeEnd,
                        top = 10.dp,
                        bottom = if (layout.isShortLandscape) 14.dp else 24.dp
                    ),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = CONTENT_MAX_WIDTH_DP.dp)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val canStart = permissionState.allRequiredGranted
                    val statusHintColor by animateColorAsState(
                        targetValue = if (canStart) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        animationSpec = tween(COLOR_ANIM_DURATION_MS),
                        label = "welcomeStatusHintColor"
                    )

                    Text(
                        text = if (canStart) {
                            stringResource(R.string.welcome_status_all_granted)
                        } else {
                            stringResource(R.string.welcome_status_storage_required)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (canStart) FontWeight.SemiBold else FontWeight.Medium,
                        color = statusHintColor,
                        textAlign = TextAlign.Center
                    )

                    val startBgColor by animateColorAsState(
                        targetValue = if (canStart) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        },
                        animationSpec = tween(COLOR_ANIM_DURATION_MS),
                        label = "welcomeStartBgColor"
                    )
                    val startTextColor by animateColorAsState(
                        targetValue = if (canStart) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                        },
                        animationSpec = tween(COLOR_ANIM_DURATION_MS),
                        label = "welcomeStartTextColor"
                    )

                    Button(
                        onClick = onStartApp,
                        enabled = canStart,
                        shape = RoundedCornerShape(28.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = startBgColor,
                            contentColor = startTextColor,
                            disabledContainerColor = startBgColor,
                            disabledContentColor = startTextColor
                        ),
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.welcome_action_start),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        if (canStart) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    if (pendingSettingsReason != null) {
        val messageRes = when (pendingSettingsReason) {
            SettingsRedirectReason.STORAGE -> R.string.welcome_settings_dialog_storage_msg
            SettingsRedirectReason.NOTIFICATION -> R.string.welcome_settings_dialog_notification_msg
        }
        AlertDialog(
            onDismissRequest = onDismissSettingsDialog,
            title = {
                Text(
                    text = stringResource(R.string.welcome_settings_dialog_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = stringResource(messageRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(onClick = onConfirmOpenSettings) {
                    Text(stringResource(R.string.welcome_settings_dialog_open))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissSettingsDialog) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

// ═══ Sub-components ═══
@Composable
private fun WelcomeHeroHeader(compact: Boolean) {
    Column(
        horizontalAlignment = Alignment.Start,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = stringResource(R.string.welcome_title),
            style = if (compact) {
                MaterialTheme.typography.headlineMedium
            } else {
                MaterialTheme.typography.headlineLarge
            },
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Start
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.welcome_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Start,
            lineHeight = 24.sp
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PermissionActionCard(
    icon: ImageVector,
    title: String,
    description: String,
    isGranted: Boolean,
    actionLabel: String,
    optionalBadge: String?,
    subSteps: List<StorageSubStepItem>,
    onClick: () -> Unit
) {
    val containerColor by animateColorAsState(
        targetValue = if (isGranted) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.38f)
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        animationSpec = tween(COLOR_ANIM_DURATION_MS),
        label = "permCardBg"
    )
    val borderColor by animateColorAsState(
        targetValue = if (isGranted) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
        } else {
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.32f)
        },
        animationSpec = tween(COLOR_ANIM_DURATION_MS),
        label = "permCardBorder"
    )
    val iconContainerColor by animateColorAsState(
        targetValue = if (isGranted) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
        animationSpec = tween(COLOR_ANIM_DURATION_MS),
        label = "permIconBg"
    )
    val iconTintColor by animateColorAsState(
        targetValue = if (isGranted) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSecondaryContainer
        },
        animationSpec = tween(COLOR_ANIM_DURATION_MS),
        label = "permIconTint"
    )

    val cardShape = RoundedCornerShape(24.dp)

    Surface(
        shape = cardShape,
        color = containerColor,
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .clickable(enabled = !isGranted, onClick = onClick)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = iconContainerColor,
                    modifier = Modifier.size(44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (isGranted) Icons.Rounded.CheckCircle else icon,
                            contentDescription = null,
                            tint = iconTintColor,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.align(Alignment.CenterVertically)
                        )
                        if (optionalBadge != null) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                modifier = Modifier.align(Alignment.CenterVertically)
                            ) {
                                Text(
                                    text = optionalBadge,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 18.sp
                    )
                }
            }

            if (subSteps.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    subSteps.forEach { step ->
                        StorageStepPill(
                            label = step.label,
                            granted = step.granted
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isGranted) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = actionLabel,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                } else {
                    FilledTonalButton(
                        onClick = onClick,
                        shape = RoundedCornerShape(20.dp),
                        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = actionLabel,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StorageStepPill(
    label: String,
    granted: Boolean
) {
    val bgColor = if (granted) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.78f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val contentColor = if (granted) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = bgColor
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Icon(
                imageVector = if (granted) {
                    Icons.Rounded.CheckCircle
                } else {
                    Icons.Outlined.RadioButtonUnchecked
                },
                contentDescription = null,
                tint = if (granted) MaterialTheme.colorScheme.primary else contentColor,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (granted) FontWeight.SemiBold else FontWeight.Medium,
                color = contentColor
            )
        }
    }
}
