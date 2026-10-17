package com.wallper.live

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import com.wallper.live.TestCatalog.Companion.SCENE_A
import com.wallper.live.TestCatalog.Companion.SCENE_B
import com.wallper.live.TestCatalog.Companion.SCENE_HFR
import com.wallper.live.ui.components.TestTags
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every viewer action, checked on the real display: after each one the wallpaper
 * must still be on screen and moving — never black, never frozen.
 */
@RunWith(AndroidJUnit4::class)
class ViewerTest {
    private val app = AppRobot()

    @Before
    fun start() {
        app.launch()
    }

    @Test
    fun viewerStartsPlayingQuickly() {
        app.openFootage(SCENE_A)
        val took = Screen.awaitMoving(app::videoArea, START_TIMEOUT_MS, "viewer start")
        Log.i("WallperTest", "viewer autoplay took ${took}ms")
        assertTrue("Autoplay took ${took}ms", took < FAST_START_MS)
    }

    @Test
    fun framingKeepsThePictureMoving() {
        app.openFootage(SCENE_A)
        Screen.awaitMoving(app::videoArea, START_TIMEOUT_MS, "before framing")

        app.tap(TestTags.DOCK_FRAME)
        Screen.assertKeepsPlaying(app::videoArea, 3_000, "after tapping Frame")

        app.dragVideo()
        Screen.assertKeepsPlaying(app::videoArea, 2_000, "after dragging the frame")

        app.tap(TestTags.DOCK_FRAME) // "Done"
        Screen.assertKeepsPlaying(app::videoArea, 2_000, "after tapping Done")
    }

    @Test
    fun speedKeepsPlaying() {
        app.openFootage(SCENE_A)
        Screen.awaitMoving(app::videoArea, START_TIMEOUT_MS, "before speed")
        app.tap(TestTags.DOCK_SPEED)
        app.dragSlider(TestTags.SPEED_SLIDER, 0.4f)
        Screen.assertKeepsPlaying(app::videoArea, 2_500, "after speeding up")
        app.dragSlider(TestTags.SPEED_SLIDER, -0.6f)
        Screen.assertKeepsPlaying(app::videoArea, 2_500, "after slowing down")
        app.tap(TestTags.DOCK_SPEED)
        Screen.assertKeepsPlaying(app::videoArea, 1_500, "after closing speed")
    }

    @Test
    fun changingTheFragmentKeepsPlaying() {
        app.openFootage(SCENE_A)
        Screen.awaitMoving(app::videoArea, START_TIMEOUT_MS, "before fragment")
        app.device.wait(Until.findObject(By.res(TestTags.DOCK_FRAGMENT).enabled(true)), AppRobot.UI_TIMEOUT_MS)
        app.tap(TestTags.DOCK_FRAGMENT)
        app.dragSlider(TestTags.FRAGMENT_SLIDER, 0.25f)
        Screen.assertKeepsPlaying(app::videoArea, 4_000, "after moving the fragment")
        app.tap(TestTags.DOCK_FRAGMENT)
    }

    @Test
    fun swipeAndArrowsSwitchWallpapers() {
        app.openFootage(SCENE_A)
        Screen.awaitMoving(app::videoArea, START_TIMEOUT_MS, "first page")
        val first = app.title()

        app.swipeVideo(towardsLeft = true)
        Screen.awaitMoving(app::videoArea, START_TIMEOUT_MS, "after swipe")
        val second = app.title()
        assertNotEquals("Swipe must open the next wallpaper", first, second)

        app.tap(TestTags.PREVIOUS)
        SystemClock.sleep(800)
        Screen.awaitMoving(app::videoArea, START_TIMEOUT_MS, "after previous arrow")
        assertTrue("Arrow must return to '$first'", app.title() == first)

        app.tap(TestTags.NEXT)
        SystemClock.sleep(800)
        Screen.awaitMoving(app::videoArea, START_TIMEOUT_MS, "after next arrow")
    }

    @Test
    fun highFrameRateFootagePlays() {
        app.openFootage(SCENE_HFR)
        Screen.awaitMoving(app::videoArea, START_TIMEOUT_MS, "HFR viewer")
        app.tap(TestTags.DOCK_FRAME)
        Screen.assertKeepsPlaying(app::videoArea, 2_500, "HFR after Frame")
    }

    @Test
    fun leavingAndReturningKeepsPlaying() {
        app.openFootage(SCENE_A)
        Screen.awaitMoving(app::videoArea, START_TIMEOUT_MS, "before Home")
        app.device.pressHome()
        SystemClock.sleep(1_500)
        app.bringBack()
        Screen.awaitMoving(app::videoArea, RESUME_TIMEOUT_MS, "after returning to the app")
        Screen.assertKeepsPlaying(app::videoArea, 2_000, "after returning to the app")
    }

    @Test
    fun installMenuOffersLockScreenChoices() {
        app.openFootage(SCENE_B)
        Screen.awaitMoving(app::videoArea, START_TIMEOUT_MS, "before menu")
        app.tap(TestTags.INSTALL_MORE)
        app.find(TestTags.INSTALL_LOCK)
        app.find(TestTags.INSTALL_BOTH)
        app.back()
        Screen.assertKeepsPlaying(app::videoArea, 2_000, "after closing the menu")
    }

    @Test
    fun backToGalleryKeepsCardsPlaying() {
        app.openFootage(SCENE_A)
        Screen.awaitMoving(app::videoArea, START_TIMEOUT_MS, "viewer")
        app.closeViewer()
        app.find(TestTags.GRID)
        Screen.awaitMoving({ app.cardArea(SCENE_A) }, START_TIMEOUT_MS, "hero after back")
    }

    companion object {
        const val START_TIMEOUT_MS = 20_000L
        const val RESUME_TIMEOUT_MS = 6_000L

        /** Emulators decode in software; on a phone this is a fraction of it. */
        const val FAST_START_MS = 6_000L
    }
}
