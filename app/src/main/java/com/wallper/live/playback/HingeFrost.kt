package com.wallper.live.playback

import com.wallper.live.core.PanelKind
import kotlin.math.abs

/**
 * How frosted a foldable panel's glass is, driven by the hinge (iPhone Duo style).
 *
 * The glass frosts only while the hinge moves: unfolding frosts the inner panel and
 * clears it as it opens, closing frosts the cover panel. Once the hinge has been still
 * for a moment the picture turns sharp at any angle, so a Fold that reports 172° when
 * flat, a laptop-style half fold, or a sensor that stays silent never leaves the
 * wallpaper blurred.
 *
 * Time is passed in (nanoseconds) so the behaviour is unit-testable. [update] runs on
 * the GL thread; sensor readings may arrive from any thread.
 */
class HingeFrost {

    /** Panel being drawn; only foldable panels react to the hinge. */
    @Volatile var panel = PanelKind.Regular
        set(value) {
            if (value != field) restartAtRest = true
            field = value
        }

    /** A panel that just appeared starts sharp unless the hinge is moving. */
    @Volatile private var restartAtRest = true

    /** Off: the hinge is ignored and the picture always sharp. */
    @Volatile var enabled = false

    /** Stops-only sensors (Samsung: 0/90/180°) need a softer spring and longer pauses. */
    @Volatile var coarse = false

    @Volatile private var target = OPEN
    @Volatile private var lastEventNanos = Long.MIN_VALUE
    @Volatile private var snapTo: Float? = null

    private var angle = OPEN
    private var velocity = 0f
    private var lastUpdateNanos = 0L

    /** True while the displayed glass is still changing: keep drawing frames. */
    var animating = false
        private set

    /** A hinge sensor reading. */
    fun onHinge(degrees: Float, nowNanos: Long) {
        target = degrees.coerceIn(0f, OPEN)
        lastEventNanos = nowNanos
    }

    /** Jumps straight to [degrees], as if the hinge just moved there (panel lighting up mid-unfold). */
    fun snap(degrees: Float, nowNanos: Long) {
        snapTo = degrees.coerceIn(0f, OPEN)
        onHinge(degrees, nowNanos)
    }

    /** Whether the hinge has been still long enough for the glass to clear. */
    fun settled(nowNanos: Long): Boolean {
        val last = lastEventNanos
        if (last == Long.MIN_VALUE) return true
        return nowNanos - last > if (coarse) COARSE_SETTLE_NANOS else FINE_SETTLE_NANOS
    }

    /**
     * Advances the spring to [nowNanos] and returns the fold amount 0 (sharp) .. 1 (fully
     * frosted). A critically damped spring starts and settles softly, hides 1° sensor
     * steps and turns 0/90/180° stops into one continuous motion.
     */
    fun update(nowNanos: Long): Float {
        val dt = if (lastUpdateNanos == 0L) 0f else ((nowNanos - lastUpdateNanos) / 1e9f).coerceIn(0f, 0.05f)
        lastUpdateNanos = nowNanos
        val p = panel
        snapTo?.let {
            angle = it
            velocity = 0f
            snapTo = null
            restartAtRest = false
        }
        if (restartAtRest) {
            restartAtRest = false
            if (settled(nowNanos)) {
                angle = restAngle(p)
                velocity = 0f
            }
        }
        val goal = when {
            !enabled || p == PanelKind.Regular -> restAngle(p)
            settled(nowNanos) -> restAngle(p)
            else -> target
        }
        val omega = if (coarse) COARSE_OMEGA else FINE_OMEGA
        // Semi-implicit Euler in small steps keeps the spring stable at any frame rate.
        var remaining = dt
        while (remaining > 0f) {
            val h = minOf(remaining, 1f / 240f)
            val acceleration = -2f * omega * velocity - omega * omega * (angle - goal)
            velocity += acceleration * h
            angle += velocity * h
            remaining -= h
        }
        val moving = abs(goal - angle) > 0.05f || abs(velocity) > 0.5f
        if (!moving) {
            angle = goal
            velocity = 0f
        }
        val amount = frostAt(p, angle)
        // Also keep drawing until the pause after the last reading ends and the glass clears.
        animating = moving || (amount > 0f && !settled(nowNanos))
        return amount
    }

    companion object {
        const val OPEN = 180f

        /** Fully frosted by the time the inner panel is this far from flat. */
        private const val INNER_RANGE = 150f

        /** The cover panel frosts over during the first degrees of opening. */
        private const val COVER_RANGE = 25f

        private const val FINE_OMEGA = 16f // ≈ 0.3 s to settle
        private const val COARSE_OMEGA = 9f // ≈ 0.55 s across a 90° stop
        // Long enough that a hand pausing mid-unfold does not clear and re-frost the glass.
        private const val FINE_SETTLE_NANOS = 1_200_000_000L
        private const val COARSE_SETTLE_NANOS = 1_500_000_000L

        /** The angle at which a panel is sharp: flat for the inner one, shut for the cover. */
        fun restAngle(panel: PanelKind): Float = if (panel == PanelKind.Cover) 0f else OPEN

        /** Frost of [panel] at hinge [degrees], smoothstepped so it has no visible edges. */
        fun frostAt(panel: PanelKind, degrees: Float): Float {
            val raw = when (panel) {
                PanelKind.Inner -> ((OPEN - degrees) / INNER_RANGE).coerceIn(0f, 1f)
                PanelKind.Cover -> (degrees / COVER_RANGE).coerceIn(0f, 1f)
                PanelKind.Regular -> 0f
            }
            return raw * raw * (3f - 2f * raw)
        }
    }
}
