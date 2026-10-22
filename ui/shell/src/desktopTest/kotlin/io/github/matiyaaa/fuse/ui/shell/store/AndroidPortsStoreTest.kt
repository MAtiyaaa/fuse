package io.github.matiyaaa.fuse.ui.shell.store

import io.github.matiyaaa.fuse.data.FuseData
import io.github.matiyaaa.fuse.data.db.DesktopDatabase
import io.github.matiyaaa.fuse.model.AppEntry
import io.github.matiyaaa.fuse.model.AppFilter
import io.github.matiyaaa.fuse.model.AppKind
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.Host
import io.github.matiyaaa.fuse.model.LaunchTarget
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.model.PortPlace
import io.github.matiyaaa.fuse.model.ScopeRef
import io.github.matiyaaa.fuse.model.ScopedSettings
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Ports on Android against a fake device: the lists inside "Fuse" and GitHub's releases are fake
 * answers in the real formats, the installer installs what the file says it is, and installed apps
 * appear as Android lists them. Everything between (the lists, matching, installing, attaching,
 * Play starting the app in the game's place) is real.
 */
class AndroidPortsStoreTest {
    private lateinit var cache: File
    private lateinit var scope: CoroutineScope

    @BeforeTest
    fun setUp() {
        cache = Files.createTempDirectory("fuse-android-ports").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        cache.deleteRecursively()
    }

    /** A fake device: an APK's text is "package:versionCode:versionName"; installing one lists its app. */
    private class Bridge(private val dir: File, private val apps: FakeApps) : PackageBridge {
        override val abis = listOf("arm64-v8a")
        override val hasSecondScreen = false
        override val changes = MutableSharedFlow<String>(extraBufferCapacity = 8)
        val installed = java.util.concurrent.ConcurrentHashMap<String, InstalledPackage>()
        val installs = java.util.Collections.synchronizedList(mutableListOf<String>())

        override suspend fun installed(packageName: String) = installed[packageName]
        override fun canInstall() = true
        override fun requestInstallPermission() = Unit
        override fun freeBytes(): Long = 1L shl 34

        override suspend fun newDownload(fileName: String): DownloadSink {
            val file = File(dir, "dl-${System.nanoTime()}-$fileName")
            val out = file.outputStream()
            val digest = MessageDigest.getInstance("SHA-256")
            return object : DownloadSink {
                override val path: String = file.absolutePath
                override suspend fun write(bytes: ByteArray, count: Int) { out.write(bytes, 0, count); digest.update(bytes, 0, count) }
                override suspend fun finish(): String { out.close(); return digest.digest().joinToString("") { "%02x".format(it) } }
                override suspend fun discard() { runCatching { out.close() }; file.delete() }
            }
        }

        override suspend fun inspect(path: String): ArchiveInfo? {
            val parts = File(path).takeIf { it.isFile }?.readText()?.split(':') ?: return null
            if (parts.size != 3) return null
            return ArchiveInfo(parts[0], parts[2], parts[1].toLong())
        }

        override suspend fun install(path: String, onTurn: () -> Unit): PackageOutcome {
            installs += path
            val a = inspect(path) ?: return PackageOutcome.Failed("Not an app")
            installed[a.packageName] = InstalledPackage(a.packageName, a.versionName, a.versionCode, a.packageName)
            if (apps.apps.value.none { it.packageName == a.packageName }) {
                apps.apps.value = apps.apps.value + AppEntry("${a.packageName}/${a.packageName}.Main", a.packageName.substringAfterLast('.'), a.packageName, isGame = false)
            }
            changes.tryEmit(a.packageName)
            return PackageOutcome.Done
        }

        override suspend fun uninstall(packageName: String): PackageOutcome {
            installed.remove(packageName)
            apps.apps.value = apps.apps.value.filterNot { it.packageName == packageName }
            return PackageOutcome.Done
        }

        override fun launch(packageName: String) = installed.containsKey(packageName)
        override fun iconModel(packageName: String): Any? = null
    }

    /** Fuse's own list: two ports of Majora's Mask it checked. */
    private val index = """
        {"format":1,"revision":4,"ports":[],"android":[
          {"id":"twoship","name":"2 Ship 2 Harkinian","game":"The Legend of Zelda: Majora's Mask","systems":["n64"],"kind":"source-port","status":"beta",
           "repo":"https://github.com/example/twoship","package":"com.example.twoship","needs":"Your Majora's Mask ROM, chosen when it asks"},
          {"id":"mmrecomp","name":"Zelda 64: Recompiled","game":"The Legend of Zelda: Majora's Mask","systems":["n64"],"kind":"recompilation","status":"beta",
           "repo":"https://github.com/example/mmrecomp","roms":["ef18b4a9e2386169"],"asset":"\\.apk$"}
        ]}
    """.trimIndent()

    /** Open Ports Games' snapshot: a listing of the same project as Fuse's (left out), a fork, and another system's game. */
    private val listing = """
        {"schema":"openportsgames/catalog","version":2,"generatedAt":"2026-10-18","ports":[
          {"id":"twoship-listed","kind":"game","title":"2S2H (listed)","game":"The Legend of Zelda: Majora's Mask","portType":"source-port","platforms":["android"],"status":"alpha","sources":["https://github.com/example/twoship"],"originalSystems":["Nintendo 64"]},
          {"id":"mm-fork","kind":"game","title":"MM Fork","game":"The Legend of Zelda: Majora's Mask","portType":"source-port","platforms":["android"],"status":"stable","sources":["https://github.com/someone/mmfork"],"originalSystems":["Nintendo 64"]},
          {"id":"red","kind":"game","title":"Red","game":"Pokémon Red","portType":"reimplementation","platforms":["android"],"status":"beta","sources":["https://github.com/someone/red"],"originalSystems":["Game Boy"]}
        ]}
    """.trimIndent()

    /** What each APK contains; a test can change it. */
    private val apk = java.util.concurrent.ConcurrentHashMap(mapOf("mmrecomp.apk" to "com.example.mmrecomp:3:1.0"))

    private fun services(apps: FakeApps, emulators: List<io.github.matiyaaa.fuse.model.InstalledEmulator> = emptyList()): FakeServices {
        val services = FakeServices(FuseData(DesktopDatabase.open(File(cache, "fuse.db").absolutePath)), cache, host = Host.ANDROID, apps = apps, installedEmulators = emulators)
        services.packages = Bridge(cache, apps)
        services.portLists = object : AndroidPortLists {
            override fun bundledIndex() = index
            override fun bundledListing() = listing
        }
        services.web = web@{ request ->
            val url = request.url.toString()
            when {
                // The newest release is a data release with no APK; the one before has it.
                request.url.host == "api.github.com" && url.contains("/repos/example/mmrecomp/releases") -> respond(
                    """[{"tag_name":"maps-2","name":"Map pack","published_at":"2026-10-02T00:00:00Z","html_url":"https://github.com/example/mmrecomp/releases/tag/maps-2","draft":false,"prerelease":false,"body":"","assets":[
                         {"name":"maps.zip","browser_download_url":"https://github.com/example/mmrecomp/releases/download/maps-2/maps.zip","size":0}]},
                        {"tag_name":"v1.0","name":"1.0","published_at":"2026-10-01T00:00:00Z","html_url":"https://github.com/example/mmrecomp/releases/tag/v1.0","draft":false,"prerelease":false,"body":"","assets":[
                         {"name":"mmrecomp.apk","browser_download_url":"https://github.com/example/mmrecomp/releases/download/v1.0/mmrecomp.apk","size":0}]}]""",
                    HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"),
                )
                request.url.host == "github.com" && url.startsWith("https://github.com/example/mmrecomp/releases/download/") ->
                    respond(apk.getValue(url.substringAfterLast('/')), HttpStatusCode.OK)
                else -> null
            }
        }
        return services
    }

    private suspend fun <T> eventually(what: String, read: suspend () -> T?): T = withTimeout(20_000) {
        while (true) {
            read()?.let { return@withTimeout it }
            kotlinx.coroutines.delay(25)
        }
        @Suppress("UNREACHABLE_CODE")
        error(what)
    }

    /** Majora's Mask in the library, as a ROM file of the N64. */
    private suspend fun majorasMask(store: FuseStore): GameId {
        val rom = File(cache, "roms/n64/Legend of Zelda, The - Majora's Mask (USA).z64").apply { parentFile.mkdirs(); writeBytes(ByteArray(4096) { it.toByte() }) }
        return assertNotNull(store.library.addGameFile(rom.absolutePath, PlatformId("n64")))
    }

    private val twoship = AppEntry("com.example.twoship/com.example.twoship.Main", "2 Ship 2 Harkinian", "com.example.twoship", isGame = false)

    @Test
    fun aGamesPortsAreOfferedAndTheOneAttachedPlaysItInTheEmulatorsPlace(): Unit = runBlocking {
        val apps = FakeApps(emptyList())
        val services = services(apps)
        val store = createFuseStore(services, scope)
        assertTrue(store.ports.supported && store.ports.androidApps)
        val game = majorasMask(store)

        // Installed by the person: known by its package, it is a port, and plays the library's one copy of its game.
        apps.apps.value = listOf(twoship)
        eventually("known as a port") { store.apps.apps(AppFilter.GAMES).first().firstOrNull { it.entry.id == twoship.id && it.entry.kind == AppKind.PORT } }
        assertEquals(twoship.id, eventually("attached by itself") { store.ports.androidPlayWith(game) })

        // Installed first, then Fuse's checked, then the listings; the listing of a project Fuse checked
        // is left out, and a port of another system's game isn't offered.
        val offers = store.ports.androidPortsFor(game)
        assertEquals(listOf("twoship", "mmrecomp", "mm-fork"), offers.map { it.view.port.id })
        assertTrue(offers[0].view.installed)
        assertFalse(offers[1].view.installed)
        assertEquals(PortMatch.NAME, offers[0].match)
        assertTrue(offers[0].chosen)

        // Use the system's emulator, then choose it again in Play With.
        store.ports.setAndroidPlayWith(game, null)
        assertFalse(store.ports.androidPortsFor(game).any { it.chosen })
        assertNull(store.ports.attach(twoship.id, game))
        assertEquals(twoship.id, store.ports.androidPlayWith(game))
        assertTrue(store.ports.androidPortsFor(game).first().chosen)
        eventually("attached") { store.apps.apps(AppFilter.ALL).first().firstOrNull { it.entry.id == twoship.id && it.entry.plays == game.value } }

        // Play starts the app in the game's place, and the game's page says so.
        assertEquals(LaunchOutcome.Started, store.library.launch(game))
        assertEquals(LaunchTarget.App(twoship.id), services.launched.last().target)
        val detail = eventually("page") { store.library.game(game).first()?.takeIf { it.emulator.port != null } }
        assertEquals("Starts in 2 Ship 2 Harkinian", detail.emulator.launchSummary)
        // It is how the game plays, not a game of its own in the Android system.
        assertTrue(store.library.games(GameQuery(platform = PlatformId("android"))).first().isEmpty())
        // The computer's own choice of port is untouched.
        assertTrue(services.data.settings.current().ports.playWith.isEmpty())

        // Uninstalled: Play goes back to the emulator (none here, so it says so) and never starts the app.
        apps.apps.value = emptyList()
        eventually("gone") { store.apps.apps(AppFilter.ALL).first().takeIf { it.isEmpty() } }
        val before = services.launched.size
        assertIs<LaunchOutcome.Problem>(store.library.launch(game))
        assertEquals(before, services.launched.size)
    }

    @Test
    fun aGamePlayedWithAPortShowsInPortsOrAndroidTooAndPlaysAsItsListSays(): Unit = runBlocking {
        val apps = FakeApps(emptyList())
        val services = services(apps)
        val store = createFuseStore(services, scope)
        val game = majorasMask(store)
        val n64 = PlatformId("n64")
        val ports = PlatformId("ports")
        val android = PlatformId("android")
        apps.apps.value = listOf(twoship)
        assertEquals(twoship.id, eventually("attached by itself") { store.ports.androidPlayWith(game) })

        suspend fun via(platform: PlatformId): PlayVia? = store.library.games(GameQuery(platform = platform)).first().firstOrNull { it.id == game }?.via

        // By default it shows in Ports too: one game, played with the port from there and its emulator from the N64.
        assertEquals(PlayVia.PORT, eventually("in Ports") { via(ports) })
        assertEquals(PlayVia.EMULATOR, via(n64))
        assertNull(via(android))
        val systems = eventually("Ports on Systems") { store.library.platforms.value.firstOrNull { it.platform.id == ports } }
        assertEquals(1, systems.gameCount)
        assertTrue(systems.emulatorInstalled, "nothing to get for Ports on Android: its games play with their ports")
        assertEquals(1, store.library.platforms.value.single { it.platform.id == n64 }.gameCount)

        // From Ports, Play starts the app; from the N64, the emulator (none here, so it says so) and never the app.
        assertEquals(LaunchOutcome.Started, store.library.launch(game, via = PlayVia.PORT))
        assertEquals(LaunchTarget.App(twoship.id), services.launched.last().target)
        val before = services.launched.size
        assertIs<LaunchOutcome.Problem>(store.library.launch(game, via = PlayVia.EMULATOR))
        assertEquals(before, services.launched.size)

        // Its page says the same, by where it was opened from.
        val fromPorts = eventually("page from Ports") { store.library.game(game, PlayVia.PORT).first()?.takeIf { it.emulator.port != null } }
        assertEquals("Starts in 2 Ship 2 Harkinian", fromPorts.emulator.launchSummary)
        val fromN64 = eventually("page from the N64") { store.library.game(game, PlayVia.EMULATOR).first()?.takeIf { "From Ports" in it.emulator.playLine.orEmpty() } }
        assertNull(fromN64.emulator.port)
        assertTrue("it starts in 2 Ship 2 Harkinian" in fromN64.emulator.playLine.orEmpty(), fromN64.emulator.playLine)

        // The N64's own setting: Android too, in place of Ports.
        store.settings.set(ScopedSettings.PortShowsIn, ScopeRef.platform(n64), PortPlace.ANDROID)
        assertEquals(PlayVia.PORT, eventually("in Android") { via(android) })
        eventually("out of Ports") { store.library.games(GameQuery(platform = ports)).first().takeIf { l -> l.none { it.id == game } } }
        assertEquals(PlayVia.EMULATOR, via(n64))

        // The game's own: its system only, where Play starts it as Play With says.
        store.settings.set(ScopedSettings.PortShowsIn, ScopeRef.game(game), PortPlace.OWN_SYSTEM)
        assertEquals(PlayVia.DEFAULT, eventually("its own system only") { via(n64)?.takeIf { it == PlayVia.DEFAULT } })
        assertNull(via(android))
        assertNull(via(ports))
        assertNull(store.library.platforms.value.firstOrNull { it.platform.id == android && it.gameCount > 0 })
        assertEquals(LaunchOutcome.Started, store.library.launch(game))
        assertEquals(LaunchTarget.App(twoship.id), services.launched.last().target)

        // Back to its system's setting; then every game's, with the system's taken back too.
        store.settings.clear(ScopedSettings.PortShowsIn, ScopeRef.game(game))
        assertEquals(PlayVia.PORT, eventually("its system's again") { via(android) })
        store.settings.clear(ScopedSettings.PortShowsIn, ScopeRef.platform(n64))
        store.settings.set(ScopedSettings.PortShowsIn, ScopeRef.Global, PortPlace.OWN_SYSTEM)
        eventually("its own system only, for every game") { via(n64)?.takeIf { it == PlayVia.DEFAULT } }
        assertNull(via(android))
        assertNull(via(ports))

        // Detached, it is only its system's again, whatever the setting.
        store.settings.set(ScopedSettings.PortShowsIn, ScopeRef.Global, PortPlace.PORTS)
        eventually("in Ports again") { via(ports) }
        store.ports.detach(twoship.id)
        eventually("only the N64's") { via(n64)?.takeIf { it == PlayVia.DEFAULT } }
        assertNull(via(ports))
    }

    @Test
    fun fromItsSystemItPlaysWithItsEmulatorFromPortsWithItsAppAndElsewhereWithWhatPlayedItLast(): Unit = runBlocking {
        val apps = FakeApps(emptyList())
        val retroArch = io.github.matiyaaa.fuse.model.InstalledEmulator(io.github.matiyaaa.fuse.model.EmulatorId("retroarch"), "RetroArch", Host.ANDROID, "com.retroarch", platforms = setOf(PlatformId("n64")), detectedVia = "Package")
        val services = services(apps, listOf(retroArch))
        val store = createFuseStore(services, scope)
        val game = majorasMask(store)
        apps.apps.value = listOf(twoship)
        assertEquals(twoship.id, eventually("attached by itself") { store.ports.androidPlayWith(game) })
        val n64 = PlatformId("n64")
        val ports = PlatformId("ports")
        suspend fun card(platform: PlatformId?) = store.library.games(GameQuery(platform = platform)).first().firstOrNull { it.id == game }
        suspend fun page(via: PlayVia) = store.library.game(game, via).first()
        fun startedApp() = services.launched.last().target is LaunchTarget.App
        // Each play starts a moment after the last, as a person's do.
        suspend fun play(via: PlayVia): LaunchOutcome = store.library.launch(game, via = via).also { kotlinx.coroutines.delay(5) }

        // Opened from the N64 (Systems), the page names its emulator and Play starts it, never the app.
        eventually("in Ports too") { card(ports)?.takeIf { it.via == PlayVia.PORT } }
        val fromN64 = assertNotNull(card(n64))
        assertEquals(PlayVia.EMULATOR, fromN64.via)
        val n64Page = eventually("the N64's page") { page(fromN64.via)?.takeIf { it.emulator.selected != null } }
        assertNull(n64Page.emulator.port)
        assertEquals("RetroArch", n64Page.emulator.selected?.name)
        assertEquals(LaunchOutcome.Started, play(fromN64.via))
        assertFalse(startedApp(), "from the N64 its emulator starts")

        // Opened from Ports, the page names the app and Play starts it.
        assertEquals("2 Ship 2 Harkinian", eventually("Ports' page") { page(PlayVia.PORT)?.emulator?.port })
        assertEquals(LaunchOutcome.Started, play(PlayVia.PORT))
        assertTrue(startedApp(), "from Ports the app starts")

        // From the library (not a system), what played it last: the app, as it just did...
        assertEquals(PlayVia.DEFAULT, card(null)?.via)
        assertEquals("2 Ship 2 Harkinian", eventually("the library's page, after the app") { page(PlayVia.DEFAULT)?.emulator?.port })
        assertEquals(LaunchOutcome.Started, play(PlayVia.DEFAULT))
        assertTrue(startedApp())
        // ...and its emulator once that played it last, the page saying where the app starts instead.
        assertEquals(LaunchOutcome.Started, play(PlayVia.EMULATOR))
        val afterEmulator = eventually("the library's page, after the emulator") { page(PlayVia.DEFAULT)?.takeIf { it.emulator.port == null } }
        assertTrue("From Ports, it starts in 2 Ship 2 Harkinian" in afterEmulator.emulator.playLine.orEmpty(), afterEmulator.emulator.playLine)
        assertEquals(LaunchOutcome.Started, play(PlayVia.DEFAULT))
        assertFalse(startedApp(), "the emulator played it last")

        // Choosing the app in Play With since makes it the library's Play again.
        store.ports.setAndroidPlayWith(game, twoship.id)
        assertEquals("2 Ship 2 Harkinian", eventually("the library's page, the app chosen") { page(PlayVia.DEFAULT)?.emulator?.port })
        assertEquals(LaunchOutcome.Started, play(PlayVia.DEFAULT))
        assertTrue(startedApp())
    }

    @Test
    fun aRecompOrPortThatPlaysNoGameIsAGameOfItsOwnInPorts(): Unit = runBlocking {
        val apps = FakeApps(emptyList())
        val services = services(apps)
        val store = createFuseStore(services, scope)
        val ports = PlatformId("ports")
        val android = PlatformId("android")
        // An APK Android says is a game: a game of the Android system.
        val recomp = AppEntry("com.example.sonicrecomp/com.example.sonicrecomp.Main", "Sonic Recompiled", "com.example.sonicrecomp", isGame = true)
        apps.apps.value = listOf(recomp)
        val asGame = eventually("a game of Android") { store.library.games(GameQuery(platform = android)).first().firstOrNull { it.title == "Sonic Recompiled" } }

        // Made a recomp or port, attached to nothing: the same game, in Ports, and no longer Android's.
        store.apps.setKind(recomp.id, AppKind.PORT)
        val inPorts = eventually("a game of its own in Ports") { store.library.games(GameQuery(platform = ports)).first().firstOrNull { it.title == "Sonic Recompiled" } }
        assertEquals(asGame.id, inPorts.id, "the same game, its play time and art with it")
        eventually("out of Android") { store.library.games(GameQuery(platform = android)).first().takeIf { l -> l.none { it.id == inPorts.id } } }
        assertTrue(eventually("Ports on Systems") { store.library.platforms.value.firstOrNull { it.platform.id == ports } }.gameCount >= 1)

        // Play starts the app itself.
        assertEquals(LaunchOutcome.Started, store.library.launch(inPorts.id))
        assertEquals(LaunchTarget.App(recomp.id), services.launched.last().target)

        // Attached to a game later, it is that game's way to play instead; detached, its own again.
        val game = majorasMask(store)
        assertNull(store.ports.attach(recomp.id, game))
        eventually("no game of its own while it plays one") { store.library.games(GameQuery(platform = ports)).first().takeIf { l -> l.none { it.id == inPorts.id } } }
        store.ports.detach(recomp.id)
        assertEquals(inPorts.id, eventually("its own again") { store.library.games(GameQuery(platform = ports)).first().firstOrNull { it.title == "Sonic Recompiled" } }.id)
    }

    /** RomM's and the household's other devices' games, as a test sets them, and what was asked of them. */
    private class FakeSources : io.github.matiyaaa.fuse.ui.shell.store.impl.PortGameSources {
        val copies = java.util.concurrent.CopyOnWriteArrayList<PortGameCopy>()
        val asked = java.util.concurrent.CopyOnWriteArrayList<PortGameCopy>()
        val landed = MutableStateFlow<GameId?>(null)
        override suspend fun games(systems: Set<String>) = copies.filter { it.platform.value in systems }
        override suspend fun bring(copy: PortGameCopy): String? = null.also { asked += copy }
        override fun arrived(copy: PortGameCopy): kotlinx.coroutines.flow.Flow<GameId?> = landed
    }

    private val onRomm = PortGameCopy(GameId(-5), "Legend of Zelda, The - Majora's Mask (USA)", PlatformId("n64"), CopyPlace.ROMM, "RomM", romId = 5, sizeBytes = 33_554_432)
    private val onPc = PortGameCopy(GameId(-9), "The Legend of Zelda: Majora's Mask", PlatformId("n64"), CopyPlace.DEVICE, "Gaming PC", online = false)

    @Test
    fun aPortsGameOnRommOrAnotherDeviceIsSaidAndBroughtHereThenPlaysWithIt(): Unit = runBlocking {
        val apps = FakeApps(emptyList())
        val services = services(apps)
        val store = createFuseStore(services, scope)
        val ports = store.ports as io.github.matiyaaa.fuse.ui.shell.store.impl.AndroidPortsStore
        val sources = FakeSources().apply { copies += listOf(onRomm, onPc, onRomm.copy(id = GameId(-6), title = "Super Mario 64 (USA)", romId = 6)) }
        ports.sources = sources
        ports.open()

        // Not on this device: where it is instead, a device that is on before RomM, RomM before one that's away.
        val away = eventually("its game elsewhere") { store.ports.android.value.port("twoship")?.takeIf { it.elsewhere.isNotEmpty() } }
        assertFalse(away.here)
        assertEquals(listOf("RomM", "Gaming PC"), away.elsewhere.map { it.from })
        assertTrue(store.ports.android.value.ports.none { v -> v.elsewhere.any { it.title.startsWith("Super Mario") } }, "only its own game")

        // Brought here before the port is installed: asked of RomM, said while it comes.
        assertNull(store.ports.bringGame("twoship", onRomm))
        assertEquals(listOf(onRomm), sources.asked.toList())
        assertEquals(onRomm, eventually("bringing") { store.ports.android.value.port("twoship")?.bringing })

        // It lands in the library, then the port's app arrives: the game plays with it.
        val game = majorasMask(store)
        sources.landed.value = game
        apps.apps.value = listOf(twoship)
        assertEquals(twoship.id, eventually("attached") { store.ports.androidPlayWith(game) })
        val done = eventually("here") { store.ports.android.value.port("twoship")?.takeIf { it.bringing == null && it.here } }
        assertTrue(done.elsewhere.isEmpty(), "on this device, nowhere else is offered")
    }

    @Test
    fun aPortWithoutBoxArtGetsOneFoundByItsGamesNameOnceAndKept(): Unit = runBlocking {
        val apps = FakeApps(emptyList())
        val services = services(apps)
        val store = createFuseStore(services, scope)
        val ports = store.ports as io.github.matiyaaa.fuse.ui.shell.store.impl.AndroidPortsStore
        val asked = java.util.concurrent.CopyOnWriteArrayList<Pair<String, PlatformId?>>()
        val offline = java.util.concurrent.atomic.AtomicBoolean(true)
        ports.findCover = { title, platform ->
            asked += title to platform
            // Pokémon Red's look finds no source answering the first time (offline).
            if (title == "Pokémon Red" && offline.get()) error("offline")
            if ("Majora" in title) "https://example.org/mm.png" else null
        }
        ports.open()
        val found = eventually("found") { store.ports.android.value.port("twoship")?.takeIf { it.foundCover != null } }
        assertEquals("https://example.org/mm.png", found.cover)
        // Asked once for each game and system, with the system its ports play.
        eventually("every port asked") { asked.takeIf { a -> a.any { it.first == "Pokémon Red" } } }
        assertEquals(1, asked.count { it.first == "The Legend of Zelda: Majora's Mask" })
        assertEquals(PlatformId("n64"), asked.first { "Majora" in it.first }.second)

        // Kept: opened again, only the game no source answered for is asked again.
        offline.set(false)
        ports.open()
        eventually("asked again") { asked.takeIf { a -> a.count { it.first == "Pokémon Red" } == 2 } }
        kotlinx.coroutines.delay(300)
        assertEquals(1, asked.count { it.first == "The Legend of Zelda: Majora's Mask" })
    }

    @Test
    fun aPortInstalledFromItsReleasesIsAttachedAndStaysTheSameApp(): Unit = runBlocking {
        val apps = FakeApps(emptyList())
        val services = services(apps)
        val store = createFuseStore(services, scope)
        val bridge = services.packages as Bridge
        val game = majorasMask(store)

        store.ports.installAndroid("mmrecomp", game)
        val appId = eventually("chosen") { store.ports.androidPlayWith(game) }
        assertEquals("com.example.mmrecomp/com.example.mmrecomp.Main", appId)
        // The data release on top was passed over for the one with the APK.
        assertEquals(1, bridge.installs.size)
        assertEquals("com.example.mmrecomp", eventually("pinned") { services.data.settings.current().ports.androidInstalled["mmrecomp"] })
        val view = eventually("installed") { store.ports.android.value.port("mmrecomp")?.takeIf { it.installed && it.attachedTo.isNotEmpty() } }
        assertEquals(listOf(game), view.attachedTo.map { it.id })
        assertNull(view.job)
        eventually("a port") { store.apps.apps(AppFilter.ALL).first().firstOrNull { it.entry.id == appId && it.entry.kind == AppKind.PORT } }

        // A later download that is another app is never installed in its place.
        apk["mmrecomp.apk"] = "com.other.app:4:1.1"
        store.ports.installAndroid("mmrecomp")
        val failed = eventually("refused") { store.ports.android.value.port("mmrecomp")?.job as? PortJob.Failed }
        assertTrue("different app" in failed.message, failed.message)
        assertEquals(1, bridge.installs.size)
    }

    @Test
    fun anAppOrAnApkIsKnownAsItsPortAndItsGameIsFound(): Unit = runBlocking {
        val apps = FakeApps(emptyList())
        val services = services(apps)
        val store = createFuseStore(services, scope)
        val game = majorasMask(store)

        // An app named as a port is (the listing of the same project is left out, so it is one port).
        val guess = store.ports.gamesForApp("org.unknown", "2 Ship 2 Harkinian")
        assertEquals("twoship", guess.port?.id)
        assertEquals(listOf(game), guess.games.filter { it.plays }.map { it.id })
        // An app Fuse doesn't know: the library's likeliest games by its name.
        val unknown = store.ports.gamesForApp("org.unknown", "Majora Mask Android")
        assertNull(unknown.port)
        assertEquals(game, unknown.games.first().id)
        assertTrue(store.ports.gamesForApp("org.unknown", "Calculator").games.isEmpty())

        // An APK whose package Fuse knows says what it is before it is installed.
        val known = File(cache, "twoship.apk").apply { writeText("com.example.twoship:1:1.0") }
        val port = assertNotNull(store.ports.apkPort(known.absolutePath))
        assertEquals("twoship", port.port.id)
        assertEquals(listOf(game), port.games.map { it.id })
        val other = File(cache, "other.apk").apply { writeText("com.example.other:1:1.0") }
        assertNull(store.ports.apkPort(other.absolutePath))

        // Attached once Android has installed it.
        store.ports.attachOnceInstalled("com.example.twoship", game)
        assertNull(store.ports.androidPlayWith(game))
        apps.apps.value = listOf(twoship)
        assertEquals(twoship.id, eventually("attached on arrival") { store.ports.androidPlayWith(game) })
        eventually("its game") { store.apps.apps(AppFilter.ALL).first().firstOrNull { it.entry.plays == game.value && it.entry.kind == AppKind.PORT } }

        // Detached: the game plays with its emulator again.
        store.ports.detach(twoship.id)
        assertNull(store.ports.androidPlayWith(game))
        val detached = eventually("detached") { store.apps.apps(AppFilter.ALL).first().firstOrNull { it.entry.id == twoship.id && it.entry.plays == null } }
        assertEquals(AppKind.PORT, detached.entry.kind, "still a port, of no game")
    }
}
