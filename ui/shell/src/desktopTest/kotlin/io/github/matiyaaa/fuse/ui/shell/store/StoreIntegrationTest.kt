package io.github.matiyaaa.fuse.ui.shell.store

import io.github.matiyaaa.fuse.data.FuseData
import io.github.matiyaaa.fuse.data.db.DesktopDatabase
import io.github.matiyaaa.fuse.model.AppEntry
import io.github.matiyaaa.fuse.model.AppFilter
import io.github.matiyaaa.fuse.model.AppKind
import io.github.matiyaaa.fuse.model.CartridgeStatus
import io.github.matiyaaa.fuse.model.CollectionKind
import io.github.matiyaaa.fuse.model.EmulatorId
import io.github.matiyaaa.fuse.model.Host
import io.github.matiyaaa.fuse.model.InstalledEmulator
import io.github.matiyaaa.fuse.model.LaunchPlan
import io.github.matiyaaa.fuse.model.LaunchTarget
import io.github.matiyaaa.fuse.model.LibrarySourceKind
import io.github.matiyaaa.fuse.model.MediaFillMode
import io.github.matiyaaa.fuse.model.MediaKind
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.model.ScanPhase
import io.github.matiyaaa.fuse.model.ScanScope
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * The store end to end on a real (temporary) folder and an in-memory database: scanning, platform
 * cards, launching through a fake launcher, honest play sessions, generated playlists in the cache
 * and persisted preferences. Nothing touches the network.
 */
