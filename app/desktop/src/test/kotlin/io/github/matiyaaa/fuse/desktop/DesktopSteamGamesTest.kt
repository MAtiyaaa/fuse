package io.github.matiyaaa.fuse.desktop

import io.github.matiyaaa.fuse.desktop.platform.DesktopSteam
import io.github.matiyaaa.fuse.desktop.services.KnownFolders
import io.github.matiyaaa.fuse.desktop.system.DesktopOs
import io.github.matiyaaa.fuse.library.steam.BinaryVdf
import io.github.matiyaaa.fuse.library.steam.SteamArt
import io.github.matiyaaa.fuse.library.steam.SteamCollections
import io.github.matiyaaa.fuse.library.steam.SteamDirect
import io.github.matiyaaa.fuse.library.steam.SteamGameAction
import io.github.matiyaaa.fuse.library.steam.SteamGameEntry
import io.github.matiyaaa.fuse.library.steam.SteamGames
import io.github.matiyaaa.fuse.library.steam.SteamHealthIssue
import io.github.matiyaaa.fuse.library.steam.SteamShortcut
import io.github.matiyaaa.fuse.library.steam.SteamShortcuts
import io.github.matiyaaa.fuse.library.steam.SteamStart
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import io.github.matiyaaa.fuse.ui.shell.platform.SteamLiveChange
import io.github.matiyaaa.fuse.ui.shell.platform.SteamLiveResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Fuse's games in Steam on a computer: planned, written for each Steam user with their art from
 * Fuse's own files and a collection per system, taken out again, and undone, while every other
 * entry, collection and picture stays exactly as it was.
 */
