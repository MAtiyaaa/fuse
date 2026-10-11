package io.github.matiyaaa.fuse.sync

import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A device without a host shows each person what it keeps for them: their games with play time,
 * and their saves (the newest, and the one set aside while someone else plays), never another's.
 */
class LocalReportTest {
    private val root: File = Files.createTempDirectory("local-report").toFile()

    @AfterTest fun clean() { root.deleteRecursively() }

    private val zelda = GameKey.of("gba", null, null, "Minish Cap")
    private val saves = File(root, "saves").apply { mkdirs() }
    private val file = File(saves, "Minish Cap.srm")
    private fun slot() = LocalSlot(zelda, SaveKind.SAVE, "sram", saves, listOf(LocalFile("save.srm", file)), targets = mapOf("save.srm" to file))
    private fun person(id: String, name: String) = ProfileInfo(id, name, "fox", protected = false, createdAt = 0)

    @Test
    fun eachPersonSeesTheirOwnGamesAndSaves() = runBlocking<Unit> {
        val d = SyncDevice(File(root, "state"), "dev-1", "Thor")
        // Mo plays first: an hour, and a save.
        file.writeText("mo's save")
        d.handover("mo", slot())
        d.played("mo", zelda, SessionEntry(SessionEntry.idOf(1_000, 3_601_000), "dev-1", 1_000, 3_601_000))
        assertNotNull(d.capture("mo", slot(), 3_600, title = "Minish Cap"))
        // Sam takes the device: Mo's save is set aside, and Sam starts their own.
        d.handover("sam", slot())
        file.writeText("sam's save")
        assertNotNull(d.capture("sam", slot(), 60, title = "Minish Cap"))

        val mo = d.localReport(person("mo", "Mo"))
        assertTrue(mo.local)
        val game = mo.games.single()
        assertEquals(zelda.id, game.game)
        assertEquals("Minish Cap", game.name)
        assertEquals(3_600, game.playSeconds)
        val versions = game.slots.single().versions
        assertTrue(versions.isNotEmpty())
        assertEquals(1, versions.count { it.current }, "one save is the one in use")
        assertTrue(versions.all { it.device == "Thor" })

        val sam = d.localReport(person("sam", "Sam"))
        assertEquals(listOf(zelda.id), sam.games.map { it.game })
        assertEquals(0, sam.games.single().playSeconds, "Mo's play time is Mo's")
        // Nobody else's saves are in anyone's report.
        val samSaves = sam.games.single().slots.flatMap { it.versions }.map { it.id }
        val moSaves = versions.map { it.id }
        assertTrue(samSaves.none { it in moSaves })

        // Someone who never played here has nothing.
        assertTrue(d.localReport(person("kai", "Kai")).games.isEmpty())
    }
}
