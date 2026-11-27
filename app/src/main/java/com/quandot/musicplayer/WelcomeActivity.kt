/**
 * WelcomeActivity — Standalone onboarding Activity coordinating runtime and special-access permissions.
 *
 * @remarks
 * Acts as the app's launcher entrypoint. Returning users who have completed onboarding and retain
 * all required permissions bypass directly to [MainActivity] before composition so cold-start
 * latency stays minimal. First-time users—or users who later revoke a required permission—step
 * through Storage (single legacy prompt on Android 10 and below; sequential Photos & Videos ->
 * Music & Audio -> All Files Access on Android 11+) and Notification permissions until every
 * requirement is verified live, then enter [MainActivity] via the Start button.
 *
 * @see MainActivity
 * @see WelcomeScreen
 * @see PermissionHelper
 */
package com.quandot.musicplayer

// External SDK / Jetpack / third-party libraries
import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

// Internal modules
import com.quandot.musicplayer.debug.AppLogManager
import com.quandot.musicplayer.debug.PDLog
import com.quandot.musicplayer.ui.screens.SettingsRedirectReason
import com.quandot.musicplayer.ui.screens.WelcomeScreen
import com.quandot.musicplayer.util.LocaleUtils
import com.quandot.musicplayer.util.PermissionHelper
import com.quandot.musicplayer.util.StoragePermissionStep

// Domain models & theme tokens (last)
import com.quandot.musicplayer.ui.theme.MusicPlayerTheme

// ═══ Constants ═══
private const val PREFS_NAME = "music_playback_prefs"
private const val PREF_THEME_MODE = "theme_mode"
private const val PREF_DYNAMIC_COLOR = "dynamic_color"
private const val PREF_AMOLED_MODE = "amoled_mode"
private const val DEFAULT_THEME_MODE = "system"

// ═══ Activity Host ═══
class WelcomeActivity : ComponentActivity() {
    companion object {
        const val EXTRA_PENDING_AUDIO_URI = "com.quandot.musicplayer.EXTRA_PENDING_AUDIO_URI"
        const val EXTRA_PENDING_AUDIO_TYPE = "com.quandot.musicplayer.EXTRA_PENDING_AUDIO_TYPE"
    }

