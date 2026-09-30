package io.github.matiyaaa.fuse.library.scan

import io.github.matiyaaa.fuse.library.InMemoryFileSystem
import io.github.matiyaaa.fuse.library.InMemoryFolderStateStore
import io.github.matiyaaa.fuse.model.FolderInterpretation
import io.github.matiyaaa.fuse.model.LibrarySource
import io.github.matiyaaa.fuse.model.LibrarySourceId
import io.github.matiyaaa.fuse.model.LibrarySourceKind
import io.github.matiyaaa.fuse.model.MediaKind
import io.github.matiyaaa.fuse.model.PlatformFolderScan
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.model.ScanPhase
import io.github.matiyaaa.fuse.model.ScanProgress
import io.github.matiyaaa.fuse.model.ScanReport
import io.github.matiyaaa.fuse.model.ScanScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LibraryScannerTest {
    private val fs = InMemoryFileSystem()
    private val state = InMemoryFolderStateStore()
    private val scanner = LibraryScanner(fs, state)

    private fun source(path: String, kind: LibrarySourceKind, id: Long = 1) =
        LibrarySource(LibrarySourceId(id), path, "Library $id", kind)

    private suspend fun scan(
        vararg sources: LibrarySource,
        scope: ScanScope = ScanScope.FULL,
        platform: PlatformId? = null,
        mediaRoots: List<String> = emptyList(),
        sourcePlatforms: Map<LibrarySourceId, PlatformId> = emptyMap(),
    ): ScanReport = scanner.scan(ScanRequest(sources.toList(), scope, platform, mediaRoots = mediaRoots, sourcePlatforms = sourcePlatforms))

    private fun ScanReport.of(platform: String): PlatformFolderScan = scanned.single { it.platformId.value == platform }

    @Test
    fun rommStructureA() = runTest {
        fs.file("/lib/roms/psx/Crash (USA).chd")
        fs.file("/lib/roms/snes/Mario (USA).sfc")
        fs.file("/lib/roms/mystery/thing.bin")
        fs.file("/lib/bios/psx/scph5501.bin")
        fs.file("/lib/assets/cover.png")

        val report = scan(source("/lib", LibrarySourceKind.ROMM_LIBRARY))
        assertEquals(setOf("psx", "snes"), report.scanned.map { it.platformId.value }.toSet())
        assertEquals("/lib/roms/psx", report.of("psx").folderPath)
        assertEquals(listOf("Crash (USA)"), report.of("psx").games.map { it.title })
        assertEquals(listOf("mystery"), report.unknownFolders.map { it.name })
        assertTrue(report.of("psx").complete)
    }

    @Test
    fun rommStructureB() = runTest {
        fs.file("/lib/psx/roms/Crash (USA).chd")
        fs.file("/lib/psx/bios/scph5501.bin")
        fs.file("/lib/gba/roms/Pokemon Emerald/Pokemon Emerald (USA).gba")
        fs.file("/lib/gba/roms/Pokemon Emerald/dlc/Extra.gba")

        val report = scan(source("/lib", LibrarySourceKind.ROMM_LIBRARY))
        assertEquals("/lib/psx/roms", report.of("psx").folderPath)
        val emerald = report.of("gba").games.single()
        assertEquals(FolderInterpretation.MULTI_FILE_GAME, emerald.interpretation)
        assertEquals(1, emerald.content.size)
        assertTrue(report.unknownFolders.isEmpty())
    }

    @Test
    fun esDeRootWithSystemNamesAndUnknownFolders() = runTest {
        fs.file("/ROMs/megadrive/Sonic (USA, Europe).md")
        fs.file("/ROMs/n3ds/Zelda (USA).3ds")
        fs.file("/ROMs/pcenginecd/Ys (Japan).chd")
        fs.file("/ROMs/bios/scph5501.bin")
        fs.file("/ROMs/.stfolder/x")
        fs.file("/ROMs/Holiday Photos/a.jpg")

        val report = scan(source("/ROMs", LibrarySourceKind.ROMS_ROOT))
        assertEquals(setOf("genesis", "3ds", "turbografx-cd"), report.scanned.map { it.platformId.value }.toSet())
        assertEquals(listOf("Holiday Photos"), report.unknownFolders.map { it.name })
    }

    @Test
    fun platformFolderSourcesResolveFromNameParentOrExplicitChoice() = runTest {
        fs.file("/games/snes/Mario.sfc")
        fs.file("/lib/psx/roms/Crash.chd")
        fs.file("/stuff/My Sony Games/Spyro.chd")
        fs.file("/stuff/Unknown/x.zip")

        val report = scan(
            source("/games/snes", LibrarySourceKind.PLATFORM_FOLDER, 1),
            source("/lib/psx/roms", LibrarySourceKind.PLATFORM_FOLDER, 2),
            source("/stuff/My Sony Games", LibrarySourceKind.PLATFORM_FOLDER, 3),
            source("/stuff/Unknown", LibrarySourceKind.PLATFORM_FOLDER, 4),
            sourcePlatforms = mapOf(LibrarySourceId(3) to PlatformId("psx")),
        )
        assertEquals(listOf("Mario"), report.of("snes").games.map { it.title })
        assertEquals(setOf("Crash", "Spyro"), report.scanned.filter { it.platformId.value == "psx" }.flatMap { it.games }.map { it.title }.toSet())
        assertEquals(listOf("/stuff/Unknown"), report.unknownFolders.map { it.path })
    }

    @Test
    fun shortcutsSplitIntoSteamAndWindowsByContent() = runTest {
        fs.file("/shortcuts/Portal 2.steam", content = "620")
        fs.file("/shortcuts/Hades.desktop", content = "[Desktop Entry]\nName=Hades\nExec=steam steam://rungameid/1145360\n")
        fs.file("/shortcuts/Witcher 3.desktop", content = "[Desktop Entry]\nExec=env WINEPREFIX=... wine witcher3.exe\n[Extra Data]\ncontainer_id=1\n")
        fs.file("/shortcuts/Cyberpunk 2077.gog", content = "1423049311")
        fs.file("/shortcuts/readme.txt", content = "hi")
        fs.file("/shortcuts/Epic/Fortnite.epic", content = "abc")

        val report = scan(source("/shortcuts", LibrarySourceKind.SHORTCUTS))
        assertEquals(setOf("Portal 2", "Hades"), report.of("steam").games.map { it.title }.toSet())
        assertEquals(setOf("Witcher 3", "Cyberpunk 2077", "Fortnite"), report.of("win").games.map { it.title }.toSet())
        assertTrue(report.scanned.all { it.folderPath == "/shortcuts" && it.complete })
    }

    @Test
    fun quickScanSkipsUnchangedFoldersAndCatchesNestedChanges() = runTest {
        fs.file("/ROMs/snes/Chrono Trigger (USA).sfc")
        fs.file("/ROMs/snes/Hacks/Kaizo Mario.sfc")
        fs.file("/ROMs/snes/Hacks/Zelda Redux.sfc")
        fs.file("/ROMs/gba/Metroid Fusion (USA).gba")
        val roms = source("/ROMs", LibrarySourceKind.ROMS_ROOT)

        val first = scan(roms, scope = ScanScope.QUICK)
        assertEquals(2, first.scanned.size)
        assertTrue(first.unchanged.isEmpty())

        val callsBefore = fs.listCalls
        val second = scan(roms, scope = ScanScope.QUICK)
        assertTrue(second.scanned.isEmpty())
        assertEquals(setOf("snes", "gba"), second.unchanged.map { it.platformId?.value }.toSet())
        // Only the root and the two platform folders were listed; nothing below them.
        assertEquals(3, fs.listCalls - callsBefore)

        // A new file inside an organisational folder changes Hacks/ but not snes/.
        val snesTime = fs.mtime("/ROMs/snes")
        fs.file("/ROMs/snes/Hacks/New Hack.sfc")
        assertEquals(snesTime, fs.mtime("/ROMs/snes"))
        val third = scan(roms, scope = ScanScope.QUICK)
        assertEquals(listOf("snes"), third.scanned.map { it.platformId.value })
        assertTrue(third.of("snes").games.any { it.title == "New Hack" })
        assertEquals(listOf("gba"), third.unchanged.map { it.platformId?.value })
    }

    @Test
    fun quickScanMissesChangesTwoLevelsDownButFullScanFindsThem() = runTest {
        fs.file("/ROMs/psx/Europe/Final Fantasy VII/FF7 (Disc 1).chd")
        fs.file("/ROMs/psx/Europe/Final Fantasy VII/FF7 (Disc 2).chd")
        fs.file("/ROMs/psx/Europe/Crash (Europe).chd")
        val roms = source("/ROMs", LibrarySourceKind.ROMS_ROOT)
        scan(roms, scope = ScanScope.QUICK)

        fs.file("/ROMs/psx/Europe/Final Fantasy VII/FF7 (Disc 3).chd")
        val quick = scan(roms, scope = ScanScope.QUICK)
        assertTrue(quick.scanned.isEmpty(), "documented limitation: nested changes need a FULL scan")

        val full = scan(roms, scope = ScanScope.FULL)
        assertEquals(3, full.of("psx").games.single { it.title == "Final Fantasy VII" }.discs.size)
    }

    @Test
    fun incompleteScansAreNotRememberedSoTheyAreRetried() = runTest {
        fs.file("/ROMs/gba/Good.gba")
        fs.file("/ROMs/gba/Locked/Secret.gba")
        fs.makeUnreadable("/ROMs/gba/Locked")
        val roms = source("/ROMs", LibrarySourceKind.ROMS_ROOT)

        val first = scan(roms, scope = ScanScope.QUICK)
        assertFalse(first.of("gba").complete)
        assertTrue(first.errors.any { it.contains("Locked") })
        val second = scan(roms, scope = ScanScope.QUICK)
        assertEquals(1, second.scanned.size)
    }

    @Test
    fun platformScopeOnlyScansThatPlatform() = runTest {
        fs.file("/ROMs/snes/Mario.sfc")
        fs.file("/ROMs/gba/Metroid.gba")
        fs.file("/ROMs/Unknown Stuff/x")
        val report = scan(source("/ROMs", LibrarySourceKind.ROMS_ROOT), scope = ScanScope.PLATFORM, platform = PlatformId("gba"))
        assertEquals(listOf("gba"), report.scanned.map { it.platformId.value })
        assertTrue(report.unknownFolders.isEmpty())
    }

    @Test
    fun findsLocalMediaInEsDeAndBatoceraLayouts() = runTest {
        fs.file("/ROMs/snes/Chrono Trigger (USA).sfc")
        fs.file("/ROMs/snes/media/covers/Chrono Trigger (USA).png")
        fs.file("/ROMs/snes/media/marquees/Chrono Trigger (USA).png")
        fs.file("/ROMs/snes/media/squares/Chrono Trigger (USA).png")
        fs.file("/ROMs/snes/videos/Chrono Trigger (USA).mp4")
        fs.file("/ROMs/snes/Europe/Secret of Mana (Europe).sfc")
        fs.file("/ROMs/snes/Europe/Other (Europe).sfc")
        fs.file("/ES-DE/downloaded_media/snes/screenshots/Chrono Trigger (USA).png")
        fs.file("/ES-DE/downloaded_media/snes/fanart/Chrono Trigger (USA).jpg")
        fs.file("/ES-DE/downloaded_media/snes/covers/Europe/Secret of Mana (Europe).png")
        fs.file("/ROMs/gba/Pokemon Emerald.gba")
        fs.file("/ROMs/gba/images/Pokemon Emerald-thumb.png")
        fs.file("/ROMs/gba/images/Pokemon Emerald-marquee.png")
        fs.file("/ROMs/gba/images/Pokemon Emerald-square.png")
        fs.file("/ROMs/gba/images/Pokemon Emerald-notes.txt")

        val report = scan(source("/ROMs", LibrarySourceKind.ROMS_ROOT), mediaRoots = listOf("/ES-DE/downloaded_media"))
        val chrono = report.of("snes").games.single { it.title == "Chrono Trigger (USA)" }
        assertEquals("/ROMs/snes/media/covers/Chrono Trigger (USA).png", chrono.localMedia[MediaKind.BOXART])
        assertEquals("/ROMs/snes/media/marquees/Chrono Trigger (USA).png", chrono.localMedia[MediaKind.LOGO])
        assertEquals("/ROMs/snes/media/squares/Chrono Trigger (USA).png", chrono.localMedia[MediaKind.SQUARE])
        assertEquals("/ROMs/snes/videos/Chrono Trigger (USA).mp4", chrono.localMedia[MediaKind.VIDEO])
        assertEquals("/ES-DE/downloaded_media/snes/screenshots/Chrono Trigger (USA).png", chrono.localMedia[MediaKind.SCREENSHOT])
        assertEquals("/ES-DE/downloaded_media/snes/fanart/Chrono Trigger (USA).jpg", chrono.localMedia[MediaKind.HERO])
        val mana = report.of("snes").games.single { it.title == "Secret of Mana (Europe)" }
        assertEquals("/ES-DE/downloaded_media/snes/covers/Europe/Secret of Mana (Europe).png", mana.localMedia[MediaKind.BOXART])
        assertTrue(report.of("snes").games.single { it.title == "Other (Europe)" }.localMedia.isEmpty())

        val emerald = report.of("gba").games.single()
        assertEquals("/ROMs/gba/images/Pokemon Emerald-thumb.png", emerald.localMedia[MediaKind.BOXART])
        assertEquals("/ROMs/gba/images/Pokemon Emerald-marquee.png", emerald.localMedia[MediaKind.LOGO])
        assertEquals("/ROMs/gba/images/Pokemon Emerald-square.png", emerald.localMedia[MediaKind.SQUARE])
        // Media folders are never games.
        assertEquals(1, report.of("gba").games.size)
    }

    @Test
    fun reportsProgressAndCompletesAsAFlow() = runTest {
        fs.file("/ROMs/snes/Mario.sfc")
        fs.file("/ROMs/snes/Hacks/A.sfc")
        fs.file("/ROMs/snes/Hacks/B Other.sfc")
        val events = scanner.scanAsFlow(ScanRequest(listOf(source("/ROMs", LibrarySourceKind.ROMS_ROOT)), ScanScope.FULL)).toList()
        val progress = events.filterIsInstance<ScanEvent.Progress>().map { it.progress }
        assertEquals(ScanPhase.DISCOVERING, progress.first().phase)
        assertEquals(ScanPhase.DONE, progress.last().phase)
        assertTrue(progress.any { it.phase == ScanPhase.SCANNING && it.currentPath == "/ROMs/snes/Hacks" })
        assertEquals(3, progress.last().gamesFound)
        val finished = assertIs<ScanEvent.Finished>(events.last())
        assertEquals(3, finished.report.of("snes").games.size)
    }

    @Test
    fun scanIsCancellable() = runTest {
        for (i in 1..20) fs.file("/ROMs/snes/Folder $i/Game $i.sfc")
        val seen = ArrayList<ScanProgress>()
        val job = launch {
            scanner.scan(ScanRequest(listOf(source("/ROMs", LibrarySourceKind.ROMS_ROOT)), ScanScope.FULL)) { progress ->
                seen += progress
                if (seen.size == 3) currentCoroutineContext().cancel()
            }
        }
        job.join()
        assertTrue(job.isCancelled)
        assertTrue(seen.none { it.phase == ScanPhase.DONE })
        assertTrue(state.values.isEmpty())
    }

    @Test
    fun missingSourceIsAnError() = runTest {
        val report = scan(source("/nowhere", LibrarySourceKind.ROMS_ROOT))
        assertTrue(report.scanned.isEmpty())
        assertTrue(report.errors.single().contains("/nowhere"))
    }
}
