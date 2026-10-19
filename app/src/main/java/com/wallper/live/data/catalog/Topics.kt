package com.wallper.live.data.catalog

import com.wallper.live.core.FootageCategory

/**
 * Splits Apple's broad "Landscapes" (half of all scenes) into topics people browse by,
 * from Apple's own subcategory (a place) and, for scenes without one, the scene's label.
 */
internal object Topics {

    /** Topic of a scene of Apple's [category] in [subcategory] (e.g. "AerialSubcategoryTahoe"), labelled [label]. */
    fun of(category: FootageCategory, subcategory: String?, label: String): FootageCategory {
        if (category != FootageCategory.Landscapes) return category
        // A beach filmed for a forest collection is still a beach.
        if (BEACH.any { label.contains(it, ignoreCase = true) }) return FootageCategory.Coasts
        val place = subcategory?.removePrefix("AerialSubcategory")?.takeIf { it.isNotBlank() } ?: label
        return PLACES.entries.firstOrNull { (key, _) -> place.contains(key, ignoreCase = true) }?.value
            ?: FootageCategory.Landscapes
    }

    /** Night scenes, whatever their topic. */
    fun isNight(label: String): Boolean = label.contains("Night", ignoreCase = true)

    private val BEACH = listOf("Beach")

    /** Apple's places (subcategory names, or labels of tvOS scenes) to topics; first match wins. */
    private val PLACES = linkedMapOf(
        "Uttarakhand" to FootageCategory.Mountains,
        "Himalaya" to FootageCategory.Mountains,
        "Yosemite" to FootageCategory.Mountains,
        "Patagonia" to FootageCategory.Mountains,
        "Scotland" to FootageCategory.Mountains,
        "Tahoe" to FootageCategory.Mountains,
        "China" to FootageCategory.Mountains,
        "Sequoia" to FootageCategory.Forests,
        "Redwood" to FootageCategory.Forests,
        "Goa" to FootageCategory.Coasts,
        "Hawaii" to FootageCategory.Coasts,
        "MonumentValley" to FootageCategory.Deserts,
        "Monument Valley" to FootageCategory.Deserts,
        "Canyon" to FootageCategory.Deserts,
        "Liwa" to FootageCategory.Deserts,
        "Sonoma" to FootageCategory.Meadows,
        "Kerala" to FootageCategory.Meadows,
        "Superbloom" to FootageCategory.Meadows,
        "Wildflower" to FootageCategory.Meadows,
        "Iceland" to FootageCategory.Ice,
        "Greenland" to FootageCategory.Ice,
    )
}
