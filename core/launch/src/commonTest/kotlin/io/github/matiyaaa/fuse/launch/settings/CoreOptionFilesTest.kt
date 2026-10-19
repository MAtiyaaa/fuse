package io.github.matiyaaa.fuse.launch.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CoreOptionFilesTest {
    private val config = "/home/p/.config/retroarch/config"

    @Test
    fun aGamesOptionsAreItsContentsNameUnderTheCoresLibraryName() {
        assertEquals("$config/mGBA/Golden Sun (USA).opt", CoreOptionFiles.gameFile(config, "mGBA", "/roms/gba/Golden Sun (USA).gba"))
        assertEquals("$config/mGBA/gba.opt", CoreOptionFiles.folderFile(config, "mGBA", "C:\\roms\\gba\\Golden Sun (USA).gba"))
        assertNull(CoreOptionFiles.folderFile(config, "mGBA", "Golden Sun (USA).gba"), "no folder to name")
        assertEquals("$config/mGBA/mGBA.opt", CoreOptionFiles.coreFile(config, "mGBA"))
        assertEquals("core.mgba", CoreOptionFiles.family("mgba"))
    }

    @Test
    fun everyCoresOptionsAreBesideRetroArchsOwnFileUnlessItNamesAnother() {
        assertEquals("/home/p/.config/retroarch/retroarch-core-options.cfg", CoreOptionFiles.globalFile(null, "/home/p/.config/retroarch/retroarch.cfg"))
        assertEquals("/home/p/.config/retroarch/retroarch-core-options.cfg", CoreOptionFiles.globalFile("", "/home/p/.config/retroarch/retroarch.cfg"))
        assertEquals("/data/options.cfg", CoreOptionFiles.globalFile("/data/options.cfg", "/home/p/.config/retroarch/retroarch.cfg"))
        assertEquals("D:/RetroArch/options.cfg", CoreOptionFiles.globalFile("D:\\RetroArch\\options.cfg", "D:\\RetroArch\\retroarch.cfg"))
        assertEquals("D:/RetroArch/retroarch-core-options.cfg", CoreOptionFiles.globalFile(":\\options.cfg", "D:\\RetroArch\\retroarch.cfg"), "a path RetroArch works out itself")
    }
}
