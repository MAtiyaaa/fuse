package io.github.matiyaaa.fuse.library.platform

import io.github.matiyaaa.fuse.library.PlatformCatalog
import io.github.matiyaaa.fuse.model.FolderPolicy
import io.github.matiyaaa.fuse.model.PlatformId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class PlatformCatalogTest {
    private val required = listOf(
        "psx", "ps2", "ps3", "ps4", "psp", "psvita", "n64", "nds", "nintendo-dsi", "3ds", "new-nintendo-3ds",
        "gb", "gbc", "gba", "nes", "famicom", "fds", "snes", "sfam", "ngc", "wii", "wiiu", "switch", "virtualboy",
        "pokemon-mini", "dc", "saturn", "genesis", "segacd", "sega32", "sms", "gamegear", "sg1000", "arcade",
        "neogeoaes", "neo-geo-cd", "neo-geo-pocket", "neo-geo-pocket-color", "tg16", "turbografx-cd", "atari2600",
        "atari5200", "atari7800", "lynx", "jaguar", "wonderswan", "wonderswan-color", "xbox", "xbox360", "win", "dos",
        "steam", "android", "scummvm", "msx", "c64", "amiga", "3do", "colecovision", "intellivision", "vectrex",
    )

    /** Every game system folder ES-DE (and so EmuDeck, RetroDeck and Batocera's layouts) names. */
    private val esdeFolders = listOf(
        "3do", "adam", "amiga", "amiga1200", "amiga600", "amigacd32", "amstradcpc", "apple2", "apple2gs", "arcade",
        "arcadia", "archimedes", "arduboy", "astrocde", "atari2600", "atari5200", "atari7800", "atari800",
        "atarijaguar", "atarijaguarcd", "atarilynx", "atarist", "atarixe", "atomiswave", "bbcmicro", "c64",
        "cdimono1", "cdtv", "chailove", "channelf", "coco", "colecovision", "consolearcade", "cps", "cps1", "cps2",
        "cps3", "crvision", "daphne", "doom", "dos", "dragon32", "dreamcast", "easyrpg", "electron", "famicom",
        "fba", "fbneo", "fds", "flash", "fm7", "fmtowns", "fpinball", "gamate", "gameandwatch", "gamecom",
        "gamegear", "gb", "gba", "gbc", "gc", "genesis", "gmaster", "gx4000", "intellivision", "j2me", "laserdisc",
        "lcdgames", "lowresnx", "lutro", "macintosh", "mame", "mame-advmame", "mark3", "mastersystem", "megacd",
        "megacdjp", "megadrive", "megadrivejp", "megaduck", "model2", "model3", "moto", "msx", "msx1", "msx2",
        "msxturbor", "mugen", "multivision", "n3ds", "n64", "n64dd", "naomi", "naomi2", "naomigd", "nds", "neogeo",
        "neogeocd", "neogeocdjp", "nes", "ngage", "ngp", "ngpc", "odyssey2", "openbor", "oric", "palm", "pc", "pc88",
        "pc98", "pcengine", "pcenginecd", "pcfx", "pico8", "plus4", "pokemini", "ps2", "ps3", "ps4", "psp", "psvita",
        "psx", "pv1000", "quake", "samcoupe", "satellaview", "saturn", "saturnjp", "scummvm", "scv", "sega32x",
        "sega32xjp", "sega32xna", "segacd", "sfc", "sg-1000", "sgb", "snes", "snesna", "solarus", "spectravideo",
        "stv", "sufami", "supergrafx", "supervision", "supracan", "switch", "symbian", "tanodragon", "tg-cd", "tg16",
        "ti99", "tic80", "to8", "triforce", "trs-80", "uzebox", "vectrex", "vic20", "videopac", "vircon32",
        "virtualboy", "vpinball", "vsmile", "wasm4", "wii", "wiiu", "windows", "windows3x", "windows9x",
        "wonderswan", "wonderswancolor", "x1", "x68000", "xbox", "xbox360", "xboxone", "zmachine", "zx81", "zxnext",
        "zxspectrum",
    )

    @Test
    fun everyEsDeSystemFolderIsKnown() {
        val unknown = esdeFolders.filter { PlatformCatalog.resolveFolder(it) == null }
        assertTrue(unknown.isEmpty(), "ES-DE folders Fuse doesn't read: $unknown")
    }

    @Test
    fun containsEveryRequiredId() {
        val missing = required.filter { PlatformCatalog.byId(it) == null }
        assertTrue(missing.isEmpty(), "Missing platforms: $missing")
        assertTrue(PlatformCatalog.all.size >= 60)
    }

    @Test
    fun idsAreUniqueAndEntriesAreComplete() {
        val ids = PlatformCatalog.all.map { it.id.value }
        assertEquals(ids.size, ids.toSet().size)
        for (p in PlatformCatalog.all) {
            assertTrue(p.name.isNotBlank() && p.shortName.isNotBlank(), p.id.value)
            assertTrue(p.extensions.isNotEmpty(), "${p.id} has no extensions")
            assertTrue(p.extensions.all { it == it.lowercase() && !it.startsWith(".") }, "${p.id} extensions")
            assertTrue(p.folderAliases.all { it == it.lowercase() }, "${p.id} aliases must be lower case")
            assertTrue(p.id.value in p.folderAliases, "${p.id} must alias its own slug")
            assertTrue(p.coverAspect > 0f)
            assertEquals(0xFF000000, p.accent and 0xFF000000, "${p.id} accent must be opaque ARGB")
        }
    }

    @Test
    fun aliasesNeverPointAtTwoPlatforms() {
        val owners = HashMap<String, String>()
        for (p in PlatformCatalog.all) for (alias in p.folderAliases) {
            val previous = owners.put(alias, p.id.value)
            if (previous != null && previous != p.id.value) fail("Alias '$alias' used by $previous and ${p.id}")
        }
    }

    @Test
    fun resolvesRommSlugsAliasesAndEsDeNames() {
        val cases = mapOf(
            "psx" to "psx", "PS1" to "psx", "playstation" to "psx", "ps" to "psx",
            "megadrive" to "genesis", "Genesis" to "genesis", "megadrivejp" to "genesis",
            "gc" to "ngc", "GameCube" to "ngc", "n3ds" to "3ds", "sgb" to "gbc", "sfc" to "sfam",
            "windows" to "win", "pc" to "win", "mastersystem" to "sms", "pcengine" to "tg16",
            "pcenginecd" to "turbografx-cd", "neogeo" to "neogeoaes", "dreamcast" to "dc", "atarilynx" to "lynx",
            "ngp" to "neo-geo-pocket", "ngpc" to "neo-geo-pocket-color", "wswan" to "wonderswan",
            "wswanc" to "wonderswan-color", "x360" to "xbox360", "xbox360" to "xbox360", "vita" to "psvita",
            "androidgames" to "android", "androidapps" to "android", "megacd" to "segacd", "sega32x" to "sega32",
            "mame" to "arcade", "fbneo" to "arcade", "ps3-psn" to "ps3", "wiiware" to "wii", "pokemini" to "pokemon-mini",
        )
        for ((folder, id) in cases) {
            assertEquals(id, PlatformCatalog.resolveFolder(folder)?.id?.value, "folder '$folder'")
        }
    }

    @Test
    fun resolvesLooseSpellingsAndFullNames() {
        assertEquals("ps2", PlatformCatalog.resolveFolder("PlayStation 2")?.id?.value)
        assertEquals("gba", PlatformCatalog.resolveFolder("Game Boy Advance")?.id?.value)
        assertEquals("gba", PlatformCatalog.resolveFolder("game_boy_advance")?.id?.value)
        assertEquals("n64", PlatformCatalog.resolveFolder("Nintendo 64")?.id?.value)
        assertEquals("genesis", PlatformCatalog.resolveFolder("Mega Drive")?.id?.value)
        assertEquals("nintendo-dsi", PlatformCatalog.resolveFolder("Nintendo-DSi")?.id?.value)
        assertEquals("new-nintendo-3ds", PlatformCatalog.resolveFolder("NEW-NINTENDO-3DS")?.id?.value)
        assertNull(PlatformCatalog.resolveFolder("My Holiday Photos"))
        assertNull(PlatformCatalog.resolveFolder("  "))
    }

    @Test
    fun findsPlatformsByExtension() {
        assertEquals(listOf("switch"), PlatformCatalog.forExtension(".NSP").map { it.id.value }.filter { it == "switch" })
        assertTrue(PlatformCatalog.forExtension("gba").any { it.id.value == "gba" })
        assertTrue(PlatformCatalog.forExtension("chd").map { it.id.value }.containsAll(listOf("psx", "dc", "saturn", "arcade")))
        assertTrue(PlatformCatalog.forExtension("steam").map { it.id.value }.containsAll(listOf("steam", "win")))
        assertTrue(PlatformCatalog.forExtension("unknownext").isEmpty())
    }

    @Test
    fun carriesRetroAchievementsIdsAndPolicies() {
        val ra = mapOf("genesis" to 1, "n64" to 2, "snes" to 3, "psx" to 12, "ps2" to 21, "3ds" to 62, "ps3" to 82, "fds" to 81)
        for ((id, console) in ra) assertEquals(console, PlatformCatalog.byId(id)?.retroAchievementsConsoleId, id)
        assertNull(PlatformCatalog.byId("switch")?.retroAchievementsConsoleId)
        assertEquals(FolderPolicy.FOLDER_AS_GAME, PlatformCatalog.byId("win")?.defaultFolderPolicy)
        assertEquals(FolderPolicy.FOLDER_AS_GAME, PlatformCatalog.byId("xbox360")?.defaultFolderPolicy)
        assertEquals(FolderPolicy.AUTO, PlatformCatalog.byId("snes")?.defaultFolderPolicy)
        assertEquals(0.667f, PlatformCatalog.byId("steam")?.coverAspect)
    }

    @Test
    fun describesFirmwareWithoutShippingIt() {
        val psx = assertNotNull(PlatformCatalog.byId(PlatformId("psx"))?.bios)
        assertEquals(1, psx.requiredCount)
        assertTrue(psx.files.any { it.name == "scph5501.bin" && "490f666e1afb15b7362b406ed1cea246" in it.md5 })
        assertTrue(assertNotNull(PlatformCatalog.byId("ps3")?.bios).installedInEmulator)
        assertTrue(assertNotNull(PlatformCatalog.byId("switch")?.bios).installedInEmulator)
        assertTrue(assertNotNull(PlatformCatalog.byId("dc")?.bios).optional)
        assertEquals(2, PlatformCatalog.byId("xbox")?.bios?.requiredCount)
        assertNull(PlatformCatalog.byId("snes")?.bios)
    }
}
