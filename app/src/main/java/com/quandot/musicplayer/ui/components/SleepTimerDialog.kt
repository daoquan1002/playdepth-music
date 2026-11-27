/**
 * SleepTimerDialog — Material 3 clock-face dialog for scheduling playback auto-stop.
 *
 * @remarks
 * Defaults to a 24-hour circular analog [TimePicker] dial seeded to either the active scheduled
 * shutoff time or `now + 30 min`, with an instant toggle to compact [TimeInput] on short landscape
 * screens. Computes live countdown previews and wraps cleanly across midnight.
 *
 * @see NowPlayingSheet
 * @see MusicViewModel.setSleepTimerUntil
 */
package com.quandot.musicplayer.ui.components

// External SDK / Jetpack / third-party libraries
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.util.Calendar
import java.util.Locale

// Internal modules
import com.quandot.musicplayer.R
import com.quandot.musicplayer.ui.MusicViewModel

// Domain models & theme tokens
import com.quandot.musicplayer.ui.theme.rememberAdaptiveLayoutInfo

// ═══ Constants ═══
private const val DEFAULT_OFFSET_MINUTES = 30
private const val SLEEP_TIMER_END_OF_TRACK = -1
private const val COMPACT_CLOCK_HEIGHT_DP = 560

// ═══ Public Dialog ═══
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepTimerClockDialog(
    viewModel: MusicViewModel,
    onDismissRequest: () -> Unit
) {
    val context = LocalContext.current
    val layout = rememberAdaptiveLayoutInfo()

    val sleepTimerMinutes by viewModel.sleepTimerMinutes.collectAsState()
    val sleepTimerRemainingSec by viewModel.sleepTimerRemainingSec.collectAsState()
    val sleepTimerTargetEpochMs by viewModel.sleepTimerTargetEpochMs.collectAsState()

    val isTimerActive = sleepTimerMinutes == SLEEP_TIMER_END_OF_TRACK ||
        (sleepTimerMinutes > 0 && sleepTimerRemainingSec > 0L)

    // Seed initial clock time to active scheduled time, or current time + 30 min
    val initialCal = remember {
        Calendar.getInstance().apply {
            if (sleepTimerTargetEpochMs > System.currentTimeMillis()) {
                timeInMillis = sleepTimerTargetEpochMs
            } else {
                add(Calendar.MINUTE, DEFAULT_OFFSET_MINUTES)
            }
        }
    }

    var seedHour by remember { mutableIntStateOf(initialCal.get(Calendar.HOUR_OF_DAY)) }
    var seedMinute by remember { mutableIntStateOf(initialCal.get(Calendar.MINUTE)) }
    var dialSeedNonce by remember { mutableIntStateOf(0) }
    var useKeyboardInput by remember(layout.screenHeightDp) {
        mutableStateOf(layout.screenHeightDp < COMPACT_CLOCK_HEIGHT_DP)
    }

    val applyQuickOffset: (Int) -> Unit = { addMinutes ->
        val cal = Calendar.getInstance().apply {
            add(Calendar.MINUTE, addMinutes)
            if (get(Calendar.SECOND) > 0 || get(Calendar.MILLISECOND) > 0) {
                add(Calendar.MINUTE, 1)
            }
        }
        seedHour = cal.get(Calendar.HOUR_OF_DAY)
        seedMinute = cal.get(Calendar.MINUTE)
        dialSeedNonce++
    }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        SyncDialogSystemBars()

        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 4.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
            modifier = Modifier
                .widthIn(min = 300.dp, max = 380.dp)
                .fillMaxWidth(0.92f)
                .heightIn(max = (layout.screenHeightDp - 40).coerceAtLeast(320).dp)
        ) {
            key(dialSeedNonce) {
                val timePickerState = rememberTimePickerState(
                    initialHour = seedHour,
                    initialMinute = seedMinute,
                    is24Hour = true
                )

                val previewInfo = computeShutoffPreview(
                    selectedHour = timePickerState.hour,
                    selectedMinute = timePickerState.minute
                )
                val formattedTime = String.format(
                    Locale.US,
                    "%02d:%02d",
                    timePickerState.hour,
                    timePickerState.minute
                )
                val timeLabel = if (previewInfo.isTomorrow) {
                    stringResource(R.string.sleep_timer_tomorrow_format, formattedTime)
                } else {
                    formattedTime
                }
                val previewText = when {
                    previewInfo.hours > 0 && previewInfo.minutes > 0 -> stringResource(
                        R.string.sleep_timer_stop_at_hours_minutes,
                        previewInfo.hours,
                        previewInfo.minutes,
                        timeLabel
                    )
                    previewInfo.hours > 0 -> stringResource(
                        R.string.sleep_timer_stop_at_hours,
                        previewInfo.hours,
                        timeLabel
                    )
                    else -> stringResource(
                        R.string.sleep_timer_stop_at_minutes,
                        previewInfo.minutes.coerceAtLeast(1),
                        timeLabel
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Header row with clock / keyboard input switch
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Timer,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                            Column {
                                Text(
                                    text = stringResource(R.string.settings_sleep_timer_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = stringResource(R.string.sleep_timer_dialog_subtitle),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        IconButton(
                            onClick = { useKeyboardInput = !useKeyboardInput },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                imageVector = if (useKeyboardInput) Icons.Outlined.Schedule else Icons.Outlined.Keyboard,
                                contentDescription = stringResource(
                                    if (useKeyboardInput) R.string.sleep_timer_switch_clock
                                    else R.string.sleep_timer_switch_keyboard
                                ),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (isTimerActive) {
                        Spacer(modifier = Modifier.height(10.dp))
                        val statusText = if (sleepTimerMinutes == SLEEP_TIMER_END_OF_TRACK) {
                            stringResource(R.string.sleep_timer_active_track_end)
                        } else {
                            stringResource(
                                R.string.sleep_timer_active_remaining,
                                formatSleepCountdown(sleepTimerRemainingSec)
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = statusText,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Material 3 24-hour clock dial or compact keyboard time input
                    val pickerColors = TimePickerDefaults.colors(
                        clockDialColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        selectorColor = MaterialTheme.colorScheme.primary,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        timeSelectorSelectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        timeSelectorUnselectedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        timeSelectorSelectedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        timeSelectorUnselectedContentColor = MaterialTheme.colorScheme.onSurface
                    )
                    if (useKeyboardInput) {
                        TimeInput(
                            state = timePickerState,
                            colors = pickerColors
                        )
                    } else {
                        TimePicker(
                            state = timePickerState,
                            colors = pickerColors,
                            layoutType = TimePickerLayoutType.Vertical
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Live shutoff preview
                    Text(
                        text = previewText,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Quick offset & track-end chips
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        QuickTimerChip(
                            label = stringResource(R.string.sleep_timer_quick_15m),
                            onClick = { applyQuickOffset(15) }
                        )
                        QuickTimerChip(
                            label = stringResource(R.string.sleep_timer_quick_30m),
                            onClick = { applyQuickOffset(30) }
                        )
                        QuickTimerChip(
                            label = stringResource(R.string.sleep_timer_quick_60m),
                            onClick = { applyQuickOffset(60) }
                        )
                        QuickTimerChip(
                            label = stringResource(R.string.settings_timer_track_end),
                            highlighted = sleepTimerMinutes == SLEEP_TIMER_END_OF_TRACK,
                            onClick = {
                                viewModel.setSleepTimer(SLEEP_TIMER_END_OF_TRACK)
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.toast_timer_track_end),
                                    Toast.LENGTH_SHORT
                                ).show()
                                onDismissRequest()
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Action buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        if (isTimerActive) {
                            TextButton(
                                onClick = {
                                    viewModel.setSleepTimer(0)
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.toast_timer_off),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    onDismissRequest()
                                },
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                Text(
                                    text = stringResource(R.string.sleep_timer_turn_off),
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        } else {
                            Spacer(modifier = Modifier.width(1.dp))
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = onDismissRequest) {
                                Text(text = stringResource(R.string.action_cancel))
                            }
                            Button(
                                onClick = {
                                    viewModel.setSleepTimerUntil(
                                        hour = timePickerState.hour,
                                        minute = timePickerState.minute
                                    )
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.toast_timer_scheduled, formattedTime),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    onDismissRequest()
                                },
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.sleep_timer_set),
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ═══ Helpers ═══
@Composable
private fun QuickTimerChip(
    label: String,
    onClick: () -> Unit,
    highlighted: Boolean = false
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (highlighted) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.7f)
        },
        border = BorderStroke(
            width = 1.dp,
            color = if (highlighted) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
            }
        )
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (highlighted) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

private data class ShutoffPreview(
    val hours: Int,
    val minutes: Int,
    val isTomorrow: Boolean
)

private fun computeShutoffPreview(selectedHour: Int, selectedMinute: Int): ShutoffPreview {
    val now = Calendar.getInstance()
    val target = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, selectedHour)
        set(Calendar.MINUTE, selectedMinute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    var isTomorrow = false
    if (!target.after(now)) {
        target.add(Calendar.DAY_OF_YEAR, 1)
        isTomorrow = true
    }
    val totalMinutes = ((target.timeInMillis - now.timeInMillis + 59_999L) / 60_000L).toInt().coerceAtLeast(1)
    return ShutoffPreview(
        hours = totalMinutes / 60,
        minutes = totalMinutes % 60,
        isTomorrow = isTomorrow
    )
}

internal fun formatSleepCountdown(remainingSec: Long): String {
    val safeSec = remainingSec.coerceAtLeast(0L)
    val hours = safeSec / 3600L
    val mins = (safeSec % 3600L) / 60L
    val secs = safeSec % 60L
    return if (hours > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", hours, mins, secs)
    } else {
        String.format(Locale.US, "%02d:%02d", mins, secs)
    }
}
