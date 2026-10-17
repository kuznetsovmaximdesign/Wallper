package com.wallper.live

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import com.wallper.live.TestCatalog.Companion.SCENE_A
import com.wallper.live.TestCatalog.Companion.SCENE_B
import com.wallper.live.ui.components.TestTags
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Gallery: live cards, categories, tabs, settings and installing a wallpaper. */
@RunWith(AndroidJUnit4::class)
class GalleryTest {
    private val app = AppRobot()

    @Before
    fun start() {
        app.launch()
    }

    @Test
    fun heroCardPlaysLive() {
        Screen.awaitMoving({ app.cardArea(SCENE_A) }, START_TIMEOUT_MS, "hero card")
    }

    @Test
    fun gridCardsAnimate() {
        app.scrollTo(TestTags.card(SCENE_B))
        // The live loop is cut on the device the first time; afterwards it is cached.
        Screen.awaitMoving({ app.cardArea(SCENE_B) }, THUMB_TIMEOUT_MS, "grid card")
    }

    @Test
    fun categoriesFilterTheGallery() {
        app.tap(TestTags.category("Underwater"))
        app.find(TestTags.card(SCENE_B))
        assertTrue("Other categories must be hidden", !app.exists(TestTags.card(SCENE_A), 1_500))
        app.tap(TestTags.category("All"))
        app.find(TestTags.card(SCENE_A))
    }

    @Test
    fun settingsKeepTheGalleryPlaying() {
        Screen.awaitMoving({ app.cardArea(SCENE_A) }, START_TIMEOUT_MS, "hero before settings")
        app.tap(TestTags.SETTINGS)
        val rows = listOf(R.string.settings_frost, R.string.editor_loop, R.string.settings_parallax, R.string.settings_battery)
        for (row in rows.map(app::string)) {
            app.tapText(row)
            app.tapText(row) // and back to how it was
        }
        app.back()
        SystemClock.sleep(600)
        Screen.assertKeepsPlaying({ app.cardArea(SCENE_A) }, 2_500, "hero after settings")

        app.openFootage(SCENE_A)
        Screen.awaitMoving(app::videoArea, START_TIMEOUT_MS, "viewer after settings")
    }

    @Test
    fun installingAddsTheClipAndKeepsPlaying() {
        app.openFootage(SCENE_A)
        Screen.awaitMoving(app::videoArea, START_TIMEOUT_MS, "viewer before install")
        app.tap(TestTags.INSTALL)
        // The clip is cut, then the system wallpaper preview opens over the app.
        val left = app.device.wait(Until.gone(By.pkg(app.instrumentation.targetContext.packageName).depth(0)), INSTALL_TIMEOUT_MS)
        if (left) {
            SystemClock.sleep(1_500)
            app.device.pressBack()
            SystemClock.sleep(1_000)
            app.bringBack()
        }
        app.find(TestTags.VIEWER)
        Screen.awaitMoving(app::videoArea, START_TIMEOUT_MS, "viewer after install")

        app.closeViewer()
        app.tap(TestTags.TAB_MINE)
        // The new clip is in "Mine" and its card comes alive too.
        val clip = app.device.wait(Until.findObject(By.res(java.util.regex.Pattern.compile("card-.*"))), START_TIMEOUT_MS)
        assertTrue("The installed clip must be listed in Mine", clip != null)
        clip!!.click()
        app.find(TestTags.VIEWER)
        Screen.awaitMoving(app::videoArea, START_TIMEOUT_MS, "clip viewer")
        app.tap(TestTags.DOCK_FRAME)
        Screen.assertKeepsPlaying(app::videoArea, 2_500, "clip after Frame")
        app.tap(TestTags.DOCK_FRAME)
        app.closeViewer()
        app.tap(TestTags.TAB_GALLERY)
    }

    companion object {
        const val START_TIMEOUT_MS = 20_000L
        const val THUMB_TIMEOUT_MS = 90_000L
        const val INSTALL_TIMEOUT_MS = 60_000L
    }
}
