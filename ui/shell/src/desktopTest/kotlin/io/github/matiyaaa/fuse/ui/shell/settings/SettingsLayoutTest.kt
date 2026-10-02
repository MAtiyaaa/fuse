package io.github.matiyaaa.fuse.ui.shell.settings

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Settings' twelve sections: old links still land, and search only points at what exists. */
class SettingsLayoutTest {
    private val ids = settingsSections.map { it.id }.toSet()

    @Test
    fun everyOldSectionIdLandsInASectionThatExists() {
        val old = listOf(
            "appearance", "home", "library", "systems", "emulators", "media", "achievements", "cartridge", "inputs",
            "sound", "displays", "performance", "network", "phonelink", "health", "backup", "storage", "privacy", "updates", "about",
        )
        for (id in old) assertTrue(settingsSectionId(id) in ids, "$id lands nowhere")
        assertEquals("about", settingsSectionId("updates"))
        assertEquals("accounts", settingsSectionId("phonelink"))
        assertEquals("storage", settingsSectionId("backup"))
        assertEquals(null, settingsSectionId(null))
        // No alias hides a section that still exists.
        assertTrue(settingsAliases.keys.none { it in ids })
    }

    @Test
    fun twelveSectionsUnderFiveHeadingsAndTheStoreWhereItRuns() {
        // Twelve everywhere, and the Store's own section where Fuse has a Store (Android).
        assertEquals(13, settingsSections.size)
        assertEquals("Connections", settingsSections.first { it.id == "store" }.group)
        assertEquals(listOf("Personalize", "Games", "This device", "Connections", "General"), settingsSections.mapNotNull { it.group }.distinct())
        assertEquals(ids.size, settingsSections.size)
        // Summaries fit the narrow list on one line.
        for (s in settingsSections) assertTrue(s.summary.length <= 42, "${s.label}: ${s.summary}")
    }

    @Test
    fun searchOnlyPointsAtSectionsAndFoldsThatExist() {
        // The folds Settings draws, read from the code that draws them.
        val source = File("src/commonMain/kotlin/io/github/matiyaaa/fuse/ui/shell/settings").walk()
            .filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        val folds = Regex("""group\(\s*"([a-z.]+)"""").findAll(source).map { it.groupValues[1] }.toSet()
        assertTrue("appearance.crt" in folds && "media.sources" in folds, folds.toString())
        for (t in SettingsIndex.rows) {
            assertTrue(t.section in ids, "${t.row} is in ${t.section}, which isn't a section")
            t.group?.let { assertTrue(it in folds, "${t.row} unfolds $it, which Settings doesn't draw") }
            t.row?.let { row -> assertTrue("\"$row\"" in source, "No row is labelled $row") }
        }
    }

    @Test
    fun aFoldedSettingIsFoundWithItsFold() {
        val hit = SettingsIndex.search("scanlines", settingsSections).first()
        assertEquals("CRT effect", hit.title)
        assertEquals("appearance.crt", hit.topic.group)
        val deadzone = SettingsIndex.search("deadzone", settingsSections).first()
        assertEquals("inputs.feel", deadzone.topic.group)
        assertTrue(SettingsIndex.search("cartridge", settingsSections, cartridge = false).none { it.title == "Cartridge support" })
    }
}
