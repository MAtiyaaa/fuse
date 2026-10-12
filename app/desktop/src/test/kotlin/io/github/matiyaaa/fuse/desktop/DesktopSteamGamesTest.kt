package io.github.matiyaaa.fuse.desktop

import io.github.matiyaaa.fuse.desktop.platform.DesktopSteam
import io.github.matiyaaa.fuse.desktop.services.KnownFolders
import io.github.matiyaaa.fuse.desktop.system.DesktopOs
import io.github.matiyaaa.fuse.library.steam.BinaryVdf
import io.github.matiyaaa.fuse.library.steam.SteamArt
import io.github.matiyaaa.fuse.library.steam.SteamCollections
import io.github.matiyaaa.fuse.library.steam.SteamGameAction
import io.github.matiyaaa.fuse.library.steam.SteamGameEntry
import io.github.matiyaaa.fuse.library.steam.SteamGames
import io.github.matiyaaa.fuse.library.steam.SteamHealthIssue
import io.github.matiyaaa.fuse.library.steam.SteamShortcut
import io.github.matiyaaa.fuse.library.steam.SteamShortcuts
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
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
        val entry = (BinaryVdf.parse(bytes)!!["shortcuts"] as BinaryVdf.Block).entries.map { it.second as BinaryVdf.Block }.single { (it["LaunchOptions"] as? BinaryVdf.Str)?.value == "--play 12" }
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
