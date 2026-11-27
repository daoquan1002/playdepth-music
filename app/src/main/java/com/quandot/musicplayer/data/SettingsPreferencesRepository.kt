package com.quandot.musicplayer.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.quandot.musicplayer.model.NowPlayingBlendStyle
import com.quandot.musicplayer.model.NowPlayingSeekbarStyle
import com.quandot.musicplayer.model.NowPlayingStyle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "playdepth_settings"
)

/**
 * Preferences DataStore repository for user-facing appearance and player settings.
 */
class SettingsPreferencesRepository(context: Context) {
    private val dataStore = context.applicationContext.settingsDataStore

    val nowPlayingStyle: Flow<NowPlayingStyle> = dataStore.data.map { prefs ->
        NowPlayingStyle.fromStoredValue(prefs[KEY_NOW_PLAYING_STYLE])
    }

    val nowPlayingBlendStyle: Flow<NowPlayingBlendStyle> = dataStore.data.map { prefs ->
        NowPlayingBlendStyle.fromStoredValue(prefs[KEY_NOW_PLAYING_BLEND_STYLE])
    }

    val nowPlayingSeekbarStyle: Flow<NowPlayingSeekbarStyle> = dataStore.data.map { prefs ->
        NowPlayingSeekbarStyle.fromStoredValue(prefs[KEY_NOW_PLAYING_SEEKBAR_STYLE])
    }

    suspend fun setNowPlayingStyle(style: NowPlayingStyle) {
        dataStore.edit { prefs ->
            prefs[KEY_NOW_PLAYING_STYLE] = style.name
        }
    }

    suspend fun setNowPlayingBlendStyle(style: NowPlayingBlendStyle) {
        dataStore.edit { prefs ->
            prefs[KEY_NOW_PLAYING_BLEND_STYLE] = style.name
        }
    }

    suspend fun setNowPlayingSeekbarStyle(style: NowPlayingSeekbarStyle) {
        dataStore.edit { prefs ->
            prefs[KEY_NOW_PLAYING_SEEKBAR_STYLE] = style.name
        }
    }

    companion object {
        private val KEY_NOW_PLAYING_STYLE = stringPreferencesKey("now_playing_style")
        private val KEY_NOW_PLAYING_BLEND_STYLE = stringPreferencesKey("now_playing_blend_style")
        private val KEY_NOW_PLAYING_SEEKBAR_STYLE = stringPreferencesKey("now_playing_seekbar_style")
    }
}
