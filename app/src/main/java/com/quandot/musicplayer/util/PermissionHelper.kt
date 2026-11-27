/**
 * PermissionHelper — Dynamic runtime and special-access permission evaluator for onboarding.
 *
 * @remarks
 * Always queries Android permission APIs live without caching grant states in memory so runtime
 * revocations or returns from System Settings take effect immediately. Separates pure API-level
 * decision logic (`evaluateStorageStatus`) from `Context` lookups so every Android 8–15 tier
 * (API 26–29 legacy storage, API 30–32 storage + all-files, API 33+ sequential visual media ->
 * audio media -> all-files) is deterministically unit-testable.
 *
 * @see WelcomeActivity
 * @see MainActivity
 */
package com.quandot.musicplayer.util

// External SDK / Jetpack / third-party libraries
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

// ═══ Constants ═══
private const val PREFS_NAME = "music_playback_prefs"
private const val PREF_WELCOME_COMPLETED = "welcome_onboarding_completed"

// ═══ Models & Steps ═══
enum class StoragePermissionStep {
    LEGACY_STORAGE,
    MEDIA_STORAGE_LEGACY,
    PHOTOS_AND_VIDEOS,
    MUSIC_AND_AUDIO,
    ALL_FILES_ACCESS
}

data class StoragePermissionStatus(
    val sdkInt: Int,
    val photosAndVideosGranted: Boolean,
    val musicAndAudioGranted: Boolean,
    val allFilesAccessGranted: Boolean,
    val isFullyGranted: Boolean,
    val nextRequiredStep: StoragePermissionStep?,
    val completedStepCount: Int,
    val totalStepCount: Int
)

data class WelcomePermissionState(
    val storage: StoragePermissionStatus,
    val notificationGranted: Boolean
) {
    /** Storage is mandatory; notification access is optional. */
    val allRequiredGranted: Boolean
        get() = storage.isFullyGranted
}

// ═══ Permission Evaluator ═══
object PermissionHelper {

    fun isWelcomeCompleted(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(PREF_WELCOME_COMPLETED, false)
    }

    fun markWelcomeCompleted(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(PREF_WELCOME_COMPLETED, true).apply()
    }

    fun canEnterMainActivity(context: Context): Boolean =
        isWelcomeCompleted(context) && hasAllRequiredPermissions(context)

    fun evaluateWelcomePermissions(context: Context): WelcomePermissionState {
        return WelcomePermissionState(
            storage = evaluateStoragePermissions(context),
            notificationGranted = isNotificationPermissionGranted(context)
        )
    }

    fun hasAllRequiredPermissions(context: Context): Boolean =
        evaluateStoragePermissions(context).isFullyGranted

