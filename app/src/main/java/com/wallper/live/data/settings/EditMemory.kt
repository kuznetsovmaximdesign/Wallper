package com.wallper.live.data.settings

import android.content.SharedPreferences
import com.wallper.live.core.Focus
import com.wallper.live.core.Playback

/**
 * What a person chose for one source in the viewer. [speed] is null until they pick a
 * speed for this very source: until then it follows the speed picked last anywhere.
 * [startMs] is [DEFAULT_FRAGMENT] while the fragment was never moved.
 */
data class EditChoice(val startMs: Long, val loopMs: Long, val speed: Float?, val focus: Focus) {
    companion object {
        const val DEFAULT_FRAGMENT = -1L
    }
}

/**
 * Remembers viewer choices per wallpaper, across switching and restarts: coming back
 * to a wallpaper restores its fragment and framing, and the speed picked for it. A
 * wallpaper whose speed was never touched plays at the speed picked last, so browsing
 * keeps the chosen speed (framing and fragment depend on the video, so those don't carry).
 */
class EditMemory(private val store: Store) {

    /** Minimal key-value storage, so the rules are testable without Android. */
    interface Store {
        fun get(key: String): String?
        fun put(key: String, value: String)
    }

    fun choiceFor(key: String): EditChoice? = store.get(PREFIX + key)?.let(::decode)

    /** Speed for a wallpaper whose own speed was never picked: the one picked last. */
    fun startingSpeed(): Float =
        store.get(LAST_SPEED)?.toFloatOrNull()?.let(Playback::clampSpeed) ?: Playback.DEFAULT_SPEED

    fun remember(key: String, choice: EditChoice) {
        store.put(PREFIX + key, encode(choice))
    }

    /** The person just picked [speed] (merely viewing a wallpaper does not count). */
    fun pickedSpeed(speed: Float) {
        store.put(LAST_SPEED, Playback.clampSpeed(speed).toString())
    }

    private fun encode(c: EditChoice) =
        listOf(VERSION, c.startMs, c.loopMs, c.speed?.toString().orEmpty(), c.focus.x, c.focus.y).joinToString(";")

    private fun decode(text: String): EditChoice? {
        var parts = text.split(';')
        // Records from before v2 saved every fragment, moved or not, under the old 30 s
        // cap: their fragment goes back to the default, the rest is kept.
        val current = parts.size == 6 && parts[0] == VERSION
        if (current) parts = parts.drop(1) else if (parts.size != 5) return null
        val startMs = parts[0].toLongOrNull() ?: return null
        val loopMs = parts[1].toLongOrNull() ?: return null
        return EditChoice(
            startMs = if (current && startMs >= 0) startMs else EditChoice.DEFAULT_FRAGMENT,
            loopMs = if (current && startMs >= 0) loopMs else 0,
            speed = if (parts[2].isEmpty()) null else parts[2].toFloatOrNull()?.let(Playback::clampSpeed) ?: return null,
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
        const val VERSION = "v2"
    }
}
