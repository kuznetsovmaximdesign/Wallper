package com.wallper.live.data

import com.wallper.live.core.Focus
import com.wallper.live.core.Playback
import com.wallper.live.data.settings.EditChoice
import com.wallper.live.data.settings.EditMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EditMemoryTest {

    private val map = HashMap<String, String>()
    private val memory = EditMemory(object : EditMemory.Store {
        override fun get(key: String) = map[key]
        override fun put(key: String, value: String) {
            map[key] = value
        }
    })

    @Test
    fun aNewWallpaperStartsAtNormalSpeed() {
        assertNull(memory.choiceFor("a"))
        assertEquals(Playback.DEFAULT_SPEED, memory.startingSpeed(), 0f)
    }

    @Test
    fun eachWallpaperKeepsItsOwnChoices() {
        val a = EditChoice(startMs = 12_000, loopMs = 20_000, speed = 2f, focus = Focus(0.3f, 0.6f))
        val b = EditChoice(startMs = 5_000, loopMs = 30_000, speed = 0.5f, focus = Focus.Center)
        memory.remember("a", a)
        memory.remember("b", b)
        assertEquals(a, memory.choiceFor("a"))
        assertEquals(b, memory.choiceFor("b"))
    }

    @Test
    fun theLastPickedSpeedCarriesOverToWallpapersNotYetTouched() {
        memory.pickedSpeed(3.5f)
        memory.remember("a", EditChoice(0, 30_000, 3.5f, Focus.Center))
        assertEquals(3.5f, memory.startingSpeed(), 0f)
        assertNull(memory.choiceFor("never-opened"))
    }

    @Test
    fun aWallpaperOpenedWithoutPickingASpeedFollowsTheLastOne() {
        memory.remember("b", EditChoice(0, 30_000, null, Focus.Center)) // only looked at
        memory.pickedSpeed(2.5f)
        memory.remember("a", EditChoice(0, 30_000, 2.5f, Focus.Center))
        assertNull(memory.choiceFor("b")!!.speed)
        assertEquals(2.5f, memory.startingSpeed(), 0f)
    }

    @Test
    fun viewingAWallpaperWithItsOwnSpeedDoesNotChangeTheLastPick() {
        memory.pickedSpeed(4f)
        memory.remember("a", EditChoice(0, 30_000, 4f, Focus.Center))
        memory.pickedSpeed(0.5f)
        memory.remember("b", EditChoice(0, 30_000, 0.5f, Focus.Center))
        memory.remember("a", EditChoice(0, 30_000, 4f, Focus.Center)) // back on A, just looking
        assertEquals(0.5f, memory.startingSpeed(), 0f)
    }

    @Test
    fun brokenOrOutOfRangeEntriesAreSafe() {
        map["edit:x"] = "garbage"
        assertNull(memory.choiceFor("x"))
        map["edit:y"] = "v2;1000;20000;9.0;1.7;-2"
        assertEquals(EditChoice(1_000, 20_000, Playback.MAX_SPEED, Focus(1f, 0f)), memory.choiceFor("y"))
        map["last-speed"] = "not a number"
        assertEquals(Playback.DEFAULT_SPEED, memory.startingSpeed(), 0f)
    }

    @Test
    fun anUntouchedFragmentIsNotPinned() {
        memory.remember("a", EditChoice(EditChoice.DEFAULT_FRAGMENT, 0, 2f, Focus(0.2f, 0.5f)))
        val restored = memory.choiceFor("a")!!
        assertEquals(EditChoice.DEFAULT_FRAGMENT, restored.startMs)
        assertEquals(2f, restored.speed!!, 0f)
        assertEquals(Focus(0.2f, 0.5f), restored.focus)
    }

    @Test
    fun recordsFromBeforeLongerLoopsGetTheNewDefaultFragment() {
        map["edit:a"] = "6000;30000;1.5;0.4;0.5" // v1: fragment saved even if never moved
        val restored = memory.choiceFor("a")!!
        assertEquals(EditChoice.DEFAULT_FRAGMENT, restored.startMs)
        assertEquals(1.5f, restored.speed!!, 0f)
        assertEquals(Focus(0.4f, 0.5f), restored.focus)
    }

    @Test
    fun brokenRecordsAreIgnored() {
        map["edit:a"] = "garbage"
        assertNull(memory.choiceFor("a"))
        map["edit:b"] = "v2;x;1;;0.5;0.5"
        assertNull(memory.choiceFor("b"))
    }
}
