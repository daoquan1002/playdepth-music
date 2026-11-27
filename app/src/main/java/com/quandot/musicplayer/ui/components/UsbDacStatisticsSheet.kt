/**
 * UsbDacStatisticsSheet — Reusable USB DAC telemetry cards and Now Playing flyout bottom sheet.
 *
 * @remarks
 * Provides a single source of truth for hardware capabilities, active signal-path telemetry, and
 * volume/clock-sync diagnostics shared between the full-screen statistics page in [SettingsScreen]
 * and the interactive modal flyout triggered by tapping the USB DAC status badge in [NowPlayingSheet].
 *
 * @see SettingsScreen
 * @see NowPlayingSheet
 */
package com.quandot.musicplayer.ui.components

// External SDK / Jetpack / third-party libraries
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.outlined.AvTimer
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale

// Internal modules
import com.quandot.musicplayer.R
import com.quandot.musicplayer.audio.UsbDacNative
import com.quandot.musicplayer.audio.UsbDacStatus
import com.quandot.musicplayer.data.AudioSpecsHelper
import com.quandot.musicplayer.ui.ActiveDspStage
import com.quandot.musicplayer.ui.AudioOutputUiState

// Domain models & theme tokens (last)
import com.quandot.musicplayer.ui.theme.rememberAdaptiveLayoutInfo

// ═══ Modal Flyout Bottom Sheet (Now Playing) ═══
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsbDacStatisticsBottomSheet(
    audioOutputState: AudioOutputUiState,
    onRefresh: () -> Unit,
    onDismissRequest: () -> Unit
) {
    val didInitialRefreshRef = remember { booleanArrayOf(false) }
    SideEffect {
        if (!didInitialRefreshRef[0]) {
            didInitialRefreshRef[0] = true
            onRefresh()
        }
    }

    val layout = rememberAdaptiveLayoutInfo()

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetMaxWidth = if (layout.isTabletOrExpanded) 580.dp else BottomSheetDefaults.SheetMaxWidth,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(
            topStart = if (layout.isTabletOrExpanded) 28.dp else 24.dp,
            topEnd = if (layout.isTabletOrExpanded) 28.dp else 24.dp
        )
    ) {
        SyncDialogSystemBars()

        val maxListHeight = (layout.screenHeightDp * 0.74f).coerceAtLeast(280f).dp

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
                .navigationBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Usb,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
                Text(
                    text = stringResource(R.string.settings_usb_stats_screen_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onRefresh) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.usb_stats_refresh),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            val sheetCardColor = MaterialTheme.colorScheme.surfaceContainerLow
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxListHeight),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                usbDacStatisticsItems(
                    audioOutputState = audioOutputState,
                    cardColor = sheetCardColor
                )
            }
        }
    }
}

