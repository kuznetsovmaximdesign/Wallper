package com.wallper.live.core

import androidx.compose.runtime.Immutable

/** A source video that can be turned into a wallpaper (e.g. an Apple Aerial). */
@Immutable
data class Footage(
    val id: String,
    val title: String,
    val category: FootageCategory,
    /** Best-quality master (4K HEVC) used to cut the wallpaper clip. */
    val masterUrl: String,
    /** Lighter stream used for the live preview and covers. */
    val previewUrl: String,
)

enum class FootageCategory {
    Landscapes,
    Underwater,
    Space,
    Cities,
}
