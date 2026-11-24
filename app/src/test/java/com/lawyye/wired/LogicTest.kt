package com.lawyye.wired

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppKeysTest {
    @Test
    fun packageOfKeepsTheProfile() {
        assertEquals("org.telegram.messenger", AppKeys.packageOf("org.telegram.messenger/org.telegram.ui.LaunchActivity"))
        assertEquals("org.telegram.messenger#w", AppKeys.packageOf("org.telegram.messenger/org.telegram.ui.LaunchActivity#w"))
    }

    @Test
    fun renamedActivityIsRepaired() {
        val map = AppKeys.heal(listOf("com.app/.OldMain"), listOf("com.app/.NewMain", "com.other/.Main"))
        assertEquals(mapOf("com.app/.OldMain" to "com.app/.NewMain"), map)
    }

    @Test
    fun presentKeysAreLeftAlone() {
        assertTrue(AppKeys.heal(listOf("com.app/.Main"), listOf("com.app/.Main")).isEmpty())
    }

    @Test
    fun ambiguousPackageIsNotGuessed() {
        val map = AppKeys.heal(listOf("com.app/.Old"), listOf("com.app/.A", "com.app/.B"))
        assertTrue(map.isEmpty())
    }

    @Test
    fun uninstalledAppIsNotTouched() {
        assertTrue(AppKeys.heal(listOf("com.gone/.Main"), listOf("com.app/.Main")).isEmpty())
    }

    @Test
    fun workProfileIsNotMixedWithPersonal() {
        val map = AppKeys.heal(listOf("com.app/.Old#w"), listOf("com.app/.New"))
        assertTrue(map.isEmpty())
        val work = AppKeys.heal(listOf("com.app/.Old#w"), listOf("com.app/.New", "com.app/.New#w"))
        assertEquals(mapOf("com.app/.Old#w" to "com.app/.New#w"), work)
    }
}

class SearchTest {
    @Test
    fun rankOrder() {
        assertEquals(0, Search.rank("telegram", "org.telegram.messenger", "tele"))
        assertEquals(1, Search.rank("google maps", "com.google.android.apps.maps", "maps"))
        assertEquals(2, Search.rank("whatsapp", "com.whatsapp", "app"))
        assertEquals(3, Search.rank("камера", "com.sec.android.app.camera", "camera"))
        assertEquals(-1, Search.rank("telegram", "org.telegram.messenger", "zzz"))
        assertEquals(-1, Search.rank("telegram", "org.telegram.messenger", ""))
    }

    @Test
    fun wrongLayoutBothWays() {
        assertEquals("telegram", Search.swapLayout("еудупкфь"))
        assertEquals("телеграм", Search.swapLayout("ntktuhfv"))
        assertEquals("ютуб", Search.swapLayout(".ne,"))
    }

    @Test
    fun swapIsItsOwnInverse() {
        val s = "qwertyuiop[]asdfghjkl;'zxcvbnm,.`"
        assertEquals(s, Search.swapLayout(Search.swapLayout(s)))
    }

    @Test
    fun digitsAndSpacesStay() {
        assertEquals("2гис", Search.swapLayout("2ubc"))
        assertEquals("a b", Search.swapLayout("ф и"))
    }
}

class CountsTest {
    @Test
    fun roundTrip() {
        val m = mapOf("a/.A" to 3, "b/.B#w" to 1)
        assertEquals(m, Counts.parse(Counts.serialize(m)))
    }

    @Test
    fun brokenLinesAreSkipped() {
        assertEquals(mapOf("a/.A" to 2), Counts.parse("a/.A\t2\nno-tab\n\t5\nb/.B\tx"))
        assertTrue(Counts.parse(null).isEmpty())
    }

    @Test
    fun bigMapKeepsTheMostUsed() {
        val m = (1..200).associate { "p$it/.A" to it }
        val kept = Counts.parse(Counts.serialize(m))
        assertEquals(120, kept.size)
        assertTrue("p200/.A" in kept)
        assertFalse("p1/.A" in kept)
    }
}

class ImagesTest {
    @Test
    fun neverDecodesBelowTheTarget() {
        // a 2600px home picture for a 2925px target: full size
        assertEquals(1, Images.sampleFor(1950, 2600, 2925))
        // a 4000px photo for the 1200px CRT store: half (2000px), not a quarter (1000px, blurry)
        assertEquals(2, Images.sampleFor(4000, 3000, 1200))
        assertEquals(4, Images.sampleFor(8000, 6000, 1200))
        assertEquals(1, Images.sampleFor(1200, 900, 900))
    }

    @Test
    fun brokenSizesAreSafe() {
        assertEquals(1, Images.sampleFor(0, 0, 900))
        assertEquals(1, Images.sampleFor(100, 100, 0))
    }
}
