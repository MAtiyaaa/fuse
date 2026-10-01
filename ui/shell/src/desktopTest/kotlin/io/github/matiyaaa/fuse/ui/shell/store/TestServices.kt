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
    override val fs: FuseFileSystem = JavaFileSystem()
    override val secrets: SecretStore = MemorySecrets()
    /** Hosts of every request made, in order (nothing is found anywhere). */
    val requestHosts: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
    override val http = HttpClient(MockEngine { request ->
        requestHosts += request.url.host
        if (latestRelease != null && request.url.encodedPath.endsWith("/releases/latest")) {
            respond(latestRelease, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        } else if (request.url.host == "raw.githubusercontent.com" && request.url.encodedPath == "/someone/themes/main/ember.json") {
            // A shared theme, as GitHub serves the file behind a page link.
            respond("""{"fuseTheme": 1, "name": "Ember", "colors": {"accent": "#FF7A59"}}""", HttpStatusCode.OK)
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
    override suspend fun delete(path: String): Boolean = File(path).let { !it.exists() || it.deleteRecursively() }

    override suspend fun list(path: String): List<FsEntry> {
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

    override suspend fun md5(path: String): String? = null

    override suspend fun canonical(path: String): String? = File(path).canonicalPath
}
