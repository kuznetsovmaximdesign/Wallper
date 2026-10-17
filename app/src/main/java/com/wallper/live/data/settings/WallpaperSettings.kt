package com.wallper.live.data.settings

import androidx.compose.runtime.Immutable
import com.wallper.live.core.Focus
import com.wallper.live.core.Playback

@Immutable
data class WallpaperSettings(
    val activeClipId: String? = null,
    val speeds: Map<String, Float> = emptyMap(),
    val focuses: Map<String, Focus> = emptyMap(),
    val seamlessLoop: Boolean = true,
    val parallax: Boolean = true,
    val pauseOnPowerSave: Boolean = true,
) {
    fun speedOf(clipId: String): Float = speeds[clipId] ?: Playback.DEFAULT_SPEED
    fun focusOf(clipId: String): Focus = focuses[clipId] ?: Focus.Center
}
