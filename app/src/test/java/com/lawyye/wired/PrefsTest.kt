package com.lawyye.wired

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PrefsTest {
    private fun dir(): File = Files.createTempDirectory("wired").toFile()

    @Test
    fun freshInstallGetsDefaults() {
        val sp = FakePrefs()
        val p = Prefs(sp, dir())
        assertEquals(Prefs.SCHEMA, sp.getInt("schema", 0))
        assertEquals(Prefs.HOME_MINIMAL, p.homeMode)
        assertEquals(Prefs.ICON_ORIGINAL, p.iconMode)
        assertTrue(p.dock.isEmpty())
    }

    @Test
    fun version1SettingsSurviveTheMigration() {
        val files = dir()
        File(files, "wallpaper.jpg").writeText("x")
        val sp = FakePrefs(
            mapOf(
                "accent" to 1, // v1 red
                "bg_mode" to 2, // picture
                "duotone" to false,
                "dim" to 40,
                "pinned" to "a/.A\nb/.B\nc/.C\nd/.D\ne/.E\nf/.F",
                "glitch" to 0,
            ),
        )
        val p = Prefs(sp, files)
        assertEquals(2, p.accentIndex)
        assertEquals(Prefs.HOME_IMAGE, p.homeMode)
        assertEquals(ImageFilter.ORIGINAL.ordinal, p.imgFilter)
        assertEquals(20, p.imgDim)
        assertEquals(listOf("a/.A", "b/.B", "c/.C", "d/.D", "e/.E"), p.dock)
        assertFalse(p.glitchUnlock)
        assertFalse(p.glitchTap)
        assertEquals(Prefs.ICON_ORIGINAL, p.iconMode)
    }

    @Test
    fun pictureModeNeedsAPicture() {
        val p = Prefs(FakePrefs(mapOf("bg_mode" to 2)), dir())
        assertEquals(Prefs.HOME_MINIMAL, p.homeMode)
    }

    @Test
    fun migrationRunsOnce() {
        val sp = FakePrefs(mapOf("accent" to 1))
        Prefs(sp, dir())
        val after = sp.getInt("accent", -1)
        Prefs(sp, dir())
        assertEquals(after, sp.getInt("accent", -1))
    }

    @Test
    fun renamedActivityKeepsDockHiddenAndCount() {
        val sp = FakePrefs(
            mapOf(
                "schema" to Prefs.SCHEMA,
                "dock" to "com.app/.Old\ncom.x/.Main",
                "hidden" to setOf("com.h/.Old"),
                "launch_counts" to "com.app/.Old\t5\ncom.app/.New\t2",
            ),
        )
        val p = Prefs(sp, dir())
        assertTrue(p.healKeys(listOf("com.app/.New", "com.x/.Main", "com.h/.New")))
        assertEquals(listOf("com.app/.New", "com.x/.Main"), p.dock)
        assertEquals(setOf("com.h/.New"), p.hidden)
        assertEquals(7, p.launchCounts()["com.app/.New"])
        assertFalse(p.healKeys(listOf("com.app/.New", "com.x/.Main", "com.h/.New")))
    }

    @Test
    fun dockDoesNotGetDuplicates() {
        val sp = FakePrefs(mapOf("schema" to Prefs.SCHEMA, "dock" to "com.app/.Old\ncom.app/.New"))
        val p = Prefs(sp, dir())
        p.healKeys(listOf("com.app/.New"))
        assertEquals(listOf("com.app/.New"), p.dock)
    }
}