// ═══ Shared Statistics Cards (Settings Page + Flyout) ═══
fun LazyListScope.usbDacStatisticsItems(
    audioOutputState: AudioOutputUiState,
    cardColor: Color? = null
) {
    item(key = "usb_stats_hardware", contentType = "usb_stats_card") {
        val noneText = stringResource(R.string.usb_stats_none)
        val supportedText = stringResource(R.string.usb_stats_supported)
        val unsupportedText = stringResource(R.string.usb_stats_unsupported)
        val isConnected = audioOutputState.dacStatus == UsbDacStatus.READY ||
            audioOutputState.dacStatus == UsbDacStatus.PAUSED ||
            audioOutputState.dacStatus == UsbDacStatus.STREAMING
        val statusText = when (audioOutputState.dacStatus) {
            UsbDacStatus.STREAMING -> stringResource(R.string.usb_stats_status_streaming)
            UsbDacStatus.PAUSED -> stringResource(R.string.usb_stats_status_paused)
            UsbDacStatus.READY -> stringResource(R.string.usb_stats_status_ready)
            UsbDacStatus.PERMISSION_REQUIRED -> stringResource(R.string.usb_stats_status_permission)
            UsbDacStatus.ERROR -> stringResource(R.string.usb_stats_status_error)
            UsbDacStatus.DISCONNECTED -> stringResource(R.string.usb_stats_status_disconnected)
        }
        val badgeContainer = when (audioOutputState.dacStatus) {
            UsbDacStatus.STREAMING, UsbDacStatus.PAUSED, UsbDacStatus.READY -> MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
            UsbDacStatus.ERROR -> MaterialTheme.colorScheme.errorContainer
            else -> MaterialTheme.colorScheme.surfaceContainerHighest
        }
        val badgeContent = when (audioOutputState.dacStatus) {
            UsbDacStatus.STREAMING, UsbDacStatus.PAUSED, UsbDacStatus.READY -> MaterialTheme.colorScheme.primary
            UsbDacStatus.ERROR -> MaterialTheme.colorScheme.onErrorContainer
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }

        UsbStatsSectionCard(
            icon = Icons.Default.Usb,
            title = stringResource(R.string.usb_stats_section_hardware),
            badgeText = statusText,
            badgeContainerColor = badgeContainer,
            badgeContentColor = badgeContent,
            containerColor = cardColor ?: MaterialTheme.colorScheme.surfaceContainer
        ) {
            Spacer(modifier = Modifier.height(10.dp))
            val deviceLabel = if (audioOutputState.deviceName.isNotBlank()) {
                val vidPid = if (audioOutputState.vendorId != 0 || audioOutputState.productId != 0) {
                    String.format(Locale.US, " (%04X:%04X)", audioOutputState.vendorId, audioOutputState.productId)
                } else ""
                audioOutputState.deviceName + vidPid
            } else {
                noneText
            }
            UsbStatRow(
                label = stringResource(R.string.usb_stats_label_device),
                value = deviceLabel
            )
            UsbStatDivider()
            UsbStatRow(
                label = stringResource(R.string.usb_stats_label_protocol),
                value = audioOutputState.uacVersionLabel.ifBlank { noneText }
            )
            UsbStatDivider()
            UsbStatRow(
                label = stringResource(R.string.usb_stats_label_native_dsd),
                value = if (isConnected) {
                    if (audioOutputState.supportsNativeDsd) supportedText else unsupportedText
                } else noneText,
                highlight = isConnected && audioOutputState.supportsNativeDsd
            )
            UsbStatDivider()
            UsbStatRow(
                label = stringResource(R.string.usb_stats_label_dop),
                value = if (isConnected) {
                    if (audioOutputState.supportsDop) supportedText else unsupportedText
                } else noneText,
                highlight = isConnected && audioOutputState.supportsDop
            )
            UsbStatDivider()
            UsbStatRow(
                label = stringResource(R.string.usb_stats_label_max_dsd),
                value = if (isConnected && audioOutputState.maxDsdRate > 0) {
                    "DSD${audioOutputState.maxDsdRate}"
                } else noneText
            )
            UsbStatDivider()
            UsbStatRow(
                label = stringResource(R.string.usb_stats_label_supported_rates),
                value = if (audioOutputState.supportedSampleRates.isNotEmpty()) {
                    formatSampleRateList(audioOutputState.supportedSampleRates)
                } else noneText
            )
            UsbStatDivider()
            UsbStatRow(
                label = stringResource(R.string.usb_stats_label_supported_bits),
                value = if (audioOutputState.supportedBitDepths.isNotEmpty()) {
                    audioOutputState.supportedBitDepths.joinToString(", ") { "${it}-bit" }
                } else noneText
            )
            UsbStatDivider()
            UsbStatRow(
                label = stringResource(R.string.usb_stats_label_max_channels),
                value = if (isConnected) "${audioOutputState.maxSupportedChannels} ch" else noneText
            )
        }
    }

    item(key = "usb_stats_pipeline", contentType = "usb_stats_card") {
        val idleText = stringResource(R.string.usb_stats_idle)
        val routeBadge = audioOutputState.audioPathBadge.ifBlank { idleText }
        val isBitExact = audioOutputState.isDirectStreaming && audioOutputState.isBitExactRoute

        UsbStatsSectionCard(
            icon = Icons.Outlined.GraphicEq,
            title = stringResource(R.string.usb_stats_section_stream),
            badgeText = routeBadge,
            badgeContainerColor = if (isBitExact) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
            } else if (audioOutputState.isDirectStreaming) {
                MaterialTheme.colorScheme.tertiaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHighest
            },
            badgeContentColor = if (isBitExact) {
                MaterialTheme.colorScheme.primary
            } else if (audioOutputState.isDirectStreaming) {
                MaterialTheme.colorScheme.onTertiaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            containerColor = cardColor ?: MaterialTheme.colorScheme.surfaceContainer
        ) {
            Spacer(modifier = Modifier.height(10.dp))
            UsbStatRow(
                label = stringResource(R.string.usb_stats_label_source_format),
                value = audioOutputState.sourceSpecs.ifBlank { idleText }
            )
            UsbStatDivider()
            val dacOutputText = if (audioOutputState.isDirectStreaming && audioOutputState.wireFormatLabel.isNotBlank()) {
                val chSuffix = if (audioOutputState.wireChannels > 0) " • ${audioOutputState.wireChannels} ch" else ""
                "${audioOutputState.wireFormatLabel}$chSuffix"
            } else {
                idleText
            }
            UsbStatRow(
                label = stringResource(R.string.usb_stats_label_dac_output),
                value = dacOutputText,
                highlight = audioOutputState.isDirectStreaming
            )
            UsbStatDivider()
            UsbStatRow(
                label = stringResource(R.string.usb_stats_label_transport_mode),
                value = routeBadge
            )
            UsbStatDivider()
            val dspStagesText = if (!audioOutputState.isDirectStreaming) {
                idleText
            } else {
                val stageNames = audioOutputState.activeDspStages.map { stage ->
                    when (stage) {
                        ActiveDspStage.DSD_TO_PCM -> "DSD → PCM"
                        ActiveDspStage.RESAMPLED -> "Resampled"
                        ActiveDspStage.DOWNMIXED -> "Downmixed"
                        ActiveDspStage.MONO_TO_STEREO -> "Mono → Stereo"
                        ActiveDspStage.DITHERED -> "Dithered (TPDF)"
                        ActiveDspStage.SW_VOLUME -> "SW Volume (64-bit)"
                        ActiveDspStage.ZERO_PADDED -> "Zero-Padded"
                        ActiveDspStage.FLOAT_TO_PCM -> "Float32 → PCM"
                    }
                }
                if (stageNames.isEmpty()) {
                    stringResource(R.string.usb_stats_dsp_none_bypass)
                } else {
                    stageNames.joinToString(", ")
                }
            }
            UsbStatRow(
                label = stringResource(R.string.usb_stats_label_dsp_stages),
                value = dspStagesText
            )
        }
    }

    item(key = "usb_stats_volume_clock", contentType = "usb_stats_card") {
        val noneText = stringResource(R.string.usb_stats_none)
        val idleText = stringResource(R.string.usb_stats_idle)
        val isConnected = audioOutputState.dacStatus == UsbDacStatus.READY ||
            audioOutputState.dacStatus == UsbDacStatus.PAUSED ||
            audioOutputState.dacStatus == UsbDacStatus.STREAMING

        UsbStatsSectionCard(
            icon = Icons.Outlined.AvTimer,
            title = stringResource(R.string.usb_stats_section_volume_clock),
            containerColor = cardColor ?: MaterialTheme.colorScheme.surfaceContainer
        ) {
            Spacer(modifier = Modifier.height(10.dp))
            val hwVolValue = if (!isConnected) {
                noneText
            } else if (audioOutputState.hasHardwareVolume) {
                val rangeStr = String.format(
                    Locale.US,
                    "%.1f..%.1f dB",
                    audioOutputState.hardwareVolumeMinDb256 / 256.0,
                    audioOutputState.hardwareVolumeMaxDb256 / 256.0
                )
                stringResource(R.string.usb_stats_hw_vol_supported, rangeStr)
            } else {
                stringResource(R.string.usb_stats_hw_vol_sw_fallback)
            }
            UsbStatRow(
                label = stringResource(R.string.usb_stats_label_hw_volume),
                value = hwVolValue,
                highlight = isConnected && audioOutputState.hasHardwareVolume
            )
            UsbStatDivider()
            UsbStatRow(
                label = stringResource(R.string.usb_stats_label_sw_vol_bypass),
                value = if (!isConnected) {
                    noneText
                } else if (audioOutputState.softwareVolumeBypassed) {
                    stringResource(R.string.usb_stats_bypass_active)
                } else {
                    stringResource(R.string.usb_stats_bypass_inactive)
                }
            )
            UsbStatDivider()
            val volLevelText = if (audioOutputState.isMuted) {
                "${audioOutputState.volumeStep} / ${audioOutputState.maxVolumeSteps} (${stringResource(R.string.usb_stats_muted_suffix)})"
            } else {
                "${audioOutputState.volumeStep} / ${audioOutputState.maxVolumeSteps}"
            }
            UsbStatRow(
                label = stringResource(R.string.usb_stats_label_volume_level),
                value = volLevelText
            )
            UsbStatDivider()
            UsbStatRow(
                label = stringResource(R.string.usb_stats_label_clock_sync),
                value = formatClockSyncLabel(audioOutputState.clockSyncMode, idleText)
            )
            UsbStatDivider()
            val fbLockValue = if (!audioOutputState.isDirectStreaming) {
                idleText
            } else if (audioOutputState.feedbackLocked) {
                stringResource(R.string.usb_stats_locked)
            } else {
                stringResource(R.string.usb_stats_unlocked)
            }
            UsbStatRow(
                label = stringResource(R.string.usb_stats_label_fb_lock),
                value = fbLockValue,
                highlight = audioOutputState.isDirectStreaming && audioOutputState.feedbackLocked
            )
            UsbStatDivider()
            UsbStatRow(
                label = stringResource(R.string.usb_stats_label_fb_drift),
                value = if (audioOutputState.isDirectStreaming) {
                    String.format(Locale.US, "%+.1f ppm", audioOutputState.clockDriftPpmX10 / 10.0)
                } else idleText
            )
            UsbStatDivider()
            val healthValue = if (audioOutputState.isDirectStreaming) {
                if (audioOutputState.stallCount > 0L) {
                    "${audioOutputState.underrunCount} / ${audioOutputState.stallCount} (max ${audioOutputState.maxStallMs} ms)"
                } else {
                    "${audioOutputState.underrunCount} / ${audioOutputState.stallCount}"
                }
            } else {
                idleText
            }
            UsbStatRow(
                label = stringResource(R.string.usb_stats_label_rt_health),
                value = healthValue
            )
        }
    }
}

