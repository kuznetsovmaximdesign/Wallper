package com.wallper.live.core

import androidx.compose.runtime.Immutable

/** Product rules shared by the editor, the exporter and the wallpaper. */
object Playback {
    /** Wallpapers loop at most this long at 1× speed. */
    const val MAX_LOOP_MS = 30_000L
    const val MIN_LOOP_MS = 4_000L

    /** Length of the cross-fade that hides the loop seam. */
    const val FADE_MS = 1_500L

    const val MIN_SPEED = 0.25f
    const val MAX_SPEED = 2f
    const val DEFAULT_SPEED = 1f
    val SPEED_PRESETS = listOf(0.5f, 1f, 1.5f, 2f)

    fun clampSpeed(speed: Float): Float = speed.coerceIn(MIN_SPEED, MAX_SPEED)
}

/** Framing of the video inside the screen: 0..1 on each axis, (0.5, 0.5) is centred. */
@Immutable
data class Focus(val x: Float, val y: Float) {
    companion object {
        val Center = Focus(0.5f, 0.5f)
    }
}

fun formatDuration(ms: Long): String {
    val totalSeconds = (ms + 500) / 1000
    return if (totalSeconds < 60) "$totalSeconds с" else "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

fun formatTimestamp(ms: Long): String {
    val totalSeconds = ms / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