    private var configRevision by mutableIntStateOf(0)
    private var latestConfig by mutableStateOf<Configuration?>(null)

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        latestConfig = Configuration(newConfig)
        configRevision++
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleUtils.getLocalizedContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            ),
            navigationBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            )
        )
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setBackgroundDrawable(null)

        // Work around Android launcher re-launch bug: resume existing task instead of duplicating root
        if (!isTaskRoot && intent.hasCategory(Intent.CATEGORY_LAUNCHER) && intent.action == Intent.ACTION_MAIN) {
            finish()
            return
        }

        AppLogManager.init(this)

        // Returning users with all required permissions still intact enter MainActivity immediately
        if (savedInstanceState == null && PermissionHelper.canEnterMainActivity(this)) {
            navigateToMain(animated = false)
            return
        }

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedThemeMode = prefs.getString(PREF_THEME_MODE, DEFAULT_THEME_MODE) ?: DEFAULT_THEME_MODE
        val savedDynamicColor = prefs.getBoolean(PREF_DYNAMIC_COLOR, true)
        val savedAmoledMode = prefs.getBoolean(PREF_AMOLED_MODE, false)

        setContent {
            val isSystemDark = isSystemInDarkTheme()
            val darkTheme = when (savedThemeMode) {
                "light" -> false
                "dark" -> true
                else -> isSystemDark
            }

            val baseContext = LocalContext.current
            val composeConfig = LocalConfiguration.current
            val activeConfig = latestConfig ?: composeConfig
            val targetLocale = remember(configRevision, activeConfig.locales) {
                LocaleUtils.getResolvedLocale(baseContext)
            }
            val localizedConfig = remember(
                configRevision,
                activeConfig.orientation,
                activeConfig.screenWidthDp,
                activeConfig.screenHeightDp,
                targetLocale
            ) {
                Configuration(activeConfig).apply {
                    setLocale(targetLocale)
                    setLocales(LocaleList(targetLocale))
                }
            }
            val localizedContext = remember(baseContext, localizedConfig) {
                val resContext = baseContext.createConfigurationContext(localizedConfig)
                val localizedRes = resContext.resources
                object : ContextWrapper(baseContext) {
                    override fun getResources(): Resources = localizedRes
                    override fun getAssets(): AssetManager = localizedRes.assets
                }
            }

            var permissionState by remember {
                mutableStateOf(PermissionHelper.evaluateWelcomePermissions(this@WelcomeActivity))
            }
            var pendingSettingsReason by remember { mutableStateOf<SettingsRedirectReason?>(null) }
            var continueStorageSequence by remember { mutableStateOf(false) }

            fun refreshPermissions(): com.quandot.musicplayer.util.WelcomePermissionState {
                val latest = PermissionHelper.evaluateWelcomePermissions(this@WelcomeActivity)
                permissionState = latest
                return latest
            }

            // Re-evaluate live permissions whenever the user returns from System Settings
            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        refreshPermissions()
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }

            val settingsLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.StartActivityForResult()
            ) {
                continueStorageSequence = false
                refreshPermissions()
            }

            fun openAllFilesAccessSettings() {
                continueStorageSequence = false
                try {
                    settingsLauncher.launch(PermissionHelper.buildAllFilesAccessIntent(this@WelcomeActivity))
                } catch (_: Exception) {
                    try {
                        settingsLauncher.launch(PermissionHelper.buildFallbackAllFilesAccessIntent())
                    } catch (_: Exception) {
                        settingsLauncher.launch(PermissionHelper.buildAppDetailsSettingsIntent(this@WelcomeActivity))
                    }
                }
            }

            // Android 13+ Step 2: Music & Audio -> chains directly into Step 3 (All Files Access)
            val musicAudioLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission()
            ) {
                val latest = refreshPermissions()
                if (latest.storage.musicAndAudioGranted) {
                    if (continueStorageSequence && latest.storage.nextRequiredStep == StoragePermissionStep.ALL_FILES_ACCESS) {
                        openAllFilesAccessSettings()
                    } else {
                        continueStorageSequence = false
                    }
                } else {
                    continueStorageSequence = false
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        !ActivityCompat.shouldShowRequestPermissionRationale(
                            this@WelcomeActivity,
                            Manifest.permission.READ_MEDIA_AUDIO
                        )
                    ) {
                        pendingSettingsReason = SettingsRedirectReason.STORAGE
                    }
                }
            }

            // Android 13+ Step 1: Photos & Videos -> chains directly into Step 2 (Music & Audio)
            val photosVideosLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestMultiplePermissions()
            ) {
                val latest = refreshPermissions()
                if (latest.storage.photosAndVideosGranted) {
                    if (continueStorageSequence && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        when (latest.storage.nextRequiredStep) {
                            StoragePermissionStep.MUSIC_AND_AUDIO -> {
                                musicAudioLauncher.launch(Manifest.permission.READ_MEDIA_AUDIO)
                            }
                            StoragePermissionStep.ALL_FILES_ACCESS -> {
                                openAllFilesAccessSettings()
                            }
                            else -> continueStorageSequence = false
                        }
                    } else {
                        continueStorageSequence = false
                    }
                } else {
                    continueStorageSequence = false
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        val canAskAgain = ActivityCompat.shouldShowRequestPermissionRationale(
                            this@WelcomeActivity,
                            Manifest.permission.READ_MEDIA_IMAGES
                        ) || ActivityCompat.shouldShowRequestPermissionRationale(
                            this@WelcomeActivity,
                            Manifest.permission.READ_MEDIA_VIDEO
                        )
                        if (!canAskAgain) {
                            pendingSettingsReason = SettingsRedirectReason.STORAGE
                        }
                    }
                }
            }

            // Android 11–12 Step 1: Legacy media storage -> chains directly into All Files Access
            val mediaStorageLegacyLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission()
            ) {
                val latest = refreshPermissions()
                if (latest.storage.photosAndVideosGranted) {
                    if (continueStorageSequence && latest.storage.nextRequiredStep == StoragePermissionStep.ALL_FILES_ACCESS) {
                        openAllFilesAccessSettings()
                    } else {
                        continueStorageSequence = false
                    }
                } else {
                    continueStorageSequence = false
                    if (!ActivityCompat.shouldShowRequestPermissionRationale(
                            this@WelcomeActivity,
                            Manifest.permission.READ_EXTERNAL_STORAGE
                        )
                    ) {
                        pendingSettingsReason = SettingsRedirectReason.STORAGE
                    }
                }
            }

            // Android 10 and below: Single legacy storage prompt
            val legacyStorageLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestMultiplePermissions()
            ) {
                continueStorageSequence = false
                val latest = refreshPermissions()
                if (!latest.storage.isFullyGranted) {
                    val canAskAgain = ActivityCompat.shouldShowRequestPermissionRationale(
                        this@WelcomeActivity,
                        Manifest.permission.READ_EXTERNAL_STORAGE
                    ) || ActivityCompat.shouldShowRequestPermissionRationale(
                        this@WelcomeActivity,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                    )
                    if (!canAskAgain) {
                        pendingSettingsReason = SettingsRedirectReason.STORAGE
                    }
                }
            }

            // Notification permission launcher (Android 13+)
            val notificationPermissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission()
            ) {
                val latest = refreshPermissions()
                if (!latest.notificationGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    if (!ActivityCompat.shouldShowRequestPermissionRationale(
                            this@WelcomeActivity,
                            Manifest.permission.POST_NOTIFICATIONS
                        )
                    ) {
                        pendingSettingsReason = SettingsRedirectReason.NOTIFICATION
                    }
                }
            }

            fun handleRequestStoragePermission() {
                val latest = refreshPermissions()
                when (latest.storage.nextRequiredStep) {
                    null -> Unit
                    StoragePermissionStep.LEGACY_STORAGE -> {
                        continueStorageSequence = false
                        legacyStorageLauncher.launch(PermissionHelper.legacyStoragePermissions())
                    }
                    StoragePermissionStep.MEDIA_STORAGE_LEGACY -> {
                        continueStorageSequence = true
                        mediaStorageLegacyLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                    }
                    StoragePermissionStep.PHOTOS_AND_VIDEOS -> {
                        continueStorageSequence = true
                        photosVideosLauncher.launch(PermissionHelper.photosAndVideosPermissions())
                    }
                    StoragePermissionStep.MUSIC_AND_AUDIO -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            continueStorageSequence = true
                            musicAudioLauncher.launch(Manifest.permission.READ_MEDIA_AUDIO)
                        }
                    }
                    StoragePermissionStep.ALL_FILES_ACCESS -> {
                        openAllFilesAccessSettings()
                    }
                }
            }

            fun handleRequestNotificationPermission() {
                val latest = refreshPermissions()
                if (latest.notificationGranted) return

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    !PermissionHelper.hasPostNotificationsRuntimePermission(this@WelcomeActivity)
                ) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    try {
                        settingsLauncher.launch(
                            PermissionHelper.buildNotificationSettingsIntent(this@WelcomeActivity)
                        )
                    } catch (_: Exception) {
                        settingsLauncher.launch(
                            PermissionHelper.buildAppDetailsSettingsIntent(this@WelcomeActivity)
                        )
                    }
                }
            }

            CompositionLocalProvider(
                LocalContext provides localizedContext,
                LocalConfiguration provides localizedConfig
            ) {
                MusicPlayerTheme(
                    darkTheme = darkTheme,
                    dynamicColor = savedDynamicColor,
                    amoledMode = savedAmoledMode
                ) {
                    SystemBarsColorController(
                        statusBarColor = MaterialTheme.colorScheme.surface,
                        navBarColor = MaterialTheme.colorScheme.surface,
                        darkTheme = darkTheme
                    )
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.surface
                    ) {
                        WelcomeScreen(
                            permissionState = permissionState,
                            pendingSettingsReason = pendingSettingsReason,
                            onRequestStoragePermission = ::handleRequestStoragePermission,
                            onRequestNotificationPermission = ::handleRequestNotificationPermission,
                            onStartApp = {
                                val verified = refreshPermissions()
                                if (verified.allRequiredGranted) {
                                    PermissionHelper.markWelcomeCompleted(this@WelcomeActivity)
                                    navigateToMain(animated = true)
                                }
                            },
                            onConfirmOpenSettings = {
                                val reason = pendingSettingsReason
                                pendingSettingsReason = null
                                val intent = if (reason == SettingsRedirectReason.NOTIFICATION) {
                                    PermissionHelper.buildNotificationSettingsIntent(this@WelcomeActivity)
                                } else {
                                    PermissionHelper.buildAppDetailsSettingsIntent(this@WelcomeActivity)
                                }
                                try {
                                    settingsLauncher.launch(intent)
                                } catch (_: Exception) {
                                    settingsLauncher.launch(
                                        PermissionHelper.buildAppDetailsSettingsIntent(this@WelcomeActivity)
                                    )
                                }
                            },
                            onDismissSettingsDialog = {
                                pendingSettingsReason = null
                            }
                        )
                    }
                }
            }
        }
    }

    // ═══ Private Helpers ═══
    private fun navigateToMain(animated: Boolean) {
        PDLog.d(PDLog.UI, "WELCOME_TO_MAIN") { "animated=$animated" }
        val pendingAudioUri = intent?.getStringExtra(EXTRA_PENDING_AUDIO_URI)
        val pendingAudioType = intent?.getStringExtra(EXTRA_PENDING_AUDIO_TYPE)
        val mainIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (!pendingAudioUri.isNullOrBlank()) {
                action = Intent.ACTION_VIEW
                setDataAndType(Uri.parse(pendingAudioUri), pendingAudioType)
                clipData = intent?.clipData
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        startActivity(mainIntent)
        finish()
        if (!animated) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
            } else {
                @Suppress("DEPRECATION")
                overridePendingTransition(0, 0)
            }
        }
    }
}
