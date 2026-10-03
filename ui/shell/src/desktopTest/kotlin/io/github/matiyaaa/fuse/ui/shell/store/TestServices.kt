package io.github.matiyaaa.fuse.ui.shell.store

import io.github.matiyaaa.fuse.data.FuseData
import io.github.matiyaaa.fuse.data.settings.SecretStore
import io.github.matiyaaa.fuse.integrations.github.ReleasePlatform
import io.github.matiyaaa.fuse.launch.ResolvedLaunch
import io.github.matiyaaa.fuse.library.FsAccessException
import io.github.matiyaaa.fuse.library.FsEntry
import io.github.matiyaaa.fuse.library.FuseFileSystem
import io.github.matiyaaa.fuse.model.AppEntry
import io.github.matiyaaa.fuse.model.CartridgeRoute
import io.github.matiyaaa.fuse.model.CartridgeStatus
import io.github.matiyaaa.fuse.model.EmulatorId
import io.github.matiyaaa.fuse.model.Host
import io.github.matiyaaa.fuse.model.InstalledEmulator
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.model.ReleaseAsset
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.http.HttpStatusCode
import java.io.File
import kotlin.test.Test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking

/** Test doubles for [FuseServices]: real files and database, fake launcher and system. */
internal class FakeServices(
    override val data: FuseData,
    private val cache: File,
    autoFill: Boolean = false,
    override val host: Host = Host.LINUX,
    override val apps: AppsProvider? = null,
    /** Emulators the fake detects; the Linux ones by default. */
    private val installedEmulators: List<InstalledEmulator>? = null,
    /** GitHub's answer for Fuse's latest release, as JSON; null answers 404 like everything else. */
    private val latestRelease: String? = null,
) : FuseServices {
    init {
        // Tests start fills themselves; the automatic one runs only where a test turns it on.
        kotlinx.coroutines.runBlocking { data.settings.update { it.copy(scraping = it.scraping.copy(autoFill = autoFill)) } }
    }

    val launched = mutableListOf<ResolvedLaunch>()
    var exit: CompletableDeferred<Unit>? = null

    override val appVersion = "0.0.1"
    val javaFs = JavaFileSystem()
    override val fs: FuseFileSystem = javaFs
    override val secrets: SecretStore = MemorySecrets()
    /** Hosts of every request made, in order (nothing is found anywhere). */
    val requestHosts: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
    /** Answers a test gives first, before the fake's own (null passes to them). */
    @Volatile var web: (suspend io.ktor.client.engine.mock.MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData?)? = null

    /** The Store's view of the system, when a test gives it one (Android has one; desktop doesn't). */
    override var packages: PackageBridge? = null

    override val http = HttpClient(MockEngine { request ->
        requestHosts += request.url.host
        val answer = web?.invoke(this, request)
        if (answer != null) {
            answer
        } else if (latestRelease != null && request.url.encodedPath.endsWith("/releases/latest")) {
            respond(latestRelease, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        } else if (request.url.host == "raw.githubusercontent.com" && request.url.encodedPath == "/someone/themes/main/ember.json") {
            // A shared theme, as GitHub serves the file behind a page link.
            respond("""{"fuseTheme": 1, "name": "Ember", "colors": {"accent": "#FF7A59"}}""", HttpStatusCode.OK)
        } else if (request.url.host == "rpcs3.net" && request.url.parameters["g"] == "BLUS30443") {
            respond(
                """{"return_code": 0, "results": {"BLUS30443": {"title": "Demon's Souls", "status": "Playable", "date": "2020-05-04", "thread": 194290}}}""",
                HttpStatusCode.OK,
            )
        } else if (request.url.host == "rpcs3.net") {
            // An id the list doesn't know: it searches text instead and answers with another game.
            respond("""{"return_code": 2, "search_term": "x", "results": {"BLES00917": {"title": "F1 2010", "status": "Playable"}}}""", HttpStatusCode.OK)
        } else if (request.url.host == "raw.githubusercontent.com" && request.url.encodedPath.endsWith("/big.json")) {
            respond("x".repeat(70_000), HttpStatusCode.OK)
        } else {
            respondError(HttpStatusCode.NotFound)
        }
    })
    override val cacheDir: String = cache.absolutePath

    override val emulators = object : EmulatorDetector {
        override suspend fun detect() = installedEmulators ?: listOf(
            InstalledEmulator(EmulatorId("linux.mgba"), "mGBA", Host.LINUX, "/usr/bin/mgba-qt", platforms = setOf(PlatformId("gba")), detectedVia = "PATH"),
            InstalledEmulator(EmulatorId("linux.duckstation"), "DuckStation", Host.LINUX, "/usr/bin/duckstation-qt", platforms = setOf(PlatformId("psx")), detectedVia = "PATH"),
        )
    }

    /** The device's second screen, when the test gives it one, and the display each launch asked for. */
    var secondDisplay: Int? = null
    val launchedOn = mutableListOf<Int?>()

    override val launcher = object : GameLauncher {
        override fun secondaryDisplayId(): Int? = secondDisplay

        override suspend fun run(launch: ResolvedLaunch, displayId: Int?): RunResult {
            launched += launch
            launchedOn += displayId
            val waiter = exit
            return RunResult.Started(awaitExit = waiter?.let { w -> { w.await() } })
        }

        override suspend fun openApp(appId: String): RunResult = RunResult.Started()
    }

    /** What the fake Cartridge reports; tests change it and call refresh. */
    var cartridgeStatus = CartridgeStatus(installed = false)
    var cartridgeGames: List<io.github.matiyaaa.fuse.model.CartridgeGame>? = null

    /** Games handed to the fake Cartridge to upload. */
    val uploads = mutableListOf<io.github.matiyaaa.fuse.model.CartridgeUpload>()

    override val cartridge = object : CartridgeBridge {
        override suspend fun read() = cartridgeStatus
        override suspend fun games() = cartridgeGames
        override fun open(route: CartridgeRoute, link: String) = false
        override suspend fun upload(upload: io.github.matiyaaa.fuse.model.CartridgeUpload): Boolean {
            uploads += upload
            return true
        }
    }

    override var installer: ReleaseInstaller = object : ReleaseInstaller {
        override val platform = ReleasePlatform.LINUX_X86_64
        override suspend fun install(asset: ReleaseAsset, onProgress: (Float) -> Unit) = Result.failure<Unit>(UnsupportedOperationException())
    }

    /** Where the fake's file picker starts. */
    var storageRoots: List<LocationHint> = emptyList()

    override val locations = object : DeviceLocations {
        override suspend fun libraryCandidates() = emptyList<LocationHint>()
        override suspend fun biosRoots() = emptyList<String>()
        override suspend fun storageRoots() = this@FakeServices.storageRoots
    }

    override fun writeCacheFile(relativePath: String, content: String): String? {
        require(!relativePath.contains("..")) { "Cache paths never leave the cache" }
        val file = File(cache, relativePath)
        file.parentFile.mkdirs()
        file.writeText(content)
        return file.absolutePath
    }

    override suspend fun cacheFile(relativePath: String, content: suspend () -> ByteArray): String? {
        require(!relativePath.contains("..")) { "Cache paths never leave the cache" }
        val file = File(cache, relativePath)
        if (!file.isFile) {
            file.parentFile.mkdirs()
            file.writeBytes(content())
        }
        return file.absolutePath
    }

    override suspend fun readFile(path: String, maxBytes: Int): ByteArray? =
        File(path).takeIf { it.isFile && it.length() <= maxBytes }?.readBytes()

    override suspend fun keepFile(relativePath: String, bytes: ByteArray): String? {
        require(!relativePath.contains("..")) { "Kept files never leave the data folder" }
        val file = File(cache, "kept/$relativePath")
        file.parentFile.mkdirs()
        file.writeBytes(bytes)
        return file.absolutePath
    }

    /** Where the fake PCSX2 keeps its data; null when it isn't set up. */
    @Volatile var pcsx2Home: io.github.matiyaaa.fuse.launch.patches.Pcsx2Home? = null

    override val emulatorFiles = object : EmulatorFiles {
        override suspend fun pcsx2(installed: InstalledEmulator) = pcsx2Home
        override suspend fun zipText(zip: String, entry: String): String? =
            java.util.zip.ZipFile(zip).use { z -> z.getEntry(entry)?.let { e -> z.getInputStream(e).use { it.readBytes().decodeToString() } } }
        override suspend fun write(path: String, text: String): Boolean {
            val root = pcsx2Home?.dataRoot ?: return false
            if (!path.startsWith("$root/")) return false
            File(path).also { it.parentFile.mkdirs() }.writeText(text)
            return true
        }

        override suspend fun rpcs3Storage(installed: InstalledEmulator) = listOfNotNull(rpcs3Hdd)
        override suspend fun vita3kStorage(installed: InstalledEmulator) = listOfNotNull(vitaPref)
        override suspend fun azaharStorage(installed: InstalledEmulator) = listOfNotNull(azaharSdmc)
        override suspend fun runInstaller(run: InstallerRun, onOutput: (String) -> Unit): InstallerResult {
            installerRuns += run
            return contentInstaller(run)
        }
        override suspend fun stage(source: String, name: String): String? {
            val dir = File(cache, "staged").also { it.mkdirs() }
            return File(source).copyTo(File(dir, name), overwrite = true).absolutePath
        }
        override suspend fun clearStaged() {
            File(cache, "staged").deleteRecursively()
        }
    }

    /** RPCS3's dev_hdd0 and Vita3K's pref path for the fake, and what its installer does with each run. */
    @Volatile var rpcs3Hdd: String? = null
    @Volatile var vitaPref: String? = null
    @Volatile var azaharSdmc: String? = null
    val installerRuns: MutableList<InstallerRun> = java.util.Collections.synchronizedList(mutableListOf())
    @Volatile var contentInstaller: (InstallerRun) -> InstallerResult = { InstallerResult(0, "") }

    /** The drives the fake system reports; null reports none, like a host that can't tell. */
    @Volatile var drives: List<io.github.matiyaaa.fuse.model.StorageVolume>? = null

    override val volumes = object : VolumeMonitor {
        override suspend fun volumes() = drives.orEmpty()
    }

    override fun utcOffsetMillis() = 0L
}

/** Installed apps for tests; [apps] can change like a real install or removal. */
internal class FakeApps(initial: List<AppEntry>) : AppsProvider {
    override val apps = kotlinx.coroutines.flow.MutableStateFlow(initial)
    val opened = mutableListOf<String>()
    override fun iconModel(entry: AppEntry): Any = AppIconModel(entry.packageName)
    override fun refresh() = Unit
    override suspend fun launch(entry: AppEntry, displayId: Int?): RunResult {
        opened += entry.id
        return RunResult.Started()
    }
    override fun openInfo(entry: AppEntry) = Unit
}

internal class MemorySecrets : SecretStore {
    private val values = HashMap<String, String>()
    override suspend fun get(key: String) = values[key]
    override suspend fun put(key: String, value: String) { values[key] = value }
    override suspend fun remove(key: String) { values.remove(key) }
}

internal class JavaFileSystem : FuseFileSystem {
    /** Runs before each listing, so a test can change the world in the middle of a scan. */
    @Volatile var beforeList: ((String) -> Unit)? = null

    override suspend fun delete(path: String): Boolean = File(path).let { !it.exists() || it.deleteRecursively() }

    override suspend fun list(path: String): List<FsEntry> {
        beforeList?.invoke(path)
        val dir = File(path)
        val children = dir.listFiles() ?: throw FsAccessException(path, "Unreadable")
        return children.map { FsEntry(it.name, it.absolutePath, it.isDirectory, it.length(), it.lastModified()) }
    }

    override suspend fun stat(path: String): FsEntry? = File(path).takeIf { it.exists() }?.let {
        FsEntry(it.name, it.absolutePath, it.isDirectory, it.length(), it.lastModified())
    }

    override suspend fun readText(path: String, maxBytes: Int): String? = File(path).takeIf { it.isFile }?.let {
        it.inputStream().use { s -> String(s.readNBytes(maxBytes)) }
    }

    override suspend fun readBytes(path: String, offset: Long, length: Int): ByteArray? = File(path).takeIf { it.isFile }?.let { f ->
        java.io.RandomAccessFile(f, "r").use { r ->
            if (offset >= r.length()) return@use ByteArray(0)
            r.seek(offset)
            ByteArray(minOf(length.toLong(), r.length() - offset).toInt()).also { r.readFully(it) }
        }
    }

    override suspend fun md5(path: String): String? = null

    override suspend fun canonical(path: String): String? = File(path).canonicalPath
}
