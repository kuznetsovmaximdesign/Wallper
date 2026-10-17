package com.wallper.live.playback

import android.view.Surface

/**
 * Something a [LoopSession] can draw into: a renderer exposing one decoder surface per
 * player and compositing them (front layer + cross-fading back layer).
 * All methods are called on the main thread.
 */
interface VideoTarget {
    /** Decoder surfaces, index-aligned with the session's players; null until ready. */
    val surfaces: List<Surface>?

    /** Invoked when [surfaces] are (re)created, e.g. after a GL context loss. */
    var onSurfacesChanged: (() -> Unit)?

    /** Shows player [front] with the other player blended on top by [mix] (0..1). */
    fun present(front: Int, mix: Float)

    fun setVideoSize(width: Int, height: Int, pixelRatio: Float)
}
