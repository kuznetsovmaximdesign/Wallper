package com.wallper.live.core

import androidx.compose.runtime.Immutable

/** A source video that can be turned into a wallpaper (e.g. an Apple Aerial). */
@Immutable
data class Footage(
    val id: String,
    val title: String,
    /** Place/collection, e.g. "Тахо" or "Гималаи". */
    val collection: String?,
    val category: FootageCategory,
    /** Best-quality master (4K HEVC) used to cut the wallpaper clip. */
    val masterUrl: String,
    /** Light stream for a live preview; null when only the 4K master exists. */
    val previewUrl: String?,
    /** Still image published with the footage. */
    val coverUrl: String?,
    /** Master is above 60 fps (Apple's 240 fps macOS Aerials); reduced when cut. */
    val highFrameRate: Boolean,
    /** A night scene (also listed under [FootageCategory.Night]). */
    val night: Boolean = false,
)

/**
 * Topics of the gallery, in the order of its filter buttons. Apple's "Landscapes" (half
 * of all scenes) is split by place into mountains, forests, coasts and so on;
 * [Landscapes] remains for nature that fits none of them. [Night] is not a scene's own
 * category but a filter over all of them.
 */
enum class FootageCategory {
    Mountains,
    Forests,
    Coasts,
    Deserts,
    Meadows,
    Ice,
    Landscapes,
    Cities,
    Underwater,
    Space,
    Night,
    Abstract,
    ;

    fun includes(footage: Footage): Boolean = if (this == Night) footage.night else footage.category == this
}
