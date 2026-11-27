/**
 * MainActivity — Edge-to-edge Compose activity host, permission coordinator, and hardware key router.
 *
 * @remarks
 * Injects a localized `ContextWrapper` via `CompositionLocalProvider` and overrides
 * `attachBaseContext` so system language switches update Compose string resources without
 * recreating the Activity or interrupting audio playback. Intercepts physical volume keys while
 * `UsbDacManager.isDirectReady()` is active so steps drive the USB DAC hardware Feature Unit
 * rather than Android's bypassed software mixer.
 *
 * @see MusicViewModel
 * @see UsbDacManager
 * @see LocaleUtils
 */
package com.quandot.musicplayer

// External SDK / Jetpack / third-party libraries
import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import android.view.KeyEvent
import android.view.WindowManager
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel

// Internal modules
import com.quandot.musicplayer.audio.UsbDacManager
import com.quandot.musicplayer.data.AlbumArtManager
import com.quandot.musicplayer.debug.AppLogManager
import com.quandot.musicplayer.debug.PDLog
import com.quandot.musicplayer.ui.MusicViewModel
import com.quandot.musicplayer.ui.components.LocalAlbumArtCacheVersion
import com.quandot.musicplayer.ui.components.LocalAlbumArtManager
import com.quandot.musicplayer.ui.screens.MainScreen
import com.quandot.musicplayer.util.LocaleUtils
import com.quandot.musicplayer.util.PermissionHelper

// Domain models & theme tokens (last)
import com.quandot.musicplayer.ui.theme.LocalIsUiForeground
import com.quandot.musicplayer.ui.theme.MusicPlayerTheme

// ═══ Constants ═══
private const val LUMINANCE_THRESHOLD = 0.5f

// ═══ Activity Host ═══
class MainActivity : ComponentActivity() {
    private var musicViewModelRef: MusicViewModel? = null
    private var configRevision by mutableIntStateOf(0)
    private var latestConfig by mutableStateOf<Configuration?>(null)
    private val isUiForegroundState = mutableStateOf(true)

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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        // Eliminate redundant DecorView window background fill under full-screen Compose Surface (reduces GPU overdraw)
        window.setBackgroundDrawable(null)

        // Work around Android launcher re-launch bug: finish duplicate launcher instance if task already has a root
        if (!isTaskRoot && intent.hasCategory(Intent.CATEGORY_LAUNCHER) && intent.action == Intent.ACTION_MAIN) {
            finish()
            return
        }

        AppLogManager.init(this)
        PDLog.i(PDLog.UI, "MAIN_CREATE", "action=${intent?.action ?: "none"} hasData=${intent?.data != null}")

        // Enforce onboarding gate: direct MainActivity launches fall back to WelcomeActivity until completed
        if (!PermissionHelper.canEnterMainActivity(this)) {
            PDLog.i(PDLog.UI, "MAIN_REDIRECT_WELCOME", "reason=onboarding_or_perm_missing")
            val welcomeIntent = Intent(this, WelcomeActivity::class.java).apply {
                if (intent?.action == Intent.ACTION_VIEW && intent.data != null) {
                    putExtra(WelcomeActivity.EXTRA_PENDING_AUDIO_URI, intent.data.toString())
                    putExtra(WelcomeActivity.EXTRA_PENDING_AUDIO_TYPE, intent.type)
                    clipData = intent.clipData
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            startActivity(welcomeIntent)
            finish()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
            } else {
                @Suppress("DEPRECATION")
                overridePendingTransition(0, 0)
            }
            return
        }

        UsbDacManager.init(this)

        setContent {
            val musicViewModel: MusicViewModel = viewModel()
            SideEffect { musicViewModelRef = musicViewModel }
            val themeMode by musicViewModel.themeMode.collectAsState()
            val dynamicColor by musicViewModel.dynamicColor.collectAsState()
            val songDynamicColor by musicViewModel.songDynamicColor.collectAsState()
            val songSeedColorArgb by musicViewModel.songSeedColorArgb.collectAsState()
            val amoledMode by musicViewModel.amoledMode.collectAsState()
            val isSystemDark = isSystemInDarkTheme()
            val darkTheme = when (themeMode) {
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

            val coreAudioPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_AUDIO
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }
            val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(
                    Manifest.permission.READ_MEDIA_AUDIO,
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.POST_NOTIFICATIONS
                )
            } else {
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            }

            var hasPromptedStartupPermissions by rememberSaveable { mutableStateOf(false) }

            val permissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestMultiplePermissions()
            ) { results ->
                PDLog.i(PDLog.UI, "PERM_RESULTS", "granted=${results.count { it.value }}/${results.size}")
                val audioGranted = results[coreAudioPermission] == true ||
                    ContextCompat.checkSelfPermission(baseContext, coreAudioPermission) == PackageManager.PERMISSION_GRANTED
                if (audioGranted) {
                    musicViewModel.onPermissionGranted()
                }
            }