    fun evaluateStoragePermissions(context: Context): StoragePermissionStatus {
        val sdkInt = Build.VERSION.SDK_INT
        val legacyRead = isGranted(context, Manifest.permission.READ_EXTERNAL_STORAGE)
        val legacyWrite = isGranted(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        val readImages = if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
            isGranted(context, Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            false
        }
        val readVideo = if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
            isGranted(context, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            false
        }
        val readVisualUserSelected = if (sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            isGranted(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        } else {
            false
        }
        val readAudio = if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
            isGranted(context, Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            false
        }
        val allFilesAccess = if (sdkInt >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }

        return evaluateStorageStatus(
            sdkInt = sdkInt,
            legacyReadGranted = legacyRead,
            legacyWriteGranted = legacyWrite,
            readMediaImagesGranted = readImages,
            readMediaVideoGranted = readVideo,
            readMediaVisualUserSelectedGranted = readVisualUserSelected,
            readMediaAudioGranted = readAudio,
            isExternalStorageManager = allFilesAccess
        )
    }

    /**
     * Pure API-level storage state machine shared by runtime checks and JVM unit tests.
     *
     * - API <= 29 (Android 10 and below): single legacy storage permission.
     * - API 30..32 (Android 11–12): sequential `READ_EXTERNAL_STORAGE` -> `MANAGE_EXTERNAL_STORAGE`.
     * - API 33+ (Android 13+): sequential Photos & Videos -> Music & Audio -> `MANAGE_EXTERNAL_STORAGE`.
     */
    fun evaluateStorageStatus(
        sdkInt: Int,
        legacyReadGranted: Boolean,
        legacyWriteGranted: Boolean,
        readMediaImagesGranted: Boolean,
        readMediaVideoGranted: Boolean,
        readMediaVisualUserSelectedGranted: Boolean,
        readMediaAudioGranted: Boolean,
        isExternalStorageManager: Boolean
    ): StoragePermissionStatus {
        return when {
            sdkInt <= Build.VERSION_CODES.Q -> {
                val legacyGranted = legacyReadGranted && legacyWriteGranted
                StoragePermissionStatus(
                    sdkInt = sdkInt,
                    photosAndVideosGranted = legacyGranted,
                    musicAndAudioGranted = legacyGranted,
                    allFilesAccessGranted = true,
                    isFullyGranted = legacyGranted,
                    nextRequiredStep = if (legacyGranted) null else StoragePermissionStep.LEGACY_STORAGE,
                    completedStepCount = if (legacyGranted) 1 else 0,
                    totalStepCount = 1
                )
            }

            sdkInt < Build.VERSION_CODES.TIRAMISU -> {
                val nextStep = when {
                    !legacyReadGranted -> StoragePermissionStep.MEDIA_STORAGE_LEGACY
                    !isExternalStorageManager -> StoragePermissionStep.ALL_FILES_ACCESS
                    else -> null
                }
                val completed = (if (legacyReadGranted) 1 else 0) +
                    (if (isExternalStorageManager) 1 else 0)
                StoragePermissionStatus(
                    sdkInt = sdkInt,
                    photosAndVideosGranted = legacyReadGranted,
                    musicAndAudioGranted = legacyReadGranted,
                    allFilesAccessGranted = isExternalStorageManager,
                    isFullyGranted = legacyReadGranted && isExternalStorageManager,
                    nextRequiredStep = nextStep,
                    completedStepCount = completed,
                    totalStepCount = 2
                )
            }

            else -> {
                val visualGranted = (readMediaImagesGranted && readMediaVideoGranted) ||
                    (sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && readMediaVisualUserSelectedGranted)
                val nextStep = when {
                    !visualGranted -> StoragePermissionStep.PHOTOS_AND_VIDEOS
                    !readMediaAudioGranted -> StoragePermissionStep.MUSIC_AND_AUDIO
                    !isExternalStorageManager -> StoragePermissionStep.ALL_FILES_ACCESS
                    else -> null
                }
                val completed = (if (visualGranted) 1 else 0) +
                    (if (readMediaAudioGranted) 1 else 0) +
                    (if (isExternalStorageManager) 1 else 0)
                StoragePermissionStatus(
                    sdkInt = sdkInt,
                    photosAndVideosGranted = visualGranted,
                    musicAndAudioGranted = readMediaAudioGranted,
                    allFilesAccessGranted = isExternalStorageManager,
                    isFullyGranted = visualGranted && readMediaAudioGranted && isExternalStorageManager,
                    nextRequiredStep = nextStep,
                    completedStepCount = completed,
                    totalStepCount = 3
                )
            }
        }
    }

    fun isNotificationPermissionGranted(context: Context): Boolean {
        val notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            isGranted(context, Manifest.permission.POST_NOTIFICATIONS) && notificationsEnabled
        } else {
            notificationsEnabled
        }
    }

    fun hasPostNotificationsRuntimePermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            isGranted(context, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            true
        }
    }

    fun legacyStoragePermissions(): Array<String> = arrayOf(
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE
    )

    fun photosAndVideosPermissions(): Array<String> = arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO
    )

    // ═══ System Settings Intents ═══

    /**
     * Builds an Activity-result-safe intent for All Files Access without `FLAG_ACTIVITY_NEW_TASK`
     * so `ActivityResultLauncher` delivers the callback after the user navigates back.
     */
    fun buildAllFilesAccessIntent(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.fromParts("package", context.packageName, null)
            )
        } else {
            buildAppDetailsSettingsIntent(context)
        }
    }

    fun buildFallbackAllFilesAccessIntent(): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        } else {
            Intent(Settings.ACTION_SETTINGS)
        }
    }

    fun buildNotificationSettingsIntent(context: Context): Intent {
        return Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        }
    }

    fun buildAppDetailsSettingsIntent(context: Context): Intent {
        return Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null)
        )
    }

    // ═══ Private Helpers ═══
    private fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