class StoreIntegrationTest {
    private lateinit var root: File
    private lateinit var cache: File
    private lateinit var scope: CoroutineScope

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("fuse-lib").toFile()
        cache = Files.createTempDirectory("fuse-cache").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        File(root, "gba").mkdirs()
        File(root, "gba/Advance Wars (USA).gba").writeBytes(ByteArray(1024) { it.toByte() })
        File(root, "psx").mkdirs()
        File(root, "psx/Final Fantasy VII (USA) (Disc 1).chd").writeText("disc1")
        File(root, "psx/Final Fantasy VII (USA) (Disc 2).chd").writeText("disc2")
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        root.deleteRecursively()
        cache.deleteRecursively()
    }

    @Test
    fun addedThemesAreKeptUsedAndForgotten(): Unit = runBlocking {
        val services = FakeServices(FuseData(DesktopDatabase.open(freshDb())), cache)
        val store = createFuseStore(services, scope)
        val text = """{"name": "Night Ember", "extends": "starlight", "colors": {"accent": "#FF7A59"}}"""
        val parsed = assertIs<io.github.matiyaaa.fuse.model.ThemeCodec.Imported>(store.themes.parse(text))
        store.themes.add(parsed.spec, text, source = null, apply = true)
        withTimeout(5_000) { store.prefs.first { it.themeId == "custom.night-ember" } }
        assertEquals(0xFFFF7A59, store.prefs.value.theme.palette.accent)
        assertEquals(io.github.matiyaaa.fuse.model.BackgroundStyle.STARS, store.prefs.value.theme.background)
        // Kept as it was written, and in use after a restart.
        withTimeout(5_000) { while (services.data.settings.current().appearance.themeId != "custom.night-ember") kotlinx.coroutines.delay(20) }
        assertEquals(text, services.data.settings.current().appearance.customThemes.single().json)
        val again = createFuseStore(services, scope)
        assertEquals("Night Ember", again.prefs.value.theme.name)
        // Removing the theme in use goes back to Fuse's own.
        store.themes.remove("custom.night-ember")
        assertEquals("fuse", store.prefs.value.themeId)
        assertTrue(store.prefs.value.customThemes.isEmpty())
        withTimeout(5_000) { while (services.data.settings.current().appearance.themeId != "fuse") kotlinx.coroutines.delay(20) }
        assertTrue(services.data.settings.current().appearance.customThemes.isEmpty())
    }

    @Test
    fun themesComeFromSharedLinks(): Unit = runBlocking {
        val services = FakeServices(FuseData(DesktopDatabase.open(freshDb())), cache)
        val store = createFuseStore(services, scope)
        // A GitHub page link leads to the file behind it.
        val text = store.themes.fetch("https://github.com/someone/themes/blob/main/ember.json").getOrThrow()
        assertEquals("Ember", assertIs<io.github.matiyaaa.fuse.model.ThemeCodec.Imported>(store.themes.parse(text)).spec.name)
        assertTrue(store.themes.fetch("https://raw.githubusercontent.com/someone/themes/main/big.json").isFailure, "over 64 KB")
        assertTrue(store.themes.fetch("http://example.com/theme.json").isFailure, "not https")
        assertTrue(store.themes.fetch("https://raw.githubusercontent.com/someone/themes/main/missing.json").isFailure)
    }

    @Test
    fun scansLaunchesAndTracksPlaytime() = runBlocking {
        val services = FakeServices(FuseData(DesktopDatabase.open(freshDb())), cache)
        val store = createFuseStore(services, scope)

        store.sources.add(root.absolutePath, LibrarySourceKind.ROMS_ROOT)
        awaitScan(store)

        val systems = withTimeout(10_000) { store.library.platforms.first { it.size == 2 } }
        assertEquals(setOf("gba", "psx"), systems.map { it.platform.id.value }.toSet())
        val gba = systems.first { it.platform.id.value == "gba" }
        assertEquals(1, gba.gameCount)
        assertTrue(gba.emulatorInstalled, "mGBA is installed and should be chosen automatically")

        val games = store.library.games(GameQuery(platform = PlatformId("gba"))).first()
        val game = games.single()
        assertTrue(game.title.startsWith("Advance Wars"), game.title)

        // Launch: the fake launcher gets mGBA's command and the session stays open until it exits.
        val exit = CompletableDeferred<Unit>()
        services.exit = exit
        val stages = mutableListOf<io.github.matiyaaa.fuse.ui.shell.store.LaunchStage>()
        val outcome = store.library.launch(game.id) { stages += it }
        assertIs<LaunchOutcome.Started>(outcome)
        // Without Fuse Sync or Syncthing there is no save to check: it simply starts (and the veil says so).
        assertEquals(listOf(io.github.matiyaaa.fuse.ui.shell.store.LaunchStage.STARTING), stages)
        val plan = assertIs<LaunchPlan.Command>(services.launched.single().plan)
        assertEquals(EmulatorId("linux.mgba"), plan.emulatorId)
        assertTrue(plan.argv.any { it.endsWith("Advance Wars (USA).gba") }, plan.argv.toString())
        val open = services.data.playSessions.openSession().first()
        assertEquals(game.id, open?.gameId)

        exit.complete(Unit)
        withTimeout(10_000) { services.data.playSessions.openSession().first { it == null } }
        val played = assertNotNull(services.data.games.summary(game.id))
        assertNotNull(played.lastPlayedAt)
        assertEquals(1, played.sessions)
    }

    @Test
    fun cartridgeGamesGetRommDetailsWithoutTouchingTheUsersOwn(): Unit = runBlocking {
        val services = FakeServices(FuseData(DesktopDatabase.open(freshDb())), cache)
        val store = createFuseStore(services, scope)
        store.sources.add(root.absolutePath, LibrarySourceKind.ROMS_ROOT)
        awaitScan(store)
        val wars = store.library.games(GameQuery(platform = PlatformId("gba"))).first().single()
        val ff7 = store.library.games(GameQuery(platform = PlatformId("psx"))).first().single()
        // The user already picked their own cover for Final Fantasy VII.
        store.media.setFromFile(io.github.matiyaaa.fuse.model.MediaOwner.OfGame(ff7.id), MediaKind.BOXART, "/home/me/ff7.png")

        // Cartridge's pictures: a cover and a logo it has, and one it lost.
        val pictures = File(root.parentFile, "cartridge-" + root.name).apply { mkdirs() }
        val awCover = File(pictures, "aw").apply { writeBytes(PNG + byteArrayOf(1)) }
        val awLogo = File(pictures, "77-r.png").apply { writeBytes(PNG + byteArrayOf(2)) }
        services.cartridgeGames = listOf(
            io.github.matiyaaa.fuse.model.CartridgeGame(
                romId = 77, path = "file://" + File(root, "gba/Advance Wars (USA).gba").absolutePath.replace(" ", "%20"),
                title = "Advance Wars", platformSlug = "gba", summary = "Orange Star goes to war.", year = 2001,
                genres = listOf("Strategy"), developer = "Intelligent Systems", publisher = "Nintendo", rating = 91,
                players = "1-4", series = listOf("Wars"), cover = awCover.absolutePath, logo = awLogo.absolutePath,
                screenshot = File(pictures, "gone.png").absolutePath,
                updatedAt = 10,
            ),
            // Cartridge saved the two discs as a folder; Fuse lists the game by its discs.
            io.github.matiyaaa.fuse.model.CartridgeGame(
                romId = 78, path = File(root, "psx").absolutePath + "/", title = "Final Fantasy VII", platformSlug = "psx",
                genres = listOf("RPG"), cover = "/cartridge/imgcache/ff7", updatedAt = 11,
            ),
            io.github.matiyaaa.fuse.model.CartridgeGame(romId = 79, path = "/elsewhere/Not Here.gba", title = "Not Here", platformSlug = "gba"),
        )
        services.cartridgeStatus = CartridgeStatus(installed = true, version = "0.9.11", bridge = true, protocol = 2, gamesRevision = 1)
        store.cartridge.refresh()

        val detail = withTimeout(10_000) { store.library.game(wars.id).first { it?.game?.metadata?.source == io.github.matiyaaa.fuse.model.MetadataSource.ROMM } }!!
        val meta = detail.game.metadata
        assertEquals("Orange Star goes to war.", meta.description)
        assertEquals(listOf("Strategy"), meta.genres)
        assertEquals("Wars", meta.franchise)
        assertEquals(91, meta.rating)
        assertEquals(77L, detail.game.links.rommRomId)
        // Pictures are copied into Fuse's own folder; the one Cartridge lost is never recorded.
        val cover = withTimeout(10_000) { store.library.game(wars.id).first { it?.media?.boxart != null && it.media.logo != null } }!!.media
        assertEquals(io.github.matiyaaa.fuse.model.MediaSource.ROMM, cover.boxart?.source)
        val keptCover = File(assertNotNull(cover.boxart?.model))
        assertTrue(keptCover.path.contains("kept/romm/") && keptCover.name.endsWith(".png"), keptCover.path)
        assertTrue(keptCover.readBytes().contentEquals(awCover.readBytes()))
        assertTrue(File(assertNotNull(cover.logo?.model)).readBytes().contentEquals(awLogo.readBytes()))
        assertEquals(null, cover.screenshots.firstOrNull { it.source == io.github.matiyaaa.fuse.model.MediaSource.ROMM })

        // The folder matched the one game inside it; the user's cover stayed.
        val ff7Detail = withTimeout(10_000) { store.library.game(ff7.id).first { it?.game?.metadata?.genres == listOf("RPG") } }!!
        assertEquals(78L, ff7Detail.game.links.rommRomId)
        assertEquals("/home/me/ff7.png", ff7Detail.media.boxart?.model)
    }

    @Test
    fun anotherGamesRommDetailsAreNeverTakenAndEarlierOnesAreUndone(): Unit = runBlocking {
        val services = FakeServices(FuseData(DesktopDatabase.open(freshDb())), cache)
        val store = createFuseStore(services, scope)
        store.sources.add(root.absolutePath, LibrarySourceKind.ROMS_ROOT)
        awaitScan(store)
        val wars = store.library.games(GameQuery(platform = PlatformId("gba"))).first().single()
        val ff7 = store.library.games(GameQuery(platform = PlatformId("psx"))).first().single()

        // An earlier version gave Final Fantasy VII another game's name, details and logo.
        services.data.games.applyMetadata(
            ff7.id,
            io.github.matiyaaa.fuse.model.GameMetadata(genres = listOf("Platform"), franchise = "Ratchet & Clank", source = io.github.matiyaaa.fuse.model.MetadataSource.ROMM),
            titleFromMetadata = "Ratchet & Clank",
            onlyFillEmpty = false,
        )
        services.data.games.updateLinks(ff7.id) { it.copy(rommRomId = 500) }
        val owner = io.github.matiyaaa.fuse.model.MediaOwner.OfGame(ff7.id)
        services.data.media.putScraped(owner, listOf(io.github.matiyaaa.fuse.model.MediaItem(MediaKind.LOGO, io.github.matiyaaa.fuse.model.MediaSource.ROMM, localPath = "/gone/logo.png")), io.github.matiyaaa.fuse.model.MediaFillMode.FILL_MISSING)
        assertEquals("Ratchet & Clank", services.data.games.get(ff7.id)?.displayTitle)

        // Cartridge now says Advance Wars' file is Ratchet & Clank on the PS2: nothing of it is taken.
        services.cartridgeGames = listOf(
            io.github.matiyaaa.fuse.model.CartridgeGame(
                romId = 501, path = File(root, "gba/Advance Wars (USA).gba").absolutePath, title = "Ratchet & Clank",
                platformSlug = "ps2", summary = "Not this game.", updatedAt = 1,
            ),
        )
        services.cartridgeStatus = CartridgeStatus(installed = true, version = "0.9.11", bridge = true, protocol = 2, gamesRevision = 1)
        store.cartridge.refresh()

        // The check (run once on start, and from Settings) puts Final Fantasy VII's own name back and
        // drops RomM's logo and link.
        assertEquals(1, store.cartridge.checkRommMatches())
        val fixed = withTimeout(10_000) { store.library.game(ff7.id).first { it?.game?.titles?.metadata == null } }!!
        assertTrue(fixed.game.displayTitle.startsWith("Final Fantasy VII"), fixed.game.displayTitle)
        assertEquals(null, fixed.game.links.rommRomId)
        assertEquals(null, fixed.game.metadata.franchise)
        assertEquals(null, services.data.media.get(owner).first(MediaKind.LOGO))

        delay(500)
        val untouched = assertNotNull(services.data.games.get(wars.id))
        assertTrue(untouched.displayTitle.startsWith("Advance Wars"), untouched.displayTitle)
        assertEquals(null, untouched.links.rommRomId)
        assertEquals(null, untouched.metadata.description)
        assertEquals(0, store.cartridge.checkRommMatches())
    }

    @Test
    fun resetNameAndDetailsKeepsTheUsersOwn(): Unit = runBlocking {
        val services = FakeServices(FuseData(DesktopDatabase.open(freshDb())), cache)
        val store = createFuseStore(services, scope)
        store.sources.add(root.absolutePath, LibrarySourceKind.ROMS_ROOT)
        awaitScan(store)
        val wars = store.library.games(GameQuery(platform = PlatformId("gba"))).first().single()
        val owner = io.github.matiyaaa.fuse.model.MediaOwner.OfGame(wars.id)
        services.data.games.applyMetadata(
            wars.id, io.github.matiyaaa.fuse.model.GameMetadata(description = "Wrong", source = io.github.matiyaaa.fuse.model.MetadataSource.IGDB),
            titleFromMetadata = "Dino Crisis", onlyFillEmpty = false,
        )
        services.data.media.putScraped(owner, listOf(io.github.matiyaaa.fuse.model.MediaItem(MediaKind.HERO, io.github.matiyaaa.fuse.model.MediaSource.IGDB, remoteUrl = "https://x/hero.jpg")), io.github.matiyaaa.fuse.model.MediaFillMode.FILL_MISSING)
        store.media.setFromFile(owner, MediaKind.BOXART, "/home/me/aw.png")

        assertTrue(store.media.resetDetails(wars.id))
        val game = assertNotNull(services.data.games.get(wars.id))
        assertTrue(game.displayTitle.startsWith("Advance Wars"), game.displayTitle)
        assertEquals(null, game.metadata.description)
        val media = services.data.media.get(owner)
        assertEquals(null, media.first(MediaKind.HERO))
        assertEquals("/home/me/aw.png", media.first(MediaKind.BOXART)?.model)
    }

    @Test
    fun gamesOpenOnTheScreenPickedOrRemembered(): Unit = runBlocking {
        val services = FakeServices(FuseData(DesktopDatabase.open(freshDb())), cache)
        services.secondDisplay = 7
        val store = createFuseStore(services, scope)
        store.sources.add(root.absolutePath, LibrarySourceKind.ROMS_ROOT)
        awaitScan(store)
        val wars = store.library.games(GameQuery(platform = PlatformId("gba"))).first().single()
        // Asking is the default; without an answer the game opens on the main screen.
        assertEquals(io.github.matiyaaa.fuse.model.LaunchDisplay.ASK, store.settings.observe(io.github.matiyaaa.fuse.model.ScopedSettings.LaunchScreen, wars.platformId, wars.id).first().value)
        assertIs<LaunchOutcome.Started>(store.library.launch(wars.id))
        assertEquals(null, services.launchedOn.last())
        store.library.onResume()
        // The answer picked at launch.
        assertIs<LaunchOutcome.Started>(store.library.launch(wars.id, display = io.github.matiyaaa.fuse.model.LaunchDisplay.SECONDARY))
        assertEquals(7, services.launchedOn.last())
        store.library.onResume()
        // Remembered for the system, then overridden for the game.
        store.settings.set(io.github.matiyaaa.fuse.model.ScopedSettings.LaunchScreen, io.github.matiyaaa.fuse.model.ScopeRef.platform(wars.platformId), io.github.matiyaaa.fuse.model.LaunchDisplay.SECONDARY)
        assertIs<LaunchOutcome.Started>(store.library.launch(wars.id))
        assertEquals(7, services.launchedOn.last())
        store.library.onResume()
        store.settings.set(io.github.matiyaaa.fuse.model.ScopedSettings.LaunchScreen, io.github.matiyaaa.fuse.model.ScopeRef.game(wars.id), io.github.matiyaaa.fuse.model.LaunchDisplay.PRIMARY)
        assertIs<LaunchOutcome.Started>(store.library.launch(wars.id))
        assertEquals(null, services.launchedOn.last())
    }

    @Test
    fun multiDiscGamesGetAPlaylistInTheCacheOnly() = runBlocking {
        val services = FakeServices(FuseData(DesktopDatabase.open(freshDb())), cache)
        val store = createFuseStore(services, scope)
        store.sources.add(root.absolutePath, LibrarySourceKind.ROMS_ROOT)
        awaitScan(store)
        val ff7 = store.library.games(GameQuery(platform = PlatformId("psx"))).first().single()
        assertEquals(2, ff7.discs)

        assertIs<LaunchOutcome.Started>(store.library.launch(ff7.id))
        val target = (services.launched.single().plan as LaunchPlan.Command).target
        val playlist = assertIs<LaunchTarget.Playlist>(target)
        assertTrue(playlist.generated)
        assertTrue(playlist.path.startsWith(cache.absolutePath), "Playlists belong in Fuse's cache: ${playlist.path}")
        assertTrue(File(root, "psx").listFiles()!!.none { it.name.endsWith(".m3u") }, "Nothing may be written into the ROM folder")
    }

    @Test
    fun preferencesPersistAcrossRestarts() = runBlocking {
        val file = File(cache, "prefs.db").absolutePath
        val first = FakeServices(FuseData(DesktopDatabase.open(file)), cache)
        val store = createFuseStore(first, scope)
        assertTrue(!store.prefs.value.onboardingDone)
        assertEquals(MusicPrefs(), store.prefs.value.music)
        store.updatePrefs {
            it.copy(
                themeId = "crt", onboardingDone = true, clock24h = true, heroDim = 0.5f,
                music = MusicPrefs(enabled = false, volume = 0.4f, songPath = "/data/music/1.mp3", songName = "Fuse Menu Song"),
                soundVolume = 0.3f,
            )
        }
        // Writes are asynchronous; wait for the database to have them.
        withTimeout(10_000) { first.data.settings.settings.first { it.appearance.themeId == "crt" && it.onboarding.completed && it.music.songName != null } }

        val again = createFuseStore(FakeServices(FuseData(DesktopDatabase.open(file)), cache), scope)
        val prefs = again.prefs.value
        assertEquals("crt", prefs.themeId)
        assertTrue(prefs.onboardingDone)
        assertTrue(prefs.clock24h)
        assertEquals(0.5f, prefs.heroDim)
        // Menu music and the sound effects volume are kept apart.
        assertEquals(MusicPrefs(enabled = false, volume = 0.4f, songPath = "/data/music/1.mp3", songName = "Fuse Menu Song"), prefs.music)
        assertEquals(0.3f, prefs.soundVolume)
    }

    @Test
    fun removingAGameNeverTouchesFiles() = runBlocking {
        val services = FakeServices(FuseData(DesktopDatabase.open(freshDb())), cache)
        val store = createFuseStore(services, scope)
        store.sources.add(root.absolutePath, LibrarySourceKind.ROMS_ROOT)
        awaitScan(store)
        val game = store.library.games(GameQuery(platform = PlatformId("gba"))).first().single()
        store.library.removeFromFuse(game.id)
        store.library.rename(game.id, "My Wars")
        assertTrue(File(root, "gba/Advance Wars (USA).gba").exists())
        assertTrue(store.library.games(GameQuery(platform = PlatformId("gba"))).first().isEmpty())
    }

    @Test
    fun bulkFillRemembersWhatSourcesDidNotHave() = runBlocking {
        val services = FakeServices(FuseData(DesktopDatabase.open(freshDb())), cache)
        val store = createFuseStore(services, scope)
        store.sources.add(root.absolutePath, LibrarySourceKind.ROMS_ROOT)
        awaitScan(store)
        fun libretro() = services.requestHosts.count { it == "thumbnails.libretro.com" }

        // Only libretro is set up: icons can't come from it, so only box art is looked for.
        store.media.fill(MediaFillMode.FILL_MISSING, setOf(MediaKind.ICON, MediaKind.BOXART))
        val first = withTimeout(20_000) { store.media.fillProgress.first { it?.finished == true } }!!
        assertEquals(2, first.total)
        assertEquals(2, first.done)
        assertEquals(0, first.added)
        val asked = libretro()
        assertTrue(asked > 0, "libretro should have been asked")

        // The same fill again skips both games: the sources had nothing a moment ago.
        store.media.fill(MediaFillMode.FILL_MISSING, setOf(MediaKind.ICON, MediaKind.BOXART))
        withTimeout(20_000) { store.media.fillProgress.first { it?.finished == true && it !== first } }
        assertEquals(asked, libretro())

        // A single game is always searched again.
        val game = store.library.games(GameQuery(platform = PlatformId("gba"))).first().single()
        store.media.fill(MediaFillMode.FILL_MISSING, setOf(MediaKind.BOXART), game = game.id)
        withTimeout(20_000) { store.media.fillProgress.first { it?.finished == true && it.total == 1 } }
        assertTrue(libretro() > asked)

        // Icons alone: no source can return one, so there is nothing to do.
        store.media.fill(MediaFillMode.FILL_MISSING, setOf(MediaKind.ICON))
        val none = withTimeout(20_000) { store.media.fillProgress.first { it?.finished == true && it.total == 0 } }!!
        assertEquals(0, none.done)
    }

    @Test
    fun aGameGoesToCartridgeToUploadOnlyWhenCartridgeTakesUploads() = runBlocking {
        val services = FakeServices(FuseData(DesktopDatabase.open(freshDb())), cache)
        val store = createFuseStore(services, scope)
        store.sources.add(root.absolutePath, LibrarySourceKind.ROMS_ROOT)
        awaitScan(store)
        val game = store.library.games(GameQuery(platform = PlatformId("gba"))).first().single()

        assertEquals(UploadHandoff.NOT_INSTALLED, store.cartridge.upload(game.id))
        services.cartridgeStatus = CartridgeStatus(installed = true, version = "0.9.11", bridge = true, protocol = 2)
        store.cartridge.refresh()
        withTimeout(5_000) { store.cartridge.status.first { it.protocol == 2 } }
        assertEquals(UploadHandoff.TOO_OLD, store.cartridge.upload(game.id))
        assertTrue(services.uploads.isEmpty())

        services.cartridgeStatus = services.cartridgeStatus.copy(protocol = 3)
        store.cartridge.refresh()
        withTimeout(5_000) { store.cartridge.status.first { it.protocol == 3 } }
        assertEquals(UploadHandoff.OPENED, store.cartridge.upload(game.id))
        val sent = services.uploads.single()
        assertEquals("gba", sent.platformSlug)
        assertEquals(game.title, sent.title)
        assertEquals(listOf(File(root, "gba/Advance Wars (USA).gba").absolutePath), sent.files.map { it.path })
        assertEquals(1024L, sent.sizeBytes)
    }

    @Test
    fun newGamesFindTheirArtByThemselvesOnce() = runBlocking {
        val services = FakeServices(FuseData(DesktopDatabase.open(freshDb())), cache, autoFill = true)
        val store = createFuseStore(services, scope)
        store.sources.add(root.absolutePath, LibrarySourceKind.ROMS_ROOT)
        awaitScan(store)
        fun libretro() = services.requestHosts.count { it == "thumbnails.libretro.com" }

        // Shortly after the scan, Fuse looks for the new games' art without being asked.
        val auto = withTimeout(30_000) { store.media.fillProgress.first { it?.finished == true } }!!
        assertTrue(auto.automatic)
        assertEquals(2, auto.total)
        val asked = libretro()
        assertTrue(asked > 0, "libretro should have been asked")

        // Another scan finds nothing new to try: no requests, and no progress for the user to see.
        store.sources.rescan(ScanScope.QUICK)
        withTimeout(20_000) { store.sources.scan.first { it.phase == ScanPhase.DONE } }
        delay(7_000)
        assertEquals(asked, libretro())
        assertSame(auto, store.media.fillProgress.value)
    }

    @Test
    fun seriesBecomeCollectionsAndCanBeHiddenOrKept() = runBlocking {
        val lib = Files.createTempDirectory("fuse-series").toFile()
        try {
            File(lib, "gba").mkdirs()
            listOf("Super Mario Advance (USA)", "Super Mario Advance 2 (USA)", "Super Mario Advance 4 (USA)", "Metroid Fusion (USA)")
                .forEach { File(lib, "gba/$it.gba").writeBytes(ByteArray(64)) }
            val services = FakeServices(FuseData(DesktopDatabase.open(freshDb())), cache)
            val store = createFuseStore(services, scope)
            store.sources.add(lib.absolutePath, LibrarySourceKind.ROMS_ROOT)
            val series = withTimeout(20_000) {
                store.collections.collections.first { list -> list.any { it.kind == CollectionKind.SERIES } }
            }.single { it.kind == CollectionKind.SERIES }
            // Named by everything its games share.
            assertEquals("Super Mario Advance", series.name)
            assertEquals(3, series.gameCount)

            // Hidden: it goes away and isn't made again.
            store.updatePrefs { it.copy(hiddenSeries = listOf("super mario advance")) }
            withTimeout(10_000) { store.collections.collections.first { list -> list.none { it.kind == CollectionKind.SERIES } } }

            // Back again, then kept as the user's own: Fuse stops managing it.
            store.updatePrefs { it.copy(hiddenSeries = emptyList()) }
            val again = withTimeout(10_000) { store.collections.collections.first { list -> list.any { it.kind == CollectionKind.SERIES } } }
                .single { it.kind == CollectionKind.SERIES }
            store.collections.keepSeries(again.id)
            store.updatePrefs { it.copy(hiddenSeries = listOf("super mario advance")) }
            val kept = withTimeout(10_000) { store.collections.collections.first { list -> list.any { it.id == again.id && it.kind == CollectionKind.MANUAL } } }
            assertEquals(3, kept.single { it.id == again.id }.gameCount)
        } finally {
            lib.deleteRecursively()
        }
    }

    @Test
    fun storageMeasuresGamesAndDeletesEveryFileOfOne() = runBlocking {
        val lib = Files.createTempDirectory("fuse-storage").toFile()
        try {
            File(lib, "psx").mkdirs()
            File(lib, "psx/Big Game (USA).cue").writeText("FILE \"Big Game (USA) (Track 1).bin\" BINARY\nFILE \"Big Game (USA) (Track 2).bin\" BINARY\n")
            File(lib, "psx/Big Game (USA) (Track 1).bin").writeBytes(ByteArray(4000))
            File(lib, "psx/Big Game (USA) (Track 2).bin").writeBytes(ByteArray(1000))
            File(lib, "psx/Big Game (USA).srm").writeBytes(ByteArray(10))
            File(lib, "psx/Small Game (USA).chd").writeBytes(ByteArray(300))
            val services = FakeServices(FuseData(DesktopDatabase.open(freshDb())), cache)
            val store = createFuseStore(services, scope)
            store.sources.add(lib.absolutePath, LibrarySourceKind.ROMS_ROOT)
            withTimeout(20_000) { store.library.games(GameQuery()).first { it.size == 2 } }

            store.storage.refresh()
            val usage = withTimeout(20_000) { store.storage.usage.first { it?.finished == true } }!!
            val big = usage.games.first()
            assertTrue(big.card.title.startsWith("Big Game"), big.card.title)
            assertEquals(3, big.files)
            assertTrue(big.bytes >= 5000)

            val report = store.storage.delete(listOf(big.card.id))
            assertEquals(1, report.deleted)
            assertTrue(report.failed.isEmpty())
            assertTrue(File(lib, "psx").list()!!.toSet() == setOf("Big Game (USA).srm", "Small Game (USA).chd"), File(lib, "psx").list()!!.toList().toString())
            val left = withTimeout(10_000) { store.library.games(GameQuery()).first { it.size == 1 } }
            assertTrue(left.single().title.startsWith("Small Game"))
        } finally {
            lib.deleteRecursively()
        }
    }

    /**
     * A new database file per store, like the desktop app uses. (The in-memory database shares one
     * connection between threads, which the app never does.)
     */
    @Test
    fun appsThatAreGamesJoinTheAndroidSystemAndLeaveWhenTheyAreAppsAgain() = runBlocking {
        val dredge = AppEntry("com.blackrock.dredge/com.unity3d.player.UnityPlayerActivity", "DREDGE", "com.blackrock.dredge", isGame = false)
        val genshin = AppEntry("com.miHoYo.GenshinImpact/com.miHoYo.GetMobileInfo.MainActivity", "Genshin Impact", "com.miHoYo.GenshinImpact", isGame = true)
        val retroarch = AppEntry("com.retroarch/com.retroarch.browser.mainmenu.MainMenuActivity", "RetroArch", "com.retroarch", isGame = true)
        val chrome = AppEntry("com.android.chrome/com.google.android.apps.chrome.Main", "Chrome", "com.android.chrome", isGame = false)
        val services = FakeServices(
            FuseData(DesktopDatabase.open(freshDb())), cache,
            host = Host.ANDROID,
            apps = FakeApps(listOf(chrome, dredge, genshin, retroarch)),
            installedEmulators = listOf(
                InstalledEmulator(EmulatorId("retroarch"), "RetroArch", Host.ANDROID, "com.retroarch", platforms = setOf(PlatformId("snes")), detectedVia = "Package"),
            ),
        )
        val store = createFuseStore(services, scope)
        val android = PlatformId("android")
        suspend fun androidGames(count: Int) = withTimeout(10_000) {
            store.library.games(GameQuery(platform = android)).first { it.size == count }
        }

        // What an app says about itself counts, except for an emulator Fuse detected.
        assertEquals(listOf("Genshin Impact"), androidGames(1).map { it.title })
        assertEquals(listOf("RetroArch"), withTimeout(10_000) { store.apps.apps(AppFilter.EMULATORS).first { it.isNotEmpty() } }.map { it.entry.displayTitle })

        store.apps.setKind(dredge.id, AppKind.GAME)
        val game = androidGames(2).first { it.title == "DREDGE" }
        assertTrue(game.isApp)
        // The app's icon until art is found.
        assertEquals(AppIconModel("com.blackrock.dredge"), game.art.icon)

        // It starts as the app, whatever system it is filed under.
        store.library.setPlatform(game.id, PlatformId("win"))
        assertEquals(LaunchOutcome.Started, store.library.launch(game.id))
        assertEquals(LaunchTarget.App(dredge.id), services.launched.last().target)
        store.library.setPlatform(game.id, android)

        // Not a game after all: it leaves the games, and isn't listed as missing or removed either.
        store.library.removeFromFuse(game.id)
        assertEquals(listOf("Genshin Impact"), androidGames(1).map { it.title })
        assertEquals(AppKind.APP, store.apps.apps(AppFilter.ALL).first().first { it.entry.id == dredge.id }.entry.kind)
        assertTrue(store.library.games(GameQuery(set = GameSet.MISSING)).first().isEmpty())
        assertTrue(store.library.games(GameQuery(set = GameSet.REMOVED)).first().isEmpty())

        // A game again: back with its play history.
        store.apps.setKind(dredge.id, AppKind.GAME)
        assertEquals(game.id, androidGames(2).first { it.title == "DREDGE" }.id)
    }

    @Test
    fun aGameMovedToAnotherSystemStaysAndAFileFromAnywhereCanBeAdded() = runBlocking {
        val services = FakeServices(FuseData(DesktopDatabase.open(freshDb())), cache)
        val store = createFuseStore(services, scope)
        store.sources.add(root.absolutePath, LibrarySourceKind.ROMS_ROOT)
        awaitScan(store)
        val wars = store.library.games(GameQuery(platform = PlatformId("gba"))).first().single()

        store.library.setPlatform(wars.id, PlatformId("gbc"))
        store.sources.rescan(ScanScope.FULL)
        withTimeout(20_000) { store.sources.scan.first { it.phase == ScanPhase.DONE } }
        val systems = withTimeout(10_000) { store.library.platforms.first { list -> list.any { it.platform.id.value == "gbc" } } }
        assertEquals(setOf("gbc", "psx"), systems.map { it.platform.id.value }.toSet())
        assertEquals(wars.id, store.library.games(GameQuery(platform = PlatformId("gbc"))).first().single().id)

        // A file outside every library folder, found with Fuse's file picker.
        val elsewhere = Files.createTempDirectory("fuse-elsewhere").toFile()
        try {
            File(elsewhere, "Downloads").mkdirs()
            val tetris = File(elsewhere, "Downloads/Tetris (World) (Rev 1).gb").apply { writeBytes(ByteArray(32)) }
            services.storageRoots = listOf(io.github.matiyaaa.fuse.ui.shell.store.LocationHint(elsewhere.absolutePath, "Internal storage"))
            val roots = store.library.browse(null)
            assertEquals(listOf("Internal storage"), roots.entries.map { it.name })
            val top = store.library.browse(elsewhere.absolutePath)
            assertEquals(null, top.parent)
            assertEquals(listOf("Downloads"), top.entries.map { it.name })
            val inside = store.library.browse(top.entries.single().path)
            assertEquals(elsewhere.absolutePath, inside.parent)
            assertEquals(listOf(tetris.name), inside.entries.map { it.name })

            val id = assertNotNull(store.library.addGameFile(tetris.absolutePath, PlatformId("gb")))
            val added = withTimeout(10_000) { store.library.games(GameQuery(platform = PlatformId("gb"))).first { it.isNotEmpty() } }.single()
            assertEquals(id, added.id)
            assertEquals("Tetris", added.title)
            // Scans of the library leave it alone.
            store.sources.rescan(ScanScope.FULL)
            withTimeout(20_000) { store.sources.scan.first { it.phase == ScanPhase.DONE } }
            assertEquals(listOf(id), store.library.games(GameQuery(platform = PlatformId("gb"))).first().map { it.id })
        } finally {
            elsewhere.deleteRecursively()
        }
    }

    private fun freshDb(): String = File(cache, "fuse-${System.nanoTime()}.db").absolutePath

    /** Waits until the scan's results are in the library (a follow-up quick scan may already run). */
    private suspend fun awaitScan(store: FuseStore) {
        withTimeout(20_000) {
            store.library.platforms.first { systems -> systems.sumOf { it.gameCount } == 2 }
            store.sources.scan.first { it.phase == ScanPhase.DONE }
        }
    }

    private companion object {
        val PNG = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)
    }
}
