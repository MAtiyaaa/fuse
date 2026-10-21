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
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
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

    private fun services(apps: FakeApps): FakeServices {
        val services = FakeServices(FuseData(DesktopDatabase.open(File(cache, "fuse.db").absolutePath)), cache, host = Host.ANDROID, apps = apps, installedEmulators = emptyList())
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
