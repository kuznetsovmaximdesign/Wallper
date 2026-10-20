package com.wallper.live

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.wallper.live.ui.components.TestTags
import java.util.regex.Pattern
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
            automation().grantRuntimePermission(packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun launch() {
        val intent = instrumentation.targetContext.packageManager.getLaunchIntentForPackage(packageName)!!
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        instrumentation.targetContext.startActivity(intent)
        // Right after boot the system can swallow the first launch; ask once more.
        if (!exists(TestTags.GRID, LAUNCH_TIMEOUT_MS / 2)) instrumentation.targetContext.startActivity(intent)
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
        val start = SystemClock.elapsedRealtime()
        val deadline = start + timeoutMs
        var lastRefresh = start
        while (SystemClock.elapsedRealtime() < deadline) {
            reportViewIds()
            device.findObject(By.res(tag))?.let { return it }
            dismissSystemDialog()
            // Ids can go missing again after a window change: ask for them anew now and then.
            if (SystemClock.elapsedRealtime() - lastRefresh > REFRESH_AFTER_MS) {
                lastRefresh = SystemClock.elapsedRealtime()
                refreshAccessibility()
            }
            SystemClock.sleep(250)
        }
        val visibleText = device.findObjects(By.textStartsWith("")).mapNotNull { it.text }.take(12)
        // Tells "the element is not there" from "the app's ids are not reported at all".
        val ids = device.findObjects(By.pkg(packageName)).mapNotNull { runCatching { it.resourceName }.getOrNull() }.distinct().take(12)
        fail("No element '$tag' on screen (in front: ${device.currentPackageName}, text: $visibleText, ids: $ids)")
        error("unreachable")
    }

    /**
     * Slow emulators raise "isn't responding" dialogs for system apps; they would hide
     * the app under test. Our own app reporting one is a real failure.
     */
    private fun dismissSystemDialog() {
        val dialog = device.findObject(By.res("android:id/aerr_wait")) ?: return
        val message = runCatching { device.findObject(By.res("android:id/alertTitle"))?.text }.getOrNull().orEmpty()
        if (message.contains("Wallper", ignoreCase = true)) error("The app is not responding: $message")
        runCatching { dialog.click() } // the dialog may close on its own meanwhile
    }

    /** The most visible of several elements with [tag] (pager pages next to the current one share it). */
    fun findVisible(tag: String, timeoutMs: Long = UI_TIMEOUT_MS): UiObject2 {
        find(tag, timeoutMs)
        return device.findObjects(By.res(tag))
            .mapNotNull { element -> runCatching { element to element.visibleBounds }.getOrNull() }
            .maxByOrNull { (_, b) -> b.width() * b.height() }?.first ?: find(tag)
    }

    /** Runs [action] on a freshly found element, again if the UI recomposed under it. */
    private fun <T> fresh(lookup: () -> UiObject2, action: (UiObject2) -> T): T {
        repeat(STALE_RETRIES) {
            try {
                return action(lookup())
            } catch (_: StaleObjectException) {
                SystemClock.sleep(150)
            }
        }
        return action(lookup())
    }

    fun bounds(tag: String): Rect = fresh({ find(tag) }) { it.visibleBounds }

    private fun visibleBounds(tag: String): Rect = fresh({ findVisible(tag) }) { it.visibleBounds }

    /**
     * Taps the element whose text starts with [text], waiting for it like [find]. A row
     * merges its title and hint into one text, so only the start is matched.
     */
    fun tapText(text: String) {
        val deadline = SystemClock.elapsedRealtime() + UI_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            // DOTALL: a merged row's text spans lines (title, then hint).
            val match = By.text(Pattern.compile(Pattern.quote(text) + ".*", Pattern.DOTALL))
            val found = device.findObject(match)
            if (found != null && runCatching { found.click() }.isSuccess) {
                SystemClock.sleep(SETTLE_MS)
                return
            }
            SystemClock.sleep(250)
        }
        val visibleText = device.findObjects(By.textStartsWith("")).mapNotNull { it.text }.take(12)
        fail("No '$text' on screen (in front: ${device.currentPackageName}, text: $visibleText)")
    }

    fun string(id: Int): String = instrumentation.targetContext.getString(id)

    /** Waits until something showing [text] (at the start of its text) is on screen. */
    fun awaitText(text: String, timeoutMs: Long = UI_TIMEOUT_MS) {
        val match = By.text(Pattern.compile(Pattern.quote(text) + ".*", Pattern.DOTALL))
        if (!device.wait(Until.hasObject(match), timeoutMs)) fail("No '$text' on screen (in front: ${device.currentPackageName})")
    }

    /**
     * Accessibility audit of what is on screen (Android accessibility / Material): every
     * control has a label TalkBack can read, and fully visible ones are at least 48 dp.
     */
    fun accessibilityProblems(screen: String): List<String> {
        SystemClock.sleep(SETTLE_MS)
        reportViewIds()
        val density = instrumentation.targetContext.resources.displayMetrics.density
        val minPx = (MIN_TOUCH_DP - 1) * density
        val width = device.displayWidth
        val height = device.displayHeight
        return device.findObjects(By.clickable(true).pkg(packageName)).mapNotNull { node ->
            runCatching {
                val b = node.visibleBounds
                val name = node.resourceName ?: node.className
                when {
                    b.isEmpty -> null
                    // Cut by the screen or a scrolling row/grid: partly visible, judged once scrolled in.
                    b.left <= 0 || b.top <= 0 || b.right >= width || b.bottom >= height -> null
                    clippedByScroller(node, b) -> null
                    label(node).isBlank() -> "$screen: '$name' at $b has no label for TalkBack"
                    node.resourceName?.startsWith("card-") == true -> null
                    b.width() < minPx || b.height() < minPx ->
                        "$screen: '$name' is ${(b.width() / density).toInt()}×${(b.height() / density).toInt()} dp, below $MIN_TOUCH_DP dp"
                    else -> null
                }
            }.getOrNull()
        }
    }

    /** Whether a scrollable ancestor cuts [bounds] at one of its edges. */
    private fun clippedByScroller(node: UiObject2, bounds: Rect): Boolean {
        var parent = node.parent
        repeat(4) {
            val p = parent ?: return false
            if (p.isScrollable) {
                val pb = p.visibleBounds
                return bounds.left <= pb.left || bounds.top <= pb.top || bounds.right >= pb.right || bounds.bottom >= pb.bottom
            }
            parent = p.parent
        }
        return false
    }

    private fun label(node: UiObject2, depth: Int = 0): String {
        val own = node.text?.takeIf { it.isNotBlank() } ?: node.contentDescription?.takeIf { it.isNotBlank() }
        if (own != null || depth >= 3) return own.orEmpty()
        return node.children.asSequence().map { label(it, depth + 1) }.firstOrNull { it.isNotBlank() }.orEmpty()
    }

    /** Waits until no element has [tag] (exit animations included). */
    fun gone(tag: String, timeoutMs: Long = UI_TIMEOUT_MS): Boolean {
        reportViewIds()
        return device.wait(Until.gone(By.res(tag)), timeoutMs)
    }

    /** Forgets what the viewer remembered per wallpaper, for tests that need a clean start. */
    fun forgetViewerChoices() {
        instrumentation.targetContext.getSharedPreferences("viewer-edits", 0).edit().clear().commit()
    }

    fun exists(tag: String, timeoutMs: Long = 1_000): Boolean {
        reportViewIds()
        return device.wait(Until.hasObject(By.res(tag)), timeoutMs)
    }

    fun tap(tag: String) {
        fresh({ find(tag) }) { it.click() }
        SystemClock.sleep(SETTLE_MS)
    }

    /** Clicks the first element whose tag matches [pattern], retrying while the UI changes under it. */
    fun clickFirst(pattern: String, timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            reportViewIds()
            val found = device.findObject(By.res(Pattern.compile(pattern)))
            if (found != null && runCatching { found.click() }.isSuccess) {
                SystemClock.sleep(SETTLE_MS)
                return true
            }
            SystemClock.sleep(250)
        }
        return false
    }

    /** Waits until the viewer's title differs from [old]; returns the new one. */
    fun awaitTitleChange(old: String, timeoutMs: Long = UI_TIMEOUT_MS): String {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var current = title()
        while (current == old && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(250)
            current = title()
        }
        return current
    }

    /** Scrolls the gallery until [tag] is on screen. */
    fun scrollTo(tag: String): UiObject2 {
        repeat(6) {
            reportViewIds()
            device.findObject(By.res(tag))?.takeIf { runCatching { it.visibleBounds.height() > 40 }.getOrDefault(false) }?.let { return it }
            // Swipe inside the gallery: side by side, the screen's middle is the viewer's edge.
            val grid = runCatching { bounds(TestTags.GRID) }.getOrNull()
            val x = grid?.centerX() ?: (device.displayWidth / 2)
            val top = grid?.top ?: 0
            val span = grid?.height() ?: device.displayHeight
            device.swipe(x, top + span * 3 / 4, x, top + span / 3, 30)
            SystemClock.sleep(SETTLE_MS)
        }
        return find(tag)
    }

    fun openFootage(id: String) {
        fresh({ scrollTo(TestTags.card(id)) }) { it.click() }
        find(TestTags.VIEWER)
        SystemClock.sleep(SETTLE_MS)
    }

    /** The visible middle of the viewer's video. */
    fun videoArea(): Rect = Screen.middle(visibleBounds(TestTags.VIDEO))

    fun cardArea(id: String): Rect {
        val b = bounds(TestTags.card(id))
        return Rect(b.left + b.width() / 6, b.top + b.height() / 8, b.right - b.width() / 6, b.top + b.height() / 2)
    }

    fun title(): String = fresh({ find(TestTags.TITLE) }) { it.text.orEmpty() }

    /** Visible text of [tag], including text merged from its children (a button's label). */
    fun text(tag: String): String = fresh({ find(tag) }) { element ->
        element.text ?: element.children.firstNotNullOfOrNull { it.text } ?: element.contentDescription.orEmpty()
    }

    /** A deliberate horizontal swipe, above the paging arrows at the sides' centre. */
    fun swipeVideo(towardsLeft: Boolean) {
        val b = visibleBounds(TestTags.VIDEO)
        val y = b.top + b.height() * 3 / 10
        val right = b.right - b.width() * 3 / 10
        val left = b.left + b.width() / 10
        val (from, to) = if (towardsLeft) right to left else left to right
        device.swipe(from, y, to, y, 40)
        SystemClock.sleep(900)
    }

    fun dragVideo() {
        val b = visibleBounds(TestTags.VIDEO)
        device.drag(b.centerX(), b.centerY(), b.centerX() - b.width() / 4, b.centerY() + b.height() / 10, 30)
        SystemClock.sleep(SETTLE_MS)
    }

    /** Drags along a slider by [fraction] of its width, stopping at its ends. */
    fun dragSlider(tag: String, fraction: Float) {
        val b = bounds(tag)
        val y = b.centerY()
        val from = b.left + b.width() / 3
        val to = (from + b.width() * fraction).toInt().coerceIn(b.left + 1, b.right - 1)
        device.drag(from, y, to, y, 25)
        SystemClock.sleep(SETTLE_MS)
    }

    // region Device states: rotation, folding, network, theme, font size, process death

    /** Runs a shell command as the shell user and returns its output. */
    fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(automation().executeShellCommand(command))
            .bufferedReader().use { it.readText() }

    /** True when the screen is the Fold's inner panel (side-by-side layout). */
    fun isUnfolded(): Boolean = minOf(device.displayWidth, device.displayHeight).toFloat() /
        maxOf(device.displayWidth, device.displayHeight) > 0.7f

    /** Simulates folding to the cover-sized phone screen or unfolding to the inner one. */
    fun setUnfolded(unfolded: Boolean) {
        if (unfolded) {
            shell("wm size $INNER_SIZE")
            shell("wm density $INNER_DENSITY")
        } else {
            shell("wm size reset")
            shell("wm density reset")
        }
        SystemClock.sleep(CONFIG_SETTLE_MS)
    }

    fun setNetwork(on: Boolean) {
        val verb = if (on) "enable" else "disable"
        shell("svc wifi $verb")
        shell("svc data $verb")
        SystemClock.sleep(CONFIG_SETTLE_MS)
    }

    fun setDarkTheme(dark: Boolean) {
        shell("cmd uimode night ${if (dark) "yes" else "no"}")
        SystemClock.sleep(CONFIG_SETTLE_MS)
    }

    fun setFontScale(scale: Float) {
        shell("settings put system font_scale $scale")
        SystemClock.sleep(CONFIG_SETTLE_MS)
    }

    fun rotate(landscape: Boolean) {
        if (landscape) device.setOrientationLeft() else device.setOrientationNatural()
        SystemClock.sleep(CONFIG_SETTLE_MS)
    }

    /** Android killing the app in the background to free memory (it then restores itself). */
    fun killInBackground() {
        device.pressHome()
        SystemClock.sleep(1_500)
        shell("am kill $packageName")
        SystemClock.sleep(1_000)
    }

    /** Puts back what a state test may have changed, whatever its outcome. */
    fun restoreDevice() {
        runCatching { device.setOrientationNatural() }
        runCatching { device.unfreezeRotation() }
        setNetwork(true)
        shell("settings put system font_scale 1.0")
        shell("cmd uimode night $initialNight")
        shell(initialSize?.let { "wm size $it" } ?: "wm size reset")
        shell(initialDensity?.let { "wm density $it" } ?: "wm density reset")
        SystemClock.sleep(CONFIG_SETTLE_MS)
    }

    private val initialSize = Regex("Override size: (\\S+)").find(shell("wm size"))?.groupValues?.get(1)
    private val initialDensity = Regex("Override density: (\\d+)").find(shell("wm density"))?.groupValues?.get(1)
    private val initialNight = if (shell("cmd uimode night").contains("yes")) "yes" else "no"

    // endregion

    /** Leaves the viewer: its back button on phones, system back in the side-by-side layout. */
    fun closeViewer() {
        if (exists(TestTags.BACK, 500)) tap(TestTags.BACK) else back()
    }

    fun back() {
        device.pressBack()
        SystemClock.sleep(SETTLE_MS)
    }

    companion object {
        /**
         * The one UiAutomation everything uses. Asking for it with other flags (e.g. for a
         * screenshot) reconnects it and silently drops view ids, so tags stop being found.
         */
        fun automation(): UiAutomation = InstrumentationRegistry.getInstrumentation()
            .getUiAutomation(Configurator.getInstance().uiAutomationFlags)

        /**
         * Nodes fetched while view ids were off stay cached without them after a window
         * change (a sheet, the system picker): drop the cache so they are fetched again.
         */
        fun refreshAccessibility() {
            val automation = automation()
            runCatching {
                val info = automation.serviceInfo ?: return@runCatching
                info.flags = info.flags and AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS.inv()
                automation.serviceInfo = info
                info.flags = info.flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                automation.serviceInfo = info
            }
            if (Build.VERSION.SDK_INT >= 34) runCatching { automation.clearCache() }
        }

        /** Compose exposes test tags as view ids only while the service asks for them. */
        fun reportViewIds() {
            val automation = automation()
            val info = automation.serviceInfo ?: return
            if (info.flags and AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS == 0) {
                info.flags = info.flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                automation.serviceInfo = info
            }
        }

        const val LAUNCH_TIMEOUT_MS = 30_000L
        const val UI_TIMEOUT_MS = 10_000L
        const val SETTLE_MS = 400L
        const val STALE_RETRIES = 5
        const val REFRESH_AFTER_MS = 3_000L
        const val CONFIG_SETTLE_MS = 2_500L
        const val MIN_TOUCH_DP = 48

        /** Galaxy Z Fold inner panel, as emulated in CI. */
        const val INNER_SIZE = "1840x2208"
        const val INNER_DENSITY = 360
    }
}
