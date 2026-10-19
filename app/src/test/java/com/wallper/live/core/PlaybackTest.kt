package com.wallper.live.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackTest {

    @Test
    fun speedGoesFromHalfToFourInHalfSteps() {
        val stops = (0..Playback.SPEED_STOPS + 1).map { Playback.MIN_SPEED + it * Playback.SPEED_STEP }
        assertEquals(listOf(0.5f, 1f, 1.5f, 2f, 2.5f, 3f, 3.5f, 4f), stops)
        assertEquals(6, Playback.SPEED_STOPS) // Slider steps between the two ends
    }

    @Test
    fun speedSnapsToTheNearestHalfStep() {
        assertEquals(1f, Playback.snapSpeed(1.2f), 0f)
        assertEquals(1.5f, Playback.snapSpeed(1.3f), 0f)
        assertEquals(3.5f, Playback.snapSpeed(3.6f), 0f)
        assertEquals(4f, Playback.snapSpeed(9f), 0f)
        assertEquals(0.5f, Playback.snapSpeed(0.1f), 0f)
    }

    @Test
    fun aFreshWallpaperLoopsAsRarelyAsTheVideoAllows() {
        // A typical 4–6 min Apple scene: the longest loop allowed, past the fade-in.
        assertEquals(3_000L to Playback.MAX_LOOP_MS, Playback.defaultFragment(300_000))
        assertEquals(120_000L, Playback.MAX_LOOP_MS) // 2 minutes, was 30 s
        // A 1 min scene: nearly all of it, clear of the fade-out and the seam's cross-fade.
        val (start, length) = Playback.defaultFragment(60_000)
        assertEquals(3_000L, start)
        assertEquals(60_000 - 3_000 - Playback.FADE_MS - Playback.EDGE_MS, length)
    }

    @Test
    fun aShortVideoStillGetsAValidFragment() {
        for (total in listOf(4_000L, 6_000L, 9_000L, 12_000L)) {
            val (start, length) = Playback.defaultFragment(total)
            assertTrue("start $start for $total", start >= 0)
            assertTrue("length $length for $total", length in Playback.MIN_LOOP_MS..Playback.MAX_LOOP_MS)
        }
    }
}
