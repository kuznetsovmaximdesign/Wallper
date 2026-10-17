package com.wallper.live.data.settings

import androidx.compose.runtime.Immutable
import com.wallper.live.core.Focus
import com.wallper.live.core.Playback
import com.wallper.live.core.WallpaperRole

@Immutable
data class WallpaperSettings(
    /** Home screen wallpaper (also the lock screen unless [lockClipId] is set). */
    val activeClipId: String? = null,
    /** Lock screen wallpaper on Android 14+; null means "same as [activeClipId]". */
    val lockClipId: String? = null,
    val speeds: Map<String, Float> = emptyMap(),
    val focuses: Map<String, Focus> = emptyMap(),
    val seamlessLoop: Boolean = true,
    val parallax: Boolean = true,
    val pauseOnPowerSave: Boolean = true,
    /** Frosted-glass reveal when the wallpaper appears (wake, unlock, unfold). */
    val frostReveal: Boolean = true,
) {
    fun clipIdFor(role: WallpaperRole): String? = when (role) {
        WallpaperRole.Home -> activeClipId
        WallpaperRole.Lock -> lockClipId ?: activeClipId
    }

    fun rolesOf(clipId: String): Set<WallpaperRole> =
        WallpaperRole.entries.filterTo(LinkedHashSet()) { clipIdFor(it) == clipId }

    fun speedOf(clipId: String): Float = speeds[clipId] ?: Playback.DEFAULT_SPEED
    fun focusOf(clipId: String): Focus = focuses[clipId] ?: Focus.Center
}