class DesktopSteamGamesTest {
    private val root: File = Files.createTempDirectory("fuse-steam-games").toFile()
    private val config = File(root, ".local/share/Steam/userdata/123/config").apply { mkdirs() }
    private val fuse = File(root, "Applications/Fuse.AppImage").apply { parentFile.mkdirs(); writeText("#!/bin/sh"); setExecutable(true) }
    private val steam = DesktopSteam(KnownFolders(root.path, DesktopOs.LINUX), programPath = { fuse.path })
    private val zelda = SteamGameEntry(12, "The Legend of Zelda: The Minish Cap", "Game Boy Advance")
    private val metroid = SteamGameEntry(15, "Metroid Fusion", "Game Boy Advance")

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun picture(name: String, w: Int, h: Int, format: String = "png"): String =
        File(root, "art/$name.$format").apply { parentFile.mkdirs(); ImageIO.write(BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), format, this) }.path

    /** Steam ROM Manager's Metroid Fusion and a collection of the person's own are already there. */
    private fun seed(): Pair<ByteArray, String> {
        val vdf = SteamShortcuts.add(null, SteamShortcut("Metroid Fusion", "\"/usr/bin/flatpak\"", "\"/\"", launchOptions = "run org.libretro.RetroArch"))!!
        File(config, "shortcuts.vdf").writeBytes(vdf)
        val cloud = File(config, "cloudstorage").apply { mkdirs() }
        val mine = """[["user-collections.uc-mine",{"key":"user-collections.uc-mine","timestamp":1,"value":"{\"id\":\"uc-mine\",\"name\":\"Mine\",\"added\":[7],\"removed\":[]}","version":"1"}]]"""
        File(cloud, "cloud-storage-namespace-1.json").writeText(mine)
        return vdf to mine
    }

    @Test
    fun gamesGoInWithTheirArtAndCollectionAndComeOutAgain(): Unit = runBlocking {
        val (original, mine) = seed()
        val plan = steam.planGames(listOf(zelda, metroid))!!
        assertEquals(SteamGameAction.CREATE, plan.single { it.entry == zelda }.action)
        assertEquals(SteamGameAction.ELSEWHERE, plan.single { it.entry == metroid }.action)

        val art = mapOf(12L to SteamArt(portrait = picture("box", 60, 90), hero = picture("hero", 384, 124, "jpg"), icon = picture("icon", 32, 32)))
        val message = steam.addGames(listOf(zelda, metroid), art, collections = true).getOrThrow()
        assertTrue("1 game added" in message && "another tool" in message, message)

        val bytes = File(config, "shortcuts.vdf").readBytes()
        val id = SteamGames.inSteam(bytes) { it == fuse.path }.getValue(12L)
        assertEquals(setOf(12L), steam.gamesInSteam())
        val grid = File(config, "grid")
        assertTrue(File(grid, "${id}p.png").isFile)
        assertTrue(File(grid, "${id}_hero.jpg").isFile)
        assertTrue(File(grid, "${id}_icon.png").isFile)
        assertFalse(File(grid, "$id.png").exists(), "no wide picture was given, so none is made up")
        val entry = (BinaryVdf.parse(bytes)!!["shortcuts"] as BinaryVdf.Block).entries.map { it.second as BinaryVdf.Block }.single { (it["LaunchOptions"] as? BinaryVdf.Str)?.value == "--run 12" }
        assertEquals(File(grid, "${id}_icon.png").absolutePath, (entry["icon"] as BinaryVdf.Str).value)

        // The system's collection holds the game; the person's own collection is untouched.
        val collections = File(config, "cloudstorage/cloud-storage-namespace-1.json").readText()
        assertEquals(setOf("Game Boy Advance"), SteamCollections.fuseNames(collections))
        assertTrue(collections.startsWith(mine.dropLast(1)), "the person's records come first, as they were")
        assertTrue("$id" in collections)

        // Writing again changes nothing; undo puts Steam back as it was.
        steam.addGames(listOf(zelda), art, collections = true).getOrThrow()
        assertContentEquals(bytes, File(config, "shortcuts.vdf").readBytes())
        assertTrue(steam.canUndoGames)
        steam.undoGames().getOrThrow()
        assertContentEquals(original, File(config, "shortcuts.vdf").readBytes())
        assertTrue(SteamCollections.fuseNames(File(config, "cloudstorage/cloud-storage-namespace-1.json").readText()).isEmpty())
        assertFalse(steam.canUndoGames)

        // In again, then taken out: only Fuse's entry for it goes.
        steam.addGames(listOf(zelda), art, collections = true).getOrThrow()
        steam.removeGames(setOf(12L)).getOrThrow()
        assertTrue(steam.gamesInSteam().isEmpty())
        assertEquals(listOf("Metroid Fusion"), (BinaryVdf.parse(File(config, "shortcuts.vdf").readBytes())!!["shortcuts"] as BinaryVdf.Block).entries.map { ((it.second as BinaryVdf.Block)["AppName"] as BinaryVdf.Str).value })
    }

    @Test
    fun aDirectEntryStartsTheEmulatorFromItsFullPathAndFallsBackWhereItCant(): Unit = runBlocking {
        assumeTrue(DesktopOs.current == DesktopOs.LINUX)
        seed()
        val mgba = File(root, "Applications/mGBA.AppImage").apply { writeText("#!/bin/sh"); setExecutable(true) }
        val rom = "/home/deck/ROMs/gba/Minish Cap (USA).gba"
        val direct = zelda.copy(direct = SteamDirect(listOf(mgba.path, "-f", rom)))
        // A plan naming a program Steam can't find: that game goes in through Fast Start instead.
        val lost = SteamGameEntry(3, "Chrono Trigger", "Super Nintendo", direct = SteamDirect(listOf("no-such-emulator-here", "/roms/ct.sfc")))
        assertTrue(steam.directEntries)
        val plan = steam.planGames(listOf(direct, lost))!!
        assertEquals(mgba.path, plan.single { it.entry.gameId == 12L }.entry.direct?.argv?.first())
        assertEquals(null, plan.single { it.entry.gameId == 3L }.entry.direct)

        steam.addGames(listOf(direct, lost), emptyMap(), collections = false).getOrThrow()
        assertEquals(mapOf(12L to SteamStart.DIRECT, 3L to SteamStart.FAST_START), steam.gameStarts())
        val entry = (BinaryVdf.parse(File(config, "shortcuts.vdf").readBytes())!!["shortcuts"] as BinaryVdf.Block).entries
            .map { it.second as BinaryVdf.Block }.single { (it["AppName"] as BinaryVdf.Str).value == zelda.name }
        assertEquals("\"${mgba.path}\"", (entry["Exe"] as BinaryVdf.Str).value)
        assertEquals("FUSE_GAME=12 %command% -f '$rom'", (entry["LaunchOptions"] as BinaryVdf.Str).value)
        assertTrue(steam.gamesHealth(setOf(12L, 3L)).isEmpty())

        // The emulator went away: looking them over says so.
        mgba.delete()
        assertEquals(listOf(SteamHealthIssue.Kind.EMULATOR_GONE), steam.gamesHealth(setOf(12L, 3L)).map { it.kind })
    }

    @Test
    fun onlyPicturesInTheShapeSteamShowsThemInAreUsed() {
        val grid = File(config, "grid")
        val art = SteamArt(
            portrait = picture("landscape-box", 120, 90), wide = picture("header", 920, 430),
            hero = picture("screenshot", 1600, 900), logo = picture("logo", 400, 120), icon = picture("icon", 64, 64, "jpg"),
        )
        steam.placeGameArt(grid, 77, art)
        // A landscape box and a screenshot would be cropped as a capsule and a hero: Steam's own stay.
        assertEquals(listOf("77.png", "77_icon.jpg", "77_logo.png"), grid.list()!!.sorted())
    }

    @Test
    fun aSystemsGamesStartAsThePersonsOwnShortcutsStartThemWhileTheirProgramIsThere(): Unit = runBlocking {
        assumeTrue(DesktopOs.current == DesktopOs.LINUX)
        val retroarch = File(root, "Emulation/tools/launchers/retroarch.sh").apply { parentFile.mkdirs(); writeText("#!/bin/sh"); setExecutable(true) }
        val metroidRom = "${root.path}/ROMs/gba/Metroid Fusion (USA).gba"
        val zeldaRom = "${root.path}/ROMs/gba/Minish Cap (USA).gba"
        // Their shortcut, as an EmuDeck launcher writes one: frame generation before it, the game in double quotes.
        File(config, "shortcuts.vdf").writeBytes(
            SteamShortcuts.add(null, SteamShortcut("Metroid Fusion", "\"${retroarch.path}\"", "\"/tmp/.mount_RetroAx1/usr/bin\"", launchOptions = "LSFG_PROCESS=fuse ~/lsfg %command% -L mgba \"$metroidRom\""))!!,
        )
        val learned = steam.habits(mapOf(metroidRom to 15L, zeldaRom to 12L), mapOf(15L to "Game Boy Advance", 12L to "Game Boy Advance")).single()
        assertEquals("retroarch.sh", learned.program)
        assertEquals("\"${retroarch.parent}\"", learned.startDir, "the AppImage's own folder, not its mount")
        assertEquals("LSFG_PROCESS=fuse ~/lsfg", learned.frameGeneration?.words)

        val mgba = File(root, "Applications/mGBA.AppImage").apply { writeText("#!/bin/sh"); setExecutable(true) }
        val theirWay = zelda.copy(direct = SteamDirect(listOf(mgba.path, zeldaRom), written = learned.forGame(zeldaRom)))
        val starts = steam.entryStarts(listOf(theirWay)).getValue(12L)
        assertEquals(Triple("\"${retroarch.path}\"", "\"${retroarch.parent}\"", "FUSE_GAME=12 %command% -L mgba \"$zeldaRom\""), starts)
        steam.addGames(listOf(theirWay), emptyMap(), collections = false).getOrThrow()
        assertEquals(SteamStart.DIRECT, steam.gameStarts()[12L])
        assertTrue(steam.gamesHealth(setOf(12L)).isEmpty())

        // Their program gone: the entry is written as Play starts the game instead.
        retroarch.delete()
        assertEquals(Triple("\"${mgba.path}\"", "\"${mgba.parent}\"", "FUSE_GAME=12 %command% '$zeldaRom'"), steam.entryStarts(listOf(theirWay)).getValue(12L))
    }

    @Test
    fun withSteamOpenTheFusePluginPutsGamesInThroughSteamItself(): Unit = runBlocking {
        assumeTrue(DesktopOs.current == DesktopOs.LINUX)
        seed()
        val before = File(config, "shortcuts.vdf").readBytes()
        var open = true
        val steam = DesktopSteam(KnownFolders(root.path, DesktopOs.LINUX), programPath = { fuse.path }, steamOpen = { open })
        val live = steam.live!!
        val seen = java.util.Collections.synchronizedList(mutableListOf<SteamLiveChange>())
        // The plugin, as it runs inside Steam: it asks, makes each change and reports Steam's id for it.
        val plugin = launch(Dispatchers.Default) {
            while (isActive) {
                for (c in live.take()) {
                    seen += c
                    live.done(c.id, SteamLiveResult(true, appId = c.appId ?: 3_000_000_012L))
                }
                delay(20)
            }
        }
        try {
            withTimeout(5_000) { while (!steam.changesLive) delay(10) }
            val art = mapOf(12L to SteamArt(portrait = picture("box", 60, 90)))
            val message = steam.addGames(listOf(zelda), art, collections = true).getOrThrow()
            assertTrue("In Steam now" in message, message)
            val add = seen.single()
            assertEquals(SteamLiveChange.Op.ADD, add.op)
            assertEquals("\"${fuse.path}\"", add.exe)
            assertEquals("--run 12", add.launchOptions)
            assertEquals(setOf("capsule"), add.art.keys)
            // Steam's file isn't touched while it runs; Steam writes it when it quits.
            assertContentEquals(before, File(config, "shortcuts.vdf").readBytes())
            assertEquals(mapOf(12L to 3_000_000_012L), live.made())

            // Again: Fuse knows it's there, so it's brought up to date, not added twice.
            steam.addGames(listOf(zelda), art, collections = false).getOrThrow()
            assertEquals(SteamLiveChange.Op.UPDATE, seen.last().op)
            assertEquals(3_000_000_012L, seen.last().appId)

            steam.removeGames(setOf(12L)).getOrThrow()
            assertEquals(SteamLiveChange.Op.REMOVE, seen.last().op)
            assertEquals(3_000_000_012L, seen.last().appId)
            assertTrue(live.made().isEmpty())
        } finally {
            plugin.cancel()
            open = false
        }
    }

    @Test
    fun eachWriteCanBeUndoneWithWhatItDid(): Unit = runBlocking {
        val (original, _) = seed()
        steam.addGames(listOf(zelda), emptyMap(), collections = false).getOrThrow()
        val afterFirst = File(config, "shortcuts.vdf").readBytes()
        // Straight after each other: each is still a change of its own.
        steam.addGames(listOf(SteamGameEntry(3, "Chrono Trigger", "Super Nintendo")), emptyMap(), collections = false).getOrThrow()
        steam.removeGames(setOf(12L)).getOrThrow()
        assertEquals(listOf("Took 1 game out", "Added 1 game", "Added 1 game"), steam.undoSteps().map { it.what })

        // The latest alone.
        steam.undoGames().getOrThrow()
        assertEquals(setOf(12L, 3L), steam.gamesInSteam())
        // Back to before the first: the change after it goes too.
        steam.undoTo(steam.undoSteps().last().id).getOrThrow()
        assertContentEquals(original, File(config, "shortcuts.vdf").readBytes())
        assertTrue(steam.undoSteps().isEmpty())
        assertFalse(steam.canUndoGames)
        assertTrue(afterFirst.isNotEmpty())
    }

    @Test
    fun anotherToolsShortcutIsTakenOverInPlaceAndUndone(): Unit = runBlocking {
        val rom = File(root, "ROMs/gba/Metroid Fusion (USA).gba").path
        // Steam ROM Manager's shortcut for a game Fuse has, by its file.
        val vdf = SteamShortcuts.add(null, SteamShortcut("Metroid Fusion", "\"/usr/bin/flatpak\"", "\"/\"", launchOptions = "run org.libretro.RetroArch -L mgba \"$rom\"", tags = listOf("GBA")))!!
        File(config, "shortcuts.vdf").writeBytes(vdf)
        val found = steam.learnShortcuts(mapOf(rom to 15L))
        assertEquals(listOf(15L), found.map { it.gameId })
        assertEquals("flatpak", found.single().program)

        steam.takeOver(listOf(found.single() to metroid)).getOrThrow()
        assertEquals(mapOf(15L to found.single().appId), SteamGames.inSteam(File(config, "shortcuts.vdf").readBytes()) { it == fuse.path })
        assertEquals(mapOf(15L to SteamStart.FAST_START), steam.gameStarts())
        assertTrue(steam.learnShortcuts(mapOf(rom to 15L)).isEmpty(), "Fuse's now, so not offered again")

        steam.undoGames().getOrThrow()
        assertContentEquals(vdf, File(config, "shortcuts.vdf").readBytes())
    }

    @Test
    fun anEntryForAnOlderFuseIsPointedAtThisOneAndAGoneGameTakenOut(): Unit = runBlocking {
        seed()
        val old = File(root, "Downloads/Fuse-0.4.1.AppImage").apply { parentFile.mkdirs(); writeText("old") }
        val before = SteamGames.write(File(config, "shortcuts.vdf").readBytes(), old.path, listOf(zelda, SteamGameEntry(99, "Gone Game")), { File(it).name.startsWith("Fuse") })!!
        File(config, "shortcuts.vdf").writeBytes(before)
        val issues = steam.gamesHealth(library = setOf(12L))
        assertEquals(setOf(SteamHealthIssue.Kind.PROGRAM_MOVED, SteamHealthIssue.Kind.GAME_GONE), issues.map { it.kind }.toSet())
        val zeldaId = SteamGames.inSteam(before) { File(it).name.startsWith("Fuse") }.getValue(12L)

        steam.repairGames(setOf(12L), listOf(zelda), emptyMap()).getOrThrow()
        val after = File(config, "shortcuts.vdf").readBytes()
        assertEquals(mapOf(12L to zeldaId), SteamGames.inSteam(after) { File(it).name.startsWith("Fuse") }, "the same entry, with the id its art is filed under")
        assertTrue(steam.gamesHealth(setOf(12L)).isEmpty())
    }
}
