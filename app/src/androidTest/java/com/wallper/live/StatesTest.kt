package com.wallper.live

import android.graphics.Rect
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import com.wallper.live.TestCatalog.Companion.SCENE_A
import com.wallper.live.TestCatalog.Companion.SCENE_B
import com.wallper.live.ui.components.TestTags
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The app in the states people actually put it in: rotated, folded and unfolded,
 * offline, killed by the system in the background, interrupted by the shade or recents,
 * with another theme or larger text. After each change the same wallpaper must still be
 * on screen and moving, and the controls must stay reachable.
 */
@RunWith(AndroidJUnit4::class)
class StatesTest {
    private val app = AppRobot()

    @Before
    fun start() {
        app.launch()
    }

    @After
    fun restore() {
        app.restoreDevice()
    }

    @Test
    fun rotatingKeepsTheSameWallpaperPlaying() {
        app.openFootage(SCENE_A)
        Screen.awaitMoving(app::videoArea, START_TIMEOUT_MS, "before rotating")
        val title = app.title()
        app.rotate(landscape = true)
        assertEquals("Rotation must keep the wallpaper", title, app.title())
        Screen.awaitMoving(app::videoArea, START_TIMEOUT_MS, "landscape")
        app.rotate(landscape = false)
        assertEquals(title, app.title())
        Screen.awaitMoving(app::videoArea, START_TIMEOUT_MS, "back to portrait")
    }

    /** Large-screen continuity: folding or unfolding mid-playback keeps the wallpaper. */
    @Test
    fun foldingAndUnfoldingKeepTheSameWallpaperPlaying() {
        app.openFootage(SCENE_B)
        Screen.awaitMoving(app::videoArea, START_TIMEOUT_MS, "before the posture change")
        val title = app.title()
        val unfolded = app.isUnfolded()
        app.setUnfolded(!unfolded)
        assertEquals("The viewer must survive ${if (unfolded) "folding" else "unfolding"}", title, app.title())
        Screen.awaitMoving(app::videoArea, START_TIMEOUT_MS, "after the posture change")
        app.setUnfolded(unfolded)
        assertEquals(title, app.title())
        Screen.awaitMoving(app::videoArea, START_TIMEOUT_MS, "after changing back")
    }

    @Test
    fun withoutNetworkLocalVideosStillPlay() {
        app.setNetwork(false)
        app.launch()
        Screen.awaitMoving({ app.cardArea(SCENE_A) }, START_TIMEOUT_MS, "hero offline")
        app.openFootage(SCENE_B)
        Screen.awaitMoving(app::videoArea, START_TIMEOUT_MS, "viewer offline")
        app.setNetwork(true)
        Screen.assertKeepsPlaying(app::videoArea, 3_000, "when the network comes back")
    }

    /** Android may kill the app in the background; coming back must restore where you were. */
    @Test
    fun comingBackAfterTheSystemKilledTheAppRestoresTheViewer() {
        app.openFootage(SCENE_B)
        Screen.awaitMoving(app::videoArea, START_TIMEOUT_MS, "before being killed")
        app.killInBackground()
        app.bringBack()
        app.find(TestTags.VIEWER, AppRobot.LAUNCH_TIMEOUT_MS)
        assertEquals("Сцена B", app.title())
        Screen.awaitMoving(app::videoArea, START_TIMEOUT_MS, "after the restart")
    }

    @Test
    fun theShadeAndRecentsDoNotStopPlayback() {
        app.openFootage(SCENE_A)
        Screen.awaitMoving(app::videoArea, START_TIMEOUT_MS, "before the shade")
        app.device.openNotification()
        SystemClock.sleep(1_500)
        app.back()
        Screen.awaitMoving(app::videoArea, RESUME_TIMEOUT_MS, "after the shade")
        app.device.pressRecentApps()
        SystemClock.sleep(1_500)
        app.bringBack()
        Screen.awaitMoving(app::videoArea, RESUME_TIMEOUT_MS, "after recents")
        Screen.assertKeepsPlaying(app::videoArea, 2_000, "after recents")
    }

