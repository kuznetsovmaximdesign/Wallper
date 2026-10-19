package com.wallper.live.core

import androidx.compose.runtime.Immutable

/** Product rules shared by the editor, the exporter and the wallpaper. */
object Playback {
    /** Wallpapers loop at most this long at 1× speed: long enough not to feel like a loop. */
    const val MAX_LOOP_MS = 120_000L
    const val MIN_LOOP_MS = 4_000L

    /** Length of the cross-fade that hides the loop seam. */
    const val FADE_MS = 1_500L

    /** Speeds go from 0.5× to 4× in half steps: few, clearly different stops. */
    const val MIN_SPEED = 0.5f
    const val MAX_SPEED = 4f
    const val SPEED_STEP = 0.5f
    const val DEFAULT_SPEED = 1f

    /** Stops between the ends of the speed slider (Slider's `steps`). */
    val SPEED_STOPS: Int = ((MAX_SPEED - MIN_SPEED) / SPEED_STEP).toInt() - 1

    fun clampSpeed(speed: Float): Float = speed.coerceIn(MIN_SPEED, MAX_SPEED)

    /** The nearest allowed speed: a half step between 0.5× and 4×. */
    fun snapSpeed(speed: Float): Float = clampSpeed(Math.round(speed / SPEED_STEP) * SPEED_STEP)

    /** Apple's scenes fade in from and out to black over their first and last seconds. */
    const val EDGE_MS = 3_000L

    /**
     * The fragment a [totalMs] video starts with: as long as allowed, past the fade-in and
     * clear of the fade-out, so a fresh wallpaper repeats as rarely as the video allows.
     * Returns (start, length).
     */
    fun defaultFragment(totalMs: Long): Pair<Long, Long> {
        val start = minOf(EDGE_MS, (totalMs - MIN_LOOP_MS - FADE_MS).coerceAtLeast(0))
        val usable = totalMs - start - FADE_MS
        val length = (if (usable - EDGE_MS >= MIN_LOOP_MS) usable - EDGE_MS else usable)
            .coerceIn(MIN_LOOP_MS, MAX_LOOP_MS)
        return start to length
    }
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
