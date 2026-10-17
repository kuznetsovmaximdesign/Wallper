package com.wallper.live.playback

import com.wallper.live.core.PanelKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The glass frosts only while the hinge moves; at rest the wallpaper is always sharp. */
class HingeFrostTest {

    private var now = 1_000_000_000L

    private fun frost(panel: PanelKind, coarse: Boolean = false) = HingeFrost().apply {
        this.panel = panel
        this.enabled = true
        this.coarse = coarse
        update(now)
    }

    /** Runs the glass for [ms] at 60 fps and returns the last fold amount. */
    private fun HingeFrost.run(ms: Long): Float {
        var amount = 0f
        repeat((ms / 16).toInt().coerceAtLeast(1)) {
            now += 16_000_000L
            amount = update(now)
        }
        return amount
    }

    @Test
    fun silentSensorNeverBlursAnyPanel() {
        for (panel in PanelKind.entries) {
            val glass = frost(panel)
            assertEquals("$panel without readings", 0f, glass.run(2_000), 0f)
            assertFalse(glass.animating)
        }
    }

    @Test
    fun coverPanelIsSharpWhenThePhoneIsShut() {
        val glass = frost(PanelKind.Cover)
        glass.onHinge(0f, now)
        assertEquals(0f, glass.run(1_000), 0f)
    }

    @Test
    fun coverPanelFrostsWhileOpeningThenClears() {
        val glass = frost(PanelKind.Cover)
        glass.onHinge(0f, now)
        glass.run(500)
        var peak = 0f
        for (angle in 0..120 step 5) {
            glass.onHinge(angle.toFloat(), now)
            peak = maxOf(peak, glass.run(32))
        }
        assertTrue("cover frosts while the hinge moves (peak $peak)", peak > 0.9f)
        assertEquals("and is sharp again once the hinge is still", 0f, glass.run(2_000), 0f)
    }

    @Test
    fun innerPanelFrostsWhileUnfoldingAndIsSharpWhenOpen() {
        val glass = frost(PanelKind.Inner)
        glass.snap(30f, now) // lights up mid-unfold
        val start = glass.run(16)
        assertTrue("starts frosted ($start)", start > 0.8f)
        for (angle in 30..180 step 10) {
            glass.onHinge(angle.toFloat(), now)
            glass.run(32)
        }
        // Still within the pause after the last reading: sharp because it is flat.
        val flat = glass.run(300)
        assertTrue("clears as it opens flat ($flat)", flat < 0.05f)
        assertEquals(0f, glass.run(1_500), 0f)
    }

    @Test
    fun foldThatReportsLessThanFlatIsStillSharpAtRest() {
        // Many Folds report ~172° when fully open.
        val glass = frost(PanelKind.Inner)
        glass.onHinge(172f, now)
        glass.run(100)
        assertEquals(0f, glass.run(2_000), 0f)
        assertFalse(glass.animating)
    }

    @Test
    fun halfFoldedAtRestIsSharp() {
        // Laptop-style posture (Flex mode): still, so no blur at all.
        val glass = frost(PanelKind.Inner)
        glass.onHinge(95f, now)
        assertTrue("frosted right after moving", glass.run(250) > 0.2f)
        assertEquals(0f, glass.run(2_500), 0f)
    }

    @Test
    fun coarseSamsungStopsFrostDuringTheMotion() {
        val glass = frost(PanelKind.Inner, coarse = true)
        glass.onHinge(0f, now)
        glass.run(200)
        glass.onHinge(90f, now)
        val mid = glass.run(300)
        assertTrue("frosted between stops ($mid)", mid > 0.2f)
        glass.onHinge(180f, now)
        assertEquals(0f, glass.run(2_500), 0f)
    }

    @Test
    fun disabledOrRegularIgnoresTheHinge() {
        val off = frost(PanelKind.Inner).apply { enabled = false }
        off.onHinge(20f, now)
        assertEquals(0f, off.run(300), 0f)
        val phone = frost(PanelKind.Regular)
        phone.onHinge(20f, now)
        assertEquals(0f, phone.run(300), 0f)
    }

    @Test
    fun keepsDrawingUntilTheGlassHasCleared() {
        val glass = frost(PanelKind.Inner)
        glass.onHinge(60f, now)
        glass.run(100)
        assertTrue(glass.animating)
        glass.run(2_000)
        assertFalse(glass.animating)
    }
}
