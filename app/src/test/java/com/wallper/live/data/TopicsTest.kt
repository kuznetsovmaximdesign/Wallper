package com.wallper.live.data

import com.wallper.live.core.FootageCategory
import com.wallper.live.core.FootageCategory.Coasts
import com.wallper.live.core.FootageCategory.Deserts
import com.wallper.live.core.FootageCategory.Forests
import com.wallper.live.core.FootageCategory.Ice
import com.wallper.live.core.FootageCategory.Landscapes
import com.wallper.live.core.FootageCategory.Meadows
import com.wallper.live.core.FootageCategory.Mountains
import com.wallper.live.data.catalog.Topics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real scenes of Apple's macOS 26 and tvOS feeds (labels and subcategories as published). */
class TopicsTest {

    private fun topic(subcategory: String?, label: String) = Topics.of(Landscapes, subcategory, label)

    @Test
    fun applesPlacesBecomeTopics() {
        assertEquals(Mountains, topic("AerialSubcategoryUttarakhand", "Himalayan Peaks"))
        assertEquals(Mountains, topic("AerialSubcategoryTahoe", "Tahoe Day"))
        assertEquals(Mountains, topic("AerialSubcategoryYosemite", "Yosemite"))
        assertEquals(Forests, topic("AerialSubcategorySequoia", "Sequoia Sunrise"))
        assertEquals(Forests, topic("AerialSubcategoryRedwoods", "Del Norte Coast Redwoods State Park, California"))
        assertEquals(Coasts, topic("AerialSubcategoryGoa", "Goa Beaches"))
        assertEquals(Coasts, topic("AerialSubcategoryHawaii", "Hawaii"))
        assertEquals(Deserts, topic("AerialSubcategoryMonumentValley", "Coal Mine Canyon, Arizona"))
        assertEquals(Deserts, topic("AerialSubcategoryGrandCanyon", "Grand Canyon"))
        assertEquals(Deserts, topic("AerialSubcategoryLiwa", "Liwa"))
        assertEquals(Meadows, topic("AerialSubcategorySonoma", "Cazadero"))
        assertEquals(Meadows, topic("AerialSubcategoryKerala", "Tea Gardens Mist"))
        assertEquals(Meadows, topic("AerialSubcategorySuperbloom", "California State Route 58, Carrizo Plain, California"))
        assertEquals(Ice, topic("AerialSubcategoryIceland", "Iceland"))
        assertEquals(Ice, topic("AerialSubcategoryGreenland", "Greenland"))
    }

    @Test
    fun beachesInAForestCollectionAreCoasts() {
        assertEquals(Coasts, topic("AerialSubcategoryRedwoods", "China Beach, Curry County, Oregon"))
        assertEquals(Coasts, topic("AerialSubcategoryRedwoods", "Secret Beach, Brookings, Oregon"))
    }

    @Test
    fun tvosScenesWithoutSubcategoryUseTheirLabel() {
        assertEquals(Coasts, topic(null, "Hawaii"))
        assertEquals(Deserts, topic(null, "Grand Canyon"))
        assertEquals(Mountains, topic(null, "Patagonia"))
        assertEquals(Ice, topic(null, "Iceland"))
        assertEquals("unknown nature stays nature", Landscapes, topic(null, "Somewhere New"))
    }

    @Test
    fun otherCategoriesAreKept() {
        for (category in listOf(FootageCategory.Cities, FootageCategory.Underwater, FootageCategory.Space, FootageCategory.Abstract)) {
            assertEquals(category, Topics.of(category, "AerialSubcategoryChina", "China"))
        }
    }

    @Test
    fun nightScenes() {
        assertTrue(Topics.isNight("Tahoe Night"))
        assertTrue(Topics.isNight("Korea and Japan Night"))
        assertFalse(Topics.isNight("Tahoe Evening"))
    }
}
