package com.wallper.live

import android.Manifest
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.wallper.live.ui.components.TestTags
import org.junit.Assert.fail

/** Drives the real app through UI Automator: taps, swipes and waits like a person would. */
class AppRobot {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val device: UiDevice = UiDevice.getInstance(instrumentation)
    private val packageName = instrumentation.targetContext.packageName

    init {
        // Video never idles: do not wait for an idle UI before each action.
        Configurator.getInstance().setWaitForIdleTimeout(0).setActionAcknowledgmentTimeout(500)
        if (Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.grantRuntimePermission(packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun launch() {
        val intent = instrumentation.targetContext.packageManager.getLaunchIntentForPackage(packageName)!!
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        instrumentation.targetContext.startActivity(intent)
        find(TestTags.GRID, LAUNCH_TIMEOUT_MS)
    }

    /** Brings the running app back to the front (after Home or another app). */
    fun bringBack() {
        val intent = instrumentation.targetContext.packageManager.getLaunchIntentForPackage(packageName)!!
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        instrumentation.targetContext.startActivity(intent)
        device.wait(Until.hasObject(By.pkg(packageName).depth(0)), LAUNCH_TIMEOUT_MS)
    }

    fun find(tag: String, timeoutMs: Long = UI_TIMEOUT_MS): UiObject2 {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            device.findObject(By.res(tag))?.let { return it }
            dismissSystemDialog()
            SystemClock.sleep(250)
        }
        val visibleText = device.findObjects(By.textStartsWith("")).mapNotNull { it.text }.take(12)
        fail("No element '$tag' on screen (in front: ${device.currentPackageName}, text: $visibleText)")
        error("unreachable")
    }

    /**
     * Slow emulators raise "isn't responding" dialogs for system apps; they would hide
     * the app under test. Our own app reporting one is a real failure.
     */
    private fun dismissSystemDialog() {
        val dialog = device.findObject(By.res("android:id/aerr_wait")) ?: return
        val message = device.findObject(By.res("android:id/alertTitle"))?.text.orEmpty()
        if (message.contains("Wallper", ignoreCase = true)) error("The app is not responding: $message")
        dialog.click()
    }

    fun exists(tag: String, timeoutMs: Long = 1_000): Boolean = device.wait(Until.hasObject(By.res(tag)), timeoutMs)

    fun tap(tag: String) {
        find(tag).click()
        SystemClock.sleep(SETTLE_MS)
    }

    /** Scrolls the gallery until [tag] is on screen. */
    fun scrollTo(tag: String): UiObject2 {
        repeat(6) {
            device.findObject(By.res(tag))?.takeIf { it.visibleBounds.height() > 40 }?.let { return it }
            find(TestTags.GRID).scroll(Direction.DOWN, 0.5f)
            SystemClock.sleep(SETTLE_MS)
        }
        return find(tag)
    }

    fun openFootage(id: String) {
        scrollTo(TestTags.card(id)).click()
        find(TestTags.VIEWER)
        SystemClock.sleep(SETTLE_MS)
    }

    /** The visible middle of the viewer's video. */
    fun videoArea(): Rect = Screen.middle(find(TestTags.VIDEO).visibleBounds)

    fun cardArea(id: String): Rect {
        val b = find(TestTags.card(id)).visibleBounds
        return Rect(b.left + b.width() / 6, b.top + b.height() / 8, b.right - b.width() / 6, b.top + b.height() / 2)
    }

    fun title(): String = find(TestTags.TITLE).text.orEmpty()

    fun swipeVideo(towardsLeft: Boolean) {
        val b = find(TestTags.VIDEO).visibleBounds
        val y = b.centerY()
        val (from, to) = if (towardsLeft) b.right - b.width() / 8 to b.left + b.width() / 8 else b.left + b.width() / 8 to b.right - b.width() / 8
        device.swipe(from, y, to, y, 25)
        SystemClock.sleep(900)
    }

    fun dragVideo() {
        val b = find(TestTags.VIDEO).visibleBounds
        device.drag(b.centerX(), b.centerY(), b.centerX() - b.width() / 4, b.centerY() + b.height() / 10, 30)
        SystemClock.sleep(SETTLE_MS)
    }

    /** Drags a slider's thumb area by [fraction] of its width. */
    fun dragSlider(tag: String, fraction: Float) {
        val b = find(tag).visibleBounds
        val y = b.centerY()
        val from = b.left + b.width() / 3
        device.drag(from, y, (from + b.width() * fraction).toInt(), y, 25)
        SystemClock.sleep(SETTLE_MS)
    }

    /** Leaves the viewer: its back button on phones, system back in the side-by-side layout. */
    fun closeViewer() {
        if (exists(TestTags.BACK, 500)) tap(TestTags.BACK) else back()
    }

    fun back() {
        device.pressBack()
        SystemClock.sleep(SETTLE_MS)
    }

    companion object {
        const val LAUNCH_TIMEOUT_MS = 30_000L
        const val UI_TIMEOUT_MS = 10_000L
        const val SETTLE_MS = 400L
    }
}
