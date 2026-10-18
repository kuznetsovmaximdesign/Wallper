package com.wallper.live.data.settings

import android.content.SharedPreferences
import com.wallper.live.core.Focus
import com.wallper.live.core.Playback

/** What a person chose for one source in the viewer. */
data class EditChoice(val startMs: Long, val loopMs: Long, val speed: Float, val focus: Focus)

/**
 * Remembers viewer choices per wallpaper, across switching and restarts: coming back
 * to a wallpaper restores its fragment, speed and framing, and a wallpaper opened for
 * the first time starts at the speed chosen last (framing and fragment depend on the
 * video, so those start fresh).
 */
class EditMemory(private val store: Store) {

    /** Minimal key-value storage, so the rules are testable without Android. */
    interface Store {
        fun get(key: String): String?
        fun put(key: String, value: String)
    }

    fun choiceFor(key: String): EditChoice? = store.get(PREFIX + key)?.let(::decode)

    /** Speed for a wallpaper that has no choices yet: the one picked last. */
    fun startingSpeed(): Float =
        store.get(LAST_SPEED)?.toFloatOrNull()?.let(Playback::clampSpeed) ?: Playback.DEFAULT_SPEED

    fun remember(key: String, choice: EditChoice) {
        store.put(PREFIX + key, encode(choice))
        store.put(LAST_SPEED, choice.speed.toString())
    }

    private fun encode(c: EditChoice) = listOf(c.startMs, c.loopMs, c.speed, c.focus.x, c.focus.y).joinToString(";")

    private fun decode(text: String): EditChoice? {
        val parts = text.split(';')
        if (parts.size != 5) return null
        return EditChoice(
            startMs = parts[0].toLongOrNull() ?: return null,
            loopMs = parts[1].toLongOrNull() ?: return null,
            speed = parts[2].toFloatOrNull()?.let(Playback::clampSpeed) ?: return null,
            focus = Focus(
                x = parts[3].toFloatOrNull()?.coerceIn(0f, 1f) ?: return null,
                y = parts[4].toFloatOrNull()?.coerceIn(0f, 1f) ?: return null,
            ),
        )
    }

    /** [Store] over SharedPreferences. */
    class Preferences(private val prefs: SharedPreferences) : Store {
        override fun get(key: String): String? = prefs.getString(key, null)
        override fun put(key: String, value: String) {
            prefs.edit().putString(key, value).apply()
        }
    }

    private companion object {
        const val PREFIX = "edit:"
        const val LAST_SPEED = "last-speed"
    }
}
