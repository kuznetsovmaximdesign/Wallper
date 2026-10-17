package com.wallper.live.core

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/**
 * A ready-to-play wallpaper stored on the device.
 *
 * The file holds [loopMs] of footage followed by [fadeMs] of tail that is blended over
 * the start of the next loop, so playback is seamless.
 */
@Immutable
@Serializable
data class Clip(
    val id: String,
    val title: String,
    val origin: ClipOrigin,
    val footageId: String? = null,
    val fileName: String,
    val coverFileName: String? = null,
    val loopMs: Long,
    val fadeMs: Long,
    val width: Int,
    val height: Int,
    val createdAt: Long,
) {
    val isUhd: Boolean get() = maxOf(width, height) >= 3840
}

@Serializable
enum class ClipOrigin { Apple, Device }
