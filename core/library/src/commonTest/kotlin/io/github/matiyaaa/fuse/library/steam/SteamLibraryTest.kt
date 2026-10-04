package io.github.matiyaaa.fuse.library.steam

import io.github.matiyaaa.fuse.library.InMemoryFileSystem
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SteamLibraryTest {
    private val folders = """
        "libraryfolders"
        {
            "0"
            {
                "path"		"/home/me/.local/share/Steam"
                "label"		""
                "apps" { "620" "123" }
            }
            "1"
            {
                "path"		"D:\\SteamLibrary"
            }
        }
    """.trimIndent()

    private fun manifest(id: Long, name: String, dir: String, flags: Int = 4) = """
        "AppState"
        {
            "appid"		"$id"
            "name"		"$name"
            "StateFlags"		"$flags"
            "installdir"		"$dir"
            "SizeOnDisk"		"1000"
            // A comment Steam doesn't write, but people do.
        }
    """.trimIndent()

    @Test
    fun parsesNestedKeyValues() {
        val doc = Vdf.parse(folders)
        val lib = doc["libraryfolders"] as Map<*, *>
        assertEquals("/home/me/.local/share/Steam", (lib["0"] as Map<*, *>)["path"])
        assertEquals(listOf("/home/me/.local/share/Steam", "D:/SteamLibrary"), SteamLibraryReader.libraryPaths(folders))
    }

    @Test
    fun readsOnlyInstalledGamesAndSkipsTools() {
        assertEquals("Portal 2", SteamLibraryReader.manifest(manifest(620, "Portal 2", "Portal 2"), "/lib")?.name)
        assertNull(SteamLibraryReader.manifest(manifest(620, "Portal 2", "Portal 2", flags = 1026), "/lib"), "still downloading")
        assertNull(SteamLibraryReader.manifest(manifest(1493710, "Proton Experimental", "Proton - Experimental"), "/lib"))
        assertNull(SteamLibraryReader.manifest(manifest(228980, "Steamworks Common Redistributables", "Steamworks Shared"), "/lib"))
        assertNull(SteamLibraryReader.manifest(manifest(1628350, "Steam Linux Runtime 3.0 (sniper)", "SteamLinuxRuntime_sniper"), "/lib"))
        assertTrue(SteamLibraryReader.isTool(9, "Proton 9.0"))
    }

    @Test
    fun findsLibrariesOnEveryDriveAndListsTheirGames() = runTest {
        val fs = InMemoryFileSystem()
            .file("/home/me/.local/share/Steam/steamapps/libraryfolders.vdf", content = folders.replace("D:\\\\SteamLibrary", "/run/media/me/games/SteamLibrary"))
            .file("/home/me/.local/share/Steam/steamapps/appmanifest_620.acf", content = manifest(620, "Portal 2", "Portal 2"))
            .file("/run/media/me/games/SteamLibrary/steamapps/appmanifest_1145360.acf", content = manifest(1145360, "Hades", "Hades"))
            .file("/run/media/me/games/SteamLibrary/steamapps/appmanifest_228980.acf", content = manifest(228980, "Steamworks Common Redistributables", "Steamworks Shared"))
            // A library only found by looking at the drive, not listed by Steam.
            .file("/mnt/ssd/SteamLibrary/steamapps/appmanifest_70.acf", content = manifest(70, "Half-Life", "Half-Life"))
        val reader = SteamLibraryReader(fs)
        val libraries = reader.libraries(listOf("/home/me/.local/share/Steam"), drives = listOf("/mnt/ssd"))
        assertEquals(listOf("/home/me/.local/share/Steam", "/run/media/me/games/SteamLibrary", "/mnt/ssd/SteamLibrary"), libraries)
        val games = reader.games(libraries)
        assertEquals(listOf("Hades", "Half-Life", "Portal 2"), games.map { it.name })
        assertEquals("/run/media/me/games/SteamLibrary/steamapps/common/Hades", games.first { it.appId == 1145360L }.folder)
    }

    @Test
    fun shortcutNamesAreSafeOnEverySystem() {
        assertEquals("Half-Life 2 Episode One.steam", SteamLibraryReader.shortcutName(SteamGame(1, "Half-Life 2: Episode One", "/x", "/l")))
        assertEquals("Steam 5.steam", SteamLibraryReader.shortcutName(SteamGame(5, "???", "/x", "/l")))
    }
}
