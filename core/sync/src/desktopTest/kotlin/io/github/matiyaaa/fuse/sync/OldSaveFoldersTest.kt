package io.github.matiyaaa.fuse.sync

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Save folders an emulator used before, found by its own save adapter: the Flatpak beside the
 * native install, an installed copy beside a portable one, a data folder the person chose since.
 * Fixtures are folders laid out as each install keeps them, nothing copied from any emulator.
 */
class OldSaveFoldersTest {
    private val root = Files.createTempDirectory("old-saves").toFile()

    @AfterTest fun tearDown() { root.deleteRecursively() }

    private val game = GameKey.of("psp", "ULUS10041", null, "Some Game")

    private fun query(home: File, emulatorPath: String? = null) =
        SaveQuery(game, "psp", File(home, "Games/Some Game.iso").path, "ppsspp", emulatorPath = emulatorPath, serial = "ULUS-10041", title = "Some Game")

    /** A PSP save of the game in [memstick], every file and folder in it last changed at [at]. */
    private fun save(memstick: File, text: String, at: Long, extra: String? = null): File {
        val dir = File(memstick, "PSP/SAVEDATA/ULUS10041DATA00").apply { mkdirs() }
        File(dir, "DATA.BIN").writeText(text)
        File(dir, "PARAM.SFO").writeText("param $text")
        if (extra != null) File(dir, extra).writeText("only here")
        // Another game's save beside it, which nothing here may touch.
        File(memstick, "PSP/SAVEDATA/NPUH10000SAVE").apply { mkdirs() }.let { File(it, "DATA.BIN").writeText("other game") }
        memstick.walkBottomUp().forEach { it.setLastModified(at) }
        return dir
    }

    private fun linux(home: File) = FileSaveEnvironment("LINUX", home.path.replace('\\', '/'), variables = { null })

    @Test
    fun theFlatpaksNewerSaveIsFoundAndBroughtIntoTheNativeInstallsFolderKeepingWhatWasThere() {
        val home = File(root, "home").apply { mkdirs() }
        val native = File(home, ".config/ppsspp")
        val flatpak = File(home, ".var/app/org.ppsspp.PPSSPP/config/ppsspp")
        save(native, "chapter 2", at = 1_700_000_000_000L, extra = "OLD.BIN")
        save(flatpak, "chapter 5", at = 1_750_000_000_000L)
        val env = linux(home)
        val found = OldSaveFolders.find(query(home), env).single()
        assertEquals(File(flatpak, "PSP/SAVEDATA").path.replace('\\', '/'), found.where)
        assertEquals(1_750_000_000_000L, found.modifiedAt)
        assertEquals(1_700_000_000_000L, found.inUseModifiedAt)

        val kept = assertNotNull(OldSaveMove.bringIn(game, found, File(root, "kept/Some Game")))
        val inUse = File(native, "PSP/SAVEDATA/ULUS10041DATA00")
        assertEquals("chapter 5", File(inUse, "DATA.BIN").readText())
        assertEquals("param chapter 5", File(inUse, "PARAM.SFO").readText())
        // The replaced save whole in the kept folder, and a file only it had gone from the folder in use.
        assertEquals("chapter 2", File(kept, "ULUS10041DATA00/DATA.BIN").readText())
        assertEquals("only here", File(kept, "ULUS10041DATA00/OLD.BIN").readText())
        assertTrue(!File(inUse, "OLD.BIN").exists())
        // The old folder as it was, and the other game's save untouched.
        assertEquals("chapter 5", File(flatpak, "PSP/SAVEDATA/ULUS10041DATA00/DATA.BIN").readText())
        assertEquals("other game", File(native, "PSP/SAVEDATA/NPUH10000SAVE/DATA.BIN").readText())
        // Now the same: nothing newer to offer.
        assertTrue(OldSaveFolders.find(query(home), env).isEmpty())
    }

    @Test
    fun anOlderSaveElsewhereIsNeverOfferedOverTheOneInUse() {
        val home = File(root, "home").apply { mkdirs() }
        save(File(home, ".config/ppsspp"), "newest", at = 1_750_000_000_000L)
        save(File(home, ".var/app/org.ppsspp.PPSSPP/config/ppsspp"), "older", at = 1_700_000_000_000L)
        assertTrue(OldSaveFolders.find(query(home), linux(home)).isEmpty())
    }

