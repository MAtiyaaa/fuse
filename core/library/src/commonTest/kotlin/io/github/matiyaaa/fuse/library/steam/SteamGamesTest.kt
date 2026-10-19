package io.github.matiyaaa.fuse.library.steam

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SteamGamesTest {
    private val fuse = "/home/deck/Applications/Fuse.AppImage"
    private val isFuse: (String) -> Boolean = { it.substringAfterLast('/').startsWith("Fuse") }
    private val zelda = SteamGameEntry(12, "The Legend of Zelda: The Minish Cap", "Game Boy Advance")
    private val metroid = SteamGameEntry(15, "Metroid Fusion", "Game Boy Advance")

    /** A list with someone else's entries: a browser, and Steam ROM Manager's Metroid Fusion. */
    private fun theirs(): ByteArray = SteamShortcuts.add(
        SteamShortcuts.add(null, SteamShortcut("Firefox", "\"/usr/bin/firefox\"", "\"/usr/bin\""))!!,
        SteamShortcut("Metroid Fusion", "\"/usr/bin/flatpak\"", "\"/\"", launchOptions = "run org.libretro.RetroArch -L mgba \"/roms/gba/Metroid Fusion.gba\"", tags = listOf("GBA")),
    )!!

    private fun entries(bytes: ByteArray) = (BinaryVdf.parse(bytes)!!["shortcuts"] as BinaryVdf.Block).entries.map { it.second as BinaryVdf.Block }
    private fun BinaryVdf.Block.str(k: String) = (this[k] as? BinaryVdf.Str)?.value

    @Test
    fun aGameIsPlannedThenWrittenAndWritingAgainChangesNothing() {
        val before = theirs()
        val plan = SteamGames.plan(before, fuse, listOf(zelda, metroid), isFuse)!!
        assertEquals(SteamGameAction.CREATE, plan.single { it.entry == zelda }.action)
        // Another tool already has Metroid Fusion in Steam: never a second one.
        assertEquals(SteamGameAction.ELSEWHERE, plan.single { it.entry == metroid }.action)

        val after = SteamGames.write(before, fuse, listOf(zelda, metroid), isFuse) { "/grid/${it}_icon.png" }!!
        val list = entries(after)
        assertEquals(3, list.size)
        val ours = list.single { it.str("AppName") == zelda.name }
        assertEquals("\"$fuse\"", ours.str("Exe"))
        // Fuse Fast Start: no window of Fuse's between Steam's Play and the game.
        assertEquals("--run 12", ours.str("LaunchOptions"))
        assertEquals("/grid/12_icon.png", ours.str("icon"))
        assertEquals(listOf("Game Boy Advance"), (ours["tags"] as BinaryVdf.Block).entries.map { (it.second as BinaryVdf.Str).value })
        // Everyone else's entries are exactly as they were.
        assertEquals(entries(before).map { it.str("Exe") to it.str("LaunchOptions") }, list.filter { it !== ours }.map { it.str("Exe") to it.str("LaunchOptions") })

        assertEquals(SteamGameAction.UNCHANGED, SteamGames.plan(after, fuse, listOf(zelda), isFuse)!!.single().action)
        assertContentEquals(after, SteamGames.write(after, fuse, listOf(zelda), isFuse))
    }

    @Test
    fun aMovedFuseIsUpdatedInPlaceAndThePersonsChangesStay() {
        val old = SteamGames.write(null, "/home/deck/Downloads/Fuse-0.4.1.AppImage", listOf(zelda), isFuse)!!
        // The person renamed it in Steam and added an option of their own.
        val root = BinaryVdf.parse(old)!!
        val e = (root["shortcuts"] as BinaryVdf.Block).entries.single().second as BinaryVdf.Block
        e["AppName"] = BinaryVdf.Str("Minish Cap")
        e["LaunchOptions"] = BinaryVdf.Str("--run 12 --windowed")
        val edited = BinaryVdf.write(root)
        assertEquals(SteamGameAction.UPDATE, SteamGames.plan(edited, fuse, listOf(zelda), isFuse)!!.single().action)
        val after = entries(SteamGames.write(edited, fuse, listOf(zelda), isFuse)!!).single()
        assertEquals("\"$fuse\"", after.str("Exe"))
        assertEquals("Minish Cap", after.str("AppName"))
        assertEquals("--run 12 --windowed", after.str("LaunchOptions"))
    }

    @Test
    fun anOlderEntryThatOpensFusesWindowMovesToFastStartKeepingThePersonsOptions() {
        // Fuse 0.4.3 and before: `--play`, which opens Fuse's window and plays from it.
        val old = SteamShortcuts.add(theirs(), SteamShortcut(zelda.name, "\"$fuse\"", "\"/home/deck/Applications\"", launchOptions = "--play 12 --windowed"))!!
        assertEquals(mapOf(12L to SteamStart.THROUGH_WINDOW), SteamGames.starts(old, isFuse))
        assertEquals(listOf(SteamHealthIssue.Kind.THROUGH_WINDOW), SteamGames.health(old, fuse, setOf(12L), isFuse).map { it.kind })
        assertEquals(SteamGameAction.UPDATE, SteamGames.plan(old, fuse, listOf(zelda), isFuse)!!.single().action)
        val after = SteamGames.write(old, fuse, listOf(zelda), isFuse)!!
        assertEquals("--run 12 --windowed", entries(after).single { it.str("AppName") == zelda.name }.str("LaunchOptions"))
        assertEquals(mapOf(12L to SteamStart.FAST_START), SteamGames.starts(after, isFuse))
        assertTrue(SteamGames.health(after, fuse, setOf(12L), isFuse).isEmpty())
    }

    private val mgba = SteamDirect(listOf("/usr/bin/mgba-qt", "-f", "/home/deck/ROMs/gba/Minish Cap (USA).gba"), env = mapOf("QT_QPA_PLATFORM" to "xcb"))

    @Test
    fun aDirectEntryStartsTheEmulatorItselfAndIsKnownByFusesMark() {
        val direct = zelda.copy(direct = mgba)
        assertEquals(SteamGameAction.CREATE, SteamGames.plan(theirs(), fuse, listOf(direct), isFuse)!!.single().action)
        val after = SteamGames.write(theirs(), fuse, listOf(direct), isFuse)!!
        val ours = entries(after).single { it.str("AppName") == zelda.name }
        assertEquals("\"/usr/bin/mgba-qt\"", ours.str("Exe"))
        assertEquals("\"/usr/bin\"", ours.str("StartDir"))
        // Fuse's mark and the plan's variables, Steam's %command% (the emulator), then its arguments.
        assertEquals("FUSE_GAME=12 QT_QPA_PLATFORM=xcb %command% -f '/home/deck/ROMs/gba/Minish Cap (USA).gba'", ours.str("LaunchOptions"))
        assertEquals(mapOf(12L to SteamGames.newArtId(fuse, direct)), SteamGames.inSteam(after, isFuse))
        assertEquals(mapOf(12L to SteamStart.DIRECT), SteamGames.starts(after, isFuse))
        assertEquals(mapOf(SteamGames.newArtId(fuse, direct) to 12L), SteamGames.directIn(after, isFuse))
        // Written again, nothing changes.
        assertEquals(SteamGameAction.UNCHANGED, SteamGames.plan(after, fuse, listOf(direct), isFuse)!!.single().action)
        assertContentEquals(after, SteamGames.write(after, fuse, listOf(direct), isFuse))
        // Its command changing (another emulator chosen in Fuse) brings it up to date.
        val other = direct.copy(direct = SteamDirect(listOf("/usr/bin/retroarch", "-L", "/cores/mgba_libretro.so", "/roms/zelda.gba")))
        assertEquals(SteamGameAction.UPDATE, SteamGames.plan(after, fuse, listOf(other), isFuse)!!.single().action)
        val moved = entries(SteamGames.write(after, fuse, listOf(other), isFuse)!!).single { it.str("AppName") == zelda.name }
        assertEquals("FUSE_GAME=12 %command% -L /cores/mgba_libretro.so /roms/zelda.gba", moved.str("LaunchOptions"))
    }

    @Test
    fun anEntrySwitchesBetweenFastStartAndDirectKeepingItsArtAndTheNamePersonGaveIt() {
        val fast = SteamGames.write(null, fuse, listOf(zelda), isFuse)!!
        val root = BinaryVdf.parse(fast)!!
        ((root["shortcuts"] as BinaryVdf.Block).entries.single().second as BinaryVdf.Block)["AppName"] = BinaryVdf.Str("Minish Cap")
        val renamed = BinaryVdf.write(root)
        val id = SteamGames.inSteam(renamed, isFuse).getValue(12)
        val direct = SteamGames.write(renamed, fuse, listOf(zelda.copy(direct = mgba)), isFuse)!!
        val d = entries(direct).single()
        assertEquals("Minish Cap", d.str("AppName"))
        assertEquals("\"/usr/bin/mgba-qt\"", d.str("Exe"))
        // The same id, so the art Steam shows for it stays.
        assertEquals(mapOf(12L to id), SteamGames.inSteam(direct, isFuse))
        val back = entries(SteamGames.write(direct, fuse, listOf(zelda), isFuse)!!).single()
        assertEquals("\"$fuse\"", back.str("Exe"))
        assertEquals("--run 12", back.str("LaunchOptions"))
        assertEquals("\"/home/deck/Applications\"", back.str("StartDir"))
    }

    @Test
    fun anotherToolsEmulatorEntryIsNeverFusesWithoutTheMark() {
        // Steam ROM Manager's Metroid Fusion starts RetroArch through Flatpak: never Fuse's.
        val before = theirs()
        assertTrue(SteamGames.inSteam(before, isFuse).isEmpty())
        assertEquals(SteamGameAction.ELSEWHERE, SteamGames.plan(before, fuse, listOf(metroid.copy(direct = mgba)), isFuse)!!.single().action)
        // The mark counts only before %command%, where Steam sets variables.
        assertNull(SteamGames.directId("%command% FUSE_GAME=15"))
        assertNull(SteamGames.directId("FUSE_GAME=15 -f game.gba"))
        assertEquals(15L, SteamGames.directId("FUSE_GAME=15 %command% -f game.gba"))
        assertContentEquals(before, SteamGames.remove(before, null, isFuse))
    }

    @Test
    fun directEntriesAreTakenOutAndLookedOverLikeTheOthers() {
        val all = SteamGames.write(theirs(), fuse, listOf(zelda.copy(direct = mgba), SteamGameEntry(3, "Chrono Trigger", "Super Nintendo")), isFuse)!!
        assertEquals(listOf(SteamHealthIssue.Kind.EMULATOR_GONE), SteamGames.health(all, fuse, setOf(12L, 3L), isFuse, exists = { it != "/usr/bin/mgba-qt" }).map { it.kind })
        assertTrue(SteamGames.health(all, fuse, setOf(12L, 3L), isFuse, exists = { true }).isEmpty())
        assertEquals(listOf(SteamHealthIssue.Kind.GAME_GONE), SteamGames.health(all, fuse, setOf(3L), isFuse).map { it.kind })
        assertEquals(1, SteamGames.byCollection(all, setOf("Game Boy Advance"), isFuse).getValue("Game Boy Advance").size)
        val gone = SteamGames.remove(all, setOf(12L), isFuse)!!
        assertEquals(listOf("Firefox", "Metroid Fusion", "Chrono Trigger"), entries(gone).map { it.str("AppName") })
        assertEquals(listOf("Firefox", "Metroid Fusion"), entries(SteamGames.repair(all, emptySet(), isFuse)!!).map { it.str("AppName") })
    }

    @Test
    fun aDirectEntryThatNoLongerStartsItsGameAsPlayWouldIsFoundAndAnUnreachableGameSaidAsSuch() {
        val all = SteamGames.write(theirs(), fuse, listOf(zelda.copy(direct = mgba), SteamGameEntry(3, "Chrono Trigger", "Super Nintendo")), isFuse)!!
        // As written: nothing wrong.
        assertTrue(SteamGames.health(all, fuse, setOf(12L, 3L), isFuse, wanted = mapOf(12L to zelda.copy(direct = mgba))).isEmpty())
        // The emulator updated to another file, the game moved to another drive, or the game now starts with Fast Start.
        val updated = zelda.copy(direct = mgba.copy(argv = listOf("/usr/bin/mgba-qt-0.11", "-f", "/home/deck/ROMs/gba/Minish Cap (USA).gba")))
        val moved = zelda.copy(direct = mgba.copy(argv = listOf("/usr/bin/mgba-qt", "-f", "/run/media/deck/SD/gba/Minish Cap (USA).gba")))
        for (now in listOf(updated, moved, zelda)) {
            assertEquals(listOf(SteamHealthIssue.Kind.OUT_OF_DATE), SteamGames.health(all, fuse, setOf(12L, 3L), isFuse, wanted = mapOf(12L to now)).map { it.kind })
        }
        // Its files on a drive that isn't there: said, whatever kind of entry it has.
        assertEquals(
            listOf(SteamHealthIssue.Kind.GAME_UNAVAILABLE, SteamHealthIssue.Kind.GAME_UNAVAILABLE),
            SteamGames.health(all, fuse, setOf(12L, 3L), isFuse, wanted = mapOf(12L to moved), unavailable = setOf(12L, 3L)).map { it.kind },
        )
    }

    @Test
    fun argumentsAreQuotedAsAShellReadsThemBack() {
        assertEquals("/roms/zelda.gba", SteamGames.shell("/roms/zelda.gba"))
        assertEquals("'/roms/Minish Cap.gba'", SteamGames.shell("/roms/Minish Cap.gba"))
        assertEquals("'it'\\''s'", SteamGames.shell("it's"))
        assertEquals("''", SteamGames.shell(""))
        assertEquals("'\$HOME'", SteamGames.shell("\$HOME"))
        assertTrue(SteamGames.canWrite(mgba))
        assertFalse(SteamGames.canWrite(SteamDirect(emptyList())))
        assertFalse(SteamGames.canWrite(SteamDirect(listOf("/usr/bin/x"), env = mapOf("BAD NAME" to "1"))))
    }

    @Test
    fun removingTakesOutOnlyFusesGameEntries() {
        val withFuse = SteamShortcuts.add(theirs(), SteamShortcut("Fuse", "\"$fuse\"", "\"/home/deck/Applications\"", launchOptions = "--fullscreen"))!!
        val all = SteamGames.write(withFuse, fuse, listOf(zelda), isFuse)!!
        assertEquals(mapOf(12L to SteamGames.newArtId(fuse, zelda)), SteamGames.inSteam(all, isFuse))
        val removed = SteamGames.remove(all, setOf(12L), isFuse)!!
        assertEquals(listOf("Firefox", "Metroid Fusion", "Fuse"), entries(removed).map { it.str("AppName") })
        assertTrue(SteamGames.inSteam(removed, isFuse).isEmpty())
        // Nothing of Fuse's left: the same bytes back.
        assertContentEquals(removed, SteamGames.remove(removed, null, isFuse))
    }

    @Test
    fun healthFindsGamesGoneOldProgramsAndDoubles() {
        val a = SteamGames.write(null, "/old/Fuse.AppImage", listOf(zelda, metroid), isFuse)!!
        // A second entry for Zelda, made by hand from a copy.
        val root = BinaryVdf.parse(a)!!
        val list = root["shortcuts"] as BinaryVdf.Block
        val copy = BinaryVdf.parse(BinaryVdf.write(BinaryVdf.Block(mutableListOf("x" to list.entries[0].second))))!!["x"]!!
        list.entries += "2" to copy
        val issues = SteamGames.health(BinaryVdf.write(root), fuse, library = setOf(12L), isFuse)
        assertEquals(
            listOf(SteamHealthIssue.Kind.PROGRAM_MOVED, SteamHealthIssue.Kind.GAME_GONE, SteamHealthIssue.Kind.DUPLICATE),
            issues.map { it.kind },
        )
    }

    @Test
    fun repairTakesOutOnlyWhatIsWrongAndKeepsTheFirstOfADouble() {
        val a = SteamGames.write(theirs(), fuse, listOf(zelda, metroid), isFuse)!!
        val root = BinaryVdf.parse(a)!!
        val list = root["shortcuts"] as BinaryVdf.Block
        // The person renamed Zelda's entry, and a second Zelda entry came from somewhere.
        val first = list.entries.map { it.second as BinaryVdf.Block }.first { it.str("LaunchOptions") == "--run 12" }
        first["AppName"] = BinaryVdf.Str("Minish Cap")
        list.entries += "9" to BinaryVdf.Block(mutableListOf("AppName" to BinaryVdf.Str("Zelda again"), "Exe" to BinaryVdf.Str("\"$fuse\""), "LaunchOptions" to BinaryVdf.Str("--play 12")))
        val fixed = SteamGames.repair(BinaryVdf.write(root), library = setOf(12L), isFuse)!!
        // Metroid's Fuse entry is gone (not in the library); Steam ROM Manager's Metroid stays.
        assertEquals(listOf("Firefox", "Metroid Fusion", "Minish Cap"), entries(fixed).map { it.str("AppName") })
        assertEquals("\"/usr/bin/flatpak\"", entries(fixed)[1].str("Exe"))
        assertContentEquals(fixed, SteamGames.repair(fixed, setOf(12L), isFuse))
    }

    @Test
    fun anUnreadableListIsNeverWritten() {
        assertNull(SteamGames.plan(byteArrayOf(9, 9, 9), fuse, listOf(zelda), isFuse))
        assertNull(SteamGames.write(byteArrayOf(9, 9, 9), fuse, listOf(zelda), isFuse))
    }

    @Test
    fun collectionsKeepEverythingElseAndWhatThePersonTookOut() {
        val existing = """[["user-collections.uc-abc",{"key":"user-collections.uc-abc","timestamp":1,"value":"{\"id\":\"uc-abc\",\"name\":\"Mine\",\"added\":[7],\"removed\":[]}","version":"1"}],""" +
            """["showcases.1",{"key":"showcases.1","timestamp":1,"value":"{}","version":"1"}]]"""
        val once = SteamCollections.write(existing, mapOf("Game Boy Advance" to listOf(111L, 222L)), now = 100)!!
        val gba = SteamCollections.idOf("Game Boy Advance")
        val parsed = Json.parseToJsonElement(once).jsonArray
        assertEquals(3, parsed.size)
        assertEquals(Json.parseToJsonElement(existing).jsonArray[0], parsed[0])
        val value = Json.parseToJsonElement(parsed[2].jsonArray[1].jsonObject["value"]!!.jsonPrimitive.content).jsonObject
        assertEquals("Game Boy Advance", value["name"]!!.jsonPrimitive.content)
        assertEquals(listOf(111L, 222L), value["added"]!!.jsonArray.map { it.jsonPrimitive.content.toLong() })
        assertEquals("user-collections.$gba", parsed[2].jsonArray[0].jsonPrimitive.content)

        // The person took 222 out in Steam: it stays out when Fuse writes again.
        val edited = once.replace("\\\"removed\\\":[]", "\\\"removed\\\":[222]")
        val again = SteamCollections.write(edited, mapOf("Game Boy Advance" to listOf(111L, 222L)), now = 200)!!
        val v2 = Json.parseToJsonElement(Json.parseToJsonElement(again).jsonArray[2].jsonArray[1].jsonObject["value"]!!.jsonPrimitive.content).jsonObject
        assertEquals(listOf(111L), v2["added"]!!.jsonArray.map { it.jsonPrimitive.content.toLong() })

        // No longer wanted: marked deleted, never dropped.
        val gone = Json.parseToJsonElement(SteamCollections.write(again, emptyMap(), now = 300)!!).jsonArray
        assertEquals(3, gone.size)
        assertEquals("true", gone[2].jsonArray[1].jsonObject["is_deleted"]!!.jsonPrimitive.content)
        assertNull(SteamCollections.write("not json", emptyMap(), 1))
        assertNotNull(SteamCollections.write(null, mapOf("SNES" to listOf(1L)), 1))
    }

    @Test
    fun collectionsAreReadBackFromTheEntriesAndFromSteam() {
        val bytes = SteamGames.write(null, fuse, listOf(zelda, metroid, SteamGameEntry(3, "Chrono Trigger", "Super Nintendo")), isFuse)!!
        val by = SteamGames.byCollection(bytes, setOf("Game Boy Advance", "Super Nintendo", "Empty"), isFuse)
        assertEquals(2, by.getValue("Game Boy Advance").size)
        assertEquals(1, by.getValue("Super Nintendo").size)
        assertEquals(emptyList<Long>(), by.getValue("Empty"))
        val text = SteamCollections.write(null, by.filterValues { it.isNotEmpty() }, now = 5)!!
        assertEquals(setOf("Game Boy Advance", "Super Nintendo"), SteamCollections.fuseNames(text))
        assertEquals(setOf("Super Nintendo"), SteamCollections.fuseNames(SteamCollections.write(text, mapOf("Super Nintendo" to listOf(1L)), now = 6)))
    }

    @Test
    fun theNewestNamespaceHoldsTheCollections() {
        assertEquals(3, SteamCollections.activeNamespace("""[[1,"798"],[3,"901"]]"""))
        assertEquals(1, SteamCollections.activeNamespace("""[[1,"798"],[3,"0"]]"""))
        assertEquals(1, SteamCollections.activeNamespace(null))
    }

    @Test
    fun eachPictureIsCheckedForTheShapeSteamShowsItIn() {
        assertTrue(SteamArtShape.fits("portrait", 600, 900))
        assertTrue(SteamArtShape.fits("portrait", 512, 720), "a box a little wider than 2:3")
        assertFalse(SteamArtShape.fits("portrait", 640, 480), "a landscape box")
        assertTrue(SteamArtShape.fits("wide", 920, 430))
        assertFalse(SteamArtShape.fits("wide", 1920, 1080), "a screenshot")
        assertTrue(SteamArtShape.fits("hero", 3840, 1240))
        assertFalse(SteamArtShape.fits("hero", 1920, 1080))
        assertTrue(SteamArtShape.fits("icon", 256, 256))
        assertFalse(SteamArtShape.fits("icon", 256, 192))
        assertTrue(SteamArtShape.fits("logo", 800, 200), "a logo has no shape of its own")
        assertFalse(SteamArtShape.fits("portrait", 0, 900))
    }
}
