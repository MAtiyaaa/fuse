package io.github.matiyaaa.fuse.sync

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Two people on one handheld without a host, then everyone's saves written out for Fuse V2: each
 * person's save once (the one kept aside while the other played is the same as the one made
 * here), with its files by content, and whose save is in the emulator's folder now.
 */
class FuseV2ExportTest {
    private lateinit var root: File

    @BeforeTest
    fun start() {
        root = Files.createTempDirectory("fuse-v2-export").toFile()
    }

    @AfterTest
    fun stop() {
        root.deleteRecursively()
    }

    private fun slot(dir: File, game: GameKey): LocalSlot {
        dir.mkdirs()
        val all = dir.walkTopDown().filter { it.isFile && !it.name.startsWith(".") }.map { LocalFile(it.relativeTo(dir).path.replace('\\', '/'), it) }.toList()
        return LocalSlot(game, SaveKind.SAVE, "sram", dir, all)
    }

    @Test
    fun everyonesSavesAreWrittenOutForFuseV2(): Unit = runBlocking {
        val thor = SyncDevice(File(root, "device"), "thor", "AYN Thor")
        val game = GameKey.of("gba", null, null, "Pokemon Emerald")
        val saves = File(root, "emulator-saves")
        val file = File(saves, "Pokemon Emerald.srm")

        // Mo plays, then Sam takes the handheld: Mo's save is kept aside for him.
        thor.handover("mo", slot(saves, game))
        file.writeText("mo: 8 badges")
        thor.capture("mo", slot(saves, game), 3600, title = "Pokemon Emerald")
        thor.handover("sam", slot(saves, game))
        file.writeText("sam: 1 badge")
        thor.capture("sam", slot(saves, game), 600, title = "Pokemon Emerald")

        val kept = thor.keptSaves()
        val list = FuseV2Export.saves(kept)
        assertEquals(setOf("mo", "sam"), list.map { it.person }.toSet(), "each person's save once: $list")
        assertTrue(list.all { it.game == game.id && it.kind == "SAVE" && it.format == "sram" && it.at > 0 && it.title == "Pokemon Emerald" })
        assertEquals(listOf(FuseV2Export.Holder("sam", game.id, "SAVE")), FuseV2Export.holders(kept))

        val folder = File(root, FuseV2Export.FOLDER)
        val manifest = FuseV2Export.Manifest(
            fuse = "test", device = "AYN Thor", exportedAt = 1, current = "sam",
            people = listOf(FuseV2Export.Person("mo", "Mo"), FuseV2Export.Person("sam", "Sam")),
            saveFolders = mapOf("eden" to "/storage/emulated/0/Emulation/Eden"),
            holders = FuseV2Export.holders(kept), saves = list,
        )
        val done = FuseV2Export.write(folder, manifest, thor.store)
        assertEquals(0, done.missing)
        val read = Json { ignoreUnknownKeys = true }.decodeFromString(FuseV2Export.Manifest.serializer(), File(folder, FuseV2Export.MANIFEST).readText())
        assertEquals(FuseV2Export.KIND, read.kind)
        val mo = read.saves.single { it.person == "mo" }.files.single()
        assertEquals("mo: 8 badges", File(File(folder, "objects"), mo.hash).readText())
        assertEquals("/storage/emulated/0/Emulation/Eden", read.saveFolders["eden"])
        // Written again over itself: the same, with nothing copied twice.
        assertEquals(done, FuseV2Export.write(folder, manifest, thor.store))
    }
}
