/**
 * LocaleUtils — Automatic system-locale resolution and legacy sentinel string localization.
 *
 * @remarks
 * Constrains the app locale exclusively to Vietnamese (`vi`) or English (`en`) so unsupported OS
 * languages do not produce partially translated screens from mixed resource fallbacks. The
 * `@Composable` `localized*` helpers also map persisted Vietnamese/English sentinel strings from
 * historical SQLite scans onto localized `R.string.*` resources at render time without requiring a
 * database rescan on locale switch.
 *
 * @see MainActivity
 * @see ScanNotificationHelper
 */
package com.quandot.musicplayer.util

// External SDK / Jetpack / third-party libraries
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import java.util.Locale

// Internal modules
import com.quandot.musicplayer.R

// ═══ Constants ═══
private const val LANG_VI = "vi"
private const val LANG_EN = "en"

// ═══ Locale Resolution ═══
object LocaleUtils {

    fun getResolvedLanguageCode(context: Context? = null): String {
        val sysLocales = Resources.getSystem().configuration.locales
        val primaryLocale = if (!sysLocales.isEmpty) {
            sysLocales[0]
        } else {
            context?.resources?.configuration?.locales?.let { if (!it.isEmpty) it[0] else null }
                ?: Locale.getDefault()
        }
        return if (primaryLocale.language.equals(LANG_VI, ignoreCase = true)) LANG_VI else LANG_EN
    }

    fun getResolvedLocale(context: Context? = null): Locale =
        localeForCode(getResolvedLanguageCode(context))

    fun applyDefaultLocale(languageCode: String = getResolvedLanguageCode()) {
        Locale.setDefault(localeForCode(languageCode))
    }

    fun getLocalizedContext(
        baseContext: Context,
        languageCode: String = getResolvedLanguageCode(baseContext)
    ): Context {
        val targetLocale = localeForCode(languageCode)
        Locale.setDefault(targetLocale)
        val config = Configuration(baseContext.resources.configuration).apply {
            setLocale(targetLocale)
            setLocales(LocaleList(targetLocale))
        }
        return baseContext.createConfigurationContext(config)
    }

    private fun localeForCode(languageCode: String): Locale =
        if (languageCode == LANG_VI) Locale.forLanguageTag(LANG_VI) else Locale.ENGLISH
}

// ═══ Unknown Sentinel Matchers & Localized Display Mappers ═══
fun isUnknownArtistName(raw: String): Boolean {
    val trimmed = raw.trim()
    return trimmed.isEmpty() ||
        trimmed.equals("<unknown>", ignoreCase = true) ||
        trimmed.equals("Nghệ sĩ không xác định", ignoreCase = true) ||
        trimmed.equals("Unknown artist", ignoreCase = true)
}

fun isUnknownAlbumName(raw: String): Boolean {
    val trimmed = raw.trim()
    return trimmed.isEmpty() ||
        trimmed.equals("<unknown>", ignoreCase = true) ||
        trimmed.equals("Album không tên", ignoreCase = true) ||
        trimmed.equals("Album không xác định", ignoreCase = true) ||
        trimmed.equals("Unknown album", ignoreCase = true)
}

fun isUnknownGenreName(raw: String): Boolean {
    val trimmed = raw.trim()
    return trimmed.isEmpty() ||
        trimmed.equals("<unknown>", ignoreCase = true) ||
        trimmed.equals("Không rõ thể loại", ignoreCase = true) ||
        trimmed.equals("Thể loại không xác định", ignoreCase = true) ||
        trimmed.equals("Unknown genre", ignoreCase = true)
}

@Composable
fun localizedArtistName(raw: String): String {
    val trimmed = raw.trim()
    return when {
        isUnknownArtistName(trimmed) -> stringResource(R.string.unknown_artist)
        trimmed.equals("Nhiều nghệ sĩ", ignoreCase = true) ||
            trimmed.equals("Various artists", ignoreCase = true) -> stringResource(R.string.various_artists)
        else -> raw
    }
}

@Composable
fun localizedAlbumName(raw: String): String {
    return if (isUnknownAlbumName(raw)) {
        stringResource(R.string.unknown_album)
    } else {
        raw
    }
}

@Composable
fun localizedGenreName(raw: String): String {
    return if (isUnknownGenreName(raw)) {
        stringResource(R.string.unknown_genre)
    } else {
        raw
    }
}

@Composable
fun localizedPlaylistName(raw: String, isSystem: Boolean = false): String {
    val trimmed = raw.trim()
    return if (isSystem || trimmed.equals("Bài hát yêu thích", ignoreCase = true) ||
        trimmed.equals("Favorite songs", ignoreCase = true)
    ) {
        stringResource(R.string.favorites_playlist_name)
    } else {
        raw
    }
}

@Composable
fun localizedQueueSource(raw: String): String {
    val trimmed = raw.trim()
    return when {
        trimmed.isEmpty() || trimmed.equals("Mặc định", ignoreCase = true) ||
            trimmed.equals("Default", ignoreCase = true) -> stringResource(R.string.queue_source_default)
        trimmed.equals("Nghe gần đây", ignoreCase = true) ||
            trimmed.equals("Nghe nhiều nhất", ignoreCase = true) ||
            trimmed.equals("Recently played", ignoreCase = true) -> stringResource(R.string.home_recent_source)
        trimmed.equals("Tệp bên ngoài", ignoreCase = true) -> stringResource(R.string.queue_source_default)
        else -> raw
    }
}
