package com.wallper.live.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.wallper.live.core.Focus
import com.wallper.live.core.Playback
import com.wallper.live.core.WallpaperTarget
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "wallper_settings")

class SettingsRepository(context: Context) {

    private val store = context.applicationContext.dataStore

    val settings: Flow<WallpaperSettings> = store.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map(::toSettings)
        .distinctUntilChanged()

    /** Puts [clipId] on the home screen, the lock screen or both, keeping the other one. */
    suspend fun assign(clipId: String, target: WallpaperTarget) = store.edit { prefs ->
        val home = prefs[ACTIVE_CLIP]
        val lock = prefs[LOCK_CLIP] ?: home
        val (newHome, newLock) = when (target) {
            WallpaperTarget.Both -> clipId to clipId
            WallpaperTarget.Home -> clipId to (lock ?: clipId)
            WallpaperTarget.Lock -> (home ?: clipId) to clipId
        }
        prefs[ACTIVE_CLIP] = newHome
        if (newLock == newHome) prefs.remove(LOCK_CLIP) else prefs[LOCK_CLIP] = newLock
    }

    suspend fun setSpeed(clipId: String, speed: Float) = store.edit {
        it[floatPreferencesKey(SPEED_PREFIX + clipId)] = Playback.clampSpeed(speed)
    }

    suspend fun setFocus(clipId: String, focus: Focus) = store.edit {
        it[floatPreferencesKey(FOCUS_X_PREFIX + clipId)] = focus.x
        it[floatPreferencesKey(FOCUS_Y_PREFIX + clipId)] = focus.y
    }

    suspend fun setSeamlessLoop(enabled: Boolean) = store.edit { it[SEAMLESS] = enabled }
    suspend fun setParallax(enabled: Boolean) = store.edit { it[PARALLAX] = enabled }
    suspend fun setPauseOnPowerSave(enabled: Boolean) = store.edit { it[POWER_SAVE] = enabled }
    suspend fun setFrostReveal(enabled: Boolean) = store.edit { it[FROST_REVEAL] = enabled }

    /** Drops every per-clip value of a deleted clip. */
    suspend fun forget(clipId: String) = store.edit { prefs: MutablePreferences ->
        prefs.remove(floatPreferencesKey(SPEED_PREFIX + clipId))
        prefs.remove(floatPreferencesKey(FOCUS_X_PREFIX + clipId))
        prefs.remove(floatPreferencesKey(FOCUS_Y_PREFIX + clipId))
        val lock = prefs[LOCK_CLIP]
        if (prefs[ACTIVE_CLIP] == clipId) {
            // The lock screen's own wallpaper (if any) takes over the home screen.
            if (lock != null && lock != clipId) prefs[ACTIVE_CLIP] = lock else prefs.remove(ACTIVE_CLIP)
            prefs.remove(LOCK_CLIP)
        } else if (lock == clipId) {
            prefs.remove(LOCK_CLIP)
        }
    }

    private fun toSettings(prefs: Preferences): WallpaperSettings {
        val speeds = HashMap<String, Float>()
        val xs = HashMap<String, Float>()
        val ys = HashMap<String, Float>()
        for ((key, value) in prefs.asMap()) {
            val name = key.name
            val number = value as? Float ?: continue
            when {
                name.startsWith(SPEED_PREFIX) -> speeds[name.removePrefix(SPEED_PREFIX)] = number
                name.startsWith(FOCUS_X_PREFIX) -> xs[name.removePrefix(FOCUS_X_PREFIX)] = number
                name.startsWith(FOCUS_Y_PREFIX) -> ys[name.removePrefix(FOCUS_Y_PREFIX)] = number
            }
        }
        val focuses = xs.mapValues { (id, x) -> Focus(x, ys[id] ?: 0.5f) }
        return WallpaperSettings(
            activeClipId = prefs[ACTIVE_CLIP],
            lockClipId = prefs[LOCK_CLIP],
            speeds = speeds,
            focuses = focuses,
            seamlessLoop = prefs[SEAMLESS] ?: true,
            parallax = prefs[PARALLAX] ?: true,
            pauseOnPowerSave = prefs[POWER_SAVE] ?: true,
            frostReveal = prefs[FROST_REVEAL] ?: true,
        )
    }

    private companion object {
        val ACTIVE_CLIP = stringPreferencesKey("active_clip")
        val LOCK_CLIP = stringPreferencesKey("lock_clip")
        val SEAMLESS = booleanPreferencesKey("seamless_loop")
        val PARALLAX = booleanPreferencesKey("parallax")
        val POWER_SAVE = booleanPreferencesKey("pause_on_power_save")
        val FROST_REVEAL = booleanPreferencesKey("frost_reveal")
        const val SPEED_PREFIX = "speed_"
        const val FOCUS_X_PREFIX = "focus_x_"
        const val FOCUS_Y_PREFIX = "focus_y_"
    }
}
