/**
 * ScanNotificationHelper — Progress and completion notifications for background library scans.
 *
 * @remarks
 * Progress updates are throttled (`PROGRESS_THROTTLE_MS`) and synchronized to avoid tripping
 * Android SystemUI per-app notification rate limits during rapid MediaStore cursor iteration.
 * `showCompleted` cancels the ongoing progress notification beforehand so the completion banner is
 * not dropped or left stuck as ongoing by SystemUI rate limiting.
 *
 * @see LocaleUtils
 * @see MusicRepository
 */
package com.quandot.musicplayer.util

// External SDK / Jetpack / third-party libraries
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Internal modules
import com.quandot.musicplayer.R
import com.quandot.musicplayer.debug.PDLog

object ScanNotificationHelper {

    // ═══ Constants & State ═══
    private const val CHANNEL_ID = "library_scan_channel_v2"
    private const val NOTIFICATION_ID = 2002
    private const val PROGRESS_THROTTLE_MS = 400L
    private const val COMPLETION_AUTO_DISMISS_MS = 3000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var lastProgressUpdateMs = 0L

    @Volatile
    private var isScanActive = false

    private var autoDismissJob: Job? = null

    // ═══ Public API ═══
    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val locContext = LocaleUtils.getLocalizedContext(context)
            val name = locContext.getString(R.string.notif_channel_scan_name)
            val descriptionText = locContext.getString(R.string.notif_channel_scan_desc)
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                setShowBadge(true)
            }
            val notificationManager: NotificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun showProgress(
        context: Context,
        currentCount: Int,
        totalEstimate: Int = 0,
        isInitial: Boolean = false
    ) {
        if (!hasNotificationPermission(context)) return
        val appContext = context.applicationContext
        val now = SystemClock.elapsedRealtime()

        synchronized(this) {
            if (isInitial) {
                isScanActive = true
                lastProgressUpdateMs = now
                autoDismissJob?.cancel()
                autoDismissJob = null
            } else {
                if (!isScanActive) return
                if (now - lastProgressUpdateMs < PROGRESS_THROTTLE_MS) return
                lastProgressUpdateMs = now
            }

            createNotificationChannel(appContext)
            val locContext = LocaleUtils.getLocalizedContext(appContext)

            val contentText = if (currentCount <= 0 && totalEstimate <= 0) {
                locContext.getString(R.string.settings_rescan_scanning)
            } else {
                locContext.resources.getQuantityString(
                    R.plurals.notif_scan_progress,
                    currentCount,
                    currentCount
                )
            }

            val builder = NotificationCompat.Builder(appContext, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(locContext.getString(R.string.notif_scan_title))
                .setContentText(contentText)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setOngoing(true)
                .setOnlyAlertOnce(true)

            if (totalEstimate > 0) {
                builder.setProgress(totalEstimate, currentCount.coerceAtMost(totalEstimate), false)
            } else {
                builder.setProgress(0, 0, true)
            }

            try {
                NotificationManagerCompat.from(appContext).notify(NOTIFICATION_ID, builder.build())
            } catch (e: SecurityException) {
                PDLog.w(PDLog.SCANNER, "SCAN_NOTIF_ERR", "phase=progress err=${e.message}")
            }
        }
    }

    fun showCompleted(context: Context, totalCount: Int) {
        val appContext = context.applicationContext
        synchronized(this) {
            isScanActive = false
            autoDismissJob?.cancel()
            autoDismissJob = null

            val manager = NotificationManagerCompat.from(appContext)
            if (!hasNotificationPermission(appContext)) {
                try {
                    manager.cancel(NOTIFICATION_ID)
                } catch (_: Exception) {}
                return
            }
            createNotificationChannel(appContext)
            val locContext = LocaleUtils.getLocalizedContext(appContext)

            val builder = NotificationCompat.Builder(appContext, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(locContext.getString(R.string.notif_scan_done_title))
                .setContentText(
                    locContext.resources.getQuantityString(
                        R.plurals.notif_scan_done_text,
                        totalCount,
                        totalCount
                    )
                )
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setProgress(0, 0, false)
                .setOngoing(false)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setTimeoutAfter(COMPLETION_AUTO_DISMISS_MS)

            try {
                manager.notify(NOTIFICATION_ID, builder.build())
            } catch (e: SecurityException) {
                PDLog.w(PDLog.SCANNER, "SCAN_NOTIF_ERR", "phase=completed err=${e.message}")
            }

            autoDismissJob = scope.launch {
                delay(COMPLETION_AUTO_DISMISS_MS)
                synchronized(this@ScanNotificationHelper) {
                    if (!isScanActive) {
                        try {
                            NotificationManagerCompat.from(appContext).cancel(NOTIFICATION_ID)
                        } catch (_: Exception) {}
                    }
                }
            }
        }
    }

    fun cancel(context: Context) {
        synchronized(this) {
            isScanActive = false
            autoDismissJob?.cancel()
            autoDismissJob = null
            try {
                NotificationManagerCompat.from(context.applicationContext).cancel(NOTIFICATION_ID)
            } catch (_: Exception) {}
        }
    }

    // ═══ Private Helpers ═══
    private fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }
}
