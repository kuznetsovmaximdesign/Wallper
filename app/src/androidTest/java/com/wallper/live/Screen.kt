package com.wallper.live

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.fail

/**
 * Judges what is really on the display (SurfaceView and TextureView content included)
 * from screenshots: is there a picture, and is it moving?
 */
object Screen {
    private const val TAG = "WallperScreen"
    private const val GRID = 48
    private const val DARK_LUMA = 20.0

    data class Stats(val luma: Double, val spread: Double, val darkShare: Double) {
        /**
         * The app's own chrome is near-black, so a vanished video reads as dark. A zoomed
         * video can be one flat colour (low spread), which is still a picture.
         */
        val hasPicture: Boolean get() = darkShare < 0.6 && (spread > 10.0 || luma > 3 * DARK_LUMA)
        override fun toString() = "luma=%.1f spread=%.1f dark=%.2f".format(luma, spread, darkShare)
    }

    fun shot(): Bitmap {
        // A screenshot can fail while windows change (e.g. the wallpaper picker closing).
        repeat(10) {
            val raw = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            if (raw != null) {
                return if (raw.config == Bitmap.Config.HARDWARE) raw.copy(Bitmap.Config.ARGB_8888, false) else raw
            }
            SystemClock.sleep(300)
        }
        error("Screenshot failed")
    }

    fun stats(bitmap: Bitmap, area: Rect): Stats {
        val lumas = samples(bitmap, area).map { luma(it) }
        val mean = lumas.average()
        val spread = sqrt(lumas.sumOf { (it - mean) * (it - mean) } / lumas.size)
        return Stats(mean, spread, lumas.count { it < DARK_LUMA }.toDouble() / lumas.size)
    }

    /** Mean per-channel difference (0..255) between two shots of [area]. */
    fun difference(a: Bitmap, b: Bitmap, area: Rect): Double {
        val pa = samples(a, area)
        val pb = samples(b, area)
        return pa.indices.sumOf { i ->
            val x = pa[i]
            val y = pb[i]
            (abs(Color.red(x) - Color.red(y)) + abs(Color.green(x) - Color.green(y)) + abs(Color.blue(x) - Color.blue(y))) / 3.0
        } / pa.size
    }

    /** Waits until [area] shows a moving picture; returns the time it took. */
    fun awaitMoving(area: () -> Rect, timeoutMs: Long, what: String): Long {
        val start = SystemClock.elapsedRealtime()
        var last = ""
        while (SystemClock.elapsedRealtime() - start < timeoutMs) {
            val rect = area()
            val a = shot()
            SystemClock.sleep(450)
            val b = shot()
            val stats = stats(b, rect)
            val diff = difference(a, b, rect)
            last = "$stats diff=%.2f".format(diff)
            if (stats.hasPicture && diff > MIN_MOTION) {
                val took = SystemClock.elapsedRealtime() - start
                Log.i(TAG, "$what moving after ${took}ms ($last)")
                return took
            }
        }
        fail("$what: no moving picture within ${timeoutMs}ms (last: $last)")
        error("unreachable")
    }

    /** Samples [area] for [durationMs]: it must never go black or freeze for long. */
    fun assertKeepsPlaying(area: () -> Rect, durationMs: Long, what: String) {
        val start = SystemClock.elapsedRealtime()
        var previous: Bitmap? = null
        var frozenSince = -1L
        while (SystemClock.elapsedRealtime() - start < durationMs) {
            val rect = area()
            val current = shot()
            val stats = stats(current, rect)
            if (!stats.hasPicture) fail("$what: picture disappeared after ${SystemClock.elapsedRealtime() - start}ms ($stats)")
            previous?.let {
                val diff = difference(it, current, rect)
                if (diff > MIN_MOTION) {
                    frozenSince = -1
                } else {
                    val now = SystemClock.elapsedRealtime()
                    if (frozenSince < 0) frozenSince = now
                    if (now - frozenSince > MAX_FREEZE_MS) fail("$what: picture froze for ${now - frozenSince}ms ($stats)")
                }
            }
            previous = current
            SystemClock.sleep(200)
        }
        Log.i(TAG, "$what kept playing for ${durationMs}ms")
    }

    /** The middle of [bounds], away from bars, scrims and the dock. */
    fun middle(bounds: Rect): Rect {
        val w = bounds.width()
        val h = bounds.height()
        return Rect(bounds.left + w / 6, bounds.top + h / 4, bounds.right - w / 6, bounds.top + h * 3 / 5)
    }

    private fun samples(bitmap: Bitmap, area: Rect): IntArray {
        val r = Rect(area).apply { intersect(0, 0, bitmap.width, bitmap.height) }
        return IntArray(GRID * GRID) { i ->
            val x = r.left + (i % GRID) * (r.width() - 1) / (GRID - 1)
            val y = r.top + (i / GRID) * (r.height() - 1) / (GRID - 1)
            bitmap.getPixel(x, y)
        }
    }

    private fun luma(c: Int) = 0.2126 * Color.red(c) + 0.7152 * Color.green(c) + 0.0722 * Color.blue(c)

    /** Colour cycling of the test videos changes every sampled pixel by far more. */
    private const val MIN_MOTION = 1.5
    private const val MAX_FREEZE_MS = 1_200L
}