// ═══ Private Sub-components & Formatters ═══
@Composable
private fun UsbStatsSectionCard(
    icon: ImageVector,
    title: String,
    badgeText: String? = null,
    badgeContainerColor: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
    badgeContentColor: Color = MaterialTheme.colorScheme.primary,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = containerColor,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f)),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                if (!badgeText.isNullOrBlank()) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = badgeContainerColor
                    ) {
                        Text(
                            text = badgeText,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = badgeContentColor,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
            }
            content()
        }
    }
}

@Composable
private fun UsbStatRow(
    label: String,
    value: String,
    highlight: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.46f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (highlight) FontWeight.Bold else FontWeight.Medium,
            color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(0.54f)
        )
    }
}

@Composable
private fun UsbStatDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.24f))
}

private fun formatClockSyncLabel(mode: Int, idleLabel: String): String = when (mode) {
    UsbDacNative.SYNC_MODE_ASYNC_FEEDBACK -> "Async Feedback"
    UsbDacNative.SYNC_MODE_ADAPTIVE -> "Adaptive"
    UsbDacNative.SYNC_MODE_SYNCHRONOUS -> "Synchronous"
    UsbDacNative.SYNC_MODE_WATCHDOG_FALLBACK -> "Nominal (Watchdog)"
    else -> idleLabel
}

private fun formatSampleRateList(rates: List<Int>): String {
    return rates.sorted().joinToString(", ") { hz ->
        AudioSpecsHelper.formatKhz(hz)
    }
}
