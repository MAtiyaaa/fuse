package io.github.matiyaaa.fuse.ui.shell.sync

import io.github.matiyaaa.fuse.sync.GameKey
import io.github.matiyaaa.fuse.sync.SaveImportConfidence
import io.github.matiyaaa.fuse.sync.SaveImportEntry
import io.github.matiyaaa.fuse.sync.SaveImportPlan
import io.github.matiyaaa.fuse.sync.SaveImportTarget
import io.github.matiyaaa.fuse.sync.SaveKind
import io.github.matiyaaa.fuse.sync.SaveQuery
import io.github.matiyaaa.fuse.sync.SwitchSource
import kotlin.test.Test
import kotlin.test.assertEquals

class SwitchImportTest {
    private fun query(title: String, emulator: String = "linux.eden") =
        SaveQuery(GameKey.of("switch", null, null, title), "switch", "/g/$title.nsp", emulator, title = title)

    private fun entry(q: SaveQuery, name: String, at: Long, device: Boolean = false, selected: Boolean = true, other: SaveQuery? = null): SaveImportEntry {
        val t = SaveImportTarget("$name|t", q, SaveKind.SAVE, "switch.savedata", "/eden/$name", false, SaveImportConfidence.TITLE_ID)
        val more = other?.let { listOf(SaveImportTarget("$name|o", it, SaveKind.SAVE, "switch.savedata", "/ryujinx/$name", false, SaveImportConfidence.TITLE_ID)) }.orEmpty()
        return SaveImportEntry(name, name, "switch.savedata", 10, more + t, if (selected) t.id else null, SwitchSource("JKSV", name, "Matt", at, "0100", q.title, device))
    }

    @Test fun backupsOfAGameAreOneStepNewestFirst() {
        val zelda = query("Zelda")
        val mario = query("Mario")
        val plan = SaveImportPlan("p", "JKSV", listOf(entry(zelda, "old", 100, selected = false), entry(zelda, "new", 200), entry(mario, "m", 50)), emptyList(), 0)
        val f = SwitchImport("JKSV").apply { take(plan, emptyMap(), "mo") }
        assertEquals(listOf("Mario", "Zelda"), f.games.map { it.title })
        assertEquals(listOf("new", "old"), f.games[1].backups.map { it.first.id })
        assertEquals(setOf("mo"), f.games[1].people)
        assertEquals(SwitchImport.Step.GAME, f.step)
    }

    @Test fun aDeviceSaveGoesWithItsGameAndEdenIsPreferred() {
        val zelda = query("Zelda")
        val ryujinx = query("Zelda", "linux.ryujinx")
        val plan = SaveImportPlan("p", "JKSV", listOf(
            entry(zelda, "account", 100, selected = false, other = ryujinx),
            entry(zelda, "device", 100, device = true),
        ), emptyList(), 0)
        val f = SwitchImport("JKSV").apply { take(plan, emptyMap(), null) }
        // One card: the account save, with the game's device save imported alongside it.
        assertEquals(1, f.games.size)
        val g = f.games.single()
        assertEquals("account", g.entry.id)
        assertEquals("device", g.device?.first?.id)
        assertEquals("linux.eden", g.target.query.emulatorId)
        g.people = setOf("mo")
        assertEquals(listOf("account", "device"), g.choices().map { it.entry })
    }

    @Test fun theSameGameFoundTwiceIsOneCard() {
        // Two library entries of one game (by its title id): one card, the one on this device first.
        val a = query("Mario Wonder")
        val b = query("Super Mario Bros. Wonder")
        val id = "010015100B514000"
        fun e(q: SaveQuery, name: String, at: Long) = SaveImportEntry(name, name, "switch.savedata", 10,
            listOf(SaveImportTarget("$name|t", q, SaveKind.SAVE, "switch.savedata", "/eden/$name", false, SaveImportConfidence.TITLE_ID)), "$name|t",
            SwitchSource("JKSV", name, "Matt", at, id, "Super Mario Bros. Wonder", false))
        val plan = SaveImportPlan("p", "JKSV", listOf(e(a, "one", 100), e(b, "two", 200)), emptyList(), 0)
        val f = SwitchImport("JKSV").apply { take(plan, emptyMap(), "mo") }
        assertEquals(1, f.games.size)
        assertEquals(listOf("two", "one"), f.games.single().backups.map { it.first.id })
    }

    @Test fun nothingForTheLibraryEndsAtOnce() {
        val f = SwitchImport("JKSV").apply { take(SaveImportPlan("p", "JKSV", emptyList(), listOf("x"), 0), emptyMap(), "mo") }
        assertEquals(SwitchImport.Step.DONE, f.step)
    }
}
