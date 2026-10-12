package io.github.matiyaaa.fuse.launch.patches

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModRulesTest {
    @Test
    fun aSwitchModIsTheFolderItsPartsAreIn() {
        // As mods usually come: a folder of their own inside the archive, beside a readme.
        val m = ModRules.switchMod(
            listOf("readme.txt", "60 FPS/", "60 FPS/exefs/main.npdm", "60 FPS/romfs/Data/a.bin", "__MACOSX/60 FPS/._x"),
            "botw-60fps.zip",
        )!!
        assertEquals("60 FPS", m.folder)
        assertEquals(listOf("exefs/main.npdm", "romfs/Data/a.bin"), m.files.map { it.second })
        assertTrue(m.rejected.any { "outside the mod's own folder" in it })
        // A mod whose parts are at the top takes the archive's name; Eden reads the parts without minding case.
        val top = ModRules.switchMod(listOf("RomFS/x.bfres", "cheats/0100.txt"), "Better Grass.zip")!!
        assertEquals("Better Grass", top.folder)
        assertEquals(2, top.files.size)
        // Ryujinx reads romfs and exefs only.
        assertNull(ModRules.switchMod(listOf("cheats/a.txt"), "c", setOf("romfs", "exefs")))
        assertNull(ModRules.switchMod(listOf("readme.txt", "pic.png"), "x"))
    }

    @Test
    fun entriesThatWouldLeaveTheModsFolderAreLeftOut() {
        val m = ModRules.switchMod(listOf("mod/romfs/a", "../evil/romfs/b", "/abs/romfs/c"), "m")!!
        assertEquals(listOf("romfs/a"), m.files.map { it.second })
        assertEquals(2, m.rejected.count { "outside" in it })
    }

    @Test
    fun aCemuPackIsWhereItsRulesAre() {
        val p = ModRules.cemuPack(listOf("BotW_Mod/rules.txt", "BotW_Mod/content/Actor/x.sbactorpack", "Other.txt"), "pack.zip")!!
        assertEquals("BotW_Mod", p.folder)
        assertEquals(listOf("rules.txt", "content/Actor/x.sbactorpack"), p.files.map { it.second })
        assertEquals(
            setOf("00050000101c9300", "00050000101c9400"),
            ModRules.cemuTitleIds("[Definition]\ntitleIds = 00050000101C9300,00050000101C9400\nname = Mod\n"),
        )
        assertNull(ModRules.cemuPack(listOf("content/a.bin"), "x"))
    }

    @Test
    fun dolphinTexturesAreItsTex1PicturesBelowTheGamesFolder() {
        val (layout, madeFor) = ModRules.dolphinTextures(
            listOf("Pack/GALE01/tex1_64x64_abc_14.png", "Pack/GALE01/sub/tex1_32x32_def_14.dds", "Pack/readme.txt", "Pack/GALE01/cover.png"),
        )!!
        assertEquals("GALE01", madeFor)
        assertEquals(listOf("tex1_64x64_abc_14.png", "sub/tex1_32x32_def_14.dds"), layout.files.map { it.second })
        assertTrue(layout.rejected.any { "2 other files" in it })
        // A pack with no game folder keeps its own layout below what its pictures share.
        val (plain, none) = ModRules.dolphinTextures(listOf("HD/a/tex1_1.png", "HD/b/tex1_2.png"))!!
        assertNull(none)
        assertEquals(listOf("a/tex1_1.png", "b/tex1_2.png"), plain.files.map { it.second })
        assertNull(ModRules.dolphinTextures(listOf("cover.png")))
    }

    @Test
    fun aDolphinGraphicsModIsWhereItsMetadataIs() {
        val m = ModRules.dolphinGraphicsMod(listOf("Bloom Off/metadata.json", "Bloom Off/GALE01.txt"), "x.zip")!!
        assertEquals("Bloom Off", m.folder)
        assertEquals(2, m.files.size)
        assertNull(ModRules.dolphinGraphicsMod(listOf("tex1_a.png"), "x"))
    }

    @Test
    fun rpcs3FilesGoWhereTheirUsrdirSays() {
        val m = ModRules.rpcs3Files(listOf("BLUS30443/PS3_GAME/USRDIR/data/a.dat", "BLUS30443/PS3_GAME/USRDIR/b.sprx", "readme.txt"))!!
        assertEquals(listOf("data/a.dat", "b.sprx"), m.files.map { it.second })
        assertNull(ModRules.rpcs3Files(listOf("data/a.dat")))
    }

    @Test
    fun aShadPs4ModIsLaidOutLikeTheGame() {
        val game = setOf("eboot.bin", "sce_sys", "dvdroot_ps4")
        val m = ModRules.shadPs4Files(listOf("MyMod/dvdroot_ps4/parts/a.bin", "MyMod/readme.txt", "MyMod/eboot.bin"), game)!!
        assertEquals(listOf("dvdroot_ps4/parts/a.bin", "eboot.bin"), m.files.map { it.second })
        assertTrue(m.rejected.any { "beside the game's own folders" in it })
        assertNull(ModRules.shadPs4Files(listOf("x/y.bin"), game))
    }

    @Test
    fun aModsFolderNameIsOneEveryFileSystemTakes() {
        assertEquals("A B", ModRules.folderName("A:B.zip"))
        assertEquals("Mod", ModRules.folderName("..."))
    }
}