            LaunchedEffect(Unit) {
                val audioGranted = ContextCompat.checkSelfPermission(
                    baseContext,
                    coreAudioPermission
                ) == PackageManager.PERMISSION_GRANTED
                if (audioGranted) {
                    musicViewModel.onPermissionGranted()
                } else if (!hasPromptedStartupPermissions) {
                    hasPromptedStartupPermissions = true
                    permissionLauncher.launch(permissions)
                }
                musicViewModel.handleExternalAudioIntent(intent)
            }

            val artManager = remember(applicationContext) {
                AlbumArtManager.getInstance(applicationContext)
            }
            val artCacheVersionState = artManager.cacheVersion.collectAsState()

            CompositionLocalProvider(
                LocalContext provides localizedContext,
                LocalConfiguration provides localizedConfig,
                LocalAlbumArtManager provides artManager,
                LocalAlbumArtCacheVersion provides artCacheVersionState,
                LocalIsUiForeground provides isUiForegroundState
            ) {
                MusicPlayerTheme(
                    darkTheme = darkTheme,
                    dynamicColor = dynamicColor,
                    amoledMode = amoledMode,
                    songSeedColorArgb = if (songDynamicColor) songSeedColorArgb else null
                ) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.surface
                    ) {
                        MainScreen(
                            viewModel = musicViewModel,
                            onRequestPermission = { permissionLauncher.launch(permissions) }
                        )
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        isUiForegroundState.value = true
        musicViewModelRef?.setUiVisible(true)
    }

    override fun onStop() {
        isUiForegroundState.value = false
        musicViewModelRef?.setUiVisible(false)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        UsbDacManager.onActivityResumed()
    }

    override fun onPause() {
        UsbDacManager.onActivityPaused()
        super.onPause()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (UsbDacManager.isDirectReady()) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        UsbDacManager.stepVolume(+1, source = "in_app")
                    }
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        UsbDacManager.stepVolume(-1, source = "in_app")
                    }
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        musicViewModelRef?.handleExternalAudioIntent(intent)
    }
}

// ═══ System Bars Controller ═══

/**
 * Synchronizes system status and navigation bar icon contrast and disables Android Q–14
 * automatic contrast scrims so edge-to-edge Compose surfaces render unoccluded.
 */
@Composable
fun SystemBarsColorController(
    statusBarColor: Color = MaterialTheme.colorScheme.surface,
    navBarColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    darkTheme: Boolean = (statusBarColor.red * 0.2126f + statusBarColor.green * 0.7152f + statusBarColor.blue * 0.0722f < 0.5f),
    statusBarDarkOverride: Boolean? = null,
    navBarDarkOverride: Boolean? = null,
    hideSystemBars: Boolean = false
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        val activity = view.context as? ComponentActivity
        val window = (view.context as? Activity)?.window ?: return
        val isStatusDark = statusBarDarkOverride ?: (darkTheme || isColorDark(statusBarColor))
        val isNavDark = navBarDarkOverride ?: (darkTheme || isColorDark(navBarColor))
        val lastAppliedRef = remember(window) { arrayOfNulls<Any>(4) }
        SideEffect {
            if (lastAppliedRef[0] == window &&
                lastAppliedRef[1] == isStatusDark &&
                lastAppliedRef[2] == isNavDark &&
                lastAppliedRef[3] == hideSystemBars
            ) {
                return@SideEffect
            }
            lastAppliedRef[0] = window
            lastAppliedRef[1] = isStatusDark
            lastAppliedRef[2] = isNavDark
            lastAppliedRef[3] = hideSystemBars

            activity?.enableEdgeToEdge(
                statusBarStyle = if (isStatusDark) {
                    SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                },
                navigationBarStyle = if (isNavDark) {
                    SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                }
            )
            if (Build.VERSION.SDK_INT in Build.VERSION_CODES.Q..Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                @Suppress("DEPRECATION")
                window.isStatusBarContrastEnforced = false
                window.isNavigationBarContrastEnforced = false
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
            }
            val insetsController = WindowCompat.getInsetsController(window, view)
            // Light-bar flags request dark system icons on bright surfaces and light icons on dark surfaces
            insetsController.isAppearanceLightStatusBars = !isStatusDark
            insetsController.isAppearanceLightNavigationBars = !isNavDark
            if (hideSystemBars) {
                insetsController.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                insetsController.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                insetsController.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
}

// ═══ Private Helpers ═══
private fun isColorDark(color: Color): Boolean =
    (color.red * 0.2126f + color.green * 0.7152f + color.blue * 0.0722f) < LUMINANCE_THRESHOLD
