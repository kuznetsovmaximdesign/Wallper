package com.wallper.live.core

import org.junit.Assert.assertEquals
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
}