    @Test
    fun withNoSaveInUseYetTheOldOneIsOfferedAndNothingIsKeptAside() {
        val home = File(root, "home").apply { mkdirs() }
        // The native install is new: its memory stick has only another game's save.
        File(home, ".config/ppsspp/PSP/SAVEDATA/NPUH10000SAVE").mkdirs()
        save(File(home, ".var/app/org.ppsspp.PPSSPP/config/ppsspp"), "from the flatpak", at = 1_700_000_000_000L)
        val found = OldSaveFolders.find(query(home), linux(home)).single()
        assertNull(found.inUseModifiedAt)
        assertNull(OldSaveMove.bringIn(game, found, File(root, "kept/Some Game")))
        assertEquals("from the flatpak", File(home, ".config/ppsspp/PSP/SAVEDATA/ULUS10041DATA00/DATA.BIN").readText())
        assertTrue(!File(root, "kept").exists())
    }

    @Test
    fun onWindowsAnInstalledCopysSaveIsFoundBesideThePortableOneInUse() {
        val home = File(root, "Users/Mo").apply { mkdirs() }
        val portable = File(root, "Emulators/PPSSPP").apply { mkdirs() }
        File(portable, "PPSSPPWindows64.exe").writeText("")
        save(File(portable, "memstick"), "portable", at = 1_700_000_000_000L)
        save(File(home, "Documents/PPSSPP"), "installed", at = 1_750_000_000_000L)
        val env = FileSaveEnvironment("WINDOWS", home.path.replace('\\', '/'), variables = { null })
        val found = OldSaveFolders.find(query(home, File(portable, "PPSSPPWindows64.exe").path.replace('\\', '/')), env).single()
        assertEquals(File(home, "Documents/PPSSPP/PSP/SAVEDATA").path.replace('\\', '/'), found.where)
    }

    @Test
    fun onAMacTheOtherDataFolderIsFound() {
        val home = File(root, "Users/mo").apply { mkdirs() }
        save(File(home, ".config/ppsspp"), "in use", at = 1_700_000_000_000L)
        save(File(home, "Library/Application Support/PPSSPP"), "before", at = 1_750_000_000_000L)
        val env = FileSaveEnvironment("MACOS", home.path.replace('\\', '/'), variables = { null })
        assertEquals(File(home, "Library/Application Support/PPSSPP/PSP/SAVEDATA").path.replace('\\', '/'), OldSaveFolders.find(query(home), env).single().where)
    }

    @Test
    fun aSaveFolderChosenInSettingsIsInUseAndTheInstallsOwnIsTheOldOne() {
        val home = File(root, "home").apply { mkdirs() }
        val chosen = File(root, "Games/PSP memory stick")
        save(chosen, "chosen", at = 1_700_000_000_000L)
        save(File(home, ".config/ppsspp"), "the install's own", at = 1_750_000_000_000L)
        val env = WithSaveFolders(linux(home), folders = { mapOf("ppsspp" to chosen.path.replace('\\', '/')) })
        val found = OldSaveFolders.find(query(home), env).single()
        assertEquals(File(home, ".config/ppsspp/PSP/SAVEDATA").path.replace('\\', '/'), found.where)
        assertEquals(File(chosen, "PSP/SAVEDATA").path.replace('\\', '/'), found.inUse.root)
    }

    @Test
    fun twoEarlierFoldersAreBothOfferedNewestFirstIsUpToThePerson() {
        val home = File(root, "Users/mo").apply { mkdirs() }
        val chosen = File(root, "Games/PSP memory stick")
        save(chosen, "chosen", at = 1_600_000_000_000L)
        save(File(home, ".config/ppsspp"), "first", at = 1_700_000_000_000L)
        save(File(home, "Library/Application Support/PPSSPP"), "second", at = 1_750_000_000_000L)
        val env = WithSaveFolders(FileSaveEnvironment("MACOS", home.path.replace('\\', '/'), variables = { null }), folders = { mapOf("ppsspp" to chosen.path.replace('\\', '/')) })
        assertEquals(
            listOf(File(home, ".config/ppsspp/PSP/SAVEDATA"), File(home, "Library/Application Support/PPSSPP/PSP/SAVEDATA")).map { it.path.replace('\\', '/') },
            OldSaveFolders.find(query(home), env).map { it.where },
        )
    }

    @Test
    fun aFolderThatLostItsSaveSinceIsntBroughtIn() {
        val home = File(root, "home").apply { mkdirs() }
        save(File(home, ".config/ppsspp"), "in use", at = 1_700_000_000_000L)
        val old = save(File(home, ".var/app/org.ppsspp.PPSSPP/config/ppsspp"), "gone", at = 1_750_000_000_000L)
        val found = OldSaveFolders.find(query(home), linux(home)).single()
        old.deleteRecursively()
        assertFailsWith<IllegalStateException> { OldSaveMove.bringIn(game, found, File(root, "kept/Some Game")) }
        assertEquals("in use", File(home, ".config/ppsspp/PSP/SAVEDATA/ULUS10041DATA00/DATA.BIN").readText())
    }
}