    @Test
    fun switchingTheSystemThemeKeepsPlaying() {
        app.openFootage(SCENE_A)
        Screen.awaitMoving(app::videoArea, START_TIMEOUT_MS, "before the theme change")
        val title = app.title()
        app.setDarkTheme(!app.shell("cmd uimode night").contains("yes"))
        assertEquals(title, app.title())
        Screen.awaitMoving(app::videoArea, RESUME_TIMEOUT_MS, "after the theme change")
    }

    /** WCAG 1.4.4: text at 130 % must not push controls off screen or onto each other. */
    @Test
    fun largerTextKeepsEveryControlReachable() {
        app.setFontScale(1.3f)
        app.launch()
        app.openFootage(SCENE_A)
        val controls = listOf(TestTags.DOCK_SPEED, TestTags.DOCK_FRAGMENT, TestTags.DOCK_FRAME, TestTags.INSTALL, TestTags.TITLE)
        val bounds = controls.associateWith { app.bounds(it) }
        val screen = Rect(0, 0, app.device.displayWidth, app.device.displayHeight)
        for ((tag, b) in bounds) {
            assertTrue("$tag must be on screen at 130 % text ($b)", screen.contains(b) && b.width() > 0 && b.height() > 0)
        }
        val dock = controls.dropLast(1)
        for (i in dock.indices) for (j in i + 1 until dock.size) {
            assertFalse("${dock[i]} overlaps ${dock[j]}", Rect.intersects(bounds.getValue(dock[i]), bounds.getValue(dock[j])))
        }
        Screen.awaitMoving(app::videoArea, START_TIMEOUT_MS, "with larger text")
    }

    /** Back closes what is on top first: a panel, then framing, then the viewer; a sheet before the gallery. */
    @Test
    fun backClosesTheTopmostLayerFirst() {
        app.openFootage(SCENE_A)
        Screen.awaitMoving(app::videoArea, START_TIMEOUT_MS, "viewer")

        app.tap(TestTags.DOCK_SPEED)
        app.find(TestTags.SPEED_SLIDER)
        app.back()
        assertFalse("Back must close the speed panel", app.exists(TestTags.SPEED_SLIDER, 1_500))
        app.find(TestTags.VIEWER)

        app.tap(TestTags.DOCK_FRAME)
        val hint = app.string(R.string.hint_frame)
        assertTrue("Framing shows its hint", app.device.hasObject(By.text(hint)))
        app.back()
        SystemClock.sleep(800)
        assertFalse("Back must leave framing", app.device.hasObject(By.text(hint)))
        app.find(TestTags.VIEWER)
        Screen.assertKeepsPlaying(app::videoArea, 1_500, "after leaving framing with back")

        app.back()
        assertFalse("Back must then close the viewer", app.exists(TestTags.VIEWER, 2_000) && app.exists(TestTags.TITLE, 500))
        app.find(TestTags.GRID)

        app.tap(TestTags.SETTINGS)
        app.awaitText(app.string(R.string.settings_title))
        app.back()
        SystemClock.sleep(800)
        assertFalse("Back must close the settings sheet", app.device.hasObject(By.text(app.string(R.string.settings_title))))
        app.find(TestTags.GRID)
    }

    /** Every control is labelled for TalkBack and at least 48 dp, in each screen state. */
    @Test
    fun everyControlIsLabelledAndBigEnoughToTap() {
        val problems = mutableListOf<String>()
        problems += app.accessibilityProblems("gallery")
        app.openFootage(SCENE_A)
        problems += app.accessibilityProblems("viewer")
        app.tap(TestTags.DOCK_SPEED)
        problems += app.accessibilityProblems("speed panel")
        app.tap(TestTags.DOCK_SPEED)
        app.tap(TestTags.INSTALL_MORE)
        app.find(TestTags.INSTALL_LOCK)
        problems += app.accessibilityProblems("install menu")
        app.back()
        app.closeViewer()
        app.tap(TestTags.SETTINGS)
        app.awaitText(app.string(R.string.settings_title))
        problems += app.accessibilityProblems("settings")
        assertTrue("Accessibility problems:\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    companion object {
        const val START_TIMEOUT_MS = 20_000L
        const val RESUME_TIMEOUT_MS = 8_000L
    }
}
